package dev.wiles.myagent.team;

import dev.wiles.myagent.plan.AgentRole;
import dev.wiles.myagent.plan.AgentRoles;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskOutcome;
import dev.wiles.myagent.plan.TaskRunner;
import dev.wiles.myagent.plan.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 整套编排都用假执行体测——一次模型调用都不发。
 *
 * <p>这里盯的全是「圆桌和计划不一样」的那几处：一席倒了会照开、取消之后不合议、
 * 合议看得到谁缺席。它们每一条要是错了，症状都是<b>屏幕上有一份看起来正常的合议</b>，
 * 而它建立在用户不知道的缺口上。
 */
class RoundtableTest {

    /** 记下每一席被交代了什么，并按剧本给产出。 */
    static final class FakeRunner implements TaskRunner {
        final List<Task> ran = new ArrayList<>();
        final List<List<Task>> upstreams = new ArrayList<>();
        Function<Task, TaskOutcome> script = task -> TaskOutcome.ok(task.role().id() + " 说了点什么");

        @Override
        public TaskOutcome run(Task task, List<Task> upstream) {
            ran.add(task);
            upstreams.add(List.copyOf(upstream));
            return script.apply(task);
        }

        Task seatOf(String roleId) {
            return ran.stream().filter(t -> t.role().id().equals(roleId)).findFirst().orElseThrow();
        }

        Task verdict() {
            return ran.stream().filter(t -> t.id().equals("verdict")).findFirst().orElseThrow();
        }
    }

    private static final List<AgentRole> TWO =
            List.of(AgentRoles.EXPLORER, AgentRoles.REVIEWER);

    private static Roundtable table(FakeRunner runner) {
        return new Roundtable(runner, CastPicker.fixed(TWO, "测试用"));
    }

    private static TeamResult hold(FakeRunner runner) {
        return table(runner).hold("这套角色实现有没有漏", List.of(), false);
    }

    // ── 正常一场 ────────────────────────────────────────────────

    @Test
    @DisplayName("每一席各跑一次，最后多一次合议")
    void everySeatSpeaksThenTheyReconcile() {
        FakeRunner runner = new FakeRunner();

        TeamResult result = hold(runner);

        assertEquals(TeamStatus.COMPLETED, result.status());
        assertEquals(List.of("explorer", "reviewer", "analyst"),
                runner.ran.stream().map(t -> t.role().id()).toList());
        assertEquals(2, result.seats().size(), "合议不算一席");
        assertNotNull(result.verdict());
    }

    @Test
    @DisplayName("几席之间互不通气——谁看过别人的意见，他那一遍就不独立了")
    void seatsDoNotSeeEachOther() {
        FakeRunner runner = new FakeRunner();

        hold(runner);

        assertTrue(runner.upstreams.get(0).isEmpty());
        assertTrue(runner.upstreams.get(1).isEmpty(), "第二席不该拿到第一席的产出");
    }

    @Test
    @DisplayName("各席原话进了合议的材料，而且标明了是谁说的")
    void verdictMaterialSaysWhoSaidWhat() {
        FakeRunner runner = new FakeRunner();
        runner.script = task -> TaskOutcome.ok(
                AgentRoles.EXPLORER.equals(task.role()) ? "事实是 A" : "问题是 B");

        hold(runner);

        String material = runner.verdict().description();
        assertTrue(material.contains("事实是 A"), material);
        assertTrue(material.contains("问题是 B"), material);
        assertTrue(material.contains("调研"), "合议得知道哪句是调研说的: " + material);
        assertTrue(material.contains("审查"), material);
    }

    @Test
    @DisplayName("每一席拿到的是自己的角色，工具和提示词才会真的不一样")
    void eachSeatCarriesItsOwnRole() {
        FakeRunner runner = new FakeRunner();

        hold(runner);

        assertEquals(AgentRoles.EXPLORER, runner.seatOf("explorer").role());
        assertEquals(AgentRoles.REVIEWER, runner.seatOf("reviewer").role());
        assertEquals(AgentRoles.ANALYST, runner.verdict().role(), "合议该是不带工具的那位");
    }

    // ── 一席倒了 ────────────────────────────────────────────────

    @Test
    @DisplayName("一席哑火，会照开——剩下那席的意见还是有用的")
    void oneSeatFailingDoesNotKillTheMeeting() {
        FakeRunner runner = new FakeRunner();
        runner.script = task -> AgentRoles.EXPLORER.equals(task.role())
                ? TaskOutcome.failed("读文件超时")
                : TaskOutcome.ok("照样有话说");

        TeamResult result = hold(runner);

        assertEquals(TeamStatus.PARTIAL, result.status(),
                "既不能报完成（缺了一个视角），也不能报失败（合议是真的）");
        assertNotNull(result.verdict());
        assertEquals(1, result.spoke());
        assertEquals(1, result.silent());
    }

    @Test
    @DisplayName("缺席要写进合议的材料——不然两个人的共识会被当成三个人的")
    void absenceIsToldToTheSynthesizer() {
        FakeRunner runner = new FakeRunner();
        runner.script = task -> AgentRoles.EXPLORER.equals(task.role())
                ? TaskOutcome.failed("读文件超时")
                : TaskOutcome.ok("照样有话说");

        hold(runner);

        String material = runner.verdict().description();
        assertTrue(material.contains("没能给出意见"), material);
        assertTrue(material.contains("读文件超时"), material);
    }

    @Test
    @DisplayName("一席都没说成话就别合议了——没有材料的合议只会是一段空话")
    void allSilentMeansNoVerdict() {
        FakeRunner runner = new FakeRunner();
        runner.script = task -> TaskOutcome.failed("模型挂了");

        TeamResult result = hold(runner);

        assertEquals(TeamStatus.FAILED, result.status());
        assertNull(result.verdict());
        assertEquals(2, runner.ran.size(), "不该多花一次合议调用");
    }

    @Test
    @DisplayName("执行体自己抛异常，也只算这一席倒了，不把整场会带走")
    void runnerExplosionIsJustOneSeatDown() {
        FakeRunner runner = new FakeRunner();
        runner.script = task -> {
            if (AgentRoles.EXPLORER.equals(task.role())) {
                throw new IllegalStateException("执行体没兜住");
            }
            return TaskOutcome.ok("我还好");
        };

        TeamResult result = hold(runner);

        assertEquals(TeamStatus.PARTIAL, result.status());
        assertTrue(result.seats().get(0).error().contains("执行体没兜住"),
                result.seats().get(0).error());
    }

    @Test
    @DisplayName("合议那一步自己倒了就是失败——没有结论可看，不能报成功")
    void failedSynthesisIsAFailedMeeting() {
        FakeRunner runner = new FakeRunner();
        runner.script = task -> task.id().equals("verdict")
                ? TaskOutcome.failed("合议调用挂了")
                : TaskOutcome.ok("说了点什么");

        TeamResult result = hold(runner);

        assertEquals(TeamStatus.FAILED, result.status());
        assertNull(result.verdict(), "跑砸的合议不能当成结论端出去");
    }

    // ── 取消 ────────────────────────────────────────────────────

    @Test
    @DisplayName("中途叫停：剩下的席位跳过，也不再花钱合议")
    void cancelSkipsTheRestAndTheVerdict() {
        FakeRunner runner = new FakeRunner();
        Roundtable table = table(runner);
        runner.script = task -> {
            table.cancel();
            return TaskOutcome.ok("第一席说完了才被叫停");
        };

        TeamResult result = table.hold("议题", List.of(), false);

        assertEquals(TeamStatus.CANCELLED, result.status());
        assertEquals(1, runner.ran.size(), "叫停之后不该再开新席位，也不该合议");
        assertEquals(TaskStatus.SKIPPED, result.seats().get(1).status());
        assertEquals(TaskStatus.SUCCEEDED, result.seats().get(0).status(),
                "已经说过的那席要留着——花掉的钱至少还能看");
    }

    @Test
    @DisplayName("上一场的取消标志不能带到下一场")
    void cancelDoesNotLeakIntoTheNextMeeting() {
        FakeRunner runner = new FakeRunner();
        Roundtable table = table(runner);
        table.cancel();

        assertEquals(TeamStatus.COMPLETED, table.hold("新议题", List.of(), false).status());
    }

    // ── 谁能坐上桌 ──────────────────────────────────────────────

    @Test
    @DisplayName("没有工具的角色坐不上桌：圆桌上几席互不通气，它什么材料都拿不到")
    void toollessRolesAreNotSeated() {
        assertFalse(Roundtable.seatable(false).contains(AgentRoles.ANALYST),
                "analyst 靠上游材料干活，而圆桌一席没有上游——它只能说「我什么都没看到」。"
                        + "它真正的位置是合议那一步，那里材料是齐的");
        assertTrue(Roundtable.seatable(false).stream().noneMatch(r -> r.tools().isEmpty()));
    }

    @Test
    @DisplayName("会写文件的角色坐不上桌：几个人各改一遍同一处，后写的会盖掉先写的")
    void writersAreNotSeated() {
        List<AgentRole> seatable = Roundtable.seatable(false);

        assertFalse(seatable.contains(AgentRoles.CODER));
        assertTrue(seatable.contains(AgentRoles.REVIEWER));
        assertTrue(seatable.contains(AgentRoles.VERIFIER),
                "verifier 有 bash 但不写文件——它给的是事实核查那一视角，挡掉圆桌就废一半");
    }

    @Test
    @DisplayName("只读模式下再砍一刀：会动工作区的一个都不留")
    void readOnlyDropsEveryoneWhoTouchesTheWorkspace() {
        List<AgentRole> seatable = Roundtable.seatable(true);

        assertFalse(seatable.isEmpty());
        assertTrue(seatable.stream().noneMatch(AgentRole::mutates));
        assertFalse(seatable.contains(AgentRoles.VERIFIER), "它有 bash，只读模式下不放行");
        assertTrue(seatable.contains(AgentRoles.EXPLORER));
    }

    @Test
    @DisplayName("用户指名了谁就是谁，不再问模型——他已经表过态了")
    void namedCastSkipsThePicker() {
        FakeRunner runner = new FakeRunner();
        CastPicker boom = (goal, available) -> {
            throw new AssertionError("用户都指名了，还去问模型就是白烧一次调用");
        };

        TeamResult result = new Roundtable(runner, boom)
                .hold("议题", List.of(AgentRoles.VERIFIER, AgentRoles.SCOUT), false);

        assertEquals(List.of("verifier", "scout"), result.cast().ids());
    }

    @Test
    @DisplayName("指名的角色也要过「谁能坐上桌」这道闸——闸不能靠调用方自觉")
    void namedCastStillGoesThroughTheDoor() {
        FakeRunner runner = new FakeRunner();

        TeamResult result = new Roundtable(runner, CastPicker.fixed(List.of(), ""))
                .hold("议题", List.of(AgentRoles.CODER, AgentRoles.REVIEWER, AgentRoles.EXPLORER),
                        false);

        assertEquals(List.of("reviewer", "explorer"), result.cast().ids(),
                "coder 会写文件，几个人各改一遍同一处就是互相覆盖");
    }

    @Test
    @DisplayName("只读模式下指名一个会动工作区的角色，同样挡掉")
    void namedCastRespectsReadOnly() {
        FakeRunner runner = new FakeRunner();

        TeamResult result = new Roundtable(runner, CastPicker.fixed(List.of(), ""))
                .hold("议题", List.of(AgentRoles.VERIFIER, AgentRoles.EXPLORER), true);

        assertEquals(List.of("explorer"), result.cast().ids());
    }

    @Test
    @DisplayName("执行体自己把任务推到终态了，也不能把整场会带走")
    void runnerThatTerminalizesTheTaskDoesNotKillTheMeeting() {
        FakeRunner runner = new FakeRunner();
        runner.script = task -> {
            task.succeed("我自己把状态改了");     // 执行体的 bug，但会得照开
            return TaskOutcome.ok("产出");
        };

        assertDoesNotThrow(() -> hold(runner));
    }

    @Test
    @DisplayName("同一个角色坐两次就是白烧一次调用，去重")
    void duplicateSeatsAreDropped() {
        FakeRunner runner = new FakeRunner();

        TeamResult result = new Roundtable(runner, CastPicker.fixed(List.of(), ""))
                .hold("议题", List.of(AgentRoles.REVIEWER, AgentRoles.REVIEWER), false);

        assertEquals(List.of("reviewer"), result.cast().ids());
    }

    // ── 事件 ────────────────────────────────────────────────────

    @Test
    @DisplayName("名单、每一席、合议、收工都有事件——界面全靠它画")
    void tellsTheUiEverythingItNeeds() {
        FakeRunner runner = new FakeRunner();
        List<String> seen = new ArrayList<>();

        table(runner).listener(new TeamListener() {
            public void onCastChosen(String goal, Cast cast) {
                seen.add("cast:" + String.join("+", cast.ids()));
            }

            public void onSeatStarted(Task seat) {
                seen.add("start:" + seat.role().id());
            }

            public void onSeatFinished(Task seat) {
                seen.add("done:" + seat.role().id());
            }

            public void onSynthesisStarted(Task synthesis) {
                seen.add("synth");
            }

            public void onFinished(TeamResult result) {
                seen.add("finished:" + result.status());
            }
        }).hold("议题", List.of(), false);

        assertEquals(List.of(
                "cast:explorer+reviewer",
                "start:explorer", "done:explorer",
                "start:reviewer", "done:reviewer",
                "synth",
                "finished:COMPLETED"), seen);
    }

    @Test
    @DisplayName("哑火的那席也要发结束事件，否则界面上它永远在转圈")
    void silentSeatsStillFinish() {
        FakeRunner runner = new FakeRunner();
        runner.script = task -> TaskOutcome.failed("挂了");
        List<String> finished = new ArrayList<>();

        table(runner).listener(new TeamListener() {
            public void onSeatFinished(Task seat) {
                finished.add(seat.role().id());
            }
        }).hold("议题", List.of(), false);

        assertEquals(List.of("explorer", "reviewer"), finished);
    }
}
