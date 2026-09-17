package dev.wiles.myagent.plan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.WorkspaceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 计划存档的读写：跑到一半的计划落在 {@code ~/.myagent/plans/} 下，下次能接着跑。
 *
 * <h3>一个工作区一份存档</h3>
 * 文件名是<b>目录名 + 工作区绝对路径的哈希</b>，比如 {@code myagent-backend-a1b2c3d4.json}。
 * 前半截给人看（一眼知道是哪个项目），后半截保证不同路径不会撞车——
 * 光用目录名的话，{@code ~/a/demo} 和 {@code ~/b/demo} 会互相覆盖。
 *
 * <p>为什么一个工作区只留一份而不是每份计划一个文件：断点续跑要回答的问题是
 * 「我在这个项目里跑了一半，回来接着跑」，这个问题只可能有一个答案。
 * 留一堆历史存档，用户还得先挑一个，而挑错的后果是拿着旧步骤去改已经变了的代码。
 *
 * <h3>这个类不抛异常</h3>
 * 存档是<b>锦上添花</b>：存不下就下次从头来，读不出来就当没有。
 * 为了它让一份正在跑的计划挂掉，本末倒置。所有 IO 异常在这里就地消化成日志，
 * 和 {@code WorkspaceDigest} 是同一个态度。
 */
public final class PlanStore {

    private static final Logger log = LoggerFactory.getLogger(PlanStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path dir;

    public PlanStore(Path dir) {
        this.dir = dir;
    }

    /** 默认位置：{@code ~/.myagent/plans/}，和 config.json、logs/ 并排。 */
    public static PlanStore inUserHome() {
        return new PlanStore(Path.of(System.getProperty("user.home"), ".myagent", "plans"));
    }

    /**
     * 存一次。<b>写临时文件再原子替换</b>——直接往目标文件上写的话，
     * 进程要是恰好在写到一半时没了，盘上留下的是半个 JSON：
     * 下次启动读不出来，而用户看到的现象是「明明存过却续不了」，最难查的那种。
     */
    public void save(Plan plan, Path workspace) {
        if (plan == null || workspace == null) {
            return;
        }
        Path target = pathFor(workspace);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(dir);
            PlanCheckpoint checkpoint =
                    PlanCheckpoint.of(plan, workspace, System.currentTimeMillis());
            Files.writeString(tmp, MAPPER.writeValueAsString(checkpoint), StandardCharsets.UTF_8);
            move(tmp, target);
            log.debug("计划已存档: {}", target);
        } catch (IOException | RuntimeException e) {
            log.warn("存档失败（不影响本次执行）: {}", e.toString());
            deleteQuietly(tmp);
        }
    }

    /**
     * 读出这个工作区上次没跑完的计划。
     *
     * <p>三道闸都过了才返还：格式版本对得上、计划确实停在半路、<b>而且属于这个工作区</b>。
     * 最后一条看着多余（文件名就是按工作区算的），但哈希是可能撞的，
     * 用户也可能手工拷贝过存档文件——续跑用错计划的代价是拿 A 项目的步骤去改 B 项目。
     */
    public Optional<PlanCheckpoint> peek(Path workspace) {
        if (workspace == null) {
            return Optional.empty();
        }
        Path file = pathFor(workspace);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            PlanCheckpoint checkpoint = MAPPER.readValue(
                    Files.readString(file, StandardCharsets.UTF_8), PlanCheckpoint.class);
            if (!checkpoint.isResumable()) {
                log.debug("存档不可续（格式 {} 或状态 {}）", checkpoint.format(), checkpoint.status());
                return Optional.empty();
            }
            if (!checkpoint.belongsTo(workspace)) {
                log.warn("存档记的工作区是 {}，和当前的 {} 对不上，不续", checkpoint.workspace(), workspace);
                return Optional.empty();
            }
            return Optional.of(checkpoint);
        } catch (IOException | RuntimeException e) {
            log.warn("存档读不出来，当作没有: {}", e.toString());
            return Optional.empty();
        }
    }

    /** 读出来并立成一份能接着跑的计划。存档坏了就返空，不抛。 */
    public Optional<Plan> load(Path workspace) {
        return peek(workspace).flatMap(checkpoint -> {
            try {
                return Optional.of(checkpoint.toPlan());
            } catch (RuntimeException e) {
                log.warn("存档立不起来，当作没有: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** 删档。计划进了终态就该删——留着它，下次启动会提示续一份已经跑完的计划。 */
    public void delete(Path workspace) {
        if (workspace != null) {
            deleteQuietly(pathFor(workspace));
        }
    }

    /** 存档文件的位置。命名规则见 {@link WorkspaceKey}：目录名给人看，哈希保证不撞车。 */
    Path pathFor(Path workspace) {
        return dir.resolve(WorkspaceKey.of(workspace) + ".json");
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 有些文件系统（网络盘、某些容器挂载）不支持原子移动，退而求其次
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
