package dev.wiles.myagent.context;

/**
 * 会话列表里的一行：够画出来、够让人挑，但<b>不带对话内容</b>。
 *
 * <h3>为什么不直接传 {@link SessionSnapshot}</h3>
 * 列表是给人挑的，挑之前一条历史消息都用不上。让界面层拿着整段对话，
 * 迟早有人顺手从列表里读 {@code messages} 去渲染——那就变成了
 * 「打开选择器要先把这个工作区所有会话的全文都摆在内存里」。
 * 这里砍成一行摘要，想要正文必须回头 {@code load(id)}，多一步，但那一步是明确的。
 *
 * @param id       会话 id，挑中之后拿它去 {@code load}
 * @param model    这段对话用的模型
 * @param startedAt 第一句话的时刻，epoch 毫秒
 * @param savedAt  最后一次存盘的时刻，epoch 毫秒；列表按它倒序
 * @param turns    用户说了多少轮
 * @param title    首句，列表里显示「上次在聊什么」
 */
public record SessionSummary(
        String id,
        String model,
        long startedAt,
        long savedAt,
        int turns,
        String title) {
}
