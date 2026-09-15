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
