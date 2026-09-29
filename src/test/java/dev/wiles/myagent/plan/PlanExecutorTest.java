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

    /**
     * 记下执行顺序、以及每个任务拿到了哪些上游产出的假执行体。
     *
     * <p>{@code run} 加锁：执行器默认并行，几个任务会同时来记账。
     */
    static class RecordingRunner implements TaskRunner {
        final List<String> order = new ArrayList<>();
        final Map<String, List<String>> upstreamSeen = new HashMap<>();
        /** 这些 id 的任务会失败 */
        final List<String> failing = new ArrayList<>();

        @Override
        public synchronized TaskOutcome run(Task task, List<Task> upstream) {
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

        // 并行执行后 n2/n3 谁先记账不定，能断言的是拓扑性质本身：下游在所有上游之后
        assertEquals(4, runner.order.size(), runner.order.toString());
        for (Task task : plan.graph().tasks()) {
            for (String dep : task.dependencies()) {
                assertTrue(runner.order.indexOf(dep) < runner.order.indexOf(task.id()),
                        task.id() + " 跑在了上游 " + dep + " 之前: " + runner.order);
            }
        }
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

    // ── 并发 ────────────────────────────────────────────────────

    @Test
    @DisplayName("互不依赖的只读任务真的同时跑——两个任务互相等对方开跑，串行永远等不到")
    void independentReadOnlyTasksRunConcurrently() {
        java.util.concurrent.CountDownLatch aStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch bStarted = new java.util.concurrent.CountDownLatch(1);
        TaskRunner crossWaiting = (task, upstream) -> {
            java.util.concurrent.CountDownLatch mine =
                    task.id().equals("a") ? aStarted : bStarted;
            java.util.concurrent.CountDownLatch other =
                    task.id().equals("a") ? bStarted : aStarted;
            mine.countDown();
            try {
                return other.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        ? TaskOutcome.ok("等到了")
                        : TaskOutcome.failed("等不到对方开跑——两个任务没有同时执行");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TaskOutcome.failed("被中断");
            }
        };
        TaskGraph graph = TaskGraph.of(List.of(t("a"), t("b")));

        PlanResult result = new PlanExecutor(crossWaiting).execute(plan(graph));

        assertEquals(2, result.succeeded(), () -> "a: " + graph.get("a").result()
                + " / " + graph.get("a").error() + "; b: " + graph.get("b").error());
    }

    @Test
    @DisplayName("会动世界的任务独占运行：它在场时，场上不许有第二个人")
    void mutatingTasksRunExclusively() {
        java.util.concurrent.atomic.AtomicInteger inFlight =
                new java.util.concurrent.atomic.AtomicInteger();
        List<String> violations = java.util.Collections.synchronizedList(new ArrayList<>());
        TaskRunner counting = (task, upstream) -> {
            int now = inFlight.incrementAndGet();
            if (task.role().mutates() && now > 1) {
                violations.add(task.id() + " 在跑时场上还有别人");
            }
            try {
                Thread.sleep(10);   // 撑开一点时间窗，让「同时」有机会被看见
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return TaskOutcome.ok("干完了");
        };
        // 两个读、一个写、再两个读，全都互不依赖——按图论它们能五个一起跑，
        // 按「读共享写独占」的规矩，写那一下必须独占
        TaskGraph graph = TaskGraph.of(List.of(
                t("r1"), t("r2"),
                new Task("w", "改文件", TaskType.FILE_WRITE, List.of()),
                t("r3"), t("r4")));

        PlanResult result = new PlanExecutor(counting).execute(plan(graph));

        assertTrue(violations.isEmpty(), violations.toString());
        assertEquals(5, result.succeeded());
    }

    private static Task coder(String id, String... files) {
        return new Task(id, "改 " + id, AgentRoles.CODER, List.of(), null, List.of(files));
    }

    /** 让每个任务等对方开跑再结束；等不到就报失败——这样"同时"是被证明的，不是碰巧的。 */
    private static TaskRunner meetingRunner(Map<String, java.util.concurrent.CountDownLatch> started,
                                            Map<String, String> partner) {
        return (task, upstream) -> {
            started.get(task.id()).countDown();
            String other = partner.get(task.id());
            if (other == null) {
                return TaskOutcome.ok("干完了");
            }
            try {
                return started.get(other).await(3, java.util.concurrent.TimeUnit.SECONDS)
                        ? TaskOutcome.ok("和 " + other + " 同时跑了")
                        : TaskOutcome.failed("等不到 " + other + "——没有并行");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TaskOutcome.failed("被打断");
            }
        };
    }

    @Test
    @DisplayName("声明的文件互不相交的两个 coder 真的同时跑——这是并行修复的全部意义")
    void disjointCodersRunConcurrently() {
        Map<String, java.util.concurrent.CountDownLatch> started = Map.of(
                "a", new java.util.concurrent.CountDownLatch(1),
                "b", new java.util.concurrent.CountDownLatch(1));
        TaskGraph graph = TaskGraph.of(List.of(coder("a", "src/a.py"), coder("b", "src/b.py")));

        PlanResult result = new PlanExecutor(meetingRunner(started, Map.of("a", "b", "b", "a")))
                .execute(plan(graph));

        assertEquals(2, result.succeeded(), graph.get("a").error() + " / " + graph.get("b").error());
    }

    @Test
    @DisplayName("文件相撞、没声明文件、或者带 bash 的角色：不许和任何人同场；各管各的才能同场")
    void conflictingOrUnscopedWritersStayExclusive() {
        Map<String, Task> inFlight = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> violations = java.util.Collections.synchronizedList(new ArrayList<>());
        TaskRunner counting = (task, upstream) -> {
            for (Task other : inFlight.values()) {
                // 同场的两个人必须都划了范围且文件不撞；否则就是调度放错了人
                boolean bothScoped = PlanExecutor.scoped(task) && PlanExecutor.scoped(other);
                if (!bothScoped || task.filesConflictWith(other)) {
                    violations.add(task.id() + " 和 " + other.id() + " 同场了");
                }
            }
            inFlight.put(task.id(), task);
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.remove(task.id());
            return TaskOutcome.ok("干完了");
        };
        TaskGraph graph = TaskGraph.of(List.of(
                coder("same1", "src/x.py"), coder("same2", "src/x.py"),       // 同一个文件
                coder("dir", "src"), coder("inside", "src/y.py"),             // 目录包含
                coder("blank"),                                              // 没声明
                new Task("v", "跑测试", AgentRoles.VERIFIER, List.of(), null, List.of("src/z.py"))));  // 有 bash

        PlanResult result = new PlanExecutor(counting).execute(plan(graph));

        assertTrue(violations.isEmpty(), violations.toString());
        assertEquals(6, result.succeeded());
    }

    @Test
    @DisplayName("并行宽度照样封顶：三个互不相撞的 coder，宽度 2 时同一时刻最多两个")
    void scopedWritersRespectParallelism() {
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        TaskRunner counting = (task, upstream) -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return TaskOutcome.ok("干完了");
        };
        TaskGraph graph = TaskGraph.of(List.of(coder("a", "a.py"), coder("b", "b.py"), coder("c", "c.py")));

        new PlanExecutor(counting).parallelism(2).execute(plan(graph));

        assertTrue(peak.get() <= 2, "峰值 " + peak.get());
    }

    // ── 超时 ────────────────────────────────────────────────────

    @Test
    @DisplayName("一个任务卡死：它按失败算，下游跳过，别的分支照跑完")
    void stuckTaskTimesOutAndTheRestCarryOn() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        TaskRunner oneStuck = (task, upstream) -> {
            if (task.id().equals("stuck")) {
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return TaskOutcome.ok("我终于醒了");
            }
            return TaskOutcome.ok(task.id() + " 干完了");
        };
        // stuck 卡着，downstream 等它（该跳过），other 和它无关（该跑完）
        TaskGraph graph = TaskGraph.of(List.of(
                t("stuck"), t("downstream", "stuck"), t("other")));

        try {
            PlanResult result = new PlanExecutor(oneStuck)
                    .taskTimeout(java.time.Duration.ofMillis(300))
                    .execute(plan(graph));

            assertEquals(TaskStatus.FAILED, graph.get("stuck").status());
            assertTrue(graph.get("stuck").error().contains("超时"), graph.get("stuck").error());
            assertEquals(TaskStatus.SKIPPED, graph.get("downstream").status(),
                    "超时和别的失败一样，下游要顺着 dependents 跳过");
            assertEquals(TaskStatus.SUCCEEDED, graph.get("other").status(),
                    "无关的分支不该被一个卡死的任务拖下水");
            assertTrue(graph.isDone(), "超时之后也不能留下没了结的任务");
            assertEquals(1, result.failed());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("超时的任务迟到的结果要丢掉——它已经了结过，再落一次会撞状态机")
    void lateResultFromATimedOutTaskIsIgnored() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        TaskRunner lateFinisher = (task, upstream) -> {
            if (task.id().equals("slow")) {
                try {
                    // 超时线是 200ms，这个 600ms 之后才回来——那时它已经被判失败了
                    release.await(600, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return TaskOutcome.ok("迟到的成功");
            }
            return TaskOutcome.ok("干完了");
        };
        TaskGraph graph = TaskGraph.of(List.of(t("slow"), t("quick")));

        PlanResult result = assertDoesNotThrow(() -> new PlanExecutor(lateFinisher)
                .taskTimeout(java.time.Duration.ofMillis(200))
                .execute(plan(graph)));

        assertEquals(TaskStatus.FAILED, graph.get("slow").status(),
                "迟到的成功不能把已经判掉的任务改回来");
        assertNull(graph.get("slow").result());
        assertEquals(1, result.failed());
    }

    @Test
    @DisplayName("不限时的时候行为和以前一模一样——慢任务就是慢，不是错")
    void withoutTimeoutSlowTasksJustFinishLate() {
        TaskRunner slow = (task, upstream) -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TaskOutcome.ok("慢慢干完了");
        };

        PlanResult result = new PlanExecutor(slow)
                .taskTimeout(null)                  // 不限时
                .execute(diamondPlan());

        assertEquals(PlanStatus.COMPLETED, result.status());
        assertEquals(4, result.succeeded());
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

        assertEquals(java.util.Set.of("n1", "n2", "n3"), java.util.Set.copyOf(runner.order),
                "n4 不该被执行: " + runner.order);
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
                .parallelism(1)     // 断言第二个了结的是 n2，得按确定顺序跑
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
        PlanResult result = new PlanExecutor(second).parallelism(1).execute(resumed);

        assertFalse(second.order.contains("n1"), "已经做成的事不该重跑: " + second.order);
        // n2/n3 互不依赖且是只读任务，**并行执行**——谁先进 order 看线程调度。
        // 断言语义而不是断言运气：n1 不重来、n2 n3 都跑了、n4 压轴。
        // 原来写死 [n2,n3,n4]，单跑几乎全绿，全量跑（CPU 有争抢）偶发翻车——
        // 顺序是串行时代送的免费午餐，并发之后就不能再当承诺
        assertEquals(3, second.order.size());
        assertFalse(second.order.contains("n1"), "做成的那步不该重来");
        assertTrue(second.order.subList(0, 2).containsAll(List.of("n2", "n3")), second.order.toString());
        assertEquals("n4", second.order.get(2));
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

        // n2/n3 互不依赖且是只读任务，**并行执行**——谁先进 order 看线程调度。
        // 断言语义而不是断言运气：n1 不重来、n2 n3 都跑了、n4 压轴。
        // 原来写死 [n2,n3,n4]，单跑几乎全绿，全量跑（CPU 有争抢）偶发翻车——
        // 顺序是串行时代送的免费午餐，并发之后就不能再当承诺
        assertEquals(3, second.order.size());
        assertFalse(second.order.contains("n1"), "做成的那步不该重来");
        assertTrue(second.order.subList(0, 2).containsAll(List.of("n2", "n3")), second.order.toString());
        assertEquals("n4", second.order.get(2));
        assertEquals(PlanStatus.COMPLETED, resumed.status());
    }
}
