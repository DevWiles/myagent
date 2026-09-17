package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskGraphTest {

    private static Task t(String id, String... deps) {
        return new Task(id, "描述 " + id, TaskType.FILE_READ, List.of(deps));
    }

    /** 一张菱形图外挂一个孤岛：n1 →(n2,n3)→ n4，n5 谁也不依赖。 */
    private static TaskGraph diamond() {
        return TaskGraph.of(List.of(
                t("n1"),
                t("n2", "n1"),
                t("n3", "n1"),
                t("n4", "n2", "n3"),
                t("n5")));
    }

    private static List<String> ids(List<Task> tasks) {
        return tasks.stream().map(Task::id).toList();
    }

    // ── 建图 ────────────────────────────────────────────────────

    @Test
    @DisplayName("建图时自动补反向边，两个方向对得上")
    void buildsReverseEdges() {
        TaskGraph g = diamond();

        assertEquals(List.of("n2", "n3"), g.get("n1").dependents());
        assertEquals(List.of("n4"), g.get("n2").dependents());
        assertEquals(List.of("n4"), g.get("n3").dependents());
        assertTrue(g.get("n4").dependents().isEmpty());
        assertTrue(g.get("n5").dependents().isEmpty());
        assertEquals(5, g.size());
    }

    @Test
    @DisplayName("依赖一个不存在的任务：模型编 id 是常事，必须当场拦下")
    void rejectsDanglingDependency() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TaskGraph.of(List.of(t("n1"), t("n2", "n7"))));

        assertTrue(e.getMessage().contains("n7"), "报错要点出是哪个 id 不存在：" + e.getMessage());
    }

    @Test
    @DisplayName("id 撞车直接拒绝，不能让后来的悄悄覆盖前面的")
    void rejectsDuplicateId() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TaskGraph.of(List.of(t("n1"), t("n1"))));

        assertTrue(e.getMessage().contains("n1"));
    }

    @Test
    @DisplayName("成环必须在开跑之前发现——否则表现是静默死锁，谁都在等别人")
    void rejectsCycle() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TaskGraph.of(List.of(
                        t("n1", "n3"),
                        t("n2", "n1"),
                        t("n3", "n2"),
                        t("n4"))));

        String msg = e.getMessage();
        assertTrue(msg.contains("n1") && msg.contains("n2") && msg.contains("n3"),
                "报错要列出环里的任务：" + msg);
        assertFalse(msg.contains("n4"), "没在环里的任务不该被点名：" + msg);
        assertTrue(msg.contains("n1 → n3 → n2 → n1"),
                "要给出环的完整路径，这句话是要回灌给模型让它改的：" + msg);
    }

    @Test
    @DisplayName("环下游被牵连的任务不该混进环的路径里")
    void cycleMessagePointsAtTheCycleOnly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TaskGraph.of(List.of(
                        t("n1", "n2"),
                        t("n2", "n1"),
                        t("n3", "n1"))));

        assertFalse(e.getMessage().contains("n3"),
                "n3 只是在等一个跑不完的上游，它自己没毛病：" + e.getMessage());
    }

    @Test
    @DisplayName("空图没意义")
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> TaskGraph.of(List.of()));
    }

    // ── 分层 ────────────────────────────────────────────────────

    @Test
    @DisplayName("拓扑分层：同一层之间没有依赖，将来可以并发")
    void layersTopologically() {
        List<List<Task>> layers = diamond().layers();

        assertEquals(3, layers.size());
        assertEquals(List.of("n1", "n5"), ids(layers.get(0)));
        assertEquals(List.of("n2", "n3"), ids(layers.get(1)));
        assertEquals(List.of("n4"), ids(layers.get(2)));
    }

    // ── 就绪判定 ────────────────────────────────────────────────

    @Test
    @DisplayName("一开始只有没依赖的任务就绪")
    void initiallyReady() {
        assertEquals(List.of("n1", "n5"), ids(diamond().ready()));
    }

    @Test
    @DisplayName("上游成功才解锁下游；正在跑的和跑完的都不会再出现在就绪集合里")
    void readyAdvancesAsTasksSucceed() {
        TaskGraph g = diamond();

        g.get("n1").start();
        assertEquals(List.of("n5"), ids(g.ready()), "n1 在跑，不该再被调度一次");

        g.get("n1").succeed("读完了");
        assertEquals(List.of("n2", "n3", "n5"), ids(g.ready()));

        g.get("n2").start();
        g.get("n2").succeed("改完了");
        assertEquals(List.of("n3", "n5"), ids(g.ready()), "n4 还差 n3，不能算就绪");

        g.get("n3").start();
        g.get("n3").succeed("也改完了");
        assertEquals(List.of("n4", "n5"), ids(g.ready()));
    }

    @Test
    @DisplayName("上游失败的下游永远不会就绪——它不是在等，是等不到了")
    void failedUpstreamNeverUnlocksDownstream() {
        TaskGraph g = diamond();
        g.get("n1").start();
        g.get("n1").fail("文件读不到");

        assertEquals(List.of("n5"), ids(g.ready()));
    }

    @Test
    @DisplayName("全都进了终态才算跑完")
    void isDoneOnlyWhenAllTerminal() {
        TaskGraph g = diamond();
        assertFalse(g.isDone());

        for (Task task : g.tasks()) {
            task.skip("不跑了");
        }
        assertTrue(g.isDone());
    }

    // ── 失败扩散 ────────────────────────────────────────────────

    @Test
    @DisplayName("一条腿断了只连累自己那条线，别的腿照跑")
    void failurePropagatesDownstreamOnly() {
        TaskGraph g = diamond();
        g.get("n1").start();
        g.get("n1").succeed("读完了");
        g.get("n2").start();
        g.get("n2").fail("写文件失败");

        List<Task> skipped = g.propagateFailure("n2");

        assertEquals(List.of("n4"), ids(skipped));
        assertEquals(TaskStatus.SKIPPED, g.get("n4").status());
        assertTrue(g.get("n4").error().contains("n2"), "跳过的原因要说清是被谁连累的");
        assertEquals(TaskStatus.PENDING, g.get("n3").status(), "另一条腿不受影响");
        assertEquals(TaskStatus.PENDING, g.get("n5").status());
    }

    @Test
    @DisplayName("扩散要一路传到底，孙辈也得跳过")
    void failurePropagatesTransitively() {
        TaskGraph g = TaskGraph.of(List.of(
                t("n1"), t("n2", "n1"), t("n3", "n2"), t("n4", "n3")));
        g.get("n1").start();
        g.get("n1").fail("炸了");

        assertEquals(List.of("n2", "n3", "n4"), ids(g.propagateFailure("n1")));
    }

    @Test
    @DisplayName("已经了结的下游不会被二次跳过")
    void doesNotReSkipTerminalTasks() {
        TaskGraph g = diamond();
        g.get("n4").skip("先前就跳过了");
        g.get("n1").start();
        g.get("n1").fail("炸了");

        List<Task> skipped = g.propagateFailure("n1");

        assertEquals(List.of("n2", "n3"), ids(skipped));
        assertEquals("先前就跳过了", g.get("n4").error(), "原来的跳过理由不该被覆盖");
    }

    // ── 动态长大：PLANNING 任务拆出子任务 ────────────────────────

    @Test
    @DisplayName("PLANNING 任务跑完可以往图里追加子任务，反向边跟着更新")
    void growsWithSubTasks() {
        Task planner = new Task("n1", "先摸清要改哪儿", TaskType.PLANNING, List.of());
        TaskGraph g = TaskGraph.of(List.of(planner, t("n2", "n1")));

        g.addSubTasks(List.of(
                planner.subTask("n1.1", "改 A", TaskType.FILE_WRITE, List.of()),
                planner.subTask("n1.2", "改 B", TaskType.FILE_WRITE, List.of("n1.1"))));

        assertEquals(4, g.size());
        assertEquals(List.of("n1.2"), g.get("n1.1").dependents());
        assertEquals(1, g.get("n1.2").depth());
    }

    @Test
    @DisplayName("追加要么全成功要么不留痕迹：新来的任务成了环，原图必须干干净净")
    void failedAppendLeavesGraphIntact() {
        Task planner = new Task("n1", "探路", TaskType.PLANNING, List.of());
        TaskGraph g = TaskGraph.of(List.of(planner, t("n2", "n1")));

        assertThrows(IllegalArgumentException.class, () -> g.addSubTasks(List.of(
                planner.subTask("n1.1", "改 A", TaskType.FILE_WRITE, List.of("n1.2")),
                planner.subTask("n1.2", "改 B", TaskType.FILE_WRITE, List.of("n1.1")))));

        assertEquals(2, g.size(), "环没建成，图不该变大");
        assertTrue(g.get("n1").dependents().contains("n2"));
        assertEquals(1, g.get("n1").dependents().size(), "半截反向边也不许留");
    }

    @Test
    @DisplayName("追加的 id 撞上已有任务，同样拒绝")
    void rejectsAppendWithDuplicateId() {
        Task planner = new Task("n1", "探路", TaskType.PLANNING, List.of());
        TaskGraph g = TaskGraph.of(List.of(planner, t("n2", "n1")));

        assertThrows(IllegalArgumentException.class, () -> g.addSubTasks(
                List.of(planner.subTask("n2", "撞名字", TaskType.FILE_WRITE, List.of()))));

        assertEquals(2, g.size());
    }

    // ── 追加时不许连到已经废掉的任务 ──────────────────────────────

    /**
     * 一条已经断掉的腿：n2 自己失败，n3 被它连累跳过；n4、n5 都还没轮到。
     *
     * <p>n1 是 RUNNING——PLANNING 任务往图里落子任务的那一刻，正是这个状态
     * （见 {@code PlanExecutor.runOne}：子任务先落图，成功了才 succeed）。
     */
    private static TaskGraph graphWithDeadLeg() {
        TaskGraph g = TaskGraph.of(List.of(
                new Task("n1", "探路", TaskType.PLANNING, List.of()),
                t("n2"), t("n3", "n2"), t("n4"), t("n5")));
        g.get("n1").start();
        g.get("n2").start();
        g.get("n2").fail("编译不过");
        g.propagateFailure("n2");
        return g;
    }

    @Test
    @DisplayName("子任务依赖一个已经失败的任务：它永远等不到就绪，当场拒绝")
    void rejectsSubTaskDependingOnFailedTask() {
        TaskGraph g = graphWithDeadLeg();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> g.addSubTasks(List.of(g.get("n1").subTask(
                        "n1.1", "接着 n2 的产出改", TaskType.FILE_WRITE, List.of("n2")))));

        // 这句话会变成「子任务不合法: ...」，进而成为重规划提示词的一部分，
        // 所以得点名是谁连了谁——只说「依赖不合法」模型没法照着改
        assertTrue(e.getMessage().contains("n1.1"), "报错要点出是哪个新任务: " + e.getMessage());
        assertTrue(e.getMessage().contains("n2"), "报错要点出连坏了哪个上游: " + e.getMessage());

        assertEquals(5, g.size(), "拒绝了就不该让图变大");
        assertEquals(List.of("n3"), g.get("n2").dependents(), "半截反向边也不许留");
    }

    @Test
    @DisplayName("子任务依赖一个被跳过的任务：和依赖失败任务一样是死等，同样拒绝")
    void rejectsSubTaskDependingOnSkippedTask() {
        TaskGraph g = graphWithDeadLeg();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> g.addSubTasks(List.of(g.get("n1").subTask(
                        "n1.1", "在 n3 的成果上继续", TaskType.FILE_WRITE, List.of("n3")))));

        assertTrue(e.getMessage().contains("n3"), "报错要点出连坏了哪个上游: " + e.getMessage());
        assertEquals(5, g.size(), "拒绝了就不该让图变大");
        assertTrue(g.get("n3").dependents().isEmpty(), "半截反向边也不许留");
    }

    @Test
    @DisplayName("追加的任务依赖一个不存在的 id：报错要说得清，不能是 NPE")
    void rejectsAppendWithDanglingDependency() {
        TaskGraph g = graphWithDeadLeg();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> g.addSubTasks(List.of(g.get("n1").subTask(
                        "n1.1", "接着不存在的那步做", TaskType.FILE_WRITE, List.of("n9")))));

        assertTrue(e.getMessage().contains("n9"), "报错要点出是哪个 id 不存在: " + e.getMessage());
        assertEquals(5, g.size());
    }

    @Test
    @DisplayName("依赖还没跑的、或已经跑成的兄弟任务都正常，别顺手一起堵掉")
    void allowsSubTaskDependingOnLiveTasks() {
        TaskGraph g = graphWithDeadLeg();
        g.get("n5").start();
        g.get("n5").succeed("量完了");

        // n4 还是 PENDING、n5 已经 SUCCEEDED——这两种都是活的上游，等得到
        g.addSubTasks(List.of(g.get("n1").subTask(
                "n1.1", "等 n4 做完，用上 n5 的产出", TaskType.FILE_WRITE, List.of("n4", "n5"))));

        assertEquals(6, g.size());
        assertEquals(TaskStatus.PENDING, g.get("n4").status());
        assertEquals(List.of("n1.1"), g.get("n4").dependents());
        assertEquals(List.of("n1.1"), g.get("n5").dependents());
    }
}
