package dev.wiles.myagent.tool;

/**
 * 一次工具执行的结果。
 *
 * 最关键的设计决定在这里：**工具执行失败不是异常，是一条要交给模型的反馈**。
 *
 * 普通程序里，读不到文件就抛 IOException，让调用方崩掉或者兜住。
 * 但 agent 里不行——模型是「调用方」，它看不懂 Java 异常栈，
 * 而且它完全有能力自己纠错：路径写错了？换一个再试。命令敲错了？改一下重来。
 *
 * 所以工具的失败必须变成一段**人话**，塞进 tool 消息发回给模型，让它自己决定下一步。
 * 真正该抛异常的只有一种情况：程序自身坏了（比如 registry 没初始化）。
 */
/**
 * @param diff <b>只给人看的</b>那一份改动，统一 diff 格式。空串表示没有。
 *
 *             <p>为什么和 {@code content} 分开，而不是拼进去：
 *             {@code content} 是要<b>发给模型</b>的，每个字都进上下文、都要花钱。
 *             而模型刚刚才把新内容整段写出来，再把 diff 回灌给它，
 *             它得到的信息量是零——真正需要看 diff 的是<b>用户</b>：
 *             他没看过那段内容，而「改了什么」正是他唯一想知道的事。
 *
 *             <p>所以这个字段走事件和协议直达界面，<b>不进对话历史</b>。
 */
public record ToolResult(boolean ok, String content, String diff) {

    public ToolResult {
        content = content == null ? "" : content;
        diff = diff == null ? "" : diff;
    }

    /** 不带 diff 的那种，绝大多数工具都是。 */
    public ToolResult(boolean ok, String content) {
        this(ok, content, "");
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content);
    }

    /** 带上一份给人看的改动。见 {@link #diff}。 */
    public static ToolResult ok(String content, String diff) {
        return new ToolResult(true, content, diff);
    }

    /** 失败信息要写得像给人看的提示，模型才知道怎么改。 */
    public static ToolResult error(String message) {
        return new ToolResult(false, "错误: " + message);
    }
}
