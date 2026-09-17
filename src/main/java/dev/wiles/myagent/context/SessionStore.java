package dev.wiles.myagent.context;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.tool.WorkspaceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * 会话存档的读写：对话落在 {@code ~/.myagent/sessions/} 下，一个工作区一份。
 *
 * <p>结构和 {@link dev.wiles.myagent.plan.PlanStore} 是一对孪生：同样的命名
 * （{@link WorkspaceKey}）、同样的临时文件加原子替换、同样的<b>绝不抛异常</b>。
 * 存不下就这次白聊了，读不出来就当没有，不能为了存档让正在进行的对话挂掉。
 *
 * <h3>为什么一个工作区只留一份</h3>
 * 和计划存档同理：「我在这个项目里聊到一半，回来接着聊」这个问题只有一个答案。
 * 留一堆历史会话，用户得先挑一个，挑错的代价是拿着过时的上下文继续往下做。
 * 真想要多会话管理，那是另一个功能，不该靠存档堆出来。
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

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
     * 下次启动会提示用户「要不要接着上次的聊」，而上次根本什么都没说。
     */
    public void save(List<ChatMessage> history, Path workspace, String model) {
        if (workspace == null) {
            return;
        }
        if (history == null || history.size() <= 1) {
            delete(workspace);
            return;
        }
        Path target = pathFor(workspace);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(dir);
            SessionSnapshot snapshot =
                    SessionSnapshot.of(history, workspace, model, System.currentTimeMillis());
            Files.writeString(tmp, MAPPER.writeValueAsString(snapshot), StandardCharsets.UTF_8);
            move(tmp, target);
            log.debug("会话已存档: {}（{} 条）", target, snapshot.messages().size());
        } catch (IOException | RuntimeException e) {
            log.warn("会话存档失败（不影响本次对话）: {}", e.toString());
            deleteQuietly(tmp);
        }
    }

    /**
     * 读出这个工作区上次没聊完的会话。
     *
     * <p>三道闸：格式版本对得上、确实有内容、<b>而且属于这个工作区</b>。
     * 最后一条看着多余（文件名就按工作区算），但哈希可能撞、用户也可能拷过文件，
     * 而拿 A 项目的上下文接着聊 B 项目，模型会给出看起来很笃定的错误结论。
     */
    public Optional<SessionSnapshot> peek(Path workspace) {
        if (workspace == null) {
            return Optional.empty();
        }
        Path file = pathFor(workspace);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            SessionSnapshot snapshot = MAPPER.readValue(
                    Files.readString(file, StandardCharsets.UTF_8), SessionSnapshot.class);
            if (!snapshot.isRestorable()) {
                log.debug("会话存档不可用（格式 {}，{} 条消息）",
                        snapshot.format(), snapshot.messages().size());
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

    /** 删档。用户 {@code /clear} 或者开了新话题时该删——不然下次会提示恢复一段他已经不要的对话。 */
    public void delete(Path workspace) {
        if (workspace != null) {
            deleteQuietly(pathFor(workspace));
        }
    }

    /** 存档文件的位置，命名规则见 {@link WorkspaceKey}。 */
    Path pathFor(Path workspace) {
        return dir.resolve(WorkspaceKey.of(workspace) + ".json");
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
