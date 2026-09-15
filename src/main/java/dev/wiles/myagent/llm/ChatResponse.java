package dev.wiles.myagent.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * /chat/completions 的响应体。
 *
 * finishReason 是 agent 循环的关键分支信号：
 *   "stop"       -> 模型说完了，本轮结束
 *   "tool_calls" -> 模型要调工具，执行完把结果回传再来一轮
 *   "length"     -> 撞到 max_tokens 被截断
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            ChatMessage message,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {
    }

    /** 我们不开 n>1，永远只取第一个 choice。 */
    @JsonIgnore
    public ChatMessage message() {
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("模型响应里没有 choices: " + id);
        }
        return choices.get(0).message();
    }

    @JsonIgnore
    public String finishReason() {
        return choices == null || choices.isEmpty() ? null : choices.get(0).finishReason();
    }

    @JsonIgnore
    public boolean wantsToolCall() {
        return message().hasToolCalls();
    }

    /** 纯文本回复；模型只发工具调用时 content 为 null，这里统一成空串。 */
    @JsonIgnore
    public String text() {
        String c = message().content();
        return c == null ? "" : c;
    }
}
