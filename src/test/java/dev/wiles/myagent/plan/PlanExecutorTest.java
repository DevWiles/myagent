package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanExecutorTest {

    private static Task t(String id, String... deps) {
        return new Task(id, "描述 " + id, TaskType.FILE_READ, List.of(deps));
    }

    /** 记下执行顺序、以及每个任务拿到了哪些上游产出的假执行体。 */
    static class RecordingRunner implements TaskRunner {
        final List<String> order = new ArrayList<>();
        final Map<String, List<String>> upstreamSeen = new HashMap<>();
        /** 这些 id 的任务会失败 */
        final List<String> failing = new ArrayList<>();

        @Override
        public TaskOutcome run(Task task, List<Task> upstream) {
            order.add(task.id());
            upstreamSeen.put(task.id(),
                    upstream.stream().map(u -> u.id() + "=" + u.result()).toList());
            return failing.contains(task.id())
                    ? TaskOutcome.failed("假装挂了")
                    : TaskOutcome.ok(task.id() + " 干完了");
        }
    }

    private static Plan plan(TaskGraph graph) {
        return new Plan("测试目标", graph);
    }

    private static Plan diamondPlan() {
        return plan(diamond());
    }

    private static TaskGraph diamond() {
        return TaskGraph.of(List.of(
                t("n1"), t("n2", "n1"), t("n3", "n1"), t("n4", "n2", "n3")));
    }

    // ── 顺序 ────────────────────────────────────────────────────

    @Test
    @DisplayName("按拓扑顺序执行：下游一定在所有上游之后")
    void runsInTopologicalOrder() {
        RecordingRunner runner = new RecordingRunner();
        Plan plan = diamondPlan();

        PlanResult result = new PlanExecutor(runner).execute(plan);

        assertEquals(List.of("n1", "n2", "n3", "n4"), runner.order);
        assertEquals(PlanStatus.COMPLETED, plan.status());
        assertEquals(PlanStatus.COMPLETED, result.status());
        assertTrue(plan.durationMillis() >= 0);
        assertEquals(4, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(0, result.skipped());
        assertTrue(result.allSucceeded());
        assertFalse(result.cancelled());
    }

    @Test
    @DisplayName("每个任务都跑且只跑一次")
    void runsEachTaskExactlyOnce() {
        RecordingRunner runner = new RecordingRunner();

        new PlanExecutor(runner).execute(diamondPlan());

        assertEquals(4, runner.order.size());
        assertEquals(4, runner.order.stream().distinct().count());
    }

    @Test
    @DisplayName("执行完每个任务都落在终态，产出记在 result 里")
    void recordsResults() {
        TaskGraph graph = diamond();

        new PlanExecutor(new RecordingRunner()).execute(plan(graph));

        assertTrue(graph.isDone());
        assertEquals(TaskStatus.SUCCEEDED, graph.get("n4").status());
        assertEquals("n4 干完了", graph.get("n4").result());
        assertTrue(graph.get("n4").durationMillis() >= 0);
    }

    // ── 上游产出传递 ────────────────────────────────────────────

    @Test
    @DisplayName("上游的产出要交到下游手里，否则这条边只剩个先后顺序")
    void handsUpstreamResultsDownstream() {
        RecordingRunner runner = new RecordingRunner();

        new PlanExecutor(runner).execute(diamondPlan());

        assertEquals(List.of(), runner.upstreamSeen.get("n1"));
        assertEquals(List.of("n1=n1 干完了"), runner.upstreamSeen.get("n2"));
        assertEquals(List.of("n2=n2 干完了", "n3=n3 干完了"), runner.upstreamSeen.get("n4"));
    }

    // ── 失败 ────────────────────────────────────────────────────

    @Test
    @DisplayName("一个任务失败：下游跳过，兄弟分支照跑")
    void failureSkipsDownstreamOnly() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n2");
        TaskGraph graph = diamond();
        Plan plan = plan(graph);

        PlanResult result = new PlanExecutor(runner).execute(plan);

        assertEquals(PlanStatus.FAILED, plan.status());

        assertEquals(List.of("n1", "n2", "n3"), runner.order, "n4 不该被执行");
        assertEquals(TaskStatus.FAILED, graph.get("n2").status());
        assertEquals(TaskStatus.SUCCEEDED, graph.get("n3").status(), "兄弟分支不受连累");
        assertEquals(TaskStatus.SKIPPED, graph.get("n4").status());
        assertTrue(graph.get("n4").error().contains("n2"));

        assertEquals(2, result.succeeded());
        assertEquals(1, result.failed());
        assertEquals(1, result.skipped());
        assertFalse(result.allSucceeded());
        assertTrue(graph.isDone());
    }

    @Test
    @DisplayName("执行体自己抛异常也不能把调度打穿——当作这个任务失败就是了")
    void runnerExceptionBecomesTaskFailure() {
        TaskGraph graph = TaskGraph.of(List.of(t("n1"), t("n2", "n1")));
        TaskRunner exploding = (task, upstream) -> {
            throw new RuntimeException("执行体自己炸了");
        };

        PlanResult result = new PlanExecutor(exploding).execute(plan(graph));

        assertEquals(TaskStatus.FAILED, graph.get("n1").status());
        assertTrue(graph.get("n1").error().contains("执行体自己炸了"));
        assertEquals(TaskStatus.SKIPPED, graph.get("n2").status());
        assertEquals(1, result.failed());
        assertTrue(graph.isDone());
    }

    // ── PLANNING 任务让图长大 ────────────────────────────────────

    @Test
    @DisplayName("PLANNING 任务拆出的子任务会被接着执行")
    void executesSubTasksSpawnedAtRuntime() {
        Task planner = new Task("n1", "先摸清要改哪儿", TaskType.PLANNING, List.of());
        TaskGraph graph = TaskGraph.of(List.of(planner, t("n2", "n1")));
        RecordingRunner runner = new RecordingRunner() {
            @Override
            public TaskOutcome run(Task task, List<Task> upstream) {
                super.run(task, upstream);
                if (task.type() == TaskType.PLANNING) {
                    return TaskOutcome.planned("拆成两步", List.of(
                            task.subTask("n1.1", "改 A", TaskType.FILE_WRITE, List.of()),
                            task.subTask("n1.2", "改 B", TaskType.FILE_WRITE, List.of("n1.1"))));
                }
                return TaskOutcome.ok(task.id() + " 干完了");
            }
        };

        PlanResult result = new PlanExecutor(runner).execute(plan(graph));

        assertEquals(4, graph.size());
        assertTrue(runner.order.containsAll(List.of("n1", "n1.1", "n1.2", "n2")));
        assertTrue(runner.order.indexOf("n1.1") < runner.order.indexOf("n1.2"));
        assertEquals(4, result.succeeded());
        assertEquals("拆成两步", graph.get("n1").result());
    }

    @Test
    @DisplayName("子任务组成了环：拆它的那个任务算失败，图不留半截")
    void badSubTasksFailTheirParent() {
        Task planner = new Task("n1", "探路", TaskType.PLANNING, List.of());
        TaskGraph graph = TaskGraph.of(List.of(planner, t("n2", "n1")));
        TaskRunner cyclicPlanner = (task, upstream) -> task.type() == TaskType.PLANNING
                ? TaskOutcome.planned("拆坏了", List.of(
                        task.subTask("n1.1", "A", TaskType.FILE_WRITE, List.of("n1.2")),
                        task.subTask("n1.2", "B", TaskType.FILE_WRITE, List.of("n1.1"))))
                : TaskOutcome.ok("干完了");

        PlanResult result = new PlanExecutor(cyclicPlanner).execute(plan(graph));

        assertEquals(2, graph.size(), "环没建成，图不该变大");
        assertEquals(TaskStatus.FAILED, graph.get("n1").status());
        assertTrue(graph.get("n1").error().contains("循环依赖"),
                "错误信息要说清是子任务的问题：" + graph.get("n1").error());
        assertEquals(TaskStatus.SKIPPED, graph.get("n2").status());
        assertEquals(1, result.failed());
    }

    // ── 取消 ────────────────────────────────────────────────────

    @Test
    @DisplayName("中途取消：没跑的全部跳过，图照样收口")
    void cancelSkipsRemaining() {
        TaskGraph graph = diamond();
        // 假执行体要能反手叫停执行器，用个一格的盒子把两者串起来
        PlanExecutor[] box = new PlanExecutor[1];
        box[0] = new PlanExecutor((task, upstream) -> {
            if (task.id().equals("n1")) {
                box[0].cancel();
            }
            return TaskOutcome.ok("干完了");
        });

        Plan plan = plan(graph);
        PlanResult result = box[0].execute(plan);

        assertEquals(PlanStatus.CANCELLED, plan.status(),
                "取消要和失败分开：跳过数大于 0 不代表跑砸了");

        assertTrue(result.cancelled());
        assertEquals(TaskStatus.SUCCEEDED, graph.get("n1").status(), "已经跑完的照算");
        assertEquals(TaskStatus.SKIPPED, graph.get("n2").status());
        assertEquals(TaskStatus.SKIPPED, graph.get("n4").status());
        assertTrue(graph.get("n2").error().contains("取消"));
        assertTrue(graph.isDone(), "取消之后也不能留下没了结的任务");
        assertEquals(1, result.succeeded());
        assertEquals(3, result.skipped());
    }

    @Test
    @DisplayName("上一轮的取消标志不能带到下一轮")
    void cancelFlagResetsBetweenRuns() {
        PlanExecutor executor = new PlanExecutor(new RecordingRunner());
        executor.cancel();

        PlanResult result = executor.execute(diamondPlan());

        assertFalse(result.cancelled());
        assertEquals(4, result.succeeded());
    }

    @Test
    @DisplayName("跑完的计划不能再跑一遍——重跑要重新规划")
    void refusesToRerunAFinishedPlan() {
        Plan plan = diamondPlan();
        PlanExecutor executor = new PlanExecutor(new RecordingRunner());
        executor.execute(plan);

        assertThrows(IllegalStateException.class, () -> executor.execute(plan));
    }

    @Test
    @DisplayName("图长大了要通知出去，否则界面画的还是最初那张图")
    void notifiesWhenGraphGrows() {
        Task planner = new Task("n1", "探路", TaskType.PLANNING, List.of());
        TaskGraph graph = TaskGraph.of(List.of(planner, t("n2", "n1")));
        TaskRunner splitting = (task, upstream) -> task.type() == TaskType.PLANNING
                ? TaskOutcome.planned("拆了", List.of(
                        task.subTask("n1.1", "改 A", TaskType.FILE_WRITE, List.of())))
                : TaskOutcome.ok("干完了");
        List<String> seen = new ArrayList<>();

        new PlanExecutor(splitting)
                .onTasksAdded((parent, added) -> seen.add(parent.id() + "→"
                        + added.stream().map(Task::id).toList()))
                .execute(plan(graph));

        assertEquals(List.of("n1→[n1.1]"), seen);
    }

    @Test
    @DisplayName("子任务不合法时不能通知——图根本没长大")
    void noNotificationWhenExpansionFails() {
        Task planner = new Task("n1", "探路", TaskType.PLANNING, List.of());
        TaskGraph graph = TaskGraph.of(List.of(planner, t("n2", "n1")));
        TaskRunner bad = (task, upstream) -> task.type() == TaskType.PLANNING
                ? TaskOutcome.planned("拆坏了", List.of(
                        task.subTask("n1.1", "A", TaskType.FILE_WRITE, List.of("n1.2")),
                        task.subTask("n1.2", "B", TaskType.FILE_WRITE, List.of("n1.1"))))
                : TaskOutcome.ok("干完了");
        List<String> seen = new ArrayList<>();

        new PlanExecutor(bad).onTasksAdded((p, a) -> seen.add("x")).execute(plan(graph));

        assertTrue(seen.isEmpty());
    }
    // ── 失败后重规划 ────────────────────────────────────────────

    /** 造一个补救任务：id 用 r 开头，不依赖原计划里的任何东西。 */
    private static Task r(String id, String... deps) {
        return new Task(id, "补救 " + id, TaskType.FILE_WRITE, List.of(deps), "补上了");
    }

    /** 记下被调了几次、每次看到的是哪一步失败。 */
    static class RecordingReplanner implements Replanner {
        final List<String> failedSeen = new ArrayList<>();
        List<Task> recovery = List.of();
        RuntimeException blowUp;

        @Override
        public List<Task> replan(Plan plan, Task failed, String error) {
            failedSeen.add(failed.id());
            if (blowUp != null) {
                throw blowUp;
            }
            return recovery;
        }
    }

    @Test
    @DisplayName("一步倒了，补救任务顶上并跑完，整份计划算达成")
    void recoveredPlanCountsAsCompleted() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n2");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.recovery = List.of(r("r1"), r("r2", "r1"));
        Plan plan = diamondPlan();

        PlanResult result = new PlanExecutor(runner).withReplanner(replanner).execute(plan);

        assertEquals(List.of("n2"), replanner.failedSeen, "补救器该看到倒下的是哪一步");
        assertTrue(runner.order.containsAll(List.of("r1", "r2")), runner.order.toString());
        assertEquals(PlanStatus.COMPLETED, result.status(),
                "活都干完了还报失败，用户会以为目标没达成");
        assertEquals(1, result.replans());
        assertTrue(result.wasReplanned());
        assertEquals(1, result.failed(), "失败过就是失败过，这个数字不该被抹掉");
    }

    @Test
    @DisplayName("补救任务自己也倒了：老老实实报失败")
    void failedRecoveryStillFails() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n2");
        runner.failing.add("r1");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.recovery = List.of(r("r1"));

        PlanResult result = new PlanExecutor(runner).withReplanner(replanner)
                .execute(diamondPlan());

        assertEquals(PlanStatus.FAILED, result.status());
        assertEquals(1, result.replans());
    }

    @Test
    @DisplayName("补救只给一次机会——同一个误解反复换皮，只是烧钱")
    void replansAtMostOnce() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n2");
        runner.failing.add("r1");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.recovery = List.of(r("r1"));

        new PlanExecutor(runner).withReplanner(replanner).execute(diamondPlan());

        assertEquals(PlanLimits.MAX_REPLANS, replanner.failedSeen.size(),
                "补救次数超了上限: " + replanner.failedSeen);
    }

    @Test
    @DisplayName("模型说补不了（空列表）：照原样收场，不硬凑")
    void emptyRecoveryIsAnAnswerNotAnError() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n1");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.recovery = List.of();

        PlanResult result = new PlanExecutor(runner).withReplanner(replanner)
                .execute(diamondPlan());

        assertEquals(PlanStatus.FAILED, result.status());
        assertEquals(0, result.replans());
        assertEquals(1, replanner.failedSeen.size(), "该问过一次");
    }

    @Test
    @DisplayName("补救器自己炸了，不能把整个计划带走")
    void replannerBlowingUpDoesNotBreakThePlan() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n1");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.blowUp = new IllegalStateException("模型挂了");

        PlanResult result = assertDoesNotThrow(() ->
                new PlanExecutor(runner).withReplanner(replanner).execute(diamondPlan()));

        assertEquals(PlanStatus.FAILED, result.status());
        assertEquals(4, result.total(), "每个任务都得有个交代");
    }

    @Test
    @DisplayName("补救任务撞了已有 id：照原样收场，不让脏图跑起来")
    void illegalRecoveryIsDropped() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n2");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.recovery = List.of(r("n1"));       // 和原计划撞车

        PlanResult result = new PlanExecutor(runner).withReplanner(replanner)
                .execute(diamondPlan());

        assertEquals(PlanStatus.FAILED, result.status());
        assertEquals(0, result.replans());
    }

    @Test
    @DisplayName("补出来的任务要通知界面，不然屏幕上会冒出计划里没有的步骤")
    void notifiesTheUiAboutRecoveryTasks() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n2");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.recovery = List.of(r("r1"));

        List<String> announced = new ArrayList<>();
        new PlanExecutor(runner).withReplanner(replanner)
                .onReplanned((failed, added) -> {
                    announced.add(failed.id());
                    added.forEach(t -> announced.add(t.id()));
                })
                .execute(diamondPlan());

        assertEquals(List.of("n2", "r1"), announced);
    }

    @Test
    @DisplayName("没配补救器时，行为和以前一模一样")
    void withoutReplannerNothingChanges() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n1");

        PlanResult result = new PlanExecutor(runner).execute(diamondPlan());

        assertEquals(PlanStatus.FAILED, result.status());
        assertEquals(0, result.replans());
        assertFalse(result.wasReplanned());
    }

    @Test
    @DisplayName("两次 execute 之间，重规划的计数不许残留")
    void replanStateDoesNotLeakBetweenRuns() {
        RecordingRunner first = new RecordingRunner();
        first.failing.add("n2");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.recovery = List.of(r("r1"));
        PlanExecutor executor = new PlanExecutor(first).withReplanner(replanner);

        executor.execute(diamondPlan());
        first.failing.clear();                      // 这一轮不该再有人倒下
        PlanResult second = executor.execute(diamondPlan());

        assertEquals(0, second.replans(), "上一轮的重规划计数漏到这一轮来了");
        assertEquals(PlanStatus.COMPLETED, second.status());
    }
    // ── 存档与断点续跑 ──────────────────────────────────────────

    @Test
    @DisplayName("每了结一个任务存一次盘——最坏只丢正在跑的那一个")
    void checkpointsAfterEachTask() {
        RecordingRunner runner = new RecordingRunner();
        List<Integer> doneCounts = new ArrayList<>();

        new PlanExecutor(runner)
                .onCheckpoint(plan -> doneCounts.add((int) plan.graph().tasks().stream()
                        .filter(Task::isTerminal).count()))
                .execute(diamondPlan());

        assertEquals(List.of(1, 2, 3, 4), doneCounts, "该是一个任务一次，且进度单调往前");
    }

    @Test
    @DisplayName("存档看到的是自洽状态：补救任务已经进图了才喊存")
    void checkpointSeesRecoveryTasksAlreadyInGraph() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n2");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.recovery = List.of(r("r1"));
        List<Boolean> sawRecovery = new ArrayList<>();

        new PlanExecutor(runner).withReplanner(replanner)
                .onCheckpoint(plan -> sawRecovery.add(
                        plan.graph().tasks().stream().anyMatch(t -> t.id().equals("r1"))))
                .execute(diamondPlan());

        assertTrue(sawRecovery.get(1), "n2 失败那一轮存档时，补救任务该已经在图里了");
    }

    @Test
    @DisplayName("重规划的计数跟着计划走，存得下来")
    void replanCountLivesOnThePlan() {
        RecordingRunner runner = new RecordingRunner();
        runner.failing.add("n2");
        RecordingReplanner replanner = new RecordingReplanner();
        replanner.recovery = List.of(r("r1"));
        Plan plan = diamondPlan();

        new PlanExecutor(runner).withReplanner(replanner).execute(plan);

        assertEquals(1, plan.replans());
        assertEquals(List.of("r1"), plan.recoveryIds());
    }

    @Test
    @DisplayName("存档回调自己炸了，不能把计划带走")
    void checkpointFailureDoesNotBreakThePlan() {
        RecordingRunner runner = new RecordingRunner();

        PlanResult result = assertDoesNotThrow(() -> new PlanExecutor(runner)
                .onCheckpoint(plan -> {
                    throw new IllegalStateException("磁盘满了");
                })
                .execute(diamondPlan()));

        assertEquals(PlanStatus.COMPLETED, result.status());
    }

    @Test
    @DisplayName("端到端：跑一半存档，载回来接着跑，做完的不重跑")
    void resumesFromCheckpoint() {
        // 第一程：n2 让执行体假装「进程在这儿没了」
        RecordingRunner first = new RecordingRunner();
        Plan plan = diamondPlan();
        plan.start();
        Task n1 = plan.graph().get("n1");
        n1.start();
        n1.succeed("n1 干完了");

        PlanCheckpoint saved = PlanCheckpoint.of(plan, java.nio.file.Path.of("/tmp/ws"), 1L);

        // 第二程：从存档立起来接着跑
        RecordingRunner second = new RecordingRunner();
        Plan resumed = saved.toPlan();
        PlanResult result = new PlanExecutor(second).execute(resumed);

        assertFalse(second.order.contains("n1"), "已经做成的事不该重跑: " + second.order);
        assertEquals(List.of("n2", "n3", "n4"), second.order);
        assertEquals(PlanStatus.COMPLETED, result.status());
        assertEquals("n1 干完了", resumed.graph().get("n1").result(), "上一程的产出要还在");
    }

    @Test
    @DisplayName("续跑时下游拿得到上一程的产出——不然那条依赖边就白连了")
    void resumedDownstreamStillSeesUpstreamResults() {
        Plan plan = diamondPlan();
        plan.start();
        Task n1 = plan.graph().get("n1");
        n1.start();
        n1.succeed("上一程留下的结论");

        RecordingRunner runner = new RecordingRunner();
        new PlanExecutor(runner).execute(
                PlanCheckpoint.of(plan, java.nio.file.Path.of("/tmp/ws"), 1L).toPlan());

        assertEquals(List.of("n1=上一程留下的结论"), runner.upstreamSeen.get("n2"));
    }

    @Test
    @DisplayName("续跑的计划不会把开始时间重新计时——总耗时得是真的")
    void resumeDoesNotResetTheClock() {
        Plan plan = diamondPlan();
        plan.start();
        long originalStart = plan.startTime();

        Plan resumed = PlanCheckpoint.of(plan, java.nio.file.Path.of("/tmp/ws"), 1L).toPlan();
        new PlanExecutor(new RecordingRunner()).execute(resumed);

        assertEquals(originalStart, resumed.startTime());
    }
    @Test
    @DisplayName("Ctrl-C 之后存档还能续——这是断点续跑最常用的入口")
    void cancelledPlanIsStillResumable() {
        java.nio.file.Path ws = java.nio.file.Path.of("/tmp/ws");
        List<PlanCheckpoint> saved = new ArrayList<>();
        PlanExecutor[] holder = new PlanExecutor[1];

        PlanExecutor executor = new PlanExecutor((task, upstream) -> {
            if (task.id().equals("n1")) {
                holder[0].cancel();                 // 模拟用户在第一步跑完时按下 Ctrl-C
            }
            return TaskOutcome.ok(task.id() + " 干完了");
        }).onCheckpoint(plan -> saved.add(PlanCheckpoint.of(plan, ws, 1L)));
        holder[0] = executor;

        PlanResult result = executor.execute(diamondPlan());

        assertEquals(PlanStatus.CANCELLED, result.status());
        PlanCheckpoint last = saved.get(saved.size() - 1);
        assertTrue(last.isResumable(),
                "取消时存的是一份全都了结了的死图，那断点续跑就只在进程被 kill 时才有用");
        assertEquals(3, last.remaining(), "剩下三步该还留着，不该被标成跳过");

        // 真的能接着跑，而且做成的那步不重来
        RecordingRunner second = new RecordingRunner();
        PlanResult resumed = new PlanExecutor(second).execute(last.toPlan());

        assertEquals(List.of("n2", "n3", "n4"), second.order);
        assertEquals(PlanStatus.COMPLETED, resumed.status());
    }
}
