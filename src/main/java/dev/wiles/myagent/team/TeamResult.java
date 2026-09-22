package dev.wiles.myagent.team;

import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskStatus;

import java.util.List;

/**
 * 一条流水线跑完留下的东西。
 *
 * <p>工序用 {@link Task} 表示，不是另造一个类型：一道工序要记的东西
 * （派给谁、跑到哪一步、产出是什么、错在哪、花了多久）和计划里的一个任务<b>完全一样</b>，
 * 而且这样一来，执行、事件转发、取消这三样全部能直接复用
 * {@code AgentTaskRunner}——那才是这个复用真正的价值，不是省几个字段。
 *
 * @param goal   目标
 * @param reason 分工说明，开场时给用户看的那句
 * @param stages 全部工序，按执行顺序：每轮的规划、workers、验收都在里面，
 *               返工/重拟长出来的也在——历史不删，账要能对
 * @param review <b>最后一轮</b>验收，和 {@code stages} 里最后那道验收是同一个对象。
 *               一轮都没跑到（第一轮方案就没拟成）时是 null
 * @param rounds 验收跑了几轮（打回一次算开新一轮）
 */
public record TeamResult(String goal, String reason, List<Task> stages, Task review,
                         int rounds, TeamStatus status) {

    public TeamResult {
        stages = List.copyOf(stages == null ? List.of() : stages);
    }

    /**
     * 验收结论的正文。通过和打回都有（打回那份问题清单正是最值钱的产出）；
     * 验收没跑成、或整场被取消时是 null——调用方必须自己判，不给一句假结论。
     */
    public String verdict() {
        return status.hasVerdict() && review != null && review.status() == TaskStatus.SUCCEEDED
                ? review.result() : null;
    }

    /** 跑成的工序数。协议里叫 spoke——帧的字段名定了就不能改，Java 这头跟着它。 */
    public long spoke() {
        return stages.stream().filter(t -> t.status() == TaskStatus.SUCCEEDED).count();
    }

    public long silent() {
        return stages.size() - spoke();
    }
}
