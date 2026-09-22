package dev.wiles.myagent.team;

/**
 * 一条流水线最后是个什么下场。<b>按验收结论分档，不按「跑没跑完」</b>。
 *
 * <p><b>为什么 {@link #PARTIAL} 要单独一档</b>：施工干完了但没过验收
 * （被打回，或验收那道工序自己没跑成），工作区里<b>已经有一批没人担保的改动</b>。
 * 报成完成，用户会当它没问题；报成失败，用户会以为什么都没发生——两头都是误导。
 * 这和 {@code PlanStatus} 把「取消」和「失败」分开是同一个道理：
 * <b>结果不同就该有不同的名字，否则界面只能猜。</b>
 */
public enum TeamStatus {

    /** 验收放行。 */
    COMPLETED,

    /**
     * 活干了但没有担保：轮次用完仍被打回（结论里是问题清单）、
     * 验收自己没跑成、或重拟方案没成。
     */
    PARTIAL,

    /** 第一轮方案就没拟成，什么都没干。 */
    FAILED,

    /** 用户中途叫停。已经落定的工序原样留着。 */
    CANCELLED;

    /**
     * 这一档有没有验收结论可看。界面据此决定画不画那一块；
     * {@code TeamResult.verdict()} 也拿它把关——取消收场的话，
     * 哪怕验收恰好跑完了也不当结论报，协议里说了 CANCELLED 没有 verdict。
     */
    public boolean hasVerdict() {
        return this == COMPLETED || this == PARTIAL;
    }
}
