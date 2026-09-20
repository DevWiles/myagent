package dev.wiles.myagent.cli;

import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.PlanResult;
import dev.wiles.myagent.plan.PlanStatus;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskGraph;
import dev.wiles.myagent.plan.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanViewTest {

    @BeforeEach
    void plainText() {
        Ansi.setEnabled(false);      // 断言文本时不想跟转义序列较劲
    }

    private static Plan plan() {
        TaskGraph graph = TaskGraph.of(List.of(
                new Task("t1", "读 Task.java", TaskType.FILE_READ, List.of()),
                new Task("t2", "读 TaskTest", TaskType.FILE_READ, List.of()),
                new Task("t3", "加 retry 方法", TaskType.FILE_WRITE, List.of("t1", "t2")),
                new Task("t4", "跑 mvn test", TaskType.VERIFICATION, List.of("t3"))));
        return new Plan("给 Task 加上重试能力", graph);
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("计划面板：目标在最上面，任务按层排，同层的能一眼看出是并行的")
    void rendersPlanPanel() {
        String out = joined(PlanView.plan(plan(), 80));

        assertTrue(out.contains("给 Task 加上重试能力"), out);
        assertTrue(out.contains("读 Task.java"));
        assertTrue(out.contains("跑 mvn test"));
        assertTrue(out.indexOf("读 Task.java") < out.indexOf("加 retry 方法"),
                "上游要排在下游前面");
    }

    @Test
    @DisplayName("每个任务都标出角色和 id，出问题时用户能指着说是哪一个")
    void showsRoleAndId() {
        String out = joined(PlanView.plan(plan(), 80));

        assertTrue(out.contains("t3"), out);
        assertTrue(out.contains("编码"), "coder 要有个看得懂的标签：" + out);
        assertTrue(out.contains("验证"), out);
    }

    @Test
    @DisplayName("有依赖的任务要标出它等谁，这是 DAG 和普通清单的区别")
    void showsDependencies() {
        String out = joined(PlanView.plan(plan(), 80));

        assertTrue(out.contains("t1") && out.contains("t2"));
        assertTrue(out.matches("(?s).*t3.*(t1.*t2|t1, t2).*"), "t3 那行要写明依赖：" + out);
    }

    @Test
    @DisplayName("任务开始和结束各一行，状态符号能区分成功、失败、跳过")
    void rendersTaskLines() {
        Task task = new Task("t1", "读 Task.java", TaskType.FILE_READ, List.of());

        String started = PlanView.taskStarted(task);
        assertTrue(started.contains("t1") && started.contains("读 Task.java"));

        task.start();
        task.succeed("读完了");
        assertTrue(PlanView.taskFinished(task).contains("✓"));

        Task failed = new Task("t2", "改代码", TaskType.FILE_WRITE, List.of());
        failed.start();
        failed.fail("写不进去");
        assertTrue(PlanView.taskFinished(failed).contains("✗"));
        assertTrue(PlanView.taskFinished(failed).contains("写不进去"), "失败原因要露出来");

        Task skipped = new Task("t3", "跑测试", TaskType.VERIFICATION, List.of());
        skipped.skip("上游 t2 失败");
        assertTrue(PlanView.taskFinished(skipped).contains("⊘"));
    }

    @Test
    @DisplayName("小结把三个数字和整体状态都说清楚")
    void rendersSummary() {
        Plan plan = plan();
        plan.start();
        plan.fail();

        String out = joined(PlanView.summary(plan, new PlanResult(PlanStatus.FAILED, 2, 1, 1)));

        assertTrue(out.contains("2"));
        assertTrue(out.contains("失败"), out);
        assertTrue(out.contains("跳过"), out);
    }

    @Test
    @DisplayName("全成功时不必强调失败和跳过，报告要干净")
    void successSummaryStaysClean() {
        Plan plan = plan();
        plan.start();
        plan.complete();

        String out = joined(PlanView.summary(plan, new PlanResult(PlanStatus.COMPLETED, 4, 0, 0)));

        assertTrue(out.contains("4"));
        assertFalse(out.contains("跳过"), "没有跳过就别提跳过：" + out);
    }

    @Test
    @DisplayName("超长描述要截断，否则窄终端上会折行把整个面板撑乱")
    void truncatesLongDescriptions() {
        TaskGraph graph = TaskGraph.of(List.of(
                new Task("t1", "读".repeat(200), TaskType.FILE_READ, List.of())));

        for (String line : PlanView.plan(new Plan("目标", graph), 60)) {
            assertTrue(line.length() <= 60, "这行超宽了(" + line.length() + "): " + line);
        }
    }
}
