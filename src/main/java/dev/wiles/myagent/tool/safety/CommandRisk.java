package dev.wiles.myagent.tool.safety;

/**
 * 风险判定结果。
 *
 * reason 会同时给两个人看：终端前的用户（决定要不要放行）和模型
 * （被拒后要据此换个做法），所以要写成人话。
 */
public record CommandRisk(RiskLevel level, String reason) {

    public static CommandRisk safe() {
        return new CommandRisk(RiskLevel.SAFE, "");
    }

    public static CommandRisk approval(String reason) {
        return new CommandRisk(RiskLevel.NEEDS_APPROVAL, reason);
    }

    public static CommandRisk forbidden(String reason) {
        return new CommandRisk(RiskLevel.FORBIDDEN, reason);
    }

    public boolean isForbidden() {
        return level == RiskLevel.FORBIDDEN;
    }

    public boolean needsApproval() {
        return level == RiskLevel.NEEDS_APPROVAL;
    }
}
