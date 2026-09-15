package dev.wiles.myagent.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 发往 /chat/completions 的请求体。
 * NON_NULL 保证没设的字段不会出现在报文里（空的 tools 字段会让部分网关报错）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        List<ToolSpec> tools,
        @JsonProperty("tool_choice") String toolChoice,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        Boolean stream
) {
}
