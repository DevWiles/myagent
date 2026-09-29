package dev.wiles.myagent.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一份计划的存档格式：跑到哪一步、每一步的产出是什么，全在里面。
 *
 * <h3>为什么要存</h3>
 * 在这之前，{@link Plan} 和 {@link Task} 的状态全在内存里。进程一退出——Ctrl-C、
 * 崩溃、关掉终端——跑到一半的计划连同<b>前面那些成功任务的产出</b>一起蒸发，
 * 下次只能从头再来。而那些产出是真金白银换来的：每一条都是一轮模型调用加若干次工具执行。
 *
 * <h3>为什么是「每个任务结束存一次」</h3>
 * 存档的粒度决定崩溃后能恢复到多细。整个计划跑完才存，等于没存；
 * 每个任务结束存一次，最坏情况只丢<b>正在跑的那一个任务</b>。
 * 代价是每个任务多一次几十 KB 的写盘，和一次模型调用比可以忽略。
 *
 * <h3>三个设计点</h3>
 * <ul>
 *   <li><b>带 {@code format} 版本号</b>。格式迟早要改，届时老存档要能被识别出来
 *       并干脆地拒掉，而不是解析出一份字段对不上的残图跑起来。</li>
 *   <li><b>带 {@code workspace}</b>。一份计划里的任务描述全是针对某个仓库写的，
 *       换个目录续跑等于拿着 A 项目的步骤去改 B 项目。载入时必须核对。</li>
 *   <li><b>状态存字符串不存枚举</b>。枚举值哪天改名，老存档会在反序列化时直接炸；
 *       存字符串的话，认不出来的值退回 PENDING 还能救，见 {@link #taskStatus}。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanCheckpoint(
        int format,
        String workspace,
        long savedAt,
        String goal,
        String status,
        long startTime,
        long endTime,
        int replans,
        List<String> recoveryIds,
        List<TaskSnapshot> tasks) {

    private static final Logger log = LoggerFactory.getLogger(PlanCheckpoint.class);

    /** 当前存档格式版本。**改字段含义或删字段时必须 +1**。 */
    public static final int FORMAT = 1;

    /**
     * 一个任务的完整快照。
     *
     * <p>注意<b>没有 dependents</b>：反向边是建图时从正向边推出来的，存它等于把同一个事实
     * 记两遍，两边一旦对不上，调度就会漏跑或者死等。这条不变式在 {@link Task} 里也是一样的。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskSnapshot(
            String id,
            String description,
            String type,
            /**
             * 派给谁。<b>老存档里没有这个字段</b>，那时还没有角色这回事——
             * 读到 null 就按 {@code type} 落到默认角色上（见 {@link #agentRole}），
             * 所以这是一次纯新增，{@link #FORMAT} 不用 +1。
             *
             * <p>{@code type} 留着不删，正是为了这条退路：角色名册是会变的
             * （改名、拆分、删掉一个用不上的角色），而六种类型是稳定的。
             * 存档里两个都有，认不出角色时还能退回类型；只存角色的话，
             * 哪天把 {@code reviewer} 改个名，盘上那些计划就全废了。
             */
            String role,
            List<String> dependsOn,
            String acceptance,
            /** 声明要动的文件。老存档里没有这个字段，读出来是 null，按空处理——纯新增，FORMAT 不用 +1。 */
            List<String> files,
            String parentId,
            int depth,
            String status,
            String result,
            String error,
            long startTime,
            long endTime) {
    }

    public static PlanCheckpoint of(Plan plan, Path workspace, long now) {
        List<TaskSnapshot> snapshots = new ArrayList<>(plan.graph().size());
        for (Task task : plan.graph().tasks()) {
            snapshots.add(new TaskSnapshot(
                    task.id(), task.description(), task.type().name(), task.role().id(),
                    task.dependencies(),
                    task.acceptance(), task.files(), task.parentId(), task.depth(),
                    task.status().name(), task.result(), task.error(),
                    task.startTime(), task.endTime()));
        }
        return new PlanCheckpoint(FORMAT,
                workspace == null ? null : workspace.toAbsolutePath().normalize().toString(),
                now, plan.goal(), plan.status().name(),
                plan.startTime(), plan.endTime(),
                plan.replans(), plan.recoveryIds(), snapshots);
    }

    /** 这份存档还值不值得续：格式对得上、有任务、而且计划确实停在半路。 */
    public boolean isResumable() {
        if (format != FORMAT || tasks == null || tasks.isEmpty()) {
            return false;
        }
        PlanStatus planStatus = planStatus();
        return planStatus == PlanStatus.RUNNING || planStatus == PlanStatus.CREATED;
    }

    /** 这份存档是不是属于这个工作区。不是的话绝不能续——步骤全是针对另一个仓库写的。 */
    public boolean belongsTo(Path workspace) {
        if (workspace == null || this.workspace == null) {
            return false;
        }
        return this.workspace.equals(workspace.toAbsolutePath().normalize().toString());
    }

    /** 还没了结的任务数，用来给用户一句「还剩几步」。 */
    public long remaining() {
        return tasks == null ? 0 : tasks.stream()
                .map(t -> taskStatus(t.status(), t.id()))
                .filter(s -> !s.isTerminal())
                .count();
    }

    /**
     * 把存档立回成一份能接着跑的计划。
     *
     * <p><b>RUNNING 的任务会被退回 PENDING。</b>存档是在任务了结的那一刻写的，
     * 正常情况下盘上不该有 RUNNING；真出现了，说明进程是在那一步跑到一半时没的——
     * 它没跑完，就得重跑。连 startTime 一起清掉：没跑过就是没跑过，
     * 时间戳得对得起事实，不然界面上会显示一个莫名其妙的耗时。
     *
     * @throws IllegalArgumentException 存档坏了（格式不对、图不合法）
     */
    public Plan toPlan() {
        if (format != FORMAT) {
            throw new IllegalArgumentException(
                    "存档格式是 " + format + "，当前只认 " + FORMAT + "，没法接着跑");
        }
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("存档里一个任务都没有");
        }

        List<Task> restored = new ArrayList<>(tasks.size());
        for (TaskSnapshot snapshot : tasks) {
            TaskStatus status = taskStatus(snapshot.status(), snapshot.id());
            boolean interrupted = status == TaskStatus.RUNNING;
            if (interrupted) {
                log.info("任务 {} 上次跑到一半就断了，退回未开始重跑", snapshot.id());
            }
            restored.add(Task.restore(
                    snapshot.id(), snapshot.description(), agentRole(snapshot),
                    snapshot.dependsOn() == null ? List.of() : snapshot.dependsOn(),
                    snapshot.acceptance(),
                    snapshot.files() == null ? List.of() : snapshot.files(),
                    snapshot.parentId(), snapshot.depth(),
                    interrupted ? TaskStatus.PENDING : status,
                    snapshot.result(), snapshot.error(),
                    interrupted ? 0 : snapshot.startTime(),
                    interrupted ? 0 : snapshot.endTime()));
        }

        // 照常走 TaskGraph.of：id 重复、悬空依赖、成环一样要查。
        // 存档是自己写的没错，但磁盘上的东西可能被人改过，也可能是上个版本留下的
        return Plan.restore(goal, TaskGraph.of(restored), planStatus(),
                startTime, endTime, replans, recoveryIds);
    }

    private PlanStatus planStatus() {
        try {
            return PlanStatus.valueOf(status);
        } catch (RuntimeException e) {
            return PlanStatus.CREATED;
        }
    }

    /**
     * 存档里的这一步该派给谁。
     *
     * <p>三条路，都不让整份存档报废：
     * <ol>
     *   <li>角色名认得出来 —— 就用它</li>
     *   <li>角色名认不出来（改过名、来自更新的版本）—— 退回该类型的默认角色，
     *       打一行警告。丢的是「派谁去」这个偏好，保住的是整份存档；
     *       反过来直接拒掉，用户丢的是几十次模型调用换来的产出</li>
     *   <li>压根没有角色字段（老存档）—— 按类型取默认角色，这是正常情况，不用警告</li>
     * </ol>
     */
    private static AgentRole agentRole(TaskSnapshot snapshot) {
        TaskType type = taskType(snapshot);
        if (snapshot.role() == null || snapshot.role().isBlank()) {
            return AgentRoles.defaultFor(type);
        }
        return AgentRoles.find(snapshot.role()).orElseGet(() -> {
            log.warn("任务 {} 存的角色 {} 不认识，按类型 {} 退回默认角色",
                    snapshot.id(), snapshot.role(), type);
            return AgentRoles.defaultFor(type);
        });
    }

    private static TaskType taskType(TaskSnapshot snapshot) {
        try {
            return TaskType.valueOf(snapshot.type());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "任务 " + snapshot.id() + " 的类型 " + snapshot.type() + " 不认识，存档可能坏了");
        }
    }

    /** 状态认不出来就退回 PENDING：重跑一步的代价，比让整份存档报废小得多。 */
    private static TaskStatus taskStatus(String raw, String id) {
        try {
            return TaskStatus.valueOf(raw);
        } catch (RuntimeException e) {
            log.warn("任务 {} 的状态 {} 不认识，当成未开始", id, raw);
            return TaskStatus.PENDING;
        }
    }
}
