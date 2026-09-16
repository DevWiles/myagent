package dev.wiles.myagent.plan;

/**
 * 一个任务在执行过程中的状态。
 *
 * <p>为什么要有 {@link #SKIPPED}：DAG 的一半价值就在于「一条腿断了不影响别的腿」。
 * 上游失败时，下游既不是失败（它根本没跑过，怪不到它头上），也不能留在 PENDING
 * （那样调度器会永远等它），必须有第三种了结方式。
 *
 * <p>为什么没有 CANCELLED：用户按 Ctrl-C 在现有代码里一直被当作失败处理
 * （见 {@code Agent.abort}，发的是 {@code Failed("已取消")}），这里跟着走，
 * 取消就是 {@link #FAILED} 带一句「已取消」。多一个状态就多一处要在
 * 调度、事件、界面三个地方分别处理的分支，不值。
 */
public enum TaskStatus {

    /** 还没轮到它，或者依赖没齐。 */
    PENDING,

    /** 正在跑。 */
    RUNNING,

    /** 跑完了，结果在 {@code result} 里。 */
    SUCCEEDED,

    /** 跑砸了，原因在 {@code error} 里。 */
    FAILED,

    /** 没跑，因为依赖的任务失败或被跳过了。 */
    SKIPPED;

    /** 终态：调度器不必再管它。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED;
    }
}
