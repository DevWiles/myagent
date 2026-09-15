package dev.wiles.myagent.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 一个工具的「说明书」，随请求发给模型，模型据此决定调不调、怎么传参。
 *
 * parameters 是一段 JSON Schema。写得越清楚，模型传错参的概率越低。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolSpec(String type, Function function) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Function(String name, String description, JsonNode parameters) {
    }

    public static ToolSpec of(String name, String description, JsonNode parametersSchema) {
        return new ToolSpec("function", new Function(name, description, parametersSchema));
    }

    /** 方便直接用一段 JSON Schema 字面量声明工具。 */
    public static ToolSpec of(String name, String description, String parametersSchemaJson) {
        try {
            return of(name, description, MAPPER.readTree(parametersSchemaJson));
        } catch (Exception e) {
            throw new IllegalArgumentException("工具 " + name + " 的 JSON Schema 不合法", e);
        }
    }

    public String name() {
        return function == null ? null : function.name();
    }
}
