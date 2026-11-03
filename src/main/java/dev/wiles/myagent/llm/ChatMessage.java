package dev.wiles.myagent.llm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条对话消息。请求和响应共用同一个结构。
 *
 * 四种角色决定了哪些字段有值：
 *   system    -> content
 *   user      -> content
 *   assistant -> content 和/或 toolCalls（模型要调工具时 content 常为 null）
 *   tool      -> content + toolCallId（把工具执行结果回传给模型，id 必须对上）
 *
 * <h3>图片</h3>
 * user 消息可以带图（用户粘贴的截图）。线上和存档用的都是 OpenAI 的写法：
 * 带图时 {@code content} 是 {@code [{type:text}, {type:image_url}...]} 数组，不带图时仍是一个字符串——
 * 所以老存档一个字节不用动，没图的请求和以前完全一样。
 * 代码里读 {@link #content()} 拿到的<b>永远只是文字</b>：压缩、估算、恢复对话都只认文字，
 * 不用各自学会拆数组；图片单独放在 {@link #images()} 里，是 {@code data:} URL。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
        String role,
        @JsonIgnore String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonIgnore List<String> images
) {

    public ChatMessage {
        images = images == null || images.isEmpty() ? null : List.copyOf(images);
    }

    public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, null);
    }

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

    /** 带图的一句话。{@code images} 是 {@code data:image/png;base64,...} 这样的 URL，空的就等于不带图。 */
    public static ChatMessage user(String content, List<String> images) {
        return new ChatMessage(ROLE_USER, content, null, null, images);
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

    @JsonIgnore
    public List<String> imagesOrEmpty() {
        return images == null ? List.of() : images;
    }

    /** 线上的 content：没图就是那段文字，有图就是文字段加图片段的数组。 */
    @JsonProperty("content")
    Object wireContent() {
        if (images == null) {
            return content;
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        if (content != null && !content.isEmpty()) {
            parts.add(Map.of("type", "text", "text", content));
        }
        for (String url : images) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "image_url");
            part.put("image_url", Map.of("url", url));
            parts.add(part);
        }
        return parts;
    }

    /** 两种 content 都认：字符串（绝大多数，也是全部老存档），或者文字段加图片段的数组。 */
    @JsonCreator
    static ChatMessage fromJson(@JsonProperty("role") String role,
                                @JsonProperty("content") JsonNode content,
                                @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                                @JsonProperty("tool_call_id") String toolCallId) {
        if (content == null || content.isNull()) {
            return new ChatMessage(role, null, toolCalls, toolCallId);
        }
        if (!content.isArray()) {
            return new ChatMessage(role, content.asText(), toolCalls, toolCallId);
        }
        StringBuilder text = new StringBuilder();
        List<String> images = new ArrayList<>();
        for (JsonNode part : content) {
            switch (part.path("type").asText()) {
                case "text" -> text.append(text.isEmpty() ? "" : "\n").append(part.path("text").asText());
                case "image_url" -> images.add(part.path("image_url").path("url").asText());
                default -> {
                    // 不认识的段（音频之类）跳过：读不懂的东西不该让整份存档读不出来
                }
            }
        }
        return new ChatMessage(role, text.toString(), toolCalls, toolCallId, images);
    }
}
