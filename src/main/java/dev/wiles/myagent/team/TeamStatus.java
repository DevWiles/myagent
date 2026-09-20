package dev.wiles.myagent.team;

/**
 * 一场圆桌最后是个什么下场。
 *
 * <p><b>为什么 {@link #PARTIAL} 要单独一档</b>：三个人里倒了一个，剩下两份意见的合议
 * 仍然有用——把它报成失败，用户会以为屏幕上那段合议不作数。但也不能报成完成：
 * 缺席的那一席可能恰恰是唯一会去跑测试的那个。
 * 这和 {@code PlanStatus} 把「取消」和「失败」分开是同一个道理：
 * <b>结果不同就该有不同的名字，否则界面只能猜。</b>
 */
public enum TeamStatus {

    /** 每一席都说了话，合议也出来了。 */
    COMPLETED,

    /** 有席位哑火，但剩下的凑出了合议。 */
    PARTIAL,

    /** 一席都没成，或者合议那一步自己倒了——这时候没有任何结论可看。 */
    FAILED,

    /** 用户中途叫停。已经说过话的那几席留着，合议不做了——那还要再烧一次调用。 */
    CANCELLED;

    /** 有没有合议可看。界面据此决定画不画那一块。 */
    public boolean hasSynthesis() {
        return this == COMPLETED || this == PARTIAL;
    }
}
