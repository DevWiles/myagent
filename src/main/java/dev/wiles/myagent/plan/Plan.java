package dev.wiles.myagent.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次规划的产物：目标 + 任务图 + 整体状态。
 *
 * <p>结构跟 {@link Task} 是同一套路数，这是刻意的——两层东西长得像，读代码的人不用换脑子：
 * <pre>
 *   final   goal / graph        —— 规划产出，不许改
 *   可变     status / 两个时间戳  —— 执行过程中填上
 * </pre>
 *
 * <p><b>为什么 goal 要单独存一份</b>：它是模型用自己的话复述的「我理解你要做什么」。
 * 用户扫一眼就能判断模型有没有理解偏——如果这句话就不对，那下面拆得再漂亮也是白搭，
 * 该当场毙掉重来，而不是等它跑完十个任务才发现方向错了。
 *
 * <h3>为什么还要存用户原话和需求清单</h3>
 * goal 是转述，转述会漏。实测（bench/date_parse）：原话里写着「两位年」，
 * 规划把规格交给一步「照着现有实现和测试整理」，测试没测两位年，这条就在转述里没了——
 * coder 照着转述做、verifier 照着可见测试验，一路全绿，隐藏测试挂掉。
 * 所以原话原样留一份（{@link #request}），规划器从原话抽的需求清单也留一份
 * （{@link #requirements}），执行时交给<b>每一个</b>子任务，而不是只交给规划器。
 *
 * <p>状态不从任务图里推算，而是存下来。因为 CREATED / RUNNING / CANCELLED
 * 这三种根本推不出来：一张全是 PENDING 的图，可能是还没开跑，也可能是刚被取消。
 */
public final class Plan {

    private final String goal;
    private final TaskGraph graph;

    /** 用户的原话，一字不改。老存档、测试里手捏的计划没有它，是 null。 */
    private final String request;

    /** 规划器从原话里抽出的需求清单（R1、R2……），可能为空，不会是 null。 */
    private final List<String> requirements;

    private PlanStatus status = PlanStatus.CREATED;
    private long startTime;
    private long endTime;

    /**
     * 中途重规划了几次，上限见 {@link PlanLimits#MAX_REPLANS}。
     *
     * <p><b>为什么放在计划上而不是调度器上</b>：它是「这一次执行」的状态，
     * 而调度器是可以拿去跑别的计划的。放调度器上有两个毛病——两次 execute 之间
     * 得记着清零（漏了就串台），而且存档时拿不到它，恢复出来的计划会忘记自己
     * 已经重规划过，于是又补一次，实际补了两次。
     */
    private int replans;

    /** 补救任务的 id。收尾时靠它判断这次失败到底有没有被挽回回来。 */
    private final List<String> recoveryIds = new ArrayList<>();

    public Plan(String goal, TaskGraph graph) {
        this(goal, graph, null, List.of());
    }

    public Plan(String goal, TaskGraph graph, String request, List<String> requirements) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("计划缺少目标描述");
        }
        if (graph == null) {
            throw new IllegalArgumentException("计划缺少任务图");
        }
        this.goal = goal;
        this.graph = graph;
        this.request = request == null || request.isBlank() ? null : request.strip();
        this.requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    /**
     * 从存档里把一份跑到一半的计划立起来。包私有，只给 {@link PlanCheckpoint} 用，
     * 理由同 {@link Task#restore}。
     */
    static Plan restore(String goal, TaskGraph graph, String request, List<String> requirements,
                        PlanStatus status, long startTime, long endTime, int replans,
                        List<String> recoveryIds) {
        Plan plan = new Plan(goal, graph, request, requirements);
        plan.status = status == null ? PlanStatus.CREATED : status;
        plan.startTime = startTime;
        plan.endTime = endTime;
        plan.replans = replans;
        if (recoveryIds != null) {
            plan.recoveryIds.addAll(recoveryIds);
        }
        return plan;
    }

    public String goal() {
        return goal;
    }

    public TaskGraph graph() {
        return graph;
    }

    /** 用户原话，没有就是 null。 */
    public String request() {
        return request;
    }

    public List<String> requirements() {
        return requirements;
    }

    public PlanStatus status() {
        return status;
    }

    public int replans() {
        return replans;
    }

    /** 还能不能再补救一次。 */
    public boolean canReplan() {
        return replans < PlanLimits.MAX_REPLANS;
    }

    /** 记一次补救。补救任务落图成功之后才调，失败的尝试不算数。 */
    public void recordReplan(List<Task> recovery) {
        replans++;
        recovery.forEach(task -> recoveryIds.add(task.id()));
    }

    /**
     * 这次失败被补救回来了没有。
     *
     * <p>判据是<b>所有补救任务都成功了</b>：重规划时模型看得到被跳过的那些活，
     * 它给出的补救步骤本来就该把那些活接过去。补救任务里但凡还有一个红的或没跑的，
     * 就说明这一轮没救回来。
     */
    public boolean isRecovered() {
        if (recoveryIds.isEmpty()) {
            return false;
        }
        for (String id : recoveryIds) {
            if (graph.get(id).status() != TaskStatus.SUCCEEDED) {
                return false;
            }
        }
        return true;
    }

    /** 补救任务的 id，给存档用。 */
    public List<String> recoveryIds() {
        return List.copyOf(recoveryIds);
    }

    public long startTime() {
        return startTime;
    }

    public long endTime() {
        return endTime;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** 耗时，单位毫秒；还没了结返回 -1，而不是一个会骗人的 0。 */
    public long durationMillis() {
        return endTime == 0 ? -1 : endTime - startTime;
    }

    /**
     * 开跑。<b>对已经在跑的计划是空操作</b>——那是从存档续上来的，
     * 它本来就在跑，只是中间断了。这里要是照常抛异常，续跑的第一步就撞墙；
     * 要是照常改 startTime，那份计划的总耗时会从断点重新计时，报出来的数字是假的。
     */
    public void start() {
        if (status == PlanStatus.RUNNING) {
            return;
        }
        require("开始执行", PlanStatus.CREATED);
        status = PlanStatus.RUNNING;
        startTime = System.currentTimeMillis();
    }

    public void complete() {
        require("标记完成", PlanStatus.RUNNING);
        finish(PlanStatus.COMPLETED);
    }

    public void fail() {
        require("标记失败", PlanStatus.RUNNING);
        finish(PlanStatus.FAILED);
    }

    /**
     * 取消。<b>CREATED 和 RUNNING 都能取消</b>——用户完全可能看完计划就说「不用了」，
     * 那种情况下不该为了凑状态机而先假装跑一轮。
     */
    public void cancel() {
        require("取消", PlanStatus.CREATED, PlanStatus.RUNNING);
        finish(PlanStatus.CANCELLED);
    }

    private void finish(PlanStatus terminal) {
        status = terminal;
        endTime = System.currentTimeMillis();
    }

    private void require(String action, PlanStatus... expected) {
        for (PlanStatus candidate : expected) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("计划当前是 " + status + "，不能" + action);
    }

    @Override
    public String toString() {
        return "Plan[" + status + " " + graph.size() + " 个任务: " + goal + "]";
    }
}
