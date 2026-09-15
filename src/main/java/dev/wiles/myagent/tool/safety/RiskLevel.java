package dev.wiles.myagent.tool.safety;

/** 一条命令的风险档位。 */
public enum RiskLevel {
    /** 只读或影响限于工作区，直接放行。 */
    SAFE,
    /** 可能造成损失或涉及外部世界，必须经人确认。 */
    NEEDS_APPROVAL,
    /** 无论谁同意都不执行。 */
    FORBIDDEN
}
