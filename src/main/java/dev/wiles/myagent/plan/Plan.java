package dev.wiles.myagent.plan;

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
 * <p>状态不从任务图里推算，而是存下来。因为 CREATED / RUNNING / CANCELLED
 * 这三种根本推不出来：一张全是 PENDING 的图，可能是还没开跑，也可能是刚被取消。
 */
public final class Plan {

    private final String goal;
    private final TaskGraph graph;

    private PlanStatus status = PlanStatus.CREATED;
    private long startTime;
    private long endTime;

    public Plan(String goal, TaskGraph graph) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("计划缺少目标描述");
        }
        if (graph == null) {
            throw new IllegalArgumentException("计划缺少任务图");
        }
        this.goal = goal;
        this.graph = graph;
    }

    public String goal() {
        return goal;
    }

    public TaskGraph graph() {
        return graph;
    }

    public PlanStatus status() {
        return status;
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

    public void start() {
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
