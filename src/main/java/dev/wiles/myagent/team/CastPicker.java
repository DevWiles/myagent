package dev.wiles.myagent.team;

import dev.wiles.myagent.plan.AgentRole;

import java.util.List;

/**
 * 谁来决定这场圆桌坐哪几位。
 *
 * <p>抽成接口的理由和 {@code TaskRunner} 一样：<b>把「怎么挑人」和「怎么开会」切开</b>。
 * 开会那套编排（一席一席地跑、哑火了照开、取消、合议）全部可以用一份写死的名单测干净，
 * 一次模型调用都不发。真正问模型的实现是 {@link ModelCastPicker}。
 */
@FunctionalInterface
public interface CastPicker {

    /**
     * 挑一桌人。
     *
     * <p>约定：<b>尽量别抛</b>。挑人失败不该让整场会开不成——用户要的是一场圆桌，
     * 不是一次完美的挑人，退到一份说得过去的默认名单就行。
     *
     * @param available 这次能坐上桌的角色，见 {@link Roundtable#seatable}
     */
    Cast pick(String goal, List<AgentRole> available);

    /** 掐断正在进行的挑人请求。写死名单的实现没什么好掐的。 */
    default void cancel() {
    }

    /** 写死一桌人，给测试和「用户已经指名了」这两种场合用。 */
    static CastPicker fixed(List<AgentRole> members, String reason) {
        return (goal, available) -> Cast.of(members, reason);
    }
}
