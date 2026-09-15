package dev.wiles.myagent.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 模型决定要调用的一次工具。
 *
 * 注意 function.arguments 是一个「JSON 字符串」，不是 JSON 对象——这是 OpenAI 兼容
 * 协议的规定，模型把参数序列化成字符串塞进来，需要我们自己二次解析。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolCall(
        String id,
        String type,
        Function function
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(String name, String arguments) {
    }

    public static ToolCall of(String id, String name, String argumentsJson) {
        return new ToolCall(id, "function", new Function(name, argumentsJson));
    }

    @JsonIgnore
    public String name() {
        return function == null ? null : function.name();
    }

    /** 原始参数 JSON 字符串；模型偶尔会给空串，统一成 "{}" 方便下游解析。 */
    @JsonIgnore
    public String argumentsJson() {
        if (function == null || function.arguments() == null || function.arguments().isBlank()) {
            return "{}";
        }
        return function.arguments();
    }
}
