package dev.wiles.myagent.tool.search;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 遍历工作区，挑出值得搜的文件。{@code grep} 和 {@code glob} 都从这里拿候选。
 *
 * <h3>为什么结果按「最近修改」倒序</h3>
 * 这是整个检索里<b>唯一一处排序启发式</b>，也是不建索引之后最划算的一招：
 * 人正在改的东西，就是人正在问的东西。刚动过的文件排前面，
 * 截断时被砍掉的就是那些几年没碰过的。
 *
 * <p>它同时决定了<b>被截断时留下的是哪一批</b>——这比「留下目录遍历顺序里靠前的那批」
 * 有意义得多，后者实质上是按文件名字母序，和相关性毫无关系。
 *
 * <h3>为什么先收齐再排，而不是边走边停</h3>
 * 走到上限就停的话，「按修改时间排序」排的是<b>一个任意子集</b>——
 * 先遇到的那些。那样的排序看起来正常、实际上是假的，
 * 而这种错误没有任何症状，只表现为「它就是找不到那个文件」。
 * 所以收集阶段的闸（{@link #MAX_CANDIDATES}）开得很大，
 * 真正决定给模型看多少的是各个工具自己的显示上限。
 */
public final class SourceScan {

    /**
     * 最多收集多少个候选文件。
     *
     * <p>只存路径和两个 long，两万条大约几 MB，对任何正常仓库都绰绰有余
     * （Linux 内核约 8 万个文件，是少见的例外）。真撞上了会如实报告，
     * 不会假装扫完了——「搜过了，没有」和「没搜到那儿」对模型是完全不同的信息。
     */
    public static final int MAX_CANDIDATES = 20_000;

    /** 目录递归深度上限。软链环已经由 walkFileTree 的默认行为挡掉，这道是防病态深树。 */
    public static final int MAX_DEPTH = 32;

    private SourceScan() {
    }

    /** 一个候选文件。{@code relative} 一律用 {@code /} 分隔，跨平台显示才一致。 */
    public record Found(Path path, String relative, long size, long modified) {
    }

    /** 扫描为什么结束的。 */
    public enum Stop {
        /** 走完了整棵树。 */
        COMPLETE,
        /** 撞上 {@link #MAX_CANDIDATES}。 */
        TOO_MANY_FILES,
        /** 超时。 */
        TIMEOUT
    }

    /**
     * @param files 已按最近修改倒序排好
     * @param stop  为什么结束。不是 {@link Stop#COMPLETE} 时，调用方<b>必须</b>在给模型的
     *              输出里说明——悄悄截断会让模型把「没搜到」当成「不存在」
     */
    public record Result(List<Found> files, Stop stop) {

        public boolean complete() {
            return stop == Stop.COMPLETE;
        }
    }

    /**
     * 扫。
     *
     * @param root     工作区根。.gitignore 从这里开始层层累加，即使 {@code start} 在更深处
     * @param start    从哪个目录开始走。必须在 {@code root} 之内
     * @param accept   路径过滤器，参数是相对 {@code root} 的路径。放行才收
     * @param deadline {@link System#nanoTime()} 口径的截止时刻
     */
    public static Result scan(Path root, Path start, Predicate<String> accept, long deadline) {
        List<Found> found = new ArrayList<>();

        // 单个文件也允许——模型经常把 path 指向一个具体文件来「只搜这个」
        if (Files.isRegularFile(start)) {
            String relative = relativize(root, start);
            if (accept.test(relative)) {
                addIfWorthy(found, start, relative);
            }
            return new Result(found, Stop.COMPLETE);
        }
        if (!Files.isDirectory(start)) {
            return new Result(List.of(), Stop.COMPLETE);
        }

        // start 在 root 深处时，它上面每一层的 .gitignore 都仍然管着它，
        // 所以先把 root→start 这一路的规则铺好再开走。漏掉这步的症状是
        // 「在子目录里搜，产物文件又冒出来了」
        Deque<Layer> layers = new ArrayDeque<>();
        for (Path at : chain(root, start)) {
            pushLayer(layers, at);
        }

        Stop[] stop = {Stop.COMPLETE};
        try {
            Files.walkFileTree(start, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (expired(deadline)) {
                        stop[0] = Stop.TIMEOUT;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!dir.equals(start)) {
                        String name = dir.getFileName().toString();
                        if (!SourceFilter.shouldEnter(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (ignored(layers, root, dir, true)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        pushLayer(layers, dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    if (!dir.equals(start)) {
                        popLayer(layers, dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (found.size() >= MAX_CANDIDATES) {
                        stop[0] = Stop.TOO_MANY_FILES;
                        return FileVisitResult.TERMINATE;
                    }
                    if (expired(deadline)) {
                        stop[0] = Stop.TIMEOUT;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile() || !SourceFilter.worthReading(attrs.size())) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = relativize(root, file);
                    if (!accept.test(relative) || ignored(layers, root, file, false)) {
                        return FileVisitResult.CONTINUE;
                    }
                    found.add(new Found(file, relative, attrs.size(),
                            attrs.lastModifiedTime().toMillis()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;   // 没权限就跳过，不为它让整次搜索失败
                }
            });
        } catch (IOException | RuntimeException e) {
            // 走了一半走不动了：把已经收到的交出去，总好过一条不给
            return new Result(sorted(found), found.isEmpty() ? Stop.COMPLETE : stop[0]);
        }
        return new Result(sorted(found), stop[0]);
    }

    private static void addIfWorthy(List<Found> found, Path file, String relative) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (SourceFilter.worthReading(attrs.size())) {
                found.add(new Found(file, relative, attrs.size(),
                        attrs.lastModifiedTime().toMillis()));
            }
        } catch (IOException ignored) {
            // 读不到属性就当它不存在
        }
    }

    private static List<Found> sorted(List<Found> found) {
        // 时间相同时按路径兜底：顺序必须是确定的，否则同一次搜索两次跑出不同结果，
        // 而这种不稳定在测试里表现为「偶尔失败」，最难查
        found.sort(Comparator.comparingLong(Found::modified).reversed()
                .thenComparing(Found::relative));
        return found;
    }

    private static boolean expired(long deadline) {
        return System.nanoTime() > deadline;
    }

    /** root 到 dir 之间的每一层，从浅到深（含两端）。 */
    private static List<Path> chain(Path root, Path dir) {
        List<Path> path = new ArrayList<>();
        for (Path at = dir; at != null && at.startsWith(root); at = at.getParent()) {
            path.add(at);
            if (at.equals(root)) {
                break;
            }
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private static void pushLayer(Deque<Layer> layers, Path dir) {
        GitIgnore rules = GitIgnore.of(dir);
        if (!rules.isEmpty()) {
            layers.push(new Layer(dir, rules));
        }
    }

    private static void popLayer(Deque<Layer> layers, Path dir) {
        Layer top = layers.peek();
        if (top != null && top.base.equals(dir)) {
            layers.pop();
        }
    }

    /**
     * 逐层问一遍要不要忽略。<b>深的那层说了算</b>——子目录的 .gitignore 能推翻父目录的，
     * 这是 git 的语义，也是 {@code !} 取反在嵌套目录里唯一能起作用的前提。
     *
     * <p>{@code layers} 是个栈，栈顶最深，所以从栈顶往下问，第一个给出明确答复的就是结论。
     */
    private static boolean ignored(Deque<Layer> layers, Path root, Path path, boolean directory) {
        for (Layer layer : layers) {          // ArrayDeque 的迭代顺序是栈顶到栈底
            String relative = relativize(layer.base, path);
            Boolean verdict = layer.rules.match(relative, directory);
            if (verdict != null) {
                return verdict;
            }
        }
        return false;
    }

    /** 相对路径，统一用 {@code /}。落在 base 之外时退回绝对路径，不抛。 */
    private static String relativize(Path base, Path path) {
        try {
            return base.relativize(path).toString().replace(java.io.File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return path.toString().replace(java.io.File.separatorChar, '/');
        }
    }

    /** 一个 .gitignore 文件和它管辖的目录。 */
    private record Layer(Path base, GitIgnore rules) {
    }
}
