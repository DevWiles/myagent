package dev.wiles.myagent.team;

import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskStatus;

import java.util.List;

/**
 * 一场圆桌跑完留下的东西。
 *
 * <p>席位用 {@link Task} 表示，不是另造一个类型：一席要记的东西
 * （派给谁、跑到哪一步、产出是什么、错在哪、花了多久）和计划里的一个任务<b>完全一样</b>，
 * 而且这样一来，执行、事件转发、取消这三样全部能直接复用
 * {@code AgentTaskRunner}——那才是这个复用真正的价值，不是省几个字段。
 *
 * @param goal      议题
 * @param cast      坐了谁、为什么是这几位
 * @param seats     每一席，顺序就是发言顺序
 * @param synthesis 合议那一席。没跑成或被取消时是 {@code null}
 */
public record TeamResult(String goal, Cast cast, List<Task> seats, Task synthesis,
                         TeamStatus status) {

    public TeamResult {
        seats = List.copyOf(seats == null ? List.of() : seats);
    }

    /** 合议的正文。没有合议时返回 null——调用方必须自己判，不给一句假结论。 */
    public String verdict() {
        return synthesis == null ? null : synthesis.result();
    }

    public long spoke() {
        return seats.stream().filter(t -> t.status() == TaskStatus.SUCCEEDED).count();
    }

    public long silent() {
        return seats.size() - spoke();
    }
}
