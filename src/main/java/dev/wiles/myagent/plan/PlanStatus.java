package dev.wiles.myagent.plan;

/**
 * 整个计划的状态。
 *
 * <p>和 {@link TaskStatus} 是两层东西：那个说的是「某一件事做完没」，
 * 这个说的是「这一轮规划整体什么情况」。之前这个信息散在
 * {@link PlanResult} 的几个计数里，没有一个明确的说法——
 * 界面想显示「计划进行中」还得自己去数任务，不同地方数出来的结论还可能不一致。
 *
 * <p>注意这里<b>有</b> {@link #CANCELLED} 而任务那边没有。不是自相矛盾：
 * 取消对单个任务来说就是一次失败（模型调用被掐断），但对整个计划来说
 * 「用户主动叫停」和「跑砸了」是完全不同的两件事，界面和报告都得区别对待。
 */
public enum PlanStatus {

    /** 刚规划出来，还没开始执行。 */
    CREATED,

    /** 正在执行。 */
    RUNNING,

    /** 所有任务都成了。 */
    COMPLETED,

    /** 有任务失败（连带的跳过也算在这个账上）。 */
    FAILED,

    /** 用户叫停。 */
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
