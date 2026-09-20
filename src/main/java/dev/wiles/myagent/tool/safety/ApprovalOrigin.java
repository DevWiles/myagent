package dev.wiles.myagent.tool.safety;

import java.util.Objects;

/**
 * 是谁在要这个授权。
 *
 * <h3>为什么单独记一笔</h3>
 * 单轮对话里不需要——用户刚说完话，弹出来的确认框显然是这句话引起的。
 * 但在计划执行和圆桌里就不是了：屏幕上七八个任务依次跑过，
 * 突然弹出「执行命令: rm -rf target」，用户要先想清楚<b>这是谁要干的</b>
 * 才能判断该不该点头。想不清楚的时候，人的默认动作是点同意。
 *
 * <p>所以身份要跟着请求一起送到界面上，而不是让用户自己去上下文里找。
 *
 * @param taskId    任务号，如 {@code t3}
 * @param role      角色 id，如 {@code coder}
 * @param roleTitle 角色中文名，如「编码者」
 */
public record ApprovalOrigin(String taskId, String role, String roleTitle) {

    public ApprovalOrigin {
        taskId = Objects.requireNonNullElse(taskId, "");
        role = Objects.requireNonNullElse(role, "");
        roleTitle = Objects.requireNonNullElse(roleTitle, "");
    }

    /** 一句话身份，如「编码者 · t3」。三个字段都空时返回空串。 */
    public String label() {
        String name = roleTitle.isBlank() ? role : roleTitle;
        if (name.isBlank()) {
            return taskId;
        }
        return taskId.isBlank() ? name : name + " · " + taskId;
    }

    public boolean isBlank() {
        return label().isBlank();
    }
}
