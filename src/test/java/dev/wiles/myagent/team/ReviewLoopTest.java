package dev.wiles.myagent.team;

import dev.wiles.myagent.plan.AgentRoles;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskOutcome;
import dev.wiles.myagent.plan.TaskRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 收尾审查的回路，全用假的主 agent 和假的审查方——一次模型调用都不发。
 * 工作区是真的：「改没改文件」「退回最好的一轮」都靠影子仓库，假不了。
 */
class ReviewLoopTest {

    @TempDir
    Path ws;

    /** 假主 agent：按剧本改文件、记下每次收到的话。 */
    static final class FakeMain implements ReviewLoop.MainTurn {
        final List<String> inputs = new ArrayList<>();
        final Function<Integer, String> onTurn;     // 第几轮（从 1 起）→ 收尾说明；改文件在里面做
        boolean failed;

        FakeMain(Function<Integer, String> onTurn) {
            this.onTurn = onTurn;
        }

        @Override
        public String run(String input) {
            inputs.add(input);
            return onTurn.apply(inputs.size());
        }

        @Override
        public boolean failed() {
            return failed;
        }
    }

    /** 假审查方：按剧本逐轮给结论，并记下拿到的任务。 */
    static final class FakeReviewer implements TaskRunner {
        final List<Task> seen = new CopyOnWriteArrayList<>();
        final List<TaskOutcome> script;

        FakeReviewer(TaskOutcome... script) {
            this.script = new ArrayList<>(List.of(script));
        }

        @Override
        public TaskOutcome run(Task task, List<Task> upstream) {
            seen.add(task);
            return script.remove(0);
        }
    }

    private void write(String name, String content) {
        try {
            Files.writeString(ws.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(String name) throws IOException {
        return Files.readString(ws.resolve(name));
    }

    @Test
    @DisplayName("没改文件就不审：问答、只读调研没有可审的")
    void noChangeNoReview() {
        FakeReviewer reviewer = new FakeReviewer();
        FakeMain main = new FakeMain(turn -> "就是这样");

        ReviewLoop.Outcome out = new ReviewLoop(reviewer, ws).run(main, "这个项目怎么跑测试");

        assertEquals(0, out.reviews());
        assertTrue(reviewer.seen.isEmpty());
        assertEquals("就是这样", out.text());
    }

    @Test
    @DisplayName("改了文件、审查通过：审一轮，材料里有程序算的 diff，审查方是带 bash 的 acceptor")
    void passOnFirstReview() {
        FakeReviewer reviewer = new FakeReviewer(TaskOutcome.ok("全过\n测试: 通过 10 / 共 10\n通过"));
        FakeMain main = new FakeMain(turn -> {
            write("a.py", "fixed\n");
            return "改好了 a.py";
        });

        ReviewLoop.Outcome out = new ReviewLoop(reviewer, ws).run(main, "修 a.py");

        assertEquals(1, out.reviews());
        assertEquals(List.of("通过"), out.verdicts());
        assertEquals(List.of("10/10"), out.scores());
        assertEquals(1, main.inputs.size(), "通过了就不再打扰主 agent");
        Task task = reviewer.seen.get(0);
        assertSame(AgentRoles.ACCEPTOR, task.role());
        assertTrue(task.description().contains("a.py") && task.description().contains("+fixed"),
                "审查看的是程序算出来的 diff: " + task.description());
        assertTrue(task.description().contains("改好了 a.py"), "主 agent 的收尾说明作参考");
    }

    @Test
    @DisplayName("打回：意见原样交回同一个主 agent 接着修，修完再审一次")
    void reworkGoesBackToTheSameAgent() {
        FakeReviewer reviewer = new FakeReviewer(
                TaskOutcome.ok("R2 没做到：空输入没报错，见 a.py:3\n测试: 通过 8 / 共 10\n重新执行"),
                TaskOutcome.ok("测试: 通过 10 / 共 10\n通过"));
        FakeMain main = new FakeMain(turn -> {
            write("a.py", "v" + turn + "\n");
            return "第 " + turn + " 轮做完";
        });

        ReviewLoop.Outcome out = new ReviewLoop(reviewer, ws).run(main, "修 a.py");

        assertEquals(2, out.reviews());
        assertEquals(List.of("重新执行", "通过"), out.verdicts());
        assertEquals(2, main.inputs.size());
        assertTrue(main.inputs.get(1).startsWith("[独立审查意见]"));
        assertTrue(main.inputs.get(1).contains("空输入没报错，见 a.py:3"), "审查原话要带给主 agent");
        assertEquals("第 2 轮做完", out.text());
        assertTrue(reviewer.seen.get(1).description().contains("第 2 轮做完"));
    }

    @Test
    @DisplayName("最多审两轮：第二轮还打回就收手——不留没被审过的修改")
    void stopsAfterMaxReviews() {
        FakeReviewer reviewer = new FakeReviewer(
                TaskOutcome.ok("还有问题\n重新执行"),
                TaskOutcome.ok("还是有问题\n重新执行"));
        FakeMain main = new FakeMain(turn -> {
            write("a.py", "v" + turn + "\n");
            return "做完";
        });

        ReviewLoop.Outcome out = new ReviewLoop(reviewer, ws).run(main, "修 a.py");

        assertEquals(ReviewLoop.MAX_REVIEWS, out.reviews());
        assertEquals(2, main.inputs.size(), "只修一次：第二次修改没人审，不做");
    }

    @Test
    @DisplayName("修完测试反而变少：工作区退回修之前，并说明一句")
    void regressionIsRolledBack() throws IOException {
        write("a.py", "original\n");
        FakeReviewer reviewer = new FakeReviewer(
                TaskOutcome.ok("测试: 通过 8 / 共 10\n重新执行"),
                TaskOutcome.ok("测试: 通过 5 / 共 10\n重新执行"));
        FakeMain main = new FakeMain(turn -> {
            if (turn == 1) {
                write("a.py", "good\n");
            } else {
                write("a.py", "worse\n");
                write("junk.py", "x\n");
            }
            return "做完";
        });

        ReviewLoop.Outcome out = new ReviewLoop(reviewer, ws).run(main, "修 a.py");

        assertTrue(out.restored());
        assertEquals("good\n", read("a.py"), "退回第一轮审查时的样子");
        assertFalse(Files.exists(ws.resolve("junk.py")));
        assertTrue(out.text().contains("已退回"));
    }

    @Test
    @DisplayName("主 agent 自己没收口：不审，对着半成品挑刺没有意义")
    void failedMainIsNotReviewed() {
        FakeReviewer reviewer = new FakeReviewer();
        FakeMain main = new FakeMain(turn -> {
            write("a.py", "half\n");
            return "撞上限了";
        });
        main.failed = true;

        ReviewLoop.Outcome out = new ReviewLoop(reviewer, ws).run(main, "修 a.py");

        assertEquals(0, out.reviews());
        assertTrue(reviewer.seen.isEmpty());
    }

    @Test
    @DisplayName("审查自己没跑成：主 agent 的结果原样交出，不判失败")
    void failedReviewKeepsTheResult() throws IOException {
        FakeReviewer reviewer = new FakeReviewer(TaskOutcome.failed("达到最大步数 28"));
        FakeMain main = new FakeMain(turn -> {
            write("a.py", "done\n");
            return "做完了";
        });

        ReviewLoop.Outcome out = new ReviewLoop(reviewer, ws).run(main, "修 a.py");

        assertEquals("做完了", out.text());
        assertEquals(List.of("没审成"), out.verdicts());
        assertEquals("done\n", read("a.py"));
        assertEquals(1, main.inputs.size());
    }

    @Test
    @DisplayName("审查途中按了停：意见不再交回去，主 agent 不会被拉起来再修一轮")
    void cancelDuringReviewStopsTheLoop() {
        ReviewLoop[] holder = new ReviewLoop[1];
        TaskRunner reviewer = (task, up) -> {
            holder[0].cancel();                 // 用户在审查跑着的时候按了停
            return TaskOutcome.ok("有问题\n重新执行");
        };
        FakeMain main = new FakeMain(turn -> {
            write("a.py", "v" + turn + "\n");
            return "做完";
        });
        holder[0] = new ReviewLoop(reviewer, ws);

        holder[0].run(main, "修 a.py");

        assertEquals(1, main.inputs.size(), "按停之后不再开新的一轮修改");
    }
}
