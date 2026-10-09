package dev.wiles.myagent.agent;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 委派决策器：结构化决策的解析和校验，以及「任何拿不准都退回单 agent」。
 */
class DelegationDeciderTest {

    private static final String TWO_WORKERS = """
            好的，决策如下：
            ```json
            {"delegate": true, "reason": "两层互不依赖", "expected_benefit": "并行省时间",
             "risks": ["接口对不上"], "estimated_cost": "约多 30% token",
             "subtasks": [
               {"agent_type": "worker", "goal": "实现 config 层", "owns": ["pkg/config.py"],
                "acceptance": "test_config 全过", "verify": "pytest tests/test_config.py"},
               {"agent_type": "explorer", "goal": "查清 hooks 的调用链", "owns": ["不该有"]}
             ]}
            ```""";

    @Test
    @DisplayName("委派：子任务、写入范围、完成标准都解析出来；explorer 的 owns 被丢掉")
    void parsesDelegation() {
        DelegationDecider.Decision d = DelegationDecider.parse(TWO_WORKERS, 6);

        assertTrue(d.delegate());
        assertEquals("两层互不依赖", d.reason());
        assertEquals(List.of("接口对不上"), d.risks());
        assertEquals(2, d.subtasks().size());
        assertEquals(List.of("pkg/config.py"), d.subtasks().get(0).owns());
        assertEquals(List.of(), d.subtasks().get(1).owns(), "explorer 不写文件");
        String plan = d.render();
        assertTrue(plan.startsWith(SubAgents.PLAN_TAG));
        assertTrue(plan.contains("owns: pkg/config.py"));
        assertTrue(plan.contains("pytest tests/test_config.py"));
        assertTrue(SubAgents.isNotice(plan), "计划不是人说的话，界面数用户消息时要跳过");
        assertTrue(d.toJson().path("subtasks").get(0).path("owns").get(0).asText().equals("pkg/config.py"));
    }

    @Test
    @DisplayName("不委派：保留理由，子任务清空")
    void parsesRefusal() {
        DelegationDecider.Decision d = DelegationDecider.parse(
                "{\"delegate\": false, \"reason\": \"单文件小改动\", \"subtasks\": [{\"goal\": \"x\"}]}", 6);
        assertFalse(d.delegate());
        assertEquals("单文件小改动", d.reason());
        assertTrue(d.subtasks().isEmpty());
    }

    @Test
    @DisplayName("站不住的计划一律退回单 agent，并写明原因")
    void vetoesUnsafePlans() {
        assertVetoed("不是 JSON", "没有输出 JSON");
        assertVetoed("{\"delegate\": true, }", "解析失败");
        assertVetoed("{\"delegate\": true, \"reason\": \"r\", \"subtasks\": []}", "没给出有效的子任务");
        assertVetoed("{\"delegate\": true, \"subtasks\": [{\"agent_type\": \"worker\", \"goal\": \"g\"}]}",
                "没写写入范围");
        assertVetoed("""
                {"delegate": true, "subtasks": [
                  {"agent_type": "worker", "goal": "a", "owns": ["pkg"]},
                  {"agent_type": "worker", "goal": "b", "owns": ["./pkg/sub/m.py"]}]}""", "重叠");
        assertVetoed("""
                {"delegate": true, "subtasks": [
                  {"agent_type": "worker", "goal": "a", "owns": ["x.py"]},
                  {"agent_type": "default", "goal": "b", "owns": ["x.py/"]}]}""", "重叠");
        StringBuilder many = new StringBuilder("{\"delegate\": true, \"subtasks\": [");
        for (int i = 0; i < 4; i++) {
            many.append(i == 0 ? "" : ",").append("{\"agent_type\": \"explorer\", \"goal\": \"q").append(i).append("\"}");
        }
        assertVetoed(many.append("]}").toString(), "超过上限 3");
    }

    private static void assertVetoed(String raw, String why) {
        DelegationDecider.Decision d = DelegationDecider.parse(raw, 3);
        assertFalse(d.delegate(), raw);
        assertTrue(d.reason().contains(why), d.reason());
        assertTrue(d.subtasks().isEmpty());
    }

    /** 记下收到的消息，按给定的话回答（或者抛错）。 */
    static final class Fake implements LlmClient {
        final List<List<ChatMessage>> seen = new ArrayList<>();
        final String reply;

        Fake(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            seen.add(messages);
            if (reply == null) {
                throw new LlmException("网关挂了");
            }
            ChatMessage m = ChatMessage.assistant(reply);
            return new ChatResponse("id", "fake", List.of(new ChatResponse.Choice(0, m, "stop")), null);
        }

        @Override
        public String modelName() {
            return "fake";
        }
    }

    @Test
    @DisplayName("决策请求带着用户原话、预算和工作区概览，不带工具")
    void requestCarriesTaskBudgetAndTree(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("pkg"));
        Files.writeString(ws.resolve("pkg/config.py"), "x = 1\n");
        Fake llm = new Fake("{\"delegate\": false, \"reason\": \"小\"}");

        DelegationDecider.Decision d = new DelegationDecider(llm, ws, 3).decide("修 config 的 bug", 100);

        assertFalse(d.delegate());
        String user = llm.seen.get(0).get(1).content();
        assertTrue(user.contains("修 config 的 bug"));
        assertTrue(user.contains("100"));
        assertTrue(user.contains("config.py"), "工作区概览让它判断规模");
        assertTrue(llm.seen.get(0).get(0).content().contains("最多 3 个"), "上限填进提示词");
        assertFalse(llm.seen.get(0).get(0).content().contains("{{"));
    }

    @Test
    @DisplayName("决策器调用失败不挡路：按单 agent 处理")
    void failureFallsBackToSingle(@TempDir Path ws) {
        DelegationDecider.Decision d = new DelegationDecider(new Fake(null), ws, 3).decide("任务", 0);
        assertFalse(d.delegate());
        assertTrue(d.reason().contains("调用失败"));
    }

    @Test
    @DisplayName("只允许 explorer 时，计划里出现 worker 就退回单 agent；提示词里写明允许的类型")
    void plansRespectAllowedRoles(@TempDir Path ws) {
        DelegationDecider.Decision d = DelegationDecider.parse(TWO_WORKERS, 6, java.util.Set.of("explorer"));
        assertFalse(d.delegate());
        assertTrue(d.reason().contains("不允许的类型 worker"), d.reason());

        Fake llm = new Fake("{\"delegate\": false, \"reason\": \"小\"}");
        new DelegationDecider(llm, ws, 2, java.util.Set.of("explorer")).decide("任务", 0);
        assertTrue(llm.seen.get(0).get(0).content().contains("这一次只允许派：explorer"));
    }
}
