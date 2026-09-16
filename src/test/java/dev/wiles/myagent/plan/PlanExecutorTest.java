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
}
