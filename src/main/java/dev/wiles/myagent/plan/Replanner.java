package dev.wiles.myagent.plan;

import java.util.List;

/**
 * 计划跑砸了之后，给出补救步骤的人。
 *
 * <p>做成一个单方法接口，而不是让 {@link PlanExecutor} 直接持有 {@link Planner}：
 * 调度器要的只是「给我下一批任务」这个能力，不该连带背上规划器的模型调用、
 * 重试策略和只读校验。测试调度逻辑时给个 lambda 就行，不必捏一个假模型出来。
 */
@FunctionalInterface
public interface Replanner {

    /**
     * @param plan   跑到一半的计划，里面既有已成功任务的产出，也有被跳过的那些
     * @param failed 倒在哪一步
     * @param error  它报的错，原样交给模型——这句话决定了它会换条路还是原样再试一遍
     * @return 补救任务；<b>空列表表示「这事儿做不成」</b>，调度器照原样收场，不再挣扎
     */
    List<Task> replan(Plan plan, Task failed, String error);
}
