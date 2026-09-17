package dev.wiles.myagent.llm;

import java.util.List;

/**
 * 模型客户端抽象。agent 循环只依赖这个接口，
 * 换 DeepSeek / 其它 OpenAI 兼容服务 / 将来接 Anthropic，都不影响上层。
 */
public interface LlmClient {

    /**
     * 发起一次对话补全。
     *
     * @param messages 完整对话历史（含 system），调用方负责维护
     * @param tools    可用工具清单，传 null 或空表示本次不开放工具
     */
    ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools);

    default ChatResponse chat(List<ChatMessage> messages) {
        return chat(messages, null);
    }

    /**
     * 流式版本：内容一边生成一边通过 listener 回调，返回值仍是拼装完整的响应。
     *
     * 默认实现退化成非流式，最后一次性把全文发给 listener——
     * 这样上层代码只写一条路径，换个不支持流式的实现也不用改。
     */
    default ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools,
                              StreamListener listener) {
        ChatResponse response = chat(messages, tools);
        String text = response.text();
        if (!text.isEmpty()) {
            listener.onTextDelta(text);
        }
        for (ToolCall call : response.message().toolCallsOrEmpty()) {
            listener.onToolCallStarted(call.name());
        }
        return response;
    }

    /**
     * 要求服务端保证输出是合法 JSON（见 {@link ResponseFormat}）。
     *
     * <p>做成 default 空实现而不是抽象方法：这是<b>可选的优化</b>，不是所有后端都支持，
     * 也不是所有实现都该被迫处理它。不支持的实现原样返回自己，上层照跑不误——
     * 反正解析失败还有回灌重试兜着。测试里的假客户端也因此一行都不用改。
     *
     * @return 自己，便于链式调用
     */
    default LlmClient jsonMode(boolean on) {
        return this;
    }

    /** 采样温度。结构化输出该压低，闲聊该放开，所以得能按用途分别设。 */
    default LlmClient temperature(double value) {
        return this;
    }

    /**
     * 单次回答的 token 上限。
     *
     * <p>开了 JSON 模式就<b>必须</b>设这个：输出被截断时，服务端可能一直吐空白，
     * 不封顶就会一路烧到额度见底。
     */
    default LlmClient maxTokens(int value) {
        return this;
    }

    /** 当前使用的模型名，用于展示和日志。 */
    String modelName();

    /**
     * 取消正在进行的请求。
     *
     * 用户按 Ctrl-C 时，光设个标志位没用——主线程正阻塞在读响应上，
     * 要等模型把话说完（可能几十秒）才有机会看到标志。必须真的把连接掐掉。
     */
    default void cancel() {
    }
}
