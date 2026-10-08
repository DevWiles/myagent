package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskTest {

    private static Task task(String id, String... deps) {
        return new Task(id, "描述 " + id, TaskType.FILE_READ, List.of(deps));
    }

    // ── 出生状态 ────────────────────────────────────────────────

    @Test
    @DisplayName("新任务是 PENDING，没有结果也没有时间戳")
    void startsPending() {
        Task t = task("n1");

        assertEquals(TaskStatus.PENDING, t.status());
        assertNull(t.result());
        assertNull(t.error());
        assertEquals(0, t.startTime());
        assertEquals(0, t.endTime());
        assertTrue(t.dependents().isEmpty());
        assertFalse(t.isTerminal());
    }

    @Test
    @DisplayName("id 和描述不能为空——没有 id 就没法连边")
    void rejectsBlankIdOrDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> new Task(" ", "描述", TaskType.FILE_READ, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Task("n1", "", TaskType.FILE_READ, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Task("n1", "描述", null, List.of()));
    }

    @Test
    @DisplayName("files 规范化：去 ./ 和尾部斜杠、去重；越出工作区的路径当场拒")
    void filesAreNormalized() {
        Task t = new Task("t1", "改", AgentRoles.CODER, List.of(), null,
                List.of("./src/a.py", "src/a.py", "src/pkg/", " src/b.py "));
        assertEquals(List.of("src/a.py", "src/pkg", "src/b.py"), t.files());
        assertEquals(List.of(), new Task("t2", "读", TaskType.FILE_READ, List.of()).files());
        assertThrows(IllegalArgumentException.class, () -> new Task("t3", "改", AgentRoles.CODER,
                List.of(), null, List.of("../etc/passwd")));
        assertThrows(IllegalArgumentException.class, () -> new Task("t4", "改", AgentRoles.CODER,
                List.of(), null, List.of("/tmp/x")));
    }

    @Test
    @DisplayName("文件冲突：同名撞、目录包含撞、任一方没声明也当撞；各管各的不撞")
    void fileConflictRules() {
        Task a = new Task("a", "改", AgentRoles.CODER, List.of(), null, List.of("src/a.py"));
        Task b = new Task("b", "改", AgentRoles.CODER, List.of(), null, List.of("src/b.py"));
        Task dir = new Task("d", "改", AgentRoles.CODER, List.of(), null, List.of("src"));
        Task none = new Task("n", "改", AgentRoles.CODER, List.of(), null, List.of());
        assertFalse(a.filesConflictWith(b));
        assertTrue(a.filesConflictWith(a));
        assertTrue(a.filesConflictWith(dir), "声明了整个目录的和目录里任何文件都撞");
        assertTrue(dir.filesConflictWith(b));
        assertTrue(a.filesConflictWith(none), "没声明就是什么都可能碰");
        assertFalse(new Task("x", "改", AgentRoles.CODER, List.of(), null, List.of("srcx/a.py"))
                .filesConflictWith(dir), "srcx 不是 src 的子目录，只按路径段比不按前缀字符串比");
    }

    @Test
    @DisplayName("任务不能依赖自己")
    void rejectsSelfDependency() {
        assertThrows(IllegalArgumentException.class,
                () -> new Task("n1", "描述", TaskType.FILE_READ, List.of("n1")));
    }

    @Test
    @DisplayName("依赖列表要防外部篡改：传进来的改了，任务里的不变；拿出去的改不动")
    void dependenciesAreDefensivelyCopied() {
        List<String> deps = new ArrayList<>(List.of("n1"));
        Task t = new Task("n2", "描述", TaskType.FILE_WRITE, deps);

        deps.add("n99");
        assertEquals(List.of("n1"), t.dependencies());
        assertThrows(UnsupportedOperationException.class, () -> t.dependencies().add("n99"));
    }

    // ── 反向边 ──────────────────────────────────────────────────

    @Test
    @DisplayName("反向边由外部登记，重复登记不会堆出两条")
    void dependentsAreRegisteredOnce() {
        Task t = task("n1");

        t.addDependent("n2");
        t.addDependent("n3");
        t.addDependent("n2");

        assertEquals(List.of("n2", "n3"), t.dependents());
        assertThrows(UnsupportedOperationException.class, () -> t.dependents().add("n4"));
    }

    // ── 层级：只许再生一层，不许有孙子 ──────────────────────────

    @Test
    @DisplayName("规划出来的任务是根任务：深度 0、没有爹、还能再拆一层")
    void rootTaskCanSpawn() {
        Task t = task("n1");

        assertEquals(0, t.depth());
        assertNull(t.parentId());
        assertTrue(t.canSpawnSubtasks());
    }

    @Test
    @DisplayName("子任务深度 1，记着爹是谁，但自己不能再拆")
    void subTaskIsOneLevelDeep() {
        Task parent = new Task("n1", "查清楚要改哪些文件", TaskType.PLANNING, List.of());

        Task child = parent.subTask("n1.1", "改 UserService", TaskType.FILE_WRITE, List.of());

        assertEquals(1, child.depth());
        assertEquals("n1", child.parentId());
        assertFalse(child.canSpawnSubtasks());
    }

    @Test
    @DisplayName("孙子任务在模型层就造不出来——这是硬闸门，不靠提示词自觉")
    void rejectsGrandchild() {
        Task parent = new Task("n1", "先探探路", TaskType.PLANNING, List.of());
        Task child = parent.subTask("n1.1", "再探探路", TaskType.PLANNING, List.of());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> child.subTask("n1.1.1", "孙子", TaskType.FILE_READ, List.of()));
        assertTrue(e.getMessage().contains("n1.1"), "报错要说清是谁越界了：" + e.getMessage());
    }

    @Test
    @DisplayName("子任务可以依赖别的任务，依赖不受层级限制")
    void subTaskKeepsItsOwnDependencies() {
        Task parent = new Task("n1", "探路", TaskType.PLANNING, List.of());

        Task child = parent.subTask("n1.1", "改代码", TaskType.FILE_WRITE, List.of("n2"));

        assertEquals(List.of("n2"), child.dependencies());
    }

    // ── 状态迁移 ────────────────────────────────────────────────

    @Test
    @DisplayName("PENDING → RUNNING → SUCCEEDED，时间戳一前一后")
    void happyPath() {
        Task t = task("n1");

        t.start();
        assertEquals(TaskStatus.RUNNING, t.status());
        assertTrue(t.startTime() > 0);
        assertEquals(0, t.endTime());
        assertFalse(t.isTerminal());

        t.succeed("读完了 pom.xml");
        assertEquals(TaskStatus.SUCCEEDED, t.status());
        assertEquals("读完了 pom.xml", t.result());
        assertNull(t.error());
        assertTrue(t.endTime() >= t.startTime());
        assertTrue(t.isTerminal());
        assertTrue(t.durationMillis() >= 0);
    }

    @Test
    @DisplayName("失败留下错误信息，同样是终态")
    void failurePath() {
        Task t = task("n1");
        t.start();

        t.fail("命令返回码 1");

        assertEquals(TaskStatus.FAILED, t.status());
        assertEquals("命令返回码 1", t.error());
        assertNull(t.result());
        assertTrue(t.isTerminal());
    }

    @Test
    @DisplayName("上游塌了就跳过：没跑过也能直接进终态，错误里写清被谁连累")
    void skipWithoutRunning() {
        Task t = task("n2", "n1");

        t.skip("上游 n1 失败");

        assertEquals(TaskStatus.SKIPPED, t.status());
        assertEquals("上游 n1 失败", t.error());
        assertEquals(0, t.startTime(), "没跑过就不该有开始时间");
        assertTrue(t.endTime() > 0, "但它已经了结了，得有个结束时间");
        assertTrue(t.isTerminal());
    }

    @Test
    @DisplayName("非法迁移直接抛：跑完的任务不能再跑，没跑的任务不能宣布成功")
    void rejectsIllegalTransitions() {
        Task done = task("n1");
        done.start();
        done.succeed("好了");

        assertThrows(IllegalStateException.class, done::start);
        assertThrows(IllegalStateException.class, () -> done.succeed("又好了一次"));
        assertThrows(IllegalStateException.class, () -> done.fail("其实坏了"));
        assertThrows(IllegalStateException.class, () -> done.skip("懒得跑"));

        Task fresh = task("n2");
        assertThrows(IllegalStateException.class, () -> fresh.succeed("还没跑就说成功"));
        assertThrows(IllegalStateException.class, () -> fresh.fail("还没跑就说失败"));
    }

    @Test
    @DisplayName("没结束的任务没有耗时可言")
    void durationOfUnfinishedTask() {
        Task t = task("n1");
        assertEquals(-1, t.durationMillis());

        t.start();
        assertEquals(-1, t.durationMillis());
    }
}
