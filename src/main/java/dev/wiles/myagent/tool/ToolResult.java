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
public record ToolResult(boolean ok, String content) {

    public static ToolResult ok(String content) {
        return new ToolResult(true, content == null ? "" : content);
    }

    /** 失败信息要写得像给人看的提示，模型才知道怎么改。 */
    public static ToolResult error(String message) {
        return new ToolResult(false, "错误: " + message);
    }
}
