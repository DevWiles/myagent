package dev.wiles.myagent.tool.safety;

/**
 * 危险动作要不要放行，由它说了算。
 *
 * <p>之所以单独抽一个接口，是因为「问谁」在不同场景下不一样：
 * 无头模式发给界面（{@code RemoteApproval}），自动化脚本里没人可问（一律拒绝），
 * 测试里则要一律放行。
 *
 * <h3>实现是叠起来用的</h3>
 * <pre>
 *   PolicyGate（先算规则，算不出来才往下问）
 *     └─ AuditLog.audited（每个决策都留底）
 *          └─ RemoteApproval（发给界面，阻塞等人回答）
 * </pre>
 * 每一层都只管一件事，也因此加一层不用动其它层。
 *
 * <h3>为什么参数和返回值都变成了记录</h3>
 * 原签名是 {@code boolean approve(action, detail, reason)}。三个字符串能把问题问出来，
 * 但接不住答案里真正有用的部分——用户拒绝时想说的那句话，和同意时愿意一次性授权的范围。
 * 详见 {@link ApprovalRequest} 和 {@link ApprovalVerdict}。
 */
@FunctionalInterface
public interface ApprovalPolicy {

    ApprovalVerdict decide(ApprovalRequest request);

    /** 非交互环境的安全默认值：没人能确认，那就都不批。 */
    static ApprovalPolicy denyAll() {
        return request -> ApprovalVerdict.no("当前没有可以确认的人，危险动作一律不执行。");
    }

    /** 仅用于测试。<b>没有「用户说别再问我了」这条路了</b>——那条路现在叫规则，见 {@link ApprovalRules}。 */
    static ApprovalPolicy approveAll() {
        return request -> ApprovalVerdict.yes();
    }
}
