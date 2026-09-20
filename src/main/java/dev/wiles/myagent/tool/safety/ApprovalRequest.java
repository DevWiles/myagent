package dev.wiles.myagent.tool.safety;

import java.util.Objects;

/**
 * 一次「要不要放行」的完整问法。
 *
 * <h3>为什么不是三个字符串了</h3>
 * 原来的签名是 {@code approve(action, detail, reason)}。三个字符串足够问出问题，
 * 但不足以<b>让人答得好</b>：
 * <ul>
 *   <li>规则匹配需要一个稳定的机器键 —— {@link #kind}</li>
 *   <li>覆盖文件时人要看见改了什么 —— {@link #preview}</li>
 *   <li>多 agent 跑着的时候人要知道是谁在问 —— {@link #origin}</li>
 * </ul>
 * 这些都塞不进字符串，塞进去也只能靠约定解析，而约定会腐烂。
 *
 * <h3>subject 是这里最要紧的字段</h3>
 * 它<b>同时</b>是给人看的主体和规则匹配的对象——命令原文，或工作区相对路径。
 * 两者必须是同一个东西：用户在确认框里看到 A、点了「以后都同意」，
 * 结果存下的规则匹配的是 B，那这个系统就在骗人。
 *
 * @param kind    机器键，规则只认它
 * @param action  给人看的动作名，如「执行命令」。<b>随便改，不影响规则</b>
 * @param subject 命令原文 / 工作区相对路径
 * @param reason  为什么要问。会同时给用户（判断依据）和模型（被拒后据此改做法）看
 * @param preview 可选的详情，目前是文件改动的 diff。没有就是空串
 * @param origin  可选的来源身份。单轮对话里为 null
 */
public record ApprovalRequest(ApprovalKind kind, String action, String subject,
                              String reason, String preview, ApprovalOrigin origin) {

    public ApprovalRequest {
        Objects.requireNonNull(kind, "kind");
        action = Objects.requireNonNullElse(action, "");
        subject = Objects.requireNonNullElse(subject, "");
        reason = Objects.requireNonNullElse(reason, "");
        preview = Objects.requireNonNullElse(preview, "");
    }

    public static ApprovalRequest bash(String command, String reason) {
        return new ApprovalRequest(ApprovalKind.BASH, "执行命令", command, reason, "", null);
    }

    public static ApprovalRequest write(String action, String path, String reason, String preview) {
        return new ApprovalRequest(ApprovalKind.WRITE, action, path, reason, preview, null);
    }

    /** 补上来源身份。工具本身不知道自己在哪个任务里跑，这一层由上面接。 */
    public ApprovalRequest from(ApprovalOrigin from) {
        return new ApprovalRequest(kind, action, subject, reason, preview, from);
    }

    public boolean hasPreview() {
        return !preview.isBlank();
    }
}
