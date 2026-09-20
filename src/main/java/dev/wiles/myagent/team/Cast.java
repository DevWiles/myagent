package dev.wiles.myagent.team;

import dev.wiles.myagent.plan.AgentRole;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 这场圆桌坐了谁，以及为什么是这几位。
 *
 * <p><b>{@code reason} 不是装饰</b>。圆桌一开就是好几次模型调用，
 * 用户有权在花这个钱之前看懂「凭什么派这三位」——而且这句话还是个照妖镜：
 * 模型要是给不出像样的理由，多半它也没想清楚这活该怎么看。
 *
 * @param members 落座的角色，顺序就是发言顺序
 * @param reason  一句话说清为什么是这个组合
 */
public record Cast(List<AgentRole> members, String reason) {

    public Cast {
        members = dedupe(members);
        reason = reason == null || reason.isBlank() ? "（没说理由）" : reason.strip();
    }

    public static Cast of(List<AgentRole> members, String reason) {
        return new Cast(members, reason);
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    /** 角色 id，拼在提示词和日志里。 */
    public List<String> ids() {
        return members.stream().map(AgentRole::id).toList();
    }

    /**
     * 同一个角色坐两次就是白烧一次调用——同样的提示词、同样的工具，
     * 出来的东西高度雷同，却要占掉合议那一步的上下文。
     */
    private static List<AgentRole> dedupe(List<AgentRole> members) {
        if (members == null) {
            return List.of();
        }
        Set<AgentRole> unique = new LinkedHashSet<>(members);
        return List.copyOf(unique);
    }
}
