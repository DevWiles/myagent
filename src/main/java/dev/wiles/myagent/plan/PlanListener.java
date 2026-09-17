package dev.wiles.myagent.plan;

import dev.wiles.myagent.agent.AgentEvent;

import java.util.List;

/**
 * 一次「先规划后执行」跑下来的全部动静。界面靠它把过程画出来。
 *
 * <p>每个方法都有空实现：<b>只关心其中两三件事的调用方，不该被迫写一堆空方法</b>。
 * 也因此，以后往这里加新事件不会把已有的实现全部弄崩——
 * 这和协议里「界面必须静默忽略不认识的消息」是同一个道理。
 *
 * <p>方法的顺序就是事情发生的顺序，从上往下读一遍，大致就是一次计划执行的全过程。
 */
public interface PlanListener {

    /** 计划出来了（新规划的，或从存档里捞回来的），还没开始跑。 */
    default void onPlanCreated(Plan plan) {
    }

    /** 用户看完说不跑。计划此刻已经是 CANCELLED。 */
    default void onRejected(Plan plan) {
    }

    /**
     * 确认通过，马上开跑。
     *
     * <p>值得单独一个事件，是因为这一刻界面往往要换挡：终端版在这儿才开始接管 Ctrl-C——
     * 在此之前那个「跑不跑 [Y/n]」还等着读一行，两个东西同时抢标准输入，
     * 按键会被吃掉一半。
     */
    default void onApproved(Plan plan) {
    }

    default void onTaskStarted(Task task) {
    }

    /** 被连带跳过的任务<b>也会走这里</b>——它们同样是「这一步有了结果」。 */
    default void onTaskFinished(Task task) {
    }

    /** 某个 PLANNING 任务拆出了下一层。图长大了，不是改道。 */
    default void onTasksAdded(Task parent, List<Task> added) {
    }

    /** 某一步失败后补出了救场任务。这是<b>改道</b>，界面该和上面那条画得不一样。 */
    default void onReplanned(Task failed, List<Task> recovery) {
    }

    /** 子任务里 ReAct 循环的动静：流式文本、工具调用。带着任务一起给，界面才知道是谁在动。 */
    default void onAgentEvent(Task task, AgentEvent event) {
    }

    /** 收工。{@code result.cancelled()} 为真时，存档是留着的，可以接着跑。 */
    default void onFinished(Plan plan, PlanResult result) {
    }

    /** 什么都不关心。 */
    static PlanListener none() {
        return new PlanListener() {
        };
    }
}
