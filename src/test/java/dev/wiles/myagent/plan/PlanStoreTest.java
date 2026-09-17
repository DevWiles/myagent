package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PlanStoreTest {

    @TempDir
    Path tmp;

    private PlanStore store() {
        return new PlanStore(tmp.resolve("plans"));
    }

    private static Plan runningPlan(String goal) {
        Task t1 = new Task("t1", "读文件", TaskType.FILE_READ, List.of(), "读到了");
        Task t2 = new Task("t2", "改文件", TaskType.FILE_WRITE, List.of("t1"), "改完了");
        Plan plan = new Plan(goal, TaskGraph.of(List.of(t1, t2)));
        plan.start();
        t1.start();
        t1.succeed("文件内容是 x");
        return plan;
    }

    @Test
    @DisplayName("存了能读回来，产出一并回来")
    void savesAndLoads() {
        PlanStore store = store();
        Path ws = tmp.resolve("proj");

        store.save(runningPlan("干件事"), ws);
        Plan back = store.load(ws).orElseThrow();

        assertEquals("干件事", back.goal());
        assertEquals("文件内容是 x", back.graph().get("t1").result());
        assertEquals(TaskStatus.PENDING, back.graph().get("t2").status());
    }

    @Test
    @DisplayName("按工作区隔离：两个项目各存各的，互不覆盖")
    void isolatesByWorkspace() {
        PlanStore store = store();
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");

        store.save(runningPlan("A 的活"), a);
        store.save(runningPlan("B 的活"), b);

        assertEquals("A 的活", store.load(a).orElseThrow().goal());
        assertEquals("B 的活", store.load(b).orElseThrow().goal());
    }

    @Test
    @DisplayName("同名目录在不同路径下不撞车——光用目录名的话它们会互相覆盖")
    void sameNameDifferentPathDoNotCollide() {
        PlanStore store = store();
        Path one = tmp.resolve("x/demo");
        Path two = tmp.resolve("y/demo");

        assertNotEquals(store.pathFor(one), store.pathFor(two));

        store.save(runningPlan("第一个"), one);
        store.save(runningPlan("第二个"), two);

        assertEquals("第一个", store.load(one).orElseThrow().goal());
        assertEquals("第二个", store.load(two).orElseThrow().goal());
    }

    @Test
    @DisplayName("文件名里看得出是哪个项目")
    void fileNameIsReadable() {
        assertTrue(store().pathFor(tmp.resolve("myagent-backend")).getFileName().toString()
                .startsWith("myagent-backend-"));
    }

    @Test
    @DisplayName("目录名里的空格和中文不会漏进文件名")
    void sanitizesWeirdDirectoryNames() {
        String name = store().pathFor(tmp.resolve("我的 项目")).getFileName().toString();

        assertTrue(name.matches("[A-Za-z0-9._-]+"), name);
    }

    @Test
    @DisplayName("没存过就是没有，不抛")
    void emptyWhenNothingSaved() {
        assertEquals(Optional.empty(), store().load(tmp.resolve("never")));
        assertEquals(Optional.empty(), store().peek(tmp.resolve("never")));
    }

    @Test
    @DisplayName("存档被改坏了：当作没有，绝不让一份半截的图跑起来")
    void brokenFileIsTreatedAsAbsent() throws IOException {
        PlanStore store = store();
        Path ws = tmp.resolve("proj");
        store.save(runningPlan("干件事"), ws);

        Files.writeString(store.pathFor(ws), "{\"format\": 1, \"tasks\": [ 这不是 JSON");

        assertEquals(Optional.empty(), store.load(ws));
    }

    @Test
    @DisplayName("跑完的计划不给续")
    void terminalPlanIsNotOffered() {
        PlanStore store = store();
        Path ws = tmp.resolve("proj");
        Plan plan = runningPlan("干件事");
        plan.graph().get("t2").start();
        plan.graph().get("t2").succeed("好了");
        plan.complete();

        store.save(plan, ws);

        assertEquals(Optional.empty(), store.load(ws));
    }

    @Test
    @DisplayName("删档之后就真没了")
    void deletes() {
        PlanStore store = store();
        Path ws = tmp.resolve("proj");
        store.save(runningPlan("干件事"), ws);

        store.delete(ws);

        assertEquals(Optional.empty(), store.load(ws));
        assertFalse(Files.exists(store.pathFor(ws)));
    }

    @Test
    @DisplayName("反复存只留一份，不攒历史——续跑只可能有一个答案")
    void keepsOnlyOneFilePerWorkspace() throws IOException {
        PlanStore store = store();
        Path ws = tmp.resolve("proj");

        store.save(runningPlan("第一次"), ws);
        store.save(runningPlan("第二次"), ws);

        try (var files = Files.list(tmp.resolve("plans"))) {
            assertEquals(1, files.count());
        }
        assertEquals("第二次", store.load(ws).orElseThrow().goal());
    }

    @Test
    @DisplayName("写完不留临时文件")
    void leavesNoTempFile() throws IOException {
        PlanStore store = store();
        store.save(runningPlan("干件事"), tmp.resolve("proj"));

        try (var files = Files.list(tmp.resolve("plans"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    @DisplayName("存不下也不能把计划带走：目录位置被一个文件占了，照样不抛")
    void neverThrowsOnSaveFailure() throws IOException {
        Files.writeString(tmp.resolve("blocked"), "我是个文件，不是目录");
        PlanStore store = new PlanStore(tmp.resolve("blocked"));

        assertDoesNotThrow(() -> store.save(runningPlan("干件事"), tmp.resolve("proj")));
    }

    @Test
    @DisplayName("null 工作区一律当没有，不炸")
    void toleratesNulls() {
        PlanStore store = store();

        assertDoesNotThrow(() -> store.save(runningPlan("x"), null));
        assertDoesNotThrow(() -> store.save(null, tmp));
        assertDoesNotThrow(() -> store.delete(null));
        assertEquals(Optional.empty(), store.load(null));
    }
}
