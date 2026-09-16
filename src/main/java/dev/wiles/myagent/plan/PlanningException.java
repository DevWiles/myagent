package dev.wiles.myagent.plan;

/**
 * 规划失败：重试次数用完了，模型始终没给出一份能用的计划。
 *
 * <p>为什么这里用异常，而任务执行失败用返回值（{@link TaskOutcome}）：
 * 任务失败是<b>计划内的事</b>，DAG 早就安排好了怎么处理（标失败、扩散跳过、接着跑别的腿）；
 * 而规划失败意味着<b>一张图都没有</b>，后面所有机制都无从谈起，只能一路退回去告诉用户。
 */
public class PlanningException extends RuntimeException {

    public PlanningException(String message) {
        super(message);
    }
}
