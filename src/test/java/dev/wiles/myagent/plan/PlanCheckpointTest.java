package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 存档错了不会当场报错，只会在「下次续跑」那一刻变成一份错的图——
 * 所以往返的每个字段都得盯住。
 */
class PlanCheckpointTest {

    private static final Path WS = Path.of("/tmp/proj");

    /** 一份跑到一半的计划：t1 成了，t2 倒了，t3 被连累跳过，t4 还没轮到。 */
    private static Plan halfRun() {
        Task t1 = new Task("t1", "读 pom.xml", TaskType.FILE_READ, List.of(), "说清 Java 版本");
        Task t2 = new Task("t2", "按 {{t1}} 改 App", TaskType.FILE_WRITE, List.of("t1"), "改完了");
        Task t3 = new Task("t3", "跑测试", TaskType.VERIFICATION, List.of("t2"), "测试通过");
        Task t4 = new Task("t4", "另一条腿", TaskType.FILE_READ, List.of(), "读到了");
        Plan plan = new Plan("给项目加重试", TaskGraph.of(List.of(t1, t2, t3, t4)));

        plan.start();
        t1.start();
        t1.succeed("pom 里写的是 Java 17");
        t2.start();
        t2.fail("App.java 不存在");
        plan.graph().propagateFailure("t2");
        return plan;
    }

    private static Plan roundTrip(Plan plan) {
        return PlanCheckpoint.of(plan, WS, 1_000L).toPlan();
    }

    @Test
    @DisplayName("往返之后，每个任务的状态和产出原样还在")
    void keepsStatusAndResults() {
        Plan back = roundTrip(halfRun());

        assertEquals(TaskStatus.SUCCEEDED, back.graph().get("t1").status());
        assertEquals("pom 里写的是 Java 17", back.graph().get("t1").result());
        assertEquals(TaskStatus.FAILED, back.graph().get("t2").status());
        assertEquals("App.java 不存在", back.graph().get("t2").error());
        assertEquals(TaskStatus.SKIPPED, back.graph().get("t3").status());
        assertEquals(TaskStatus.PENDING, back.graph().get("t4").status());
    }

    @Test
    @DisplayName("计划自己的状态、目标、开始时间都保住")
    void keepsPlanState() {
        Plan original = halfRun();

        Plan back = roundTrip(original);

        assertEquals("给项目加重试", back.goal());
        assertEquals(PlanStatus.RUNNING, back.status());
        assertEquals(original.startTime(), back.startTime());
    }

    @Test
    @DisplayName("任务的描述、验收标准、依赖、占位符一个不少")
    void keepsTaskShape() {
        Task t2 = roundTrip(halfRun()).graph().get("t2");

        assertEquals("按 {{t1}} 改 App", t2.description());
        assertEquals("改完了", t2.acceptance());
        assertEquals(List.of("t1"), t2.dependencies());
        assertEquals(TaskType.FILE_WRITE, t2.type());
    }

    @Test
    @DisplayName("反向边是重新推出来的，不从存档里读")
    void rebuildsDependents() {
        Plan back = roundTrip(halfRun());

        assertEquals(List.of("t2"), back.graph().get("t1").dependents());
        assertEquals(List.of("t3"), back.graph().get("t2").dependents());
    }

    @Test
    @DisplayName("子任务的爹和深度保住——不然它会被当成根任务，还能再拆一层")
    void keepsParentAndDepth() {
        Task parent = new Task("t1", "探路", TaskType.PLANNING, List.of(), "拆出步骤");
        Task child = parent.subTask("t1.1", "干活", TaskType.FILE_WRITE, List.of(), "干完");
        Plan plan = new Plan("目标", TaskGraph.of(List.of(parent, child)));

        Task back = roundTrip(plan).graph().get("t1.1");

        assertEquals("t1", back.parentId());
        assertEquals(1, back.depth());
        assertFalse(back.canSpawnSubtasks(), "深度丢了的话它又能再拆一层，套到天荒地老");
    }

    @Test
    @DisplayName("重规划的计数和补救任务 id 保住——不然续跑会再补一次，实际补了两次")
    void keepsReplanState() {
        Plan plan = halfRun();
        Task recovery = new Task("r1", "换条路", TaskType.FILE_WRITE, List.of(), "改完");
        plan.graph().addTasks(List.of(recovery));
        plan.recordReplan(List.of(recovery));

        Plan back = roundTrip(plan);

        assertEquals(1, back.replans());
        assertFalse(back.canReplan(), "续跑后又能补一次，等于突破了上限");
        assertEquals(List.of("r1"), back.recoveryIds());
    }

    @Test
    @DisplayName("上次跑到一半就断的任务，退回未开始重跑，时间戳一并清掉")
    void interruptedTaskGoesBackToPending() {
        Task t1 = new Task("t1", "干到一半断电", TaskType.COMMAND, List.of(), "干完");
        Plan plan = new Plan("目标", TaskGraph.of(List.of(t1)));
        plan.start();
        t1.start();                                  // 停在 RUNNING，模拟进程没了

        Task back = roundTrip(plan).graph().get("t1");

        assertEquals(TaskStatus.PENDING, back.status());
        assertEquals(0, back.startTime(), "没跑完就是没跑过，时间戳得对得起事实");
        assertEquals(-1, back.durationMillis());
    }

    @Test
    @DisplayName("格式版本对不上：不给续，也不硬解析出一份残图")
    void rejectsUnknownFormat() {
        PlanCheckpoint stale = new PlanCheckpoint(
                PlanCheckpoint.FORMAT + 1, WS.toString(), 1L, "目标", "RUNNING",
                0, 0, 0, List.of(), List.of());

        assertFalse(stale.isResumable());
        assertThrows(IllegalArgumentException.class, stale::toPlan);
    }

    @Test
    @DisplayName("已经跑完的计划不可续——留着它，下次启动会提示续一份跑完的计划")
    void terminalPlanIsNotResumable() {
        Plan plan = halfRun();
        plan.fail();

        assertFalse(PlanCheckpoint.of(plan, WS, 1L).isResumable());
    }

    @Test
    @DisplayName("认工作区：换个目录续跑，等于拿 A 项目的步骤去改 B 项目")
    void checksWorkspace() {
        PlanCheckpoint checkpoint = PlanCheckpoint.of(halfRun(), WS, 1L);

        assertTrue(checkpoint.belongsTo(WS));
        assertTrue(checkpoint.belongsTo(Path.of("/tmp/./proj")), "路径该归一化后再比");
        assertFalse(checkpoint.belongsTo(Path.of("/tmp/other")));
        assertFalse(checkpoint.belongsTo(null));
    }

    @Test
    @DisplayName("还剩几步：给用户一句人话")
    void countsRemaining() {
        // t1 成、t2 败、t3 跳过 —— 都是终态；只有 t4 还没跑
        assertEquals(1, PlanCheckpoint.of(halfRun(), WS, 1L).remaining());
    }

    @Test
    @DisplayName("派给谁也要存下来——不然续跑时 reviewer 会悄悄变回 explorer")
    void keepsTheRole() {
        Plan plan = new Plan("挑毛病", TaskGraph.of(List.of(
                new Task("t1", "审一遍 Task.java", AgentRoles.REVIEWER, List.of(), "给出清单"),
                new Task("t2", "按清单改", AgentRoles.CODER, List.of("t1"), "改完"))));

        Plan back = roundTrip(plan);

        assertEquals(AgentRoles.REVIEWER, back.graph().get("t1").role());
        assertEquals(AgentRoles.CODER, back.graph().get("t2").role());
    }

    @Test
    @DisplayName("files 声明往返保住；老存档没有这个字段读出来是 null，按空处理")
    void filesSurviveRoundTripAndDefaultToEmpty() {
        Task coder = new Task("t1", "改两个文件", AgentRoles.CODER, List.of(), "改完",
                List.of("src/a.py", "./src/b.py/"));
        Plan plan = new Plan("目标", TaskGraph.of(List.of(coder)));

        Plan back = PlanCheckpoint.of(plan, WS, 1L).toPlan();
        assertEquals(List.of("src/a.py", "src/b.py"), back.graph().get("t1").files());

        PlanCheckpoint old = new PlanCheckpoint(
                PlanCheckpoint.FORMAT, WS.toString(), 1L, "目标", "RUNNING", 0, 0, 0, List.of(),
                List.of(new PlanCheckpoint.TaskSnapshot("t1", "读点东西", "FILE_READ", null,
                        List.of(), "读到了", null, null, 0, "PENDING", null, null, 0, 0)));
        assertEquals(List.of(), old.toPlan().graph().get("t1").files());
    }

    @Test
    @DisplayName("老存档没有 role 字段：按 type 落到默认角色，照样能续")
    void oldArchiveWithoutRoleStillResumes() {
        PlanCheckpoint checkpoint = new PlanCheckpoint(
                PlanCheckpoint.FORMAT, WS.toString(), 1L, "目标", "RUNNING", 0, 0, 0, List.of(),
                List.of(new PlanCheckpoint.TaskSnapshot("t1", "读点东西", "FILE_READ", null,
                        List.of(), "读到了", null, null, 0, "PENDING", null, null, 0, 0)));

        assertEquals(AgentRoles.EXPLORER, checkpoint.toPlan().graph().get("t1").role());
    }

    @Test
    @DisplayName("角色名认不出来（改过名、来自新版本）就退回类型默认值，别让整份存档报废")
    void unknownRoleFallsBackToTypeDefault() {
        PlanCheckpoint checkpoint = new PlanCheckpoint(
                PlanCheckpoint.FORMAT, WS.toString(), 1L, "目标", "RUNNING", 0, 0, 0, List.of(),
                List.of(new PlanCheckpoint.TaskSnapshot("t1", "读点东西", "FILE_READ",
                        "archaeologist", List.of(), "读到了", null, null, 0, "PENDING", null, null, 0, 0)));

        Task restored = checkpoint.toPlan().graph().get("t1");
        assertEquals(AgentRoles.EXPLORER, restored.role(),
                "丢一个「派谁去」的偏好，好过让几十次模型调用换来的产出全废");
        assertEquals(TaskType.FILE_READ, restored.type());
    }

    @Test
    @DisplayName("状态字段认不出来就当没开始，别让整份存档报废")
    void unknownStatusFallsBackToPending() {
        PlanCheckpoint checkpoint = new PlanCheckpoint(
                PlanCheckpoint.FORMAT, WS.toString(), 1L, "目标", "RUNNING", 0, 0, 0, List.of(),
                List.of(new PlanCheckpoint.TaskSnapshot("t1", "干活", "FILE_READ", null, List.of(),
                        "干完", null, null, 0, "ZOMBIE", null, null, 0, 0)));

        assertEquals(TaskStatus.PENDING, checkpoint.toPlan().graph().get("t1").status());
    }

    @Test
    @DisplayName("任务类型认不出来就直接拒——那是结构坏了，不是状态偏了")
    void unknownTypeIsFatal() {
        PlanCheckpoint checkpoint = new PlanCheckpoint(
                PlanCheckpoint.FORMAT, WS.toString(), 1L, "目标", "RUNNING", 0, 0, 0, List.of(),
                List.of(new PlanCheckpoint.TaskSnapshot("t1", "干活", "TELEPORT", null, List.of(),
                        "干完", null, null, 0, "PENDING", null, null, 0, 0)));

        assertTrue(assertThrows(IllegalArgumentException.class, checkpoint::toPlan)
                .getMessage().contains("TELEPORT"));
    }

    @Test
    @DisplayName("存档里的图照样要查环——盘上的东西可能被人改过")
    void stillValidatesTheGraph() {
        PlanCheckpoint checkpoint = new PlanCheckpoint(
                PlanCheckpoint.FORMAT, WS.toString(), 1L, "目标", "RUNNING", 0, 0, 0, List.of(),
                List.of(
                        new PlanCheckpoint.TaskSnapshot("t1", "甲", "FILE_READ", null, List.of("t2"),
                                "完", null, null, 0, "PENDING", null, null, 0, 0),
                        new PlanCheckpoint.TaskSnapshot("t2", "乙", "FILE_READ", null, List.of("t1"),
                                "完", null, null, 0, "PENDING", null, null, 0, 0)));

        assertTrue(assertThrows(IllegalArgumentException.class, checkpoint::toPlan)
                .getMessage().contains("循环依赖"));
    }
}
