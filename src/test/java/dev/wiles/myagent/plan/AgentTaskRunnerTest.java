package dev.wiles.myagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskRunnerTest {

    private static final Path ROOT = Path.of("/tmp/ws");

    /** 一句话就答完的假模型，顺便记下收到了什么。 */
    static final class FakeLlm implements LlmClient {
        final List<List<ChatMessage>> receivedMessages = new ArrayList<>();
        final List<List<ToolSpec>> receivedTools = new ArrayList<>();
        Function<List<ChatMessage>, String> reply = messages -> "干完了";
        RuntimeException blowUp;

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            receivedMessages.add(List.copyOf(messages));
            receivedTools.add(tools == null ? null : List.copyOf(tools));
            if (blowUp != null) {
                throw blowUp;
            }
            return new ChatResponse("x", "fake", List.of(new ChatResponse.Choice(
                    0, ChatMessage.assistant(reply.apply(messages)), "stop")), null);
        }

        @Override
        public String modelName() {
            return "fake";
        }

        List<String> lastToolNames() {
            List<ToolSpec> tools = receivedTools.get(receivedTools.size() - 1);
            return tools == null ? List.of() : tools.stream().map(ToolSpec::name).toList();
        }

        String lastSystemMessage() {
            List<ChatMessage> messages = receivedMessages.get(receivedMessages.size() - 1);
            return messages.stream().filter(m -> "system".equals(m.role()))
                    .findFirst().orElseThrow().content();
        }

        String lastUserMessage() {
            List<ChatMessage> messages = receivedMessages.get(receivedMessages.size() - 1);
            return messages.stream().filter(m -> "user".equals(m.role()))
                    .reduce((a, b) -> b).orElseThrow().content();
        }
    }

    private static String systemOf(FakeLlm llm) {
        return llm.lastSystemMessage();
    }

    private static Tool named(String name) {
        return new Tool() {
            public String name() {
                return name;
            }

            public String description() {
                return "假的 " + name;
            }

            public String parametersSchema() {
                return "{\"type\":\"object\"}";
            }

            public ToolResult execute(JsonNode args) {
                return ToolResult.ok("");
            }
        };
    }

    private static ToolRegistry fullSet() {
        return new ToolRegistry().registerAll(List.of(
                named("read_file"), named("list_dir"), named("write_file"), named("bash")));
    }

    private static Task task(String id, TaskType type) {
        return new Task(id, "描述 " + id, type, List.of());
    }

    // ── 正常路径 ────────────────────────────────────────────────

    @Test
    @DisplayName("模型的最终答复就是这个任务的产出")
    void returnsModelAnswerAsOutput() {
        FakeLlm llm = new FakeLlm();
        llm.reply = messages -> "读完了，用的是 Java 17";

        TaskOutcome outcome = new AgentTaskRunner(llm, fullSet(), ROOT)
                .run(task("n1", TaskType.FILE_READ), List.of());

        assertTrue(outcome.ok());
        assertEquals("读完了，用的是 Java 17", outcome.output());
        assertTrue(outcome.subTasks().isEmpty());
    }

    @Test
    @DisplayName("工具按角色收窄：调研角色看不到 write_file 和 bash")
    void narrowsToolsByRole() {
        FakeLlm llm = new FakeLlm();

        new AgentTaskRunner(llm, fullSet(), ROOT).run(task("n1", TaskType.FILE_READ), List.of());

        assertEquals(List.of("read_file", "list_dir"), llm.lastToolNames());
    }

    @Test
    @DisplayName("分析任务一个工具都不发——模型没得选，只能用文字回答")
    void analysisSendsNoTools() {
        FakeLlm llm = new FakeLlm();

        new AgentTaskRunner(llm, fullSet(), ROOT).run(task("n1", TaskType.ANALYSIS), List.of());

        assertTrue(llm.lastToolNames().isEmpty());
    }

    @Test
    @DisplayName("审查和调研工具一样，但系统提示词不一样——角色不是标签")
    void sameToolsButDifferentSystemPrompt() {
        FakeLlm explorerRun = new FakeLlm();
        FakeLlm reviewerRun = new FakeLlm();

        new AgentTaskRunner(explorerRun, fullSet(), ROOT)
                .run(new Task("n1", "看看 X", AgentRoles.EXPLORER, List.of(), "看完"), List.of());
        new AgentTaskRunner(reviewerRun, fullSet(), ROOT)
                .run(new Task("n1", "看看 X", AgentRoles.REVIEWER, List.of(), "看完"), List.of());

        assertEquals(explorerRun.lastToolNames(), reviewerRun.lastToolNames(),
                "工具白名单本来就一样");
        assertNotEquals(systemOf(explorerRun), systemOf(reviewerRun),
                "工具一样的两个角色，全部区别都在这段话里——它俩一样就等于没分角色");
        assertTrue(systemOf(reviewerRun).contains("边界和空值"), systemOf(reviewerRun));
    }

    @Test
    @DisplayName("角色能比天花板更省：分析角色三步就封顶")
    void roleCanLowerTheStepCeiling() {
        FakeLlm llm = new FakeLlm();
        // 一直要工具就一直不收口，撞上限为止；分析角色的上限是 3，不是默认的 12
        llm.reply = messages -> "再想想";

        new AgentTaskRunner(llm, fullSet(), ROOT).run(task("n1", TaskType.ANALYSIS), List.of());

        assertTrue(llm.receivedMessages.size() <= AgentRoles.ANALYST.maxSteps(),
                "分析角色跑了 " + llm.receivedMessages.size() + " 轮，比它自己声明的上限还多");
    }

    @Test
    @DisplayName("上游产出进了发给模型的那条用户消息")
    void upstreamResultsReachTheModel() {
        FakeLlm llm = new FakeLlm();
        Task upstream = task("n1", TaskType.FILE_READ);
        upstream.start();
        upstream.succeed("pom 里用的是 Java 17");
        Task task = new Task("n2", "照着改", TaskType.FILE_WRITE, List.of("n1"));

        new AgentTaskRunner(llm, fullSet(), ROOT).run(task, List.of(upstream));

        assertTrue(llm.lastUserMessage().contains("pom 里用的是 Java 17"));
        assertTrue(llm.lastUserMessage().contains("照着改"));
    }

    @Test
    @DisplayName("每个任务用一条干净的上下文，不会串味")
    void eachTaskGetsItsOwnContext() {
        FakeLlm llm = new FakeLlm();
        AgentTaskRunner runner = new AgentTaskRunner(llm, fullSet(), ROOT);

        runner.run(new Task("n1", "第一件事", TaskType.FILE_READ, List.of()), List.of());
        runner.run(new Task("n2", "第二件事", TaskType.FILE_READ, List.of()), List.of());

        assertFalse(llm.lastUserMessage().contains("第一件事"),
                "第二个任务不该看见第一个任务的对话");
        assertEquals(2, llm.receivedMessages.get(1).size(), "只该有 system + user 两条");
    }

    // ── 失败路径 ────────────────────────────────────────────────

    @Test
    @DisplayName("模型调用失败：返回失败，不往外抛")
    void llmFailureBecomesFailedOutcome() {
        FakeLlm llm = new FakeLlm();
        llm.blowUp = new LlmException("限流了");

        TaskOutcome outcome = new AgentTaskRunner(llm, fullSet(), ROOT)
                .run(task("n1", TaskType.FILE_READ), List.of());

        assertFalse(outcome.ok());
        assertTrue(outcome.output().contains("限流了"), outcome.output());
    }

    @Test
    @DisplayName("验证任务给出「不通过」就算这个任务失败，下游该跳就跳")
    void verificationVerdictDecidesOutcome() {
        FakeLlm llm = new FakeLlm();
        llm.reply = messages -> "跑了 mvn test，3 个用例挂了。\n" + TaskPrompt.VERDICT_FAIL;

        TaskOutcome outcome = new AgentTaskRunner(llm, fullSet(), ROOT)
                .run(task("n1", TaskType.VERIFICATION), List.of());

        assertFalse(outcome.ok());
        assertTrue(outcome.output().contains("3 个用例挂了"), "失败也要把过程交代带上");
    }

    @Test
    @DisplayName("验证通过就是正常成功")
    void verificationPassIsSuccess() {
        FakeLlm llm = new FakeLlm();
        llm.reply = messages -> "测试全过。\n" + TaskPrompt.VERDICT_PASS;

        TaskOutcome outcome = new AgentTaskRunner(llm, fullSet(), ROOT)
                .run(task("n1", TaskType.VERIFICATION), List.of());

        assertTrue(outcome.ok());
    }

    @Test
    @DisplayName("只有验证任务才看结论行，别的类型说什么都不影响判定")
    void verdictOnlyAppliesToVerificationTasks() {
        FakeLlm llm = new FakeLlm();
        llm.reply = messages -> "我顺手提一句：" + TaskPrompt.VERDICT_FAIL;

        TaskOutcome outcome = new AgentTaskRunner(llm, fullSet(), ROOT)
                .run(task("n1", TaskType.FILE_READ), List.of());

        assertTrue(outcome.ok());
    }

    @Test
    @DisplayName("跑到一半被叫停：算这个任务失败，而不是假装成功")
    void cancelMakesTaskFail() {
        FakeLlm llm = new FakeLlm();
        AgentTaskRunner runner = new AgentTaskRunner(llm, fullSet(), ROOT);
        // 照着真实客户端的行为演：cancel() 会掐断正在进行的 HTTP 请求，
        // 于是这次 chat 以异常收场，而不是把话说完
        llm.reply = messages -> {
            runner.cancel();
            throw new LlmException("请求已被取消");
        };

        TaskOutcome outcome = runner.run(task("n1", TaskType.FILE_READ), List.of());

        assertFalse(outcome.ok());
        assertTrue(outcome.output().contains("取消"), outcome.output());
    }

    @Test
    @DisplayName("cancel 要掐断所有并行的子循环——只掐得断一个的取消等于没有取消")
    void cancelKillsEveryRunningSubLoop() throws Exception {
        // 两个任务各拿一个会阻塞的假客户端，模拟两个子循环同时在等模型。
        // cancel() 该让两边都以「取消」收场——以前用单字段记「当前那个」，
        // 并行时先跑完的会把字段清掉，另一个就成了掐不断的孤儿
        class BlockingLlm implements LlmClient {
            final java.util.concurrent.CountDownLatch entered =
                    new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch released =
                    new java.util.concurrent.CountDownLatch(1);

            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                entered.countDown();
                try {
                    if (!released.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new LlmException("等太久了");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new LlmException("请求已被取消");
            }

            @Override
            public void cancel() {
                released.countDown();   // 真客户端掐断 HTTP，这里放行并让 chat 抛异常
            }

            @Override
            public String modelName() {
                return "blocking";
            }
        }
        List<BlockingLlm> clients = java.util.Collections.synchronizedList(new ArrayList<>());
        AgentTaskRunner runner = new AgentTaskRunner(() -> {
            BlockingLlm made = new BlockingLlm();
            clients.add(made);
            return made;
        }, fullSet(), ROOT);

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> runner.run(task("n1", TaskType.FILE_READ), List.of()));
            var f2 = pool.submit(() -> runner.run(task("n2", TaskType.FILE_READ), List.of()));
            waitForTwo(clients);
            for (BlockingLlm client : List.copyOf(clients)) {
                assertTrue(client.entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                        "两个子循环都该已经在等模型了");
            }

            runner.cancel();

            assertFalse(f1.get(5, java.util.concurrent.TimeUnit.SECONDS).ok(), "n1 没被掐断");
            assertFalse(f2.get(5, java.util.concurrent.TimeUnit.SECONDS).ok(), "n2 没被掐断");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 等到两个客户端都被造出来。工厂在子循环的线程上被调，主线程只能轮询。 */
    private static void waitForTwo(List<?> clients) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (clients.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(2, clients.size(), "两个任务该各造一个客户端");
    }

    // ── 事件 ────────────────────────────────────────────────────

    @Test
    @DisplayName("子循环的事件带着任务一起转发出去，界面才知道是谁在动")
    void forwardsEventsWithTheirTask() {
        FakeLlm llm = new FakeLlm();
        List<String> seen = new ArrayList<>();
        Task task = task("n1", TaskType.FILE_READ);

        new AgentTaskRunner(llm, fullSet(), ROOT)
                .onEvent((t, event) -> seen.add(t.id() + ":" + event.getClass().getSimpleName()))
                .run(task, List.of());

        assertTrue(seen.contains("n1:Thinking"), seen.toString());
        assertTrue(seen.contains("n1:Done"), seen.toString());
    }

    // ── PLANNING 任务拆子任务 ───────────────────────────────────

    @Test
    @DisplayName("配上规划器，PLANNING 任务先调研再拆出子任务")
    void planningTaskSpawnsSubTasks() {
        FakeLlm llm = new FakeLlm();
        llm.reply = messages -> isSubPlanRequest(messages)
                ? """
                {"tasks": [
                  {"id": "t1.1", "type": "FILE_WRITE", "description": "改 A", "acceptance": "改 A——做完了", "dependsOn": []},
                  {"id": "t1.2", "type": "VERIFICATION", "description": "跑测试", "acceptance": "跑测试——做完了", "dependsOn": ["t1.1"]}
                ]}
                """
                : "看下来要改两处";

        TaskOutcome outcome = new AgentTaskRunner(llm, fullSet(), ROOT)
                .withPlanner(new Planner(llm))
                .run(task("t1", TaskType.PLANNING), List.of());

        assertTrue(outcome.ok());
        assertEquals("看下来要改两处", outcome.output(), "产出仍是它调研出来的结论");
        assertEquals(2, outcome.subTasks().size());
        assertEquals(1, outcome.subTasks().get(0).depth());
        assertEquals("t1", outcome.subTasks().get(0).parentId());
    }

    @Test
    @DisplayName("拆不出子任务就是这个任务失败——它存在的意义就是产出子任务")
    void planningTaskFailsWhenItCannotSplit() {
        FakeLlm llm = new FakeLlm();
        llm.reply = messages -> isSubPlanRequest(messages) ? "我觉得不用拆" : "看完了";

        TaskOutcome outcome = new AgentTaskRunner(llm, fullSet(), ROOT)
                .withPlanner(new Planner(llm, 0))
                .run(task("t1", TaskType.PLANNING), List.of());

        assertFalse(outcome.ok());
        assertTrue(outcome.output().contains("拆子任务失败"), outcome.output());
        assertTrue(outcome.output().contains("看完了"), "调研到的东西不该丢");
    }

    /** 子规划那次调用的系统提示词认得出来，假模型靠它区分是哪一幕。 */
    private static boolean isSubPlanRequest(List<ChatMessage> messages) {
        return messages.get(0).content().contains("拆成几个具体可执行的步骤");
    }

    @Test
    @DisplayName("没配规划器时，PLANNING 任务退化成一次普通调研，不能报错")
    void planningTaskRunsWithoutSubTasksForNow() {
        FakeLlm llm = new FakeLlm();

        TaskOutcome outcome = new AgentTaskRunner(llm, fullSet(), ROOT)
                .run(task("n1", TaskType.PLANNING), List.of());

        assertTrue(outcome.ok());
        assertTrue(outcome.subTasks().isEmpty());
        assertEquals(List.of("read_file", "list_dir"), llm.lastToolNames());
    }
}
