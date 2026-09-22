package dev.wiles.myagent.team;

import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.plan.Task;

import java.util.List;

/**
 * 一条流水线的全部动静。界面靠它把过程画出来。
 *
 * <p>和 {@code PlanListener} 一样全部给空实现：只关心其中两件事的调用方，
 * 不该被迫写一堆空方法；往里加新事件也不会把已有实现弄崩。
 *
 * <p>方法顺序就是事情发生的顺序。除了 {@link #onAgentEvent}（来自 worker 线程，
 * 而且<b>几个 worker 会同时在发</b>，实现要线程安全），
 * 其余回调都在跑流水线的那条编排线程上来。
 */
public interface TeamListener {

    /**
     * 开场。此刻座位表上<b>只有规划那一道工序</b>——worker 有几个、派谁，
     * 要等规划跑完才知道，届时走 {@link #onStagesAdded}。
     */
    default void onStarted(String goal, String reason, List<Task> stages) {
    }

    /**
     * 座位表长大了：规划拆出的 workers、每轮的验收工序、返工/重拟长出的新工序。
     * 按执行顺序追加即可，id 全场唯一。
     */
    default void onStagesAdded(List<Task> stages) {
    }

    default void onStageStarted(Task stage) {
    }

    /** 一道工序落定了。<b>倒了的、被跳过的也走这里</b>，它们同样是「这道工序有结果了」。 */
    default void onStageFinished(Task stage) {
    }

    /** 工序里 ReAct 子循环的动静，带着是哪道工序一起给。多个 worker 并行时会被并发调。 */
    default void onAgentEvent(Task stage, AgentEvent event) {
    }

    default void onFinished(TeamResult result) {
    }

    static TeamListener none() {
        return new TeamListener() {
        };
    }
}
