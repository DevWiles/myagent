package dev.wiles.myagent.plan;

import java.util.List;

/**
 * 真正干活的那一端。{@link PlanExecutor} 只管「谁什么时候跑」，怎么跑是这里的事。
 *
 * <p>抽成接口是为了把<b>调度</b>和<b>执行</b>切开：调度逻辑（拓扑顺序、失败扩散、
 * 动态扩图、取消）全部可以用假执行体测干净，一次模型调用都不用发。
 * 真正接 LLM 的实现（每个任务开一个 ReAct 子循环）是后面的事。
 */
@FunctionalInterface
public interface TaskRunner {

    /**
     * 执行一个任务。
     *
     * <p>约定：<b>尽量不要抛异常</b>，做不成就返回 {@link TaskOutcome#failed}。
     * 执行器会兜住异常（当作这个任务失败），但那条路上拿不到像样的错误描述。
     *
     * @param task     要执行的任务，此刻状态是 RUNNING
     * @param upstream 它依赖的任务，都已 SUCCEEDED，{@code result} 里装着产出
     */
    TaskOutcome run(Task task, List<Task> upstream);
}
