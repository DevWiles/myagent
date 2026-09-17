package dev.wiles.myagent.plan;

/**
 * 计划要不要跑，由人拍板。
 *
 * <p>抽成接口，是因为「怎么问」在两种界面上完全不同：终端读一行 y/n，
 * 无头模式发一帧 {@code plan_confirm_request} 再等回帧。而「问了之后怎么办」两边一模一样——
 * 那部分归 {@link PlanExecuteAgent}。
 *
 * <p><b>没有「本次都同意」这种选项，是刻意的。</b>每份计划都不一样，
 * 一次性授权掉后面所有计划是危险的：工具审批有 always，计划确认没有。
 */
@FunctionalInterface
public interface PlanApproval {

    /**
     * @return true 表示开跑。<b>拿不准就返回 false</b>——
     *         没跑的计划还能再规划一次，跑错的代价可大得多
     */
    boolean confirm(Plan plan);

    /** 不问，直接跑。给测试和非交互场景用。 */
    static PlanApproval auto() {
        return plan -> true;
    }

    /** 一律不跑。没有界面能接话时的安全默认值，理由同 {@code ApprovalPolicy.denyAll}。 */
    static PlanApproval denyAll() {
        return plan -> false;
    }
}
