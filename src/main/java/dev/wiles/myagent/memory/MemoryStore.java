package dev.wiles.myagent.memory;

import dev.wiles.myagent.tool.WorkspaceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 长期记忆的读写。一条记忆一个 Markdown 文件，落在 {@code ~/.myagent/memory/} 下。
 *
 * <pre>
 * ~/.myagent/memory/
 *   global/
 *     user-prefers-cn.md          ← 跨项目可见
 *   myagent-a1b2c3d4e5f6/         ← 工作区标识，见 WorkspaceKey
 *     mvn-offline.md
 * </pre>
 *
 * <h3>为什么是一堆 Markdown 小文件，而不是一个 JSON</h3>
 * 因为<b>用户要能直接改</b>。agent 记错了东西（它一定会），最顺手的修法是打开文件删掉它，
 * 而不是找一条命令、或者跟 agent 掰扯「你忘掉那件事」。一个 JSON 数组做不到这点：
 * 手改容易破坏格式，破坏之后<b>所有</b>记忆一起失效。一条一个文件，
 * 改坏一个只损失一个，剩下的照常有效。
 *
 * <h3>关键决定：索引不落盘</h3>
 * 每次要索引就<b>现扫目录、现读 frontmatter</b>，不维护 {@code MEMORY.md} 之类的索引文件。
 *
 * <p>索引文件的问题是它会和正文<b>漂移</b>：用户手删了一个 .md，索引里那行还在，
 * 模型照着索引去 {@code recall} 扑个空；反过来手加一个文件，索引里没有，等于没记。
 * 两边同步这件事没有任何机制能保证——除非每次都扫一遍，那还要索引文件干嘛。
 *
 * <p>代价是每次建索引要读 N 个小文件。按几十到一两百条的规模，这点开销可以忽略；
 * 真到了上千条，那时候该换的是整个召回策略（见 {@link #MAX_ENTRIES}），不止是索引。
 *
 * <h3>这个类不抛异常</h3>
 * 和 {@link dev.wiles.myagent.plan.PlanStore} 同一个态度：记忆是锦上添花。
 * 存不下就这次没记住，读不出来就当没有，<b>绝不为了它让一轮正常的对话挂掉</b>。
 */
public final class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    /** 全局记忆放这个子目录，名字不会和工作区标识撞——后者一定带 {@code -哈希} 后缀。 */
    static final String GLOBAL_DIR = "global";

    /**
     * 一次扫描最多读多少条——<b>读盘这一侧</b>的闸。
     *
     * <p>它管的是「别把整个目录无限读进内存」，<b>不代表这么多条都发得进提示词</b>。
     * 进提示词那一侧的闸是 {@link MemoryIndex#MAX_TOKENS}，按真实 token 记。
     *
     * <p>两个闸必须分开，是踩过才明白的：这里原先拿「每条约 50 token」倒推出 200，
     * 顺手就把它当成了提示词预算。但每条的 description 可以写到
     * {@link MemoryEntry#MAX_DESCRIPTION} 个字，中文约 130 token，
     * 200 条实际是 27,000 token——名义预算的 2.7 倍，而这个闸一声不响，
     * 因为它数的是条数。<b>凡是按 A 计量却拿来管 B 的闸，迟早都是这个结局。</b>
     *
     * <p>超出时<b>会打日志说明丢了多少</b>。悄悄截断最要命：
     * 用户以为记住的东西在里面，其实早被砍掉了。
     */
    public static final int MAX_ENTRIES = 200;

    /** 单条正文的长度上限。正文是按需读的，可以写长，但不能没有边。 */
    public static final int MAX_CONTENT = 4_000;

    private static final String DELIMITER = "---";
    private static final String SUFFIX = ".md";

    private final Path root;

    public MemoryStore(Path root) {
        this.root = root;
    }

    /** 默认位置：{@code ~/.myagent/memory/}，和 config.json、plans/ 并排。 */
    public static MemoryStore inUserHome() {
        return new MemoryStore(Path.of(System.getProperty("user.home"), ".myagent", "memory"));
    }

    // ------------------------------------------------------------------ 写

    /**
     * 写一条。同名覆盖——记忆被修正是正常的，留两份反而说不清该信哪个。
     *
     * @return 成功与否。失败已经打过日志，调用方只需要决定怎么告诉模型
     */
    public boolean save(MemoryEntry entry, Path workspace) {
        if (entry == null) {
            return false;
        }
        Path target = pathFor(entry.name(), entry.scope(), workspace);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            // 临时文件 + 原子替换，理由同 PlanStore：
            // 写到一半进程没了的话，盘上会留下半个文件，下次扫描时它是一条坏记忆
            Files.writeString(tmp, render(entry), StandardCharsets.UTF_8);
            move(tmp, target);
            log.debug("记住了: {}", target);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("写记忆失败（不影响本轮对话）: {}", e.toString());
            deleteQuietly(tmp);
            return false;
        }
    }

    /** 忘掉一条。两个 scope 都找，删掉哪个算哪个。 */
    public boolean forget(String name, Path workspace) {
        String slug;
        try {
            slug = MemoryEntry.normalizeName(name);
        } catch (IllegalArgumentException e) {
            return false;
        }
        boolean removed = false;
        for (MemoryScope scope : MemoryScope.values()) {
            removed |= deleteQuietly(pathFor(slug, scope, workspace));
        }
        return removed;
    }

    // ------------------------------------------------------------------ 读

    /**
     * 按名字读一条正文。
     *
     * <p><b>先找项目级，再找全局</b>。同名时项目级赢——它更具体，
     * 而具体的那条通常就是用来推翻通用规则的（「一般用 npm，但这个项目用 bun」）。
     */
    public Optional<MemoryEntry> load(String name, Path workspace) {
        String slug;
        try {
            slug = MemoryEntry.normalizeName(name);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        for (MemoryScope scope : List.of(MemoryScope.PROJECT, MemoryScope.GLOBAL)) {
            Optional<MemoryEntry> hit = read(pathFor(slug, scope, workspace), scope);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /**
     * 扫出这个工作区能看见的全部记忆：该项目的 + 全局的。
     *
     * <p>坏文件被跳过，不会让整次扫描失败——一条记忆被手改坏了，
     * 不该连累其余几十条一起消失。
     */
    public List<MemoryEntry> scan(Path workspace) {
        List<MemoryEntry> all = new ArrayList<>();
        all.addAll(readDir(dirFor(MemoryScope.PROJECT, workspace), MemoryScope.PROJECT));
        all.addAll(readDir(dirFor(MemoryScope.GLOBAL, workspace), MemoryScope.GLOBAL));

        // 项目级排在前面，同级按名字——顺序稳定，提示词才有缓存命中的可能
        all.sort(Comparator
                .comparing((MemoryEntry e) -> e.scope() == MemoryScope.PROJECT ? 0 : 1)
                .thenComparing(MemoryEntry::name));

        if (all.size() > MAX_ENTRIES) {
            log.warn("记忆共 {} 条，超过上限 {}，索引里只放前 {} 条——"
                    + "剩下的 {} 条模型看不见，该清理或改用检索了",
                    all.size(), MAX_ENTRIES, MAX_ENTRIES, all.size() - MAX_ENTRIES);
            return List.copyOf(all.subList(0, MAX_ENTRIES));
        }
        return List.copyOf(all);
    }

    /**
     * 扫出来排版成索引，并<b>裁到放得进 token 预算</b>。这是给系统提示词用的那个入口。
     *
     * <p>裁剪发生在这里而不是 {@link #scan}：scan 还要服务 {@code recall}——
     * 那条路是按名字取一条，跟提示词预算没关系，不该被它连累。
     */
    public MemoryIndex index(Path workspace) {
        return index(workspace, MemoryIndex.MAX_TOKENS);
    }

    /**
     * 按给定预算裁。调用方通常传 {@code MemoryIndex.budgetFor(上下文窗口)}——
     * 索引住在 system 里，而 system 永远不压缩，窗口小的时候它得跟着缩。
     */
    public MemoryIndex index(Path workspace, int budgetTokens) {
        MemoryIndex full = MemoryIndex.of(scan(workspace));
        MemoryIndex fitted = full.withinBudget(budgetTokens);
        if (fitted.size() < full.size()) {
            log.warn("记忆索引约 {} token，超过预算 {}，只放前 {} 条——"
                    + "剩下的 {} 条模型看不见了。该精简 description 或清理旧记忆",
                    full.estimatedTokens(), budgetTokens,
                    fitted.size(), full.size() - fitted.size());
        }
        return fitted;
    }

    // ------------------------------------------------------------------ 内部

    /** 某条记忆该落在哪个文件。 */
    Path pathFor(String slug, MemoryScope scope, Path workspace) {
        return dirFor(scope, workspace).resolve(slug + SUFFIX);
    }

    /** 某个 scope 对应的目录。 */
    Path dirFor(MemoryScope scope, Path workspace) {
        return scope == MemoryScope.GLOBAL
                ? root.resolve(GLOBAL_DIR)
                : root.resolve(WorkspaceKey.of(workspace));
    }

    private List<MemoryEntry> readDir(Path dir, MemoryScope scope) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<MemoryEntry> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .forEach(p -> read(p, scope).ifPresent(found::add));
        } catch (IOException | UncheckedIOException e) {
            log.warn("扫不了记忆目录 {}，当作空的: {}", dir, e.toString());
        }
        return found;
    }

    /**
     * 读一个文件。读不出来就返空——<b>坏的那条被跳过，不是整批失败</b>。
     *
     * @param scope 由所在目录决定，而不是信文件里写的：
     *              用户把文件从 global/ 拖到项目目录下，语义就该跟着变，
     *              而不是让文件里一个过时的字段说了算
     */
    private Optional<MemoryEntry> read(Path file, MemoryScope scope) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // 非 UTF-8、权限不足、读到一半被删……都走这里
            log.warn("记忆 {} 读不出来，跳过: {}", file.getFileName(), e.toString());
            return Optional.empty();
        }
        try {
            return Optional.of(parse(raw, scope, file));
        } catch (RuntimeException e) {
            log.warn("记忆 {} 格式不对，跳过: {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    /** frontmatter + 正文。格式不对就抛，由 {@link #read} 兜成「跳过这条」。 */
    private static MemoryEntry parse(String raw, MemoryScope scope, Path file) {
        List<String> lines = raw.lines().toList();
        if (lines.isEmpty() || !DELIMITER.equals(lines.get(0).strip())) {
            throw new IllegalArgumentException("缺少 frontmatter 起始的 ---");
        }
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (DELIMITER.equals(lines.get(i).strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("frontmatter 没有结束的 ---");
        }

        String name = null;
        String description = null;
        String source = null;
        String created = null;
        for (int i = 1; i < end; i++) {
            String line = lines.get(i);
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;   // 空行、注释、写歪了的行，忽略就好
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();   // 只切第一个冒号，值里可以有冒号
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                case "source" -> source = value;
                case "created" -> created = value;
                default -> { }
            }
        }

        // 文件名才是权威的 name：用户重命名文件之后，recall 靠的是文件名而不是里面那行
        String fileName = file.getFileName().toString();
        String fromFile = fileName.substring(0, fileName.length() - SUFFIX.length());
        if (name == null || name.isBlank()) {
            name = fromFile;
        } else if (!MemoryEntry.normalizeName(name).equals(fromFile)) {
            log.debug("记忆 {} 里写的 name 是 {}，以文件名为准", fileName, name);
            name = fromFile;
        }

        String content = String.join("\n", lines.subList(end + 1, lines.size())).strip();
        if (content.length() > MAX_CONTENT) {
            content = content.substring(0, MAX_CONTENT) + "\n…（正文过长已截断）";
        }

        return new MemoryEntry(name, description, content, scope,
                MemorySource.from(source), parseDate(created));
    }

    /** 日期坏了不算致命——记忆本身还是好的，给个今天顶上。 */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (RuntimeException e) {
            return LocalDate.now();
        }
    }

    private static String render(MemoryEntry e) {
        return DELIMITER + "\n"
                + "name: " + e.name() + "\n"
                + "description: " + e.description() + "\n"
                + "scope: " + e.scope().wire() + "\n"
                + "source: " + e.source().wire() + "\n"
                + "created: " + e.created() + "\n"
                + DELIMITER + "\n\n"
                + e.content() + "\n";
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("删不掉 {}: {}", path, e.toString());
            return false;
        }
    }
}
