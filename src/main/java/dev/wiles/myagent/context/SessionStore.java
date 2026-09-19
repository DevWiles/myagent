package dev.wiles.myagent.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.tool.GitBranch;
import dev.wiles.myagent.tool.WorkspaceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 会话存档的读写：对话落在 {@code ~/.myagent/sessions/<工作区>/<会话 id>.json}，
 * <b>一次会话一份</b>。
 *
 * <p>结构和 {@link dev.wiles.myagent.plan.PlanStore} 是一对孪生：同样的命名
 * （{@link WorkspaceKey}）、同样的临时文件加原子替换、同样的<b>绝不抛异常</b>。
 * 存不下就这次白聊了，读不出来就当没有，不能为了存档让正在进行的对话挂掉。
 *
 * <h3>为什么按工作区分成子目录</h3>
 * 会话仍然是<b>按工作区隔离</b>的，只是同一个工作区下可以有多份。
 * 分目录不是为了好看：列一个工作区的历史 = {@code Files.list} 一个目录，
 * 不用把 {@code sessions/} 下所有项目的存档都读出来再按前缀筛。
 *
 * <h3>为什么改成一次会话一份</h3>
 * 原来一个工作区只留一份，覆盖式写入，理由是「接着上次聊」只有一个答案。
 * 这个理由在<b>没有选择器</b>的前提下成立；一旦要让用户回头翻三天前那段对话，
 * 覆盖式写入意味着那段对话从来就没被留下来过。
 * 挑错会话的代价（拿着过时上下文往下做）由界面去挡：列表里带首句、轮数、时间。
 *
 * <p>目前<b>不清旧档</b>。一份会话是几十 KB 量级，堆到需要清理之前，
 * 先让它堆着——真要清，也得先有个说得清的规则（按天数还是按份数），而不是顺手删。
 *
 * <h3>格式 1 的老存档</h3>
 * 老结构是 {@code sessions/<工作区>.json} 平铺一份。改结构不能把它当不存在：
 * 用户上一次没聊完的对话就在里面。{@link #adoptLegacy} 会在第一次列表时
 * 把它收成历史里的一条会话（先写新的，再删老的），之后老文件就不在了。
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final TypeReference<List<ChatMessage>> MESSAGE_LIST =
            new TypeReference<>() {
            };

    private static final String SUFFIX = ".json";

    /** 老格式：一个工作区平铺一份。只在 {@link #adoptLegacy} 里认这个版本号。 */
    private static final int LEGACY_FORMAT = 1;

    private final Path dir;

    public SessionStore(Path dir) {
        this.dir = dir;
    }

    /** 默认位置：{@code ~/.myagent/sessions/}，和 plans/、memory/ 并排。 */
    public static SessionStore inUserHome() {
        return new SessionStore(Path.of(System.getProperty("user.home"), ".myagent", "sessions"));
    }

    /**
     * 存一次。传进来的是<b>完整历史</b>（含 index 0 的 system），
     * {@link SessionSnapshot#of} 会把 system 摘掉——理由见那个类的注释。
     *
     * <p>只有 system 一条（也就是还没聊过）时直接删档：留着一份空会话，
     * 历史列表里就会多出一行什么都没说过的对话。
     *
     * @param id        往哪一份里写。调用方（一次 REPL 运行）自己攥着这个 id，
     *                  接回一段旧会话时把它换成那段的 id，于是后面每轮都续在同一份上
     * @param startedAt 这段对话最早那一句的时刻；接回旧会话时要沿用旧的，
     *                  不然列表里的「什么时候开始的」会每次续聊都被刷新成现在
     */
    public void save(String id, List<ChatMessage> history, Path workspace,
                     String model, long startedAt) {
        save(id, history, workspace, model, startedAt, List.of(), TokenLedger.EMPTY);
    }

    /** 带上摘要段和用量账本一起存：续聊时既不用重算摘要，也不会把账清零。 */
    public void save(String id, List<ChatMessage> history, Path workspace,
                     String model, long startedAt, List<SummarySegment> segments,
                     TokenLedger ledger) {
        if (workspace == null) {
            return;
        }
        if (!SessionId.isValid(id)) {
            // 不认识的 id 一律不写。这里放过去，就是拿它去拼文件名
            log.warn("会话 id 不合格，本次不存档: {}", id);
            return;
        }
        if (history == null || history.size() <= 1) {
            delete(workspace, id);
            return;
        }
        // 分支在这儿现算，不让调用方传：它是存档的属性（「这段是在哪个分支上聊的」），
        // 而且 GitBranch 只读一个小文件，比在两个界面里各记一遍靠得住
        SessionSnapshot snapshot = SessionSnapshot.of(id, history, workspace, model,
                GitBranch.of(workspace), startedAt, System.currentTimeMillis(),
                segments, ledger);
        if (write(snapshot, pathFor(workspace, id))) {
            log.debug("会话已存档: {}（{} 条）", id, snapshot.messages().size());
        }
    }

    /**
     * 读出某一份会话。
     *
     * <p>四道闸：id 认得出、格式版本对得上、<b>文件名和内容里的 id 对得上</b>、
     * <b>而且属于这个工作区</b>。后两条看着多余（名字就是按这两样算的），
     * 但哈希可能撞、用户也可能拷过文件，
     * 而拿 A 项目的上下文接着聊 B 项目，模型会给出看起来很笃定的错误结论。
     */
    public Optional<SessionSnapshot> load(Path workspace, String id) {
        if (workspace == null || !SessionId.isValid(id)) {
            return Optional.empty();
        }
        Path file = pathFor(workspace, id);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            SessionSnapshot snapshot = MAPPER.readValue(
                    Files.readString(file, StandardCharsets.UTF_8), SessionSnapshot.class);
            if (!snapshot.isRestorable()) {
                log.debug("会话存档不可用（格式 {}，id {}，{} 条消息）",
                        snapshot.format(), snapshot.id(), snapshot.messages().size());
                return Optional.empty();
            }
            if (!id.equals(snapshot.id())) {
                log.warn("会话存档 {} 里记的 id 是 {}，和文件名对不上，不恢复", file, snapshot.id());
                return Optional.empty();
            }
            if (!snapshot.belongsTo(workspace)) {
                log.warn("会话存档记的工作区是 {}，和当前的 {} 对不上，不恢复",
                        snapshot.workspace(), workspace);
                return Optional.empty();
            }
            // 修补之后一条都不剩，等于没有可恢复的内容
            if (snapshot.restorable().isEmpty()) {
                log.debug("会话存档修补后是空的，当作没有");
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (IOException | RuntimeException e) {
            log.warn("会话存档读不出来，当作没有: {}", e.toString());
            return Optional.empty();
        }
    }

    /** 这个工作区最近存过的那一份。「接着上次聊」走这条，不用挑。 */
    public Optional<SessionSnapshot> latest(Path workspace) {
        return loadAll(workspace).stream().findFirst();
    }

    /** 这个工作区的会话历史，最近存过的在最前面。给选择器用。 */
    public List<SessionSummary> list(Path workspace) {
        return loadAll(workspace).stream()
                .map(snapshot -> snapshot.summary(sizeOf(workspace, snapshot.id())))
                .toList();
    }

    /** 存档多大。量不出来就说 0——为了列表里一个附加信息让整个列表翻车不值得。 */
    private long sizeOf(Path workspace, String id) {
        try {
            return Files.size(pathFor(workspace, id));
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** 删掉某一份。 */
    public void delete(Path workspace, String id) {
        if (workspace != null && SessionId.isValid(id)) {
            deleteQuietly(pathFor(workspace, id));
        }
    }

    /**
     * 把这个工作区的会话全读出来，按最后存盘时间倒序。
     *
     * <p>列表也走这里，也就是说打开选择器会把每份存档的全文都解一遍。
     * 现在这么做是因为摘要里的轮数和首句<b>只能从正文算出来</b>——
     * 把它们也写进存档就得多一份冗余，而冗余和正文对不上的时候更难查。
     * 等到会话多得让列表变慢，再谈给摘要单独存一份索引。
     */
    private List<SessionSnapshot> loadAll(Path workspace) {
        if (workspace == null) {
            return List.of();
        }
        adoptLegacy(workspace);
        Path workspaceDir = dirFor(workspace);
        if (!Files.isDirectory(workspaceDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(workspaceDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .filter(SessionId::isValid)      // 写坏的、别人丢进来的，一律不认
                    .map(id -> load(workspace, id))
                    .flatMap(Optional::stream)
                    // 同一毫秒存的两份（测试里常见，真实场景也不是不可能）得有个定死的先后，
                    // 否则列表每次打开顺序都可能不一样。id 带时间前缀，拿它兜底
                    .sorted(Comparator.comparingLong(SessionSnapshot::savedAt)
                            .thenComparing(SessionSnapshot::id)
                            .reversed())
                    .toList();
        } catch (IOException | RuntimeException e) {
            log.warn("会话列表读不出来，当作没有: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 把格式 1 的老存档收成历史里的一条会话。没有老档就什么都不做。
     *
     * <p>顺序是<b>先写新的、再删老的</b>。反过来的话，中间挂掉就等于
     * 「升个版本把上次没聊完的对话弄丢了」——而用户根本不会知道发生过什么。
     * 反之多写一份、老档没删掉，下次进来再收一遍，靠
     * {@link SessionId#forLegacy} 的固定尾巴覆盖掉，不会留两条。
     *
     * <p>看不懂的老档（版本号不是 1、或者根本不是 JSON）<b>留在原地不动</b>：
     * 收不进来就算了，但不能顺手删掉一个自己都没看懂的文件。
     */
    private void adoptLegacy(Path workspace) {
        Path legacy = dir.resolve(WorkspaceKey.of(workspace) + SUFFIX);
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readString(legacy, StandardCharsets.UTF_8));
            if (node.path("format").asInt() != LEGACY_FORMAT) {
                log.warn("{} 不是格式 {} 的老存档，留在原地不动", legacy, LEGACY_FORMAT);
                return;
            }
            List<ChatMessage> messages = MAPPER.convertValue(node.path("messages"), MESSAGE_LIST);
            if (messages == null || messages.isEmpty()) {
                deleteQuietly(legacy);      // 空的，没什么可收
                return;
            }
            long savedAt = node.path("savedAt").asLong();
            String id = SessionId.forLegacy(savedAt);
            // 老档没记开始时间，拿 savedAt 顶：宁可显示得晚一点，不能显示成 1970 年
            // branch 传 null 而不是现在的分支：老档没记过这件事，
            // 拿今天的分支去填等于编一个「那段是在 dev 上聊的」出来
            SessionSnapshot adopted = new SessionSnapshot(SessionSnapshot.FORMAT, id,
                    node.path("workspace").asText(null), node.path("model").asText(null),
                    null, savedAt, savedAt, messages, List.of(), TokenLedger.EMPTY);
            if (!adopted.belongsTo(workspace)) {
                log.warn("老存档 {} 记的工作区是 {}，不收", legacy, adopted.workspace());
                return;
            }
            if (write(adopted, pathFor(workspace, id))) {
                deleteQuietly(legacy);
                log.info("老会话存档已收进历史: {} -> {}", legacy.getFileName(), id);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("老会话存档收不进来，留在原地: {}", e.toString());
        }
    }

    /** 存档文件的位置，命名规则见 {@link WorkspaceKey} 和 {@link SessionId}。 */
    Path pathFor(Path workspace, String id) {
        return dirFor(workspace).resolve(id + SUFFIX);
    }

    /** 这个工作区的会话目录。 */
    Path dirFor(Path workspace) {
        return dir.resolve(WorkspaceKey.of(workspace));
    }

    /** 写一份存档。写不下去返回 false——调用方靠它决定要不要删老档。 */
    private boolean write(SessionSnapshot snapshot, Path target) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(tmp, MAPPER.writeValueAsString(snapshot), StandardCharsets.UTF_8);
            move(tmp, target);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("会话存档失败（不影响本次对话）: {}", e.toString());
            deleteQuietly(tmp);
            return false;
        }
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("删不掉 {}: {}", path, e.toString());
        }
    }
}
