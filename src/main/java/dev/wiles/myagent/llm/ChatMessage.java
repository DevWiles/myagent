package dev.wiles.myagent.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一条对话消息。请求和响应共用同一个结构。
 *
 * 四种角色决定了哪些字段有值：
 *   system    -> content
 *   user      -> content
 *   assistant -> content 和/或 toolCalls（模型要调工具时 content 常为 null）
 *   tool      -> content + toolCallId（把工具执行结果回传给模型，id 必须对上）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ROLE_ASSISTANT, content,
                toolCalls == null || toolCalls.isEmpty() ? null : toolCalls, null);
    }

    /** 工具执行完毕后回传结果。toolCallId 必须与模型给出的 ToolCall.id 一致。 */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(ROLE_TOOL, content, null, toolCallId);
    }

    @JsonIgnore
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @JsonIgnore
    public List<ToolCall> toolCallsOrEmpty() {
        return toolCalls == null ? List.of() : toolCalls;
    }
}
