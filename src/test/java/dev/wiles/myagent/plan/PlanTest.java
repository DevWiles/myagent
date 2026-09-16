package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanTest {

    private static TaskGraph graph() {
        return TaskGraph.of(List.of(
                new Task("n1", "读代码", TaskType.FILE_READ, List.of()),
                new Task("n2", "改代码", TaskType.FILE_WRITE, List.of("n1"))));
    }

    private static Plan plan() {
        return new Plan("把 Task 加上重试", graph());
    }

    @Test
    @DisplayName("刚规划出来是 CREATED，还没开始跑")
    void startsCreated() {
        Plan plan = plan();

        assertEquals(PlanStatus.CREATED, plan.status());
        assertEquals("把 Task 加上重试", plan.goal());
        assertEquals(2, plan.graph().size());
        assertEquals(0, plan.startTime());
        assertEquals(0, plan.endTime());
        assertFalse(plan.isTerminal());
        assertEquals(-1, plan.durationMillis());
    }

    @Test
    @DisplayName("目标不能为空——它是给用户看「模型理解对了没」的唯一依据")
    void rejectsBlankGoal() {
        assertThrows(IllegalArgumentException.class, () -> new Plan(" ", graph()));
        assertThrows(IllegalArgumentException.class, () -> new Plan("目标", null));
    }

    @Test
    @DisplayName("CREATED → RUNNING → COMPLETED")
    void happyPath() {
        Plan plan = plan();

        plan.start();
        assertEquals(PlanStatus.RUNNING, plan.status());
        assertTrue(plan.startTime() > 0);
        assertFalse(plan.isTerminal());

        plan.complete();
        assertEquals(PlanStatus.COMPLETED, plan.status());
        assertTrue(plan.endTime() >= plan.startTime());
        assertTrue(plan.isTerminal());
        assertTrue(plan.durationMillis() >= 0);
    }

    @Test
    @DisplayName("有任务失败，整个计划就算 FAILED")
    void failedPath() {
        Plan plan = plan();
        plan.start();

        plan.fail();

        assertEquals(PlanStatus.FAILED, plan.status());
        assertTrue(plan.isTerminal());
    }

    @Test
    @DisplayName("取消是独立一种终态，不能混进失败里")
    void cancelledPath() {
        Plan plan = plan();
        plan.start();

        plan.cancel();

        assertEquals(PlanStatus.CANCELLED, plan.status());
        assertTrue(plan.isTerminal());
        assertTrue(plan.endTime() > 0);
    }

    @Test
    @DisplayName("还没开跑就被取消：直接了结，不必先假装跑过一轮")
    void cancelBeforeStart() {
        Plan plan = plan();

        plan.cancel();

        assertEquals(PlanStatus.CANCELLED, plan.status());
        assertEquals(0, plan.startTime(), "没跑过就不该有开始时间");
        assertTrue(plan.endTime() > 0);
    }

    @Test
    @DisplayName("非法迁移当场抛：跑完的计划不能重启，没开跑的不能宣布完成")
    void rejectsIllegalTransitions() {
        Plan done = plan();
        done.start();
        done.complete();

        assertThrows(IllegalStateException.class, done::start);
        assertThrows(IllegalStateException.class, done::complete);
        assertThrows(IllegalStateException.class, done::fail);
        assertThrows(IllegalStateException.class, done::cancel);

        Plan fresh = plan();
        assertThrows(IllegalStateException.class, fresh::complete);
        assertThrows(IllegalStateException.class, fresh::fail);
    }

    @Test
    @DisplayName("终态判定跟任务那套一致")
    void terminalStatuses() {
        assertFalse(PlanStatus.CREATED.isTerminal());
        assertFalse(PlanStatus.RUNNING.isTerminal());
        assertTrue(PlanStatus.COMPLETED.isTerminal());
        assertTrue(PlanStatus.FAILED.isTerminal());
        assertTrue(PlanStatus.CANCELLED.isTerminal());
    }
}
