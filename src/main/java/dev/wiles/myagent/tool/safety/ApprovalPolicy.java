package dev.wiles.myagent.tool.safety;

/**
 * 危险动作要不要放行，由它说了算。
 *
 * 之所以单独抽一个接口，是因为「问谁」在不同场景下不一样：
 * 交互式终端问用户，自动化脚本里没人可问（只能一律拒绝），测试里则要一律放行。
 */
@FunctionalInterface
public interface ApprovalPolicy {

    /**
     * @param action 动作类型，如「执行命令」
     * @param detail 具体内容，如命令原文
     * @param reason 为什么需要确认
     * @return true 放行
     */
    boolean approve(String action, String detail, String reason);

    /** 非交互环境的安全默认值：没人能确认，那就都不批。 */
    static ApprovalPolicy denyAll() {
        return (action, detail, reason) -> false;
    }

    /** 仅用于测试，或用户明确要求「别再问我了」时。 */
    static ApprovalPolicy approveAll() {
        return (action, detail, reason) -> true;
    }
}
