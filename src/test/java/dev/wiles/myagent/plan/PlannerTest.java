package dev.wiles.myagent.plan;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {

    /** 按剧本逐条返回的假模型，同时记下每次收到的消息。 */
    static final class ScriptedLlm implements LlmClient {
        final Deque<String> script = new ArrayDeque<>();
        final List<List<ChatMessage>> received = new ArrayList<>();
        final List<List<ToolSpec>> receivedTools = new ArrayList<>();
        RuntimeException blowUp;
        Boolean jsonMode;
        Double temperature;
        Integer maxTokens;

        ScriptedLlm(String... replies) {
            script.addAll(List.of(replies));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            received.add(List.copyOf(messages));
            receivedTools.add(tools);
            if (blowUp != null) {
                throw blowUp;
            }
            String reply = script.isEmpty() ? "没词儿了" : script.poll();
            return new ChatResponse("x", "fake", List.of(new ChatResponse.Choice(
                    0, ChatMessage.assistant(reply), "stop")), null);
        }

        @Override
        public LlmClient jsonMode(boolean on) {
            this.jsonMode = on;
            return this;
        }

        @Override
        public LlmClient temperature(double value) {
            this.temperature = value;
            return this;
        }

        @Override
        public LlmClient maxTokens(int value) {
            this.maxTokens = value;
            return this;
        }

        @Override
        public String modelName() {
            return "fake";
        }

        int calls() {
            return received.size();
        }

        /** 最后一次请求里，最后那条用户消息——回灌的错误就在这里。 */
        String lastUserMessage() {
            List<ChatMessage> messages = received.get(received.size() - 1);
            return messages.stream().filter(m -> "user".equals(m.role()))
                    .reduce((a, b) -> b).orElseThrow().content();
        }
    }

    private static String validPlan() {
        return """
                {"goal": "给 Task 加上重试能力", "tasks": [
                  {"id": "t1", "type": "FILE_READ", "description": "读 Task.java", "acceptance": "读 Task.java——做完了", "dependsOn": []},
                  {"id": "t2", "type": "FILE_READ", "description": "读 TaskTest", "acceptance": "读 TaskTest——做完了", "dependsOn": []},
                  {"id": "t3", "type": "FILE_WRITE", "description": "加 retry", "acceptance": "加 retry——做完了", "dependsOn": ["t1", "t2"]},
                  {"id": "t4", "type": "FILE_WRITE", "description": "补测试", "acceptance": "补测试——做完了", "dependsOn": ["t3"]},
                  {"id": "t5", "type": "VERIFICATION", "description": "跑 mvn test", "acceptance": "跑 mvn test——做完了", "dependsOn": ["t4"]}
                ]}
                """;
    }

    // ── 一次成功 ────────────────────────────────────────────────

    @Test
    @DisplayName("模型一次给对：产出一个 CREATED 状态的计划")
    void plansInOneShot() {
        ScriptedLlm llm = new ScriptedLlm(validPlan());

        Plan plan = new Planner(llm).plan("给 Task 加上重试");

        assertEquals(PlanStatus.CREATED, plan.status());
        assertEquals("给 Task 加上重试能力", plan.goal());
        assertEquals(5, plan.graph().size());
        assertEquals(List.of("t1", "t2"), plan.graph().get("t3").dependencies());
        assertEquals(1, llm.calls());
    }

    @Test
    @DisplayName("规划时不给工具：这一步只要它想清楚，不要它动手")
    void planningSendsNoTools() {
        ScriptedLlm llm = new ScriptedLlm(validPlan());

        new Planner(llm).plan("随便");

        assertNull(llm.receivedTools.get(0));
    }

    @Test
    @DisplayName("用户的原话要原样进提示词，不能只发一句转述")
    void userGoalReachesTheModel() {
        ScriptedLlm llm = new ScriptedLlm(validPlan());

        new Planner(llm).plan("把 Task 加上重试，别动 TaskGraph");

        assertTrue(llm.lastUserMessage().contains("把 Task 加上重试，别动 TaskGraph"));
    }

    // ── 回灌重试 ────────────────────────────────────────────────

    @Test
    @DisplayName("模型答非所问：把错误回灌，第二次就对了")
    void retriesAfterNonJsonAnswer() {
        ScriptedLlm llm = new ScriptedLlm("这个需求我建议先讨论一下。", validPlan());

        Plan plan = new Planner(llm).plan("给 Task 加上重试");

        assertEquals(2, llm.calls());
        assertEquals(5, plan.graph().size());
        assertTrue(llm.lastUserMessage().contains("JSON"), "回灌的那条消息要说清哪儿不对");
    }

    @Test
    @DisplayName("重试时上一轮的回答要留在对话里，模型才知道自己刚才写了什么")
    void retryKeepsTheBadAnswerInHistory() {
        ScriptedLlm llm = new ScriptedLlm("我先问几个问题。", validPlan());

        new Planner(llm).plan("随便");

        List<ChatMessage> second = llm.received.get(1);
        assertTrue(second.stream().anyMatch(m -> "assistant".equals(m.role())
                && "我先问几个问题。".equals(m.content())));
    }

    @Test
    @DisplayName("成环也走回灌，报错里带着环的路径")
    void retriesAfterCycle() {
        String cyclic = """
                {"goal": "x", "tasks": [
                  {"id": "t1", "type": "FILE_READ", "description": "a", "acceptance": "a——做完了", "dependsOn": ["t5"]},
                  {"id": "t2", "type": "FILE_READ", "description": "b", "acceptance": "b——做完了", "dependsOn": ["t1"]},
                  {"id": "t3", "type": "FILE_READ", "description": "c", "acceptance": "c——做完了", "dependsOn": ["t2"]},
                  {"id": "t4", "type": "FILE_READ", "description": "d", "acceptance": "d——做完了", "dependsOn": ["t3"]},
                  {"id": "t5", "type": "FILE_READ", "description": "e", "acceptance": "e——做完了", "dependsOn": ["t4"]}
                ]}
                """;
        ScriptedLlm llm = new ScriptedLlm(cyclic, validPlan());

        new Planner(llm).plan("随便");

        assertEquals(2, llm.calls());
        assertTrue(llm.lastUserMessage().contains("循环依赖"), llm.lastUserMessage());
        assertTrue(llm.lastUserMessage().contains("→"), "要把环的路径给它看：" + llm.lastUserMessage());
    }

    @Test
    @DisplayName("重试用完还不对：抛出来，附上最后一次的原因")
    void givesUpAfterRetries() {
        ScriptedLlm llm = new ScriptedLlm("不行", "还是不行", "就不给你 JSON");

        PlanningException e = assertThrows(PlanningException.class,
                () -> new Planner(llm).plan("随便"));

        assertEquals(3, llm.calls(), "默认重试 2 次，总共 3 次调用");
        assertTrue(e.getMessage().contains("JSON"), e.getMessage());
    }

    @Test
    @DisplayName("重试次数可调，0 次就是不给第二次机会")
    void retryCountIsConfigurable() {
        ScriptedLlm llm = new ScriptedLlm("不行", validPlan());

        assertThrows(PlanningException.class, () -> new Planner(llm, 0).plan("随便"));
        assertEquals(1, llm.calls());
    }

    @Test
    @DisplayName("模型调用本身挂了不重试——网络重试是客户端的事，这里再套一层只会放大问题")
    void doesNotRetryTransportFailures() {
        ScriptedLlm llm = new ScriptedLlm(validPlan());
        llm.blowUp = new LlmException("限流了");

        assertThrows(LlmException.class, () -> new Planner(llm).plan("随便"));
        assertEquals(1, llm.calls());
    }

    // ── 子规划 ──────────────────────────────────────────────────

    @Test
    @DisplayName("PLANNING 任务拆子任务：走同一套重试，产出深度 1 的任务")
    void plansSubTasks() {
        String subPlan = """
                {"tasks": [
                  {"id": "t1.1", "type": "FILE_WRITE", "description": "改 A", "acceptance": "改 A——做完了", "dependsOn": []},
                  {"id": "t1.2", "type": "VERIFICATION", "description": "跑测试", "acceptance": "跑测试——做完了", "dependsOn": ["t1.1"]}
                ]}
                """;
        ScriptedLlm llm = new ScriptedLlm("我想想……", subPlan);
        Task parent = new Task("t1", "摸清要改哪儿", TaskType.PLANNING, List.of());

        List<Task> subTasks = new Planner(llm).planSubTasks(parent, "看下来要改两处");

        assertEquals(2, subTasks.size());
        assertEquals("t1", subTasks.get(0).parentId());
        assertEquals(2, llm.calls(), "子规划同样享受回灌重试");
    }

    @Test
    @DisplayName("子规划的提示词要带上父任务的描述和它刚摸到的情况")
    void subPlanPromptCarriesContext() {
        ScriptedLlm llm = new ScriptedLlm("""
                {"tasks": [{"id": "t1.1", "type": "FILE_WRITE", "description": "改", "acceptance": "改——做完了", "dependsOn": []}]}
                """);
        Task parent = new Task("t1", "摸清要改哪儿", TaskType.PLANNING, List.of());

        new Planner(llm).planSubTasks(parent, "发现要改 Task.java 和 TaskGraph.java");

        assertTrue(llm.lastUserMessage().contains("摸清要改哪儿"));
        assertTrue(llm.lastUserMessage().contains("发现要改 Task.java 和 TaskGraph.java"));
    }

    // ── 只读模式 ────────────────────────────────────────────────

    @Test
    @DisplayName("只读模式要写进提示词——不说的话它会规划出一堆跑不了的任务")
    void readOnlyIsMentionedInThePrompt() {
        ScriptedLlm llm = new ScriptedLlm("""
                {"goal": "看看代码", "tasks": [
                  {"id": "t1", "type": "FILE_READ", "description": "读 a", "acceptance": "读 a——做完了", "dependsOn": []},
                  {"id": "t2", "type": "FILE_READ", "description": "读 b", "acceptance": "读 b——做完了", "dependsOn": []},
                  {"id": "t3", "type": "FILE_READ", "description": "读 c", "acceptance": "读 c——做完了", "dependsOn": []},
                  {"id": "t4", "type": "FILE_READ", "description": "读 d", "acceptance": "读 d——做完了", "dependsOn": []},
                  {"id": "t5", "type": "ANALYSIS", "description": "总结", "acceptance": "总结——做完了", "dependsOn": ["t1"]}
                ]}
                """);

        new Planner(llm).readOnly(true).plan("看看代码");

        assertTrue(llm.received.get(0).get(0).content().contains("只读模式"));
    }

    @Test
    @DisplayName("只读模式下规划出写任务：打回重写，不能等跑到那一步才发现没工具")
    void readOnlyRejectsWriteTasks() {
        ScriptedLlm llm = new ScriptedLlm(validPlan(), """
                {"goal": "只看不改", "tasks": [
                  {"id": "t1", "type": "FILE_READ", "description": "读 a", "acceptance": "读 a——做完了", "dependsOn": []},
                  {"id": "t2", "type": "FILE_READ", "description": "读 b", "acceptance": "读 b——做完了", "dependsOn": []},
                  {"id": "t3", "type": "FILE_READ", "description": "读 c", "acceptance": "读 c——做完了", "dependsOn": []},
                  {"id": "t4", "type": "FILE_READ", "description": "读 d", "acceptance": "读 d——做完了", "dependsOn": []},
                  {"id": "t5", "type": "ANALYSIS", "description": "总结", "acceptance": "总结——做完了", "dependsOn": ["t1"]}
                ]}
                """);

        Plan plan = new Planner(llm).readOnly(true).plan("随便");

        assertEquals(2, llm.calls(), "第一份计划里有 FILE_WRITE，该被打回");
        assertTrue(llm.lastUserMessage().contains("只读模式"), llm.lastUserMessage());
        assertEquals("只看不改", plan.goal());
    }

    @Test
    @DisplayName("非只读模式下，写任务照常放行")
    void writeTasksAreFineByDefault() {
        ScriptedLlm llm = new ScriptedLlm(validPlan());

        assertEquals(5, new Planner(llm).plan("随便").graph().size());
        assertEquals(1, llm.calls());
    }
    @Test
    @DisplayName("构造时就把三个旋钮拧好：要 JSON、低温、封顶")
    void configuresTheClientForStructuredOutput() {
        ScriptedLlm llm = new ScriptedLlm(validPlan());

        new Planner(llm).plan("随便");

        assertEquals(Boolean.TRUE, llm.jsonMode, "没开 JSON 模式，会悄悄多几轮重试");
        assertEquals(Planner.PLANNING_TEMPERATURE, llm.temperature);
        assertEquals(Planner.PLANNING_MAX_TOKENS, llm.maxTokens,
                "开了 JSON 模式不封顶，截断时可能一直吐空白");
    }

    @Test
    @DisplayName("现场信息进了系统提示词，模型不必再靠探路任务去摸")
    void contextReachesTheSystemPrompt() {
        ScriptedLlm llm = new ScriptedLlm(validPlan());
        PlanContext ctx = new PlanContext("/tmp/proj", "src/\n  Main.java",
                List.of(new PlanContext.ToolBrief("read_file", "读文件。")), List.of("sudo"));

        new Planner(llm).context(ctx).plan("改点东西");

        String system = llm.received.get(0).get(0).content();
        assertTrue(system.contains("Main.java"), system);
        assertTrue(system.contains("read_file"), system);
        assertTrue(system.contains("sudo"), system);
    }
    // ── 重规划 ──────────────────────────────────────────────────

    /** 造一份跑到一半的计划：t1 成了，t2 倒了，t3 被连累跳过。 */
    private static Plan halfRunPlan() {
        Task t1 = new Task("t1", "读 pom.xml", TaskType.FILE_READ, List.of(), "Java 版本是 17");
        Task t2 = new Task("t2", "改 App.java", TaskType.FILE_WRITE, List.of("t1"), "改完了");
        Task t3 = new Task("t3", "跑测试", TaskType.VERIFICATION, List.of("t2"), "测试通过");
        Plan plan = new Plan("给项目加上重试", TaskGraph.of(List.of(t1, t2, t3)));

        plan.start();
        t1.start();
        t1.succeed("pom 里写的是 Java 17");
        t2.start();
        t2.fail("App.java 不存在");
        plan.graph().propagateFailure("t2");
        return plan;
    }

    @Test
    @DisplayName("补救提示词里四样都在：原目标、已有成果、失败原因、还没干的活")
    void replanPromptCarriesTheWholeSituation() {
        ScriptedLlm llm = new ScriptedLlm("""
                {"tasks": [{"id": "r1", "type": "FILE_READ", "description": "找 App.java 的真实位置",
                            "acceptance": "给出实际路径", "dependsOn": []}]}
                """);
        Plan plan = halfRunPlan();

        List<Task> recovery = new Planner(llm)
                .replan(plan, plan.graph().get("t2"), "App.java 不存在");

        assertEquals(1, recovery.size());
        String user = llm.lastUserMessage();
        assertTrue(user.contains("给项目加上重试"), "缺原目标: " + user);
        assertTrue(user.contains("pom 里写的是 Java 17"), "缺已有成果，它会重做一遍: " + user);
        assertTrue(user.contains("App.java 不存在"), "缺失败原因，它会原样再试一遍: " + user);
        assertTrue(user.contains("跑测试"), "缺被跳过的活，它只会修那一步: " + user);
    }

    @Test
    @DisplayName("模型交白卷：原样返回空列表，不当成错误重试")
    void replanCanGiveUp() {
        ScriptedLlm llm = new ScriptedLlm("{\"tasks\": []}");
        Plan plan = halfRunPlan();

        assertTrue(new Planner(llm).replan(plan, plan.graph().get("t2"), "缺依赖").isEmpty());
        assertEquals(1, llm.calls(), "交白卷不该触发重试");
    }

    @Test
    @DisplayName("补救计划不合法时，错误照样回灌重试")
    void replanRetriesOnBadOutput() {
        ScriptedLlm llm = new ScriptedLlm(
                "我觉得这事儿得再商量商量",
                """
                {"tasks": [{"id": "r1", "type": "FILE_READ", "description": "查清楚",
                            "acceptance": "查到了", "dependsOn": []}]}
                """);
        Plan plan = halfRunPlan();

        List<Task> recovery = new Planner(llm)
                .replan(plan, plan.graph().get("t2"), "挂了");

        assertEquals(1, recovery.size());
        assertEquals(2, llm.calls());
    }

    @Test
    @DisplayName("只读模式下补救也不许规划写任务")
    void replanRespectsReadOnly() {
        ScriptedLlm llm = new ScriptedLlm("""
                {"tasks": [{"id": "r1", "type": "FILE_WRITE", "description": "改文件",
                            "acceptance": "改完", "dependsOn": []}]}
                """);
        Plan plan = halfRunPlan();

        assertThrows(PlanningException.class, () -> new Planner(llm).readOnly(true)
                .replan(plan, plan.graph().get("t2"), "挂了"));

        String system = llm.received.get(0).get(0).content();
        assertTrue(system.contains("只读模式"), system);
        // 光靠提示词不够，产出里还得再校验一遍——这条回灌的错误就是第二道防线
        assertTrue(llm.received.get(1).stream().anyMatch(m -> m.content().contains("只读模式")),
                "写任务被打回时，理由要回灌给模型");
    }
}
