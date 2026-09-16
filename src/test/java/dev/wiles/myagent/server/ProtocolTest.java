package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.agent.AgentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {

    @Test
    @DisplayName("文本增量原样带过去，一个字符都不能丢")
    void textDelta() {
        ObjectNode n = Protocol.encode(new AgentEvent.TextDelta("你好 world\n"), 0);
        assertEquals("text_delta", n.get("t").asText());
        assertEquals("你好 world\n", n.get("text").asText());
    }

    @Test
    @DisplayName("工具的开始和结束带同一个流水号，界面才能把两条配成一行")
    void toolPairsShareId() {
        ObjectNode started = Protocol.encode(new AgentEvent.ToolStarted("read_file", "{\"path\":\"a\"}"), 7);
        ObjectNode finished = Protocol.encode(new AgentEvent.ToolFinished("read_file", true, "内容"), 7);

        assertEquals("tool_started", started.get("t").asText());
        assertEquals("tool_finished", finished.get("t").asText());
        assertEquals(7, started.get("id").asInt());
        assertEquals(7, finished.get("id").asInt());
        assertTrue(finished.get("ok").asBoolean());
    }

    @Test
    @DisplayName("工具失败要带上 ok=false 和输出——界面只显示一个叉是没法排查的")
    void toolFailureCarriesOutput() {
        ObjectNode n = Protocol.encode(
                new AgentEvent.ToolFinished("bash", false, "command not found: foo"), 1);
        assertFalse(n.get("ok").asBoolean());
        assertEquals("command not found: foo", n.get("output").asText());
    }

    @Test
    @DisplayName("压缩事件要把前后 token 数都带上，用户有权知道丢了多少")
    void compacted() {
        ObjectNode n = Protocol.encode(new AgentEvent.Compacted(50000, 12000, true), 0);
        assertEquals("compacted", n.get("t").asText());
        assertEquals(50000, n.get("tokensBefore").asInt());
        assertEquals(12000, n.get("tokensAfter").asInt());
        assertTrue(n.get("summarized").asBoolean());
    }

    @Test
    @DisplayName("每一种 AgentEvent 都有对应的报文类型")
    void everyEventTypeIsCovered() {
        AgentEvent[] all = {
                new AgentEvent.Thinking(1),
                new AgentEvent.TextDelta("x"),
                new AgentEvent.Thought("x"),
                new AgentEvent.ToolStarted("t", "{}"),
                new AgentEvent.ToolFinished("t", true, "x"),
                new AgentEvent.Compacted(1, 1, false),
                new AgentEvent.Done("x", 1),
                new AgentEvent.Failed("x"),
        };
        for (AgentEvent event : all) {
            ObjectNode n = assertDoesNotThrow(() -> Protocol.encode(event, 0),
                    () -> event.getClass().getSimpleName() + " 没有对应的报文");
            assertTrue(n.hasNonNull("t"));
        }
    }

    @Test
    @DisplayName("取字段容错：前端漏传字段不该让服务端崩")
    void lenientFieldAccess() throws Exception {
        var node = Protocol.mapper().readTree("{\"t\":\"prompt\"}");
        assertEquals("", Protocol.str(node, "text", ""));
        assertEquals(-1, Protocol.num(node, "id", -1));
    }
}
