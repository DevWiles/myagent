package dev.wiles.myagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一连串流式增量拼回一条完整的 assistant 消息。
 *
 * <h3>为什么需要它</h3>
 * 非流式请求一次就拿到完整的 message，流式则是几十上百个碎片：
 * <pre>
 *   {"delta":{"content":"我"}}
 *   {"delta":{"content":"先"}}
 *   {"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":""}}]}}
 *   {"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"pa"}}]}}
 *   {"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"pom.xml\"}"}}]}}
 *   {"delta":{},"finish_reason":"tool_calls"}
 * </pre>
 *
 * 两个关键点：
 * <ul>
 *   <li><b>工具参数是切碎了传的</b>，一片片拼起来才是完整 JSON。
 *       中途任何时候去解析都会失败——它本来就还没传完。</li>
 *   <li><b>靠 index 区分是哪个工具</b>，不是靠 id：模型一次点多个工具时，
 *       各自的分片是交替到达的，而 id 只在第一个分片里出现一次。</li>
 * </ul>
 */
final class StreamAccumulator {

    /** 一个正在拼装中的工具调用。 */
    private static final class Draft {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
        boolean announced;
    }

    private final StringBuilder content = new StringBuilder();
    /** index -> 草稿。用 LinkedHashMap 保证最终顺序和模型给的一致。 */
    private final Map<Integer, Draft> drafts = new LinkedHashMap<>();
    private String finishReason;
    private String id = "";
    private String model = "";

    /** 吃掉一个 chunk，并把新增文本回调出去。 */
    void accept(JsonNode chunk, StreamListener listener) {
        if (chunk.hasNonNull("id")) {
            id = chunk.get("id").asText();
        }
        if (chunk.hasNonNull("model")) {
            model = chunk.get("model").asText();
        }
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.get(0);
        if (choice.hasNonNull("finish_reason")) {
            finishReason = choice.get("finish_reason").asText();
        }
        JsonNode delta = choice.get("delta");
        if (delta == null) {
            return;
        }
        if (delta.hasNonNull("content")) {
            String text = delta.get("content").asText();
            if (!text.isEmpty()) {
                content.append(text);
                listener.onTextDelta(text);
            }
        }
        JsonNode toolCalls = delta.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                acceptToolCall(call, listener);
            }
        }
    }

    private void acceptToolCall(JsonNode call, StreamListener listener) {
        int index = call.hasNonNull("index") ? call.get("index").asInt() : 0;
        Draft draft = drafts.computeIfAbsent(index, k -> new Draft());

        if (call.hasNonNull("id")) {
            draft.id = call.get("id").asText();
        }
        JsonNode function = call.get("function");
        if (function == null) {
            return;
        }
        if (function.hasNonNull("name")) {
            draft.name = function.get("name").asText();
        }
        if (function.hasNonNull("arguments")) {
            draft.arguments.append(function.get("arguments").asText());
        }
        // 名字一到手就通知一次，让界面能立刻显示「正在调用 read_file」
        if (draft.name != null && !draft.announced) {
            draft.announced = true;
            listener.onToolCallStarted(draft.name);
        }
    }

    /** 拼装完毕，产出一个和非流式请求形状完全一致的响应。 */
    ChatResponse toResponse() {
        List<ToolCall> calls = new ArrayList<>();
        for (Draft draft : drafts.values()) {
            if (draft.name == null) {
                continue;                       // 只有分片没有名字，说明这条没传完，丢弃
            }
            calls.add(ToolCall.of(draft.id == null ? "" : draft.id,
                    draft.name, draft.arguments.toString()));
        }
        String text = content.length() == 0 ? null : content.toString();
        ChatMessage message = calls.isEmpty()
                ? ChatMessage.assistant(text)
                : ChatMessage.assistant(text, calls);
        return new ChatResponse(id, model,
                List.of(new ChatResponse.Choice(0, message, finishReason)), null);
    }
}
