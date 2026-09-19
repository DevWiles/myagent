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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Objects;
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

    /**
     * frontmatter 里由类型化字段管的键，不许再进 metadata。
     *
     * <p>漏掉哪一个，那个键就会被写两遍：一遍来自字段、一遍来自 metadata。
     * 解析时后写的那遍会被 {@code default} 分支收走，于是每保存一次文件就长一行。
     */
    private static final Set<String> RESERVED_KEYS =
            Set.of("name", "description", "scope", "source", "created");

    /**
     * 两个主题词是否指同一件事。
     *
     * <p>不用 {@code equals}：主题词由模型生成，同一件事两次可能给出「测试命令」和
     * 「构建测试命令」——精确匹配下取代永远不触发，矛盾的事实并列注入，
     * 而模型无从判断哪条新。
     *
     * <p>规则：忽略大小写与首尾空白后相等，<b>或</b>短的一方是长的一方的子串
     * 且长度 ≥ 3。长度门槛是必需的——否则「命令」会把「测试命令」和「包管理器命令」
     * 合并成同一主题，把过度取代变成新的问题。
     *
     * <p>这只是确定性兜底。正路是把已有主题词喂给模型让它复用
     * （见 {@link #activeTopicKeys}），本方法兜住它不听话的残余情况。
     */
    static boolean isSameTopic(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String a = left.strip().toLowerCase(Locale.ROOT);
        String b = right.strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        return shorter.length() >= MIN_TOPIC_OVERLAP && longer.contains(shorter);
    }

    /** 子串判定的最短长度。再短就会把不同主题合并，见 {@link #isSameTopic}。 */
    private static final int MIN_TOPIC_OVERLAP = 3;

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
        // 顺序要紧：**先落新的，再标旧的**。
        // 反过来的话，新条目写失败时旧事实已经被标成失效——用户什么都没得到，
        // 还丢了一条原本在用的记忆。这个顺序下最坏情况只是两条同时生效一小会儿，
        // 下次保存就收敛了。
        if (!writeQuietly(entry, workspace)) {
            return false;
        }
        log.debug("记住了: {}", pathFor(entry.name(), entry.scope(), workspace));
        supersedeConflicting(entry, workspace);
        return true;
    }

    /**
     * 把与新事实同主题的旧条目标记为<b>已被取代</b>。
     *
     * <h3>为什么是标记而不是删除</h3>
     * 删了之后用户再也看不到系统曾经记过什么，而「它为什么突然改口了」正是
     * 最该能查清的一类问题。标记之后它退出注入和召回，但还躺在盘上。
     *
     * <h3>判定只做最保守的一级</h3>
     * 要求新条目<b>显式声明了</b> {@code topic_key}，且和旧条目在同一个作用域目录里
     * （也就同一个项目）。没写主题词的条目一律不受影响——存量数据行为完全不变。
     * 「从内容自动推断这两条讲的是不是一件事」需要模型参与，不该在存储层做。
     *
     * @return 实际被标记的条数
     */
    private int supersedeConflicting(MemoryEntry incoming, Path workspace) {
        String topic = incoming.topicKey();
        if (topic == null) {
            return 0;
        }
        // 只扫新条目所在的那个目录：跨作用域取代是另一回事——
        // 一条 global 的通则不该被某个项目里的特例悄悄顶掉
        List<MemoryEntry> sameDir = readDir(dirFor(incoming.scope(), workspace), incoming.scope());
        int marked = 0;
        for (MemoryEntry old : sameDir) {
            if (old.name().equals(incoming.name()) || old.isSuperseded()) {
                continue;   // 同名是覆盖，不是取代；已经失效的不必再标一次
            }
            if (!isSameTopic(topic, old.topicKey())) {
                continue;
            }
            MemoryEntry marked1 = old.with(MemoryMetadata.SUPERSEDED_BY, incoming.name());
            if (writeQuietly(marked1, workspace)) {
                log.info("记忆 {} 被 {} 取代（主题: {}）", old.name(), incoming.name(), topic);
                marked++;
            }
        }
        return marked;
    }

    /**
     * 解除所有「被 supersededBy 取代」的标记，让旧事实重新生效。
     *
     * <p><b>撤销时必须做这一步</b>：不做的话，撤销之后新旧事实<b>都不生效</b>，
     * 用户看到的是「两条都没了」——比不提供撤销更糟。
     *
     * @return 恢复了多少条
     */
    private int restoreSupersededBy(String supersededBy, Path workspace) {
        int restored = 0;
        for (MemoryEntry entry : scan(workspace)) {
            if (!supersededBy.equals(entry.supersededBy())) {
                continue;
            }
            if (writeQuietly(entry.with(MemoryMetadata.SUPERSEDED_BY, null), workspace)) {
                log.info("记忆 {} 重新生效（取代它的 {} 被撤销了）", entry.name(), supersededBy);
                restored++;
            }
        }
        return restored;
    }

    /**
     * 撤销最近一次<b>自动写入</b>的记忆。
     *
     * <h3>不维护撤销栈，目标直接从数据推导</h3>
     * 取「当前工作区可见、仍生效、来源是 {@link MemorySource#OBSERVATION} 的条目里
     * 写得最晚的那一条」。这样做换来三件事：重启之后仍然撤得掉（内存里的栈会丢）、
     * 不可能和盘上的状态不同步、反复调用天然逐条往回退。
     *
     * <p>只认自动写入的：用户自己 {@code remember} 的东西不该被一个叫「撤销」的
     * 命令碰掉——他记的时候是清醒的。
     *
     * @return 被撤销的条目；没有可撤销的自动写入时返回空
     */
    public Optional<MemoryEntry> undoLastAutoWrite(Path workspace) {
        Optional<MemoryEntry> target = scanActive(workspace).stream()
                .filter(MemoryEntry::isAutoWritten)
                .max(Comparator.comparing(MemoryEntry::created)
                        .thenComparing(MemoryEntry::name));
        if (target.isEmpty()) {
            return target;
        }
        MemoryEntry removed = target.get();
        restoreSupersededBy(removed.name(), workspace);   // 顺序要紧：先让旧事实复活
        forget(removed.name(), workspace);
        log.info("已撤销自动记下的 {}", removed.name());
        return Optional.of(removed);
    }

    /**
     * 删掉全部<b>自动写入</b>的条目，用户手动记的一条不动。
     *
     * <p>和 {@link #undoLastAutoWrite} 同一条原则：清理机制不该碰用户自己记的东西。
     * 被它们取代过的旧条目一并复活——道理同撤销。
     *
     * @return 实际删掉的条数
     */
    public int clearAutoMemories(Path workspace) {
        int removed = 0;
        for (MemoryEntry entry : scan(workspace)) {
            if (!entry.isAutoWritten() || entry.isSuperseded()) {
                continue;
            }
            restoreSupersededBy(entry.name(), workspace);
            if (forget(entry.name(), workspace)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("清掉了 {} 条自动记下的记忆", removed);
        }
        return removed;
    }

    /**
     * 当前可见、仍生效的主题词，去重。
     *
     * <p>喂给模型让它<b>复用已有主题</b>，而不是每次凭空命名——取代靠主题匹配，
     * 而模型不知道历史时给出的词会随机漂移（「测试命令」和「构建测试命令」）。
     * {@link #isSameTopic} 的子串规则是兜底，这个才是正路。
     */
    public List<String> activeTopicKeys(Path workspace) {
        return scanActive(workspace).stream()
                .map(MemoryEntry::topicKey)
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_TOPIC_HINTS)
                .toList();
    }

    /** 喂回给模型的主题词最多这么多条。再多就成了提示词里的噪音。 */
    public static final int MAX_TOPIC_HINTS = 30;

    /**
     * 仍然生效的条目——<b>模型视角</b>。
     *
     * <p>和 {@link #scan} 的区别是刻意的：scan 是<b>审计视角</b>，必须包含被取代的
     * 历史条目，用户要能看见系统曾经记过什么；这里是给提示词和召回用的，
     * 被取代的事实一旦注入，模型就会同时收到「用 npm」和「用 bun」两条自相矛盾的
     * 上下文，而它无从判断哪条新。
     */
    public List<MemoryEntry> scanActive(Path workspace) {
        return scan(workspace).stream().filter(e -> !e.isSuperseded()).toList();
    }

    /** 写一条，不管取代关系。取代流程自己用，避免递归。 */
    private boolean writeQuietly(MemoryEntry entry, Path workspace) {
        Path target = pathFor(entry.name(), entry.scope(), workspace);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(tmp, render(entry), StandardCharsets.UTF_8);
            move(tmp, target);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("写记忆 {} 失败（不影响本轮对话）: {}", entry.name(), e.toString());
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
            // 被取代的当作不存在：索引里早就没有它了，模型还来问多半是
            // 从上一段会话里记住的名字，这时候把过期正文给它正是要防的那件事
            if (hit.isPresent() && !hit.get().isSuperseded()) {
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
        MemoryIndex full = MemoryIndex.of(scanActive(workspace));
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
        // LinkedHashMap：保住用户写的键序，改一条记忆不该让整个 frontmatter 重排
        Map<String, String> metadata = new LinkedHashMap<>();
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
                // scope 不在这儿读：它由文件所在目录决定，frontmatter 里那行只是给人看的。
                // 其余的键一律收进 metadata 并原样写回，包括我们不认识的——
                // 这是个用户能直接编辑的文件，解析时丢掉别人写的东西，下次保存就抹了
                default -> {
                    if (!RESERVED_KEYS.contains(key) && !value.isBlank()) {
                        metadata.put(key, value);
                    }
                }
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
                MemorySource.from(source), parseDate(created), metadata);
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

    /**
     * 排版成盘上那个文件。
     *
     * <p>metadata 里的键<b>原样写回</b>，包括我们不认识的——用户在 frontmatter 里
     * 自己加的东西不该因为经手了一次保存就消失。
     */
    private static String render(MemoryEntry e) {
        StringBuilder sb = new StringBuilder(DELIMITER).append("\n")
                .append("name: ").append(e.name()).append("\n")
                .append("description: ").append(e.description()).append("\n")
                .append("scope: ").append(e.scope().wire()).append("\n")
                .append("source: ").append(e.source().wire()).append("\n")
                .append("created: ").append(e.created()).append("\n");
        for (Map.Entry<String, String> kv : e.metadata().entrySet()) {
            if (RESERVED_KEYS.contains(kv.getKey())) {
                continue;   // 类型化字段已经写过了，再写一遍会在解析时打架
            }
            sb.append(kv.getKey()).append(": ").append(oneLine(kv.getValue())).append("\n");
        }
        return sb.append(DELIMITER).append("\n\n").append(e.content()).append("\n").toString();
    }

    /** frontmatter 是一行一个键值，值里有换行会把后面的行全解析错。 */
    private static String oneLine(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").strip();
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
