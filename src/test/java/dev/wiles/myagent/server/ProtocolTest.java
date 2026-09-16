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

    // ── 计划相关报文 ────────────────────────────────────────────

    private static dev.wiles.myagent.plan.Plan samplePlan() {
        dev.wiles.myagent.plan.TaskGraph graph = dev.wiles.myagent.plan.TaskGraph.of(java.util.List.of(
                new dev.wiles.myagent.plan.Task("t1", "读 a",
                        dev.wiles.myagent.plan.TaskType.FILE_READ, java.util.List.of()),
                new dev.wiles.myagent.plan.Task("t2", "读 b",
                        dev.wiles.myagent.plan.TaskType.FILE_READ, java.util.List.of()),
                new dev.wiles.myagent.plan.Task("t3", "改 c",
                        dev.wiles.myagent.plan.TaskType.FILE_WRITE, java.util.List.of("t1", "t2"))));
        return new dev.wiles.myagent.plan.Plan("把 a 和 b 合进 c", graph);
    }

    @Test
    @DisplayName("计划报文带上整张图，还有算好的分层——别让前端再实现一遍图算法")
    void encodesPlan() {
        ObjectNode n = Protocol.encodePlan(samplePlan());

        assertEquals("plan_created", n.get("t").asText());
        assertEquals("把 a 和 b 合进 c", n.get("goal").asText());
        assertEquals(3, n.get("tasks").size());
        assertEquals("FILE_WRITE", n.get("tasks").get(2).get("type").asText());
        assertEquals(2, n.get("tasks").get(2).get("dependsOn").size());

        assertEquals(2, n.get("layers").size(), "菱形收口：两层");
        assertEquals(2, n.get("layers").get(0).size());
        assertEquals("t3", n.get("layers").get(1).get(0).asText());
    }

    @Test
    @DisplayName("任务开始和结束的报文字段齐全")
    void encodesTaskLifecycle() {
        dev.wiles.myagent.plan.Task task = new dev.wiles.myagent.plan.Task(
                "t1", "读 a", dev.wiles.myagent.plan.TaskType.FILE_READ, java.util.List.of());

        ObjectNode started = Protocol.encodeTaskStarted(task);
        assertEquals("plan_task_started", started.get("t").asText());
        assertEquals("t1", started.get("taskId").asText());
        assertEquals("FILE_READ", started.get("taskType").asText());

        task.start();
        task.succeed("Java 17");
        ObjectNode finished = Protocol.encodeTaskFinished(task);
        assertEquals("SUCCEEDED", finished.get("status").asText());
        assertEquals("Java 17", finished.get("result").asText());
        assertTrue(finished.get("durationMs").asLong() >= 0);
        assertTrue(finished.get("error").isNull(), "没出错时 error 要是 null，不是空串");
    }

    @Test
    @DisplayName("动态扩图的报文标明是谁拆出来的，前端才能就地插进去")
    void encodesAddedTasks() {
        dev.wiles.myagent.plan.Task parent = new dev.wiles.myagent.plan.Task(
                "t1", "探路", dev.wiles.myagent.plan.TaskType.PLANNING, java.util.List.of());
        ObjectNode n = Protocol.encodeAddedTasks("t1", java.util.List.of(
                parent.subTask("t1.1", "改 A",
                        dev.wiles.myagent.plan.TaskType.FILE_WRITE, java.util.List.of())));

        assertEquals("plan_tasks_added", n.get("t").asText());
        assertEquals("t1", n.get("parentId").asText());
        assertEquals("t1.1", n.get("tasks").get(0).get("id").asText());
    }

    @Test
    @DisplayName("布尔字段漏传时退回默认值，不能让前端一个笔误把服务端搞崩")
    void readsBooleansDefensively() {
        com.fasterxml.jackson.databind.node.ObjectNode cmd = Protocol.event("plan_confirm_reply");

        assertFalse(Protocol.bool(cmd, "approved", false));
        cmd.put("approved", true);
        assertTrue(Protocol.bool(cmd, "approved", false));
    }
}
