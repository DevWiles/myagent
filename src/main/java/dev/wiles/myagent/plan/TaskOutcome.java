package dev.wiles.myagent.plan;

import java.util.List;

/**
 * 执行体跑完一个任务后交回来的东西。
 *
 * <p>和 {@link dev.wiles.myagent.tool.ToolResult} 一个路数：<b>失败是正常返回值，不是异常</b>。
 * 一个任务没做成，不代表整个计划该崩——DAG 里别的腿还得接着跑。
 *
 * @param ok       成没成
 * @param output   成功时是给下游看的产出摘要，失败时是原因
 * @param subTasks 只有 {@link TaskType#PLANNING} 任务才有：它当场拆出来的子任务
 */
public record TaskOutcome(boolean ok, String output, List<Task> subTasks) {

    public TaskOutcome {
        output = output == null ? "" : output;
        subTasks = subTasks == null ? List.of() : List.copyOf(subTasks);
    }

    public static TaskOutcome ok(String output) {
        return new TaskOutcome(true, output, List.of());
    }

    public static TaskOutcome failed(String error) {
        return new TaskOutcome(false, error, List.of());
    }

    /** 规划任务的产出：一句交代 + 一批新任务。 */
    public static TaskOutcome planned(String output, List<Task> subTasks) {
        return new TaskOutcome(true, output, subTasks);
    }
}
