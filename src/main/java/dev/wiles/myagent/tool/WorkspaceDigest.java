package dev.wiles.myagent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 工作区的目录树摘要——给<b>规划器</b>看的那一眼。
 *
 * <p>为什么需要它：规划模型如果对仓库一无所知，就只能凭空编文件名，
 * 或者把一半的任务花在「先摸摸情况」上。给它一棵树，很多探路任务当场就省了。
 *
 * <p>三条裁剪规则，每条都是为了<b>省 token</b>——这段文字会跟着每一次规划请求发出去：
 * <ul>
 *   <li><b>限深度</b>：三层足够看清项目骨架。再深就是实现细节，那是执行阶段
 *       用 {@code list_dir} 现查的事，不该预付给规划。</li>
 *   <li><b>限条目</b>：大仓库一层就能有上千个文件，不设上限这段摘要能把上下文吃光。</li>
 *   <li><b>跳过产物目录</b>：{@code target/}、{@code node_modules/} 这些既不是人写的，
 *       数量还最多，是最不值钱的 token。</li>
 * </ul>
 *
 * <p><b>永不抛异常</b>：读不动的目录就当它不存在。摘要是锦上添花的东西，
 * 为了它让整次规划失败，本末倒置。
 */
public final class WorkspaceDigest {

    /** 展开几层。root 的直接子项算第一层。 */
    public static final int MAX_DEPTH = 3;

    /** 最多列几项。超了就截断并明说，不能悄悄少给——模型会以为那些文件不存在。 */
    public static final int MAX_ENTRIES = 120;

    /**
     * 不进去的目录：构建产物、依赖缓存、IDE 配置。
     *
     * <p>用黑名单而不是白名单，因为源码目录的名字千奇百怪（src/lib/app/cmd/internal…），
     * 白名单必然漏掉真正该看的东西；而该跳过的产物目录就那么几个，且相当稳定。
     * 这和 {@link TaskTools} 用白名单的取舍正好相反——那边漏一个是安全缺口，
     * 这边漏一个只是多几行噪音。
     */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".vscode", ".mvn", ".gradle",
            "target", "build", "out", "dist", "node_modules",
            "__pycache__", ".venv", "venv", ".next", ".cache");

    private WorkspaceDigest() {
    }

    public static String of(Path root) {
        return of(root, MAX_DEPTH, MAX_ENTRIES);
    }

    public static String of(Path root, int maxDepth, int maxEntries) {
        if (root == null || !Files.isDirectory(root)) {
            return "（工作区不可读）";
        }
        List<String> lines = new ArrayList<>();
        boolean truncated = walk(root, 0, maxDepth, maxEntries, "", lines);
        if (lines.isEmpty()) {
            return "（空目录）";
        }
        if (truncated) {
            lines.add("…（目录太大，只列出前 " + maxEntries + " 项）");
        }
        return String.join("\n", lines);
    }

    /** @return 是否因为撞上条目上限而截断 */
    private static boolean walk(Path dir, int depth, int maxDepth, int maxEntries,
                                String indent, List<String> lines) {
        for (Path child : children(dir)) {
            if (lines.size() >= maxEntries) {
                return true;
            }
            boolean isDir = Files.isDirectory(child);
            // 目录带一个尾斜杠：模型一眼能分清「这是个包」还是「这是个文件」
            lines.add(indent + child.getFileName() + (isDir ? "/" : ""));
            if (isDir && depth + 1 < maxDepth
                    && walk(child, depth + 1, maxDepth, maxEntries, indent + "  ", lines)) {
                return true;
            }
        }
        return false;
    }

    /** 目录排在文件前面，各自再按名字排。顺序稳定，摘要才能进缓存、也才能断言。 */
    private static List<Path> children(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(WorkspaceDigest::worthListing)
                    .sorted(Comparator
                            .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException | RuntimeException e) {
            return List.of();   // 读不动就当空的，绝不让一次规划为了摘要失败
        }
    }

    private static boolean worthListing(Path path) {
        String name = path.getFileName().toString();
        // 隐藏文件一律不列：.env 之类还可能带密钥，没有任何理由送进模型上下文
        return !name.startsWith(".") && !SKIP_DIRS.contains(name);
    }
}
