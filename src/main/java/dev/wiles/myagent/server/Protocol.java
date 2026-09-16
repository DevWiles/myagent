package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.agent.AgentEvent;

/**
 * 前后端之间的线上协议：一行一个 JSON 对象，用字段 {@code t} 区分类型。
 *
 * <p>为什么把序列化单独拎出来：{@link AgentEvent} 是后端的领域模型，它不该
 * 知道前端长什么样，更不该为了传输而被塞进 Jackson 注解。这里做一次单向翻译，
 * 后端那边一个字都不用改。
 *
 * <p>协议是「事件流 + 命令」两个方向：
 * <pre>
 *   服务端 → 客户端   ready / thinking / text_delta / thought / tool_started
 *                     tool_finished / compacted / done / failed
 *                     approval_request / busy / error / pong
 *   客户端 → 服务端   prompt / cancel / approval_reply / reset / ping
 * </pre>
 */
public final class Protocol {

    /** 协议版本。两边对不上时应当直接拒绝连接，而不是带着半懂不懂往下跑。 */
    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Protocol() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode event(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", type);
        return node;
    }

    /**
     * 把一个 agent 事件翻成线上报文。
     *
     * <p>{@code toolSeq} 是服务端自己编的流水号：{@link AgentEvent.ToolStarted} 和
     * {@link AgentEvent.ToolFinished} 本身不带 id，而前端要靠它把「开始」和「结束」
     * 配成同一行来原地更新。Agent 的工具调用是严格串行的（见 Agent.run 里的 for 循环），
     * 所以同一个流水号一定对应同一次调用。
     */
    public static ObjectNode encode(AgentEvent event, int toolSeq) {
        if (event instanceof AgentEvent.Thinking e) {
            ObjectNode n = event("thinking");
            n.put("step", e.step());
            return n;
        }
        if (event instanceof AgentEvent.TextDelta e) {
            ObjectNode n = event("text_delta");
            n.put("text", e.text());
            return n;
        }
        if (event instanceof AgentEvent.Thought e) {
            ObjectNode n = event("thought");
            n.put("text", e.text());
            return n;
        }
        if (event instanceof AgentEvent.ToolStarted e) {
            ObjectNode n = event("tool_started");
            n.put("id", toolSeq);
            n.put("tool", e.tool());
            n.put("arguments", e.arguments());
            return n;
        }
        if (event instanceof AgentEvent.ToolFinished e) {
            ObjectNode n = event("tool_finished");
            n.put("id", toolSeq);
            n.put("tool", e.tool());
            n.put("ok", e.ok());
            n.put("output", e.output());
            return n;
        }
        if (event instanceof AgentEvent.Compacted e) {
            ObjectNode n = event("compacted");
            n.put("tokensBefore", e.tokensBefore());
            n.put("tokensAfter", e.tokensAfter());
            n.put("summarized", e.summarized());
            return n;
        }
        if (event instanceof AgentEvent.Done e) {
            ObjectNode n = event("done");
            n.put("text", e.text());
            n.put("steps", e.steps());
            return n;
        }
        if (event instanceof AgentEvent.Failed e) {
            ObjectNode n = event("failed");
            n.put("reason", e.reason());
            return n;
        }
        // sealed interface 保证走不到这里，但 Java 17 的 instanceof 链没法让编译器确信
        throw new IllegalStateException("未知事件类型: " + event.getClass());
    }

    /** 取字符串字段，缺了就返回默认值——前端传什么过来都不该让服务端崩。 */
    public static String str(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }

    public static int num(JsonNode node, String field, int fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asInt(fallback);
    }
}
