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

    /** 当前使用的模型名，用于展示和日志。 */
    String modelName();
}
