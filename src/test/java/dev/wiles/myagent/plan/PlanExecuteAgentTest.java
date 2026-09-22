package dev.wiles.myagent.plan;

import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PlanExecuteAgentTest {

    @TempDir
    Path tmp;

    /**
     * 一个假模型：靠系统提示词分辨这次问的是「拆计划」还是「干活」。
     *
     * <p>不按调用次数发剧本，是因为一次计划执行里两种请求是交错的
     * （子任务跑着跑着又会发一次规划请求），按次数排的剧本一改计划就错位。
     */
    static final class FakeLlm implements LlmClient {
        final Deque<String> plans = new ArrayDeque<>();
        String taskReply = "这一步干完了";
        int planCalls;
        int taskCalls;
        boolean cancelled;

        // synchronized：任务并行之后，几个子循环会同时来点这个假模型
        @Override
        public synchronized ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            String system = messages.get(0).content();
            String reply;
            if (system.contains("任务规划器") || system.contains("补救")) {
                planCalls++;
                reply = plans.isEmpty() ? "没词儿了" : plans.poll();
            } else {
                taskCalls++;
                reply = taskReply;
            }
            return new ChatResponse("x", "fake", List.of(
                    new ChatResponse.Choice(0, ChatMessage.assistant(reply), "stop")), null);
        }

        @Override
        public String modelName() {
            return "fake";
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /** 一份合法的五任务计划，全是 ANALYSIS：不碰工具，跑起来最省事。 */
    private static String planJson() {
        StringBuilder sb = new StringBuilder("{\"goal\": \"把事情办了\", \"tasks\": [");
        for (int i = 1; i <= 5; i++) {
            sb.append(i > 1 ? "," : "")
                    .append("{\"id\": \"t").append(i)
                    .append("\", \"type\": \"ANALYSIS\", \"description\": \"第 ").append(i)
                    .append(" 步\", \"acceptance\": \"这步做完\", \"dependsOn\": []}");
        }
        return sb.append("]}").toString();
    }

    /** 记下 listener 收到的每一件事。事件表要线程安全：onAgentEvent 从并行的子循环里来。 */
    static final class Recording implements PlanListener {
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        Plan created;
        PlanResult finished;

        @Override
        public void onPlanCreated(Plan plan) {
            created = plan;
            events.add("created");
        }

        @Override
        public void onRejected(Plan plan) {
            events.add("rejected");
        }

        @Override
        public void onTaskStarted(Task task) {
            events.add("start:" + task.id());
        }

        @Override
        public void onTaskFinished(Task task) {
            events.add("finish:" + task.id());
        }

        @Override
        public void onAgentEvent(Task task, AgentEvent event) {
            events.add("agent:" + task.id());
        }

        @Override
        public void onFinished(Plan plan, PlanResult result) {
            finished = result;
            events.add("done");
        }
    }

    private FakeLlm llm;

    private PlanExecuteAgent agent(FakeLlm fake, PlanStore store) {
        this.llm = fake;
        return new PlanExecuteAgent(new Planner(fake), () -> fake,
                new ToolRegistry(), tmp, store);
    }

    private PlanStore store() {
        return new PlanStore(tmp.resolve("plans"));
    }

    // ── 主干 ────────────────────────────────────────────────────

    @Test
    @DisplayName("规划 → 确认 → 逐个任务跑，一条链走通")
    void runsTheWholeChain() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());
        Recording listener = new Recording();

        Optional<PlanResult> result = agent(fake, store())
                .approval(PlanApproval.auto())
                .listener(listener)
                .run("把事情办了");

        assertTrue(result.isPresent());
        assertEquals(PlanStatus.COMPLETED, result.get().status());
        assertEquals(5, result.get().succeeded());
        assertEquals("把事情办了", listener.created.goal());
        assertEquals("created", listener.events.get(0));
        assertEquals("done", listener.events.get(listener.events.size() - 1));
        assertTrue(listener.events.contains("start:t1"));
        assertTrue(listener.events.contains("finish:t5"));
        assertEquals(5, fake.taskCalls, "五个任务该各开一个子循环");
    }

    @Test
    @DisplayName("用户说不跑：计划标成取消，一个任务都不许动")
    void rejectedPlanRunsNothing() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());
        Recording listener = new Recording();

        Optional<PlanResult> result = agent(fake, store())
                .approval(PlanApproval.denyAll())
                .listener(listener)
                .run("把事情办了");

        assertTrue(result.isEmpty(), "没跑起来就不该有执行结果");
        assertEquals(PlanStatus.CANCELLED, listener.created.status());
        assertEquals(List.of("created", "rejected"), listener.events);
        assertEquals(0, fake.taskCalls);
    }

    @Test
    @DisplayName("不设审批就一律不跑——没人能接话时，安全的默认值是拒绝")
    void defaultsToDenyAll() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());

        assertTrue(agent(fake, store()).run("把事情办了").isEmpty());
        assertEquals(0, fake.taskCalls);
    }

    // ── 存档 ────────────────────────────────────────────────────

    @Test
    @DisplayName("跑完就删档：留着它，下次启动会提示续一份早已跑完的计划")
    void deletesCheckpointWhenDone() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());
        PlanStore store = store();

        PlanExecuteAgent agent = agent(fake, store).approval(PlanApproval.auto());
        agent.run("把事情办了");

        assertFalse(agent.hasSavedPlan());
        assertEquals(Optional.empty(), store.peek(tmp));
    }

    @Test
    @DisplayName("否决之后也删档——用户都说不要了")
    void deletesCheckpointWhenRejected() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());
        PlanStore store = store();
        store.save(halfRun(), tmp);

        agent(fake, store).approval(PlanApproval.denyAll()).run("把事情办了");

        assertEquals(Optional.empty(), store.peek(tmp));
    }

    /** 一份跑到一半的计划：t1 已经成了，t2 还没轮到。 */
    private static Plan halfRun() {
        Task t1 = new Task("t1", "第一步", TaskType.ANALYSIS, List.of(), "做完");
        Task t2 = new Task("t2", "第二步", TaskType.ANALYSIS, List.of("t1"), "做完");
        Plan plan = new Plan("接着办", TaskGraph.of(List.of(t1, t2)));
        plan.start();
        t1.start();
        t1.succeed("上一程的产出");
        return plan;
    }

    @Test
    @DisplayName("接着跑：做成的那步不重跑，产出还在")
    void resumesWithoutRedoingFinishedWork() {
        FakeLlm fake = new FakeLlm();
        PlanStore store = store();
        store.save(halfRun(), tmp);
        Recording listener = new Recording();

        Optional<PlanResult> result = agent(fake, store)
                .approval(PlanApproval.auto())
                .listener(listener)
                .resume();

        assertTrue(result.isPresent());
        assertEquals(PlanStatus.COMPLETED, result.get().status());
        assertEquals(1, fake.taskCalls, "只该跑剩下的那一步");
        assertFalse(listener.events.contains("start:t1"), listener.events.toString());
        assertTrue(listener.events.contains("start:t2"));
        assertEquals("上一程的产出", listener.created.graph().get("t1").result());
    }

    @Test
    @DisplayName("续跑也要过目——隔了一阵，用户未必还记得当初让它干什么")
    void resumeAlsoNeedsApproval() {
        FakeLlm fake = new FakeLlm();
        PlanStore store = store();
        store.save(halfRun(), tmp);
        Recording listener = new Recording();

        assertTrue(agent(fake, store).approval(PlanApproval.denyAll())
                .listener(listener).resume().isEmpty());
        assertEquals(List.of("created", "rejected"), listener.events);
    }

    @Test
    @DisplayName("没有存档：安安静静返回空，不抛")
    void resumeWithoutCheckpoint() {
        FakeLlm fake = new FakeLlm();

        PlanExecuteAgent agent = agent(fake, store()).approval(PlanApproval.auto());

        assertEquals(Optional.empty(), agent.resume());
        assertFalse(agent.hasSavedPlan());
        assertEquals(0, fake.planCalls, "没存档就不该去问模型");
    }

    @Test
    @DisplayName("没配存档器一样能跑，只是断了续不上")
    void worksWithoutAStore() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());

        PlanExecuteAgent agent = agent(fake, null).approval(PlanApproval.auto());

        assertEquals(PlanStatus.COMPLETED, agent.run("把事情办了").orElseThrow().status());
        assertFalse(agent.hasSavedPlan());
        assertEquals(Optional.empty(), agent.resume());
    }

    @Test
    @DisplayName("存档摘要看得到目标和还剩几步")
    void exposesSavedPlanSummary() {
        PlanStore store = store();
        store.save(halfRun(), tmp);

        PlanCheckpoint saved = agent(new FakeLlm(), store).savedPlan().orElseThrow();

        assertEquals("接着办", saved.goal());
        assertEquals(1, saved.remaining());
    }

    // ── 取消 ────────────────────────────────────────────────────

    @Test
    @DisplayName("叫停之后存档留着，剩下的步骤还在，能接着跑")
    void cancelLeavesAResumableCheckpoint() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());
        PlanStore store = store();
        PlanExecuteAgent agent = agent(fake, store).approval(PlanApproval.auto());

        // 第一个任务一跑起来就叫停
        agent.listener(new PlanListener() {
            @Override
            public void onTaskStarted(Task task) {
                agent.cancel();
            }
        });
        PlanResult result = agent.run("把事情办了").orElseThrow();

        assertEquals(PlanStatus.CANCELLED, result.status());
        assertTrue(agent.hasSavedPlan(), "叫停之后存档就没了的话，断点续跑等于白做");
        assertTrue(store.peek(tmp).orElseThrow().remaining() > 0);
    }

    @Test
    @DisplayName("叫停要掐到模型请求上，光设个标志位用户会觉得按了没反应")
    void cancelReachesTheModelCall() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());
        PlanExecuteAgent agent = agent(fake, store()).approval(PlanApproval.auto());
        agent.listener(new PlanListener() {
            @Override
            public void onTaskStarted(Task task) {
                agent.cancel();
            }
        });

        agent.run("把事情办了");

        assertTrue(fake.cancelled, "没掐到客户端，正阻塞在读响应的线程要等模型说完才知道");
    }

    @Test
    @DisplayName("跑完之后 isRunning 归位，不会挡住下一份计划")
    void releasesTheSlotAfterwards() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());
        fake.plans.add(planJson());
        PlanExecuteAgent agent = agent(fake, store()).approval(PlanApproval.auto());

        agent.run("第一份");
        assertFalse(agent.isRunning());

        assertTrue(agent.run("第二份").isPresent(), "上一份的占位没释放，第二份就跑不起来");
    }

    @Test
    @DisplayName("上一份还在跑时又开一份：当场说清楚，别悄悄跑出一团乱麻")
    void refusesConcurrentPlans() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add(planJson());
        PlanExecuteAgent agent = agent(fake, store()).approval(PlanApproval.auto());
        List<RuntimeException> caught = new ArrayList<>();

        agent.listener(new PlanListener() {
            @Override
            public void onTaskStarted(Task task) {
                if (caught.isEmpty()) {
                    caught.add(assertThrows(IllegalStateException.class,
                            () -> agent.run("插队的那份")));
                }
            }
        });
        agent.run("正经那份");

        assertEquals(1, caught.size());
        assertTrue(caught.get(0).getMessage().contains("还在跑"), caught.get(0).getMessage());
    }

    @Test
    @DisplayName("规划就失败时，异常照原样抛给调用方去说人话")
    void planningFailurePropagates() {
        FakeLlm fake = new FakeLlm();
        fake.plans.add("我觉得这事儿得再商量商量");

        PlanExecuteAgent agent = agent(fake, store()).approval(PlanApproval.auto());

        assertThrows(PlanningException.class, () -> agent.run("把事情办了"));
        assertFalse(agent.isRunning(), "抛异常也要把占位放回去");
    }
}
