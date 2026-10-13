package dev.wiles.myagent.team;

import dev.wiles.myagent.plan.AgentRoles;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskOutcome;
import dev.wiles.myagent.plan.TaskRunner;
import dev.wiles.myagent.plan.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 整套编排都用假执行体测——一次模型调用都不发。
 *
 * <p>这里盯的全是回路自己的规矩：规划拆出的任务怎么落地并行、验收的三种结论各通向哪里、
 * 轮次预算怎么收口、worker 倒了为什么不结束流水线。它们每一条要是错了，
 * 症状都是<b>屏幕上有一个看起来正常的结论</b>，而它建立在用户不知道的缺口上——
 * 比如把没验收的施工报成完成，或者验收明明说了重拟、系统却按原方案又干了一遍。
 *
 * <p>worker 阶段跑的是真 {@code PlanExecutor}（几个任务并行），
 * 所以记账必须线程安全；规划/验收席也在独立线程上跑（为了计时），同理。
 */
class PipelineTest {

    /** 记下每道工序被交代了什么、拿到了什么上游，并按剧本给产出。 */
    static final class FakeRunner implements TaskRunner {
        final List<Task> ran = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<String, List<Task>> upstreams = new ConcurrentHashMap<>();
        BiFunction<Task, List<Task>, TaskOutcome> script =
                (task, upstream) -> TaskOutcome.ok(task.id() + " 干完了");

        @Override
        public TaskOutcome run(Task task, List<Task> upstream) {
            ran.add(task);
            upstreams.put(task.id(), List.copyOf(upstream));
            return script.apply(task, upstream);
        }

        Task stageOf(String id) {
            return ran.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
        }

        boolean ranId(String id) {
            return ran.stream().anyMatch(t -> t.id().equals(id));
        }
    }

    /** 规划席的标准剧本：拆出两个互不依赖的任务。 */
    private static TaskOutcome plans(Task plan) {
        return TaskOutcome.planned("方案: 分两处改", List.of(
                plan.subTask("t1", "改配置读取", AgentRoles.CODER, List.of(), "读到环境变量"),
                plan.subTask("t2", "查现有用法", AgentRoles.EXPLORER, List.of(), "列出调用点")));
    }

    /** 默认剧本：规划拆两个任务，worker 都成，验收通过。 */
    private static FakeRunner happyRunner() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return plans(task);
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok("都对\n通过");
            }
            return TaskOutcome.ok(task.id() + " 干完了");
        };
        return runner;
    }

    private static TeamResult run(FakeRunner runner) {
        return new Pipeline(runner).run("给配置模块加环境变量覆盖");
    }

    // ── 正常一轮 ────────────────────────────────────────────────

    @Test
    @DisplayName("规划拆出的任务全都跑了，验收通过就是 COMPLETED")
    void planExecuteReviewPass() {
        FakeRunner runner = happyRunner();

        TeamResult result = run(runner);

        assertEquals(TeamStatus.COMPLETED, result.status());
        assertEquals(1, result.rounds());
        assertTrue(runner.ranId("plan"));
        assertTrue(runner.ranId("t1"));
        assertTrue(runner.ranId("t2"));
        assertTrue(runner.ranId("review"));
        assertEquals("都对\n通过", result.verdict());
        // 座位表 = 规划 + 两个 worker + 验收，全在、全成
        assertEquals(4, result.stages().size());
        assertEquals(4, result.spoke());
    }

    @Test
    @DisplayName("每个 worker 的上游里都有方案席——方案不是给用户看的，是给工人开工用的")
    void workersStandOnThePlan() {
        FakeRunner runner = happyRunner();

        run(runner);

        for (String id : List.of("t1", "t2")) {
            assertTrue(runner.upstreams.get(id).stream().anyMatch(t -> t.id().equals("plan")),
                    id + " 的上游里该有方案席");
        }
    }

    @Test
    @DisplayName("worker 之间的依赖照拆出来的图走：下游等上游、还拿得到它的产出")
    void workerDependenciesAreHonored() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return TaskOutcome.planned("方案", List.of(
                        task.subTask("t1", "先查清楚", AgentRoles.EXPLORER, List.of(), "查到"),
                        task.subTask("t2", "再动手改", AgentRoles.CODER, List.of("t1"), "改完")));
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok("通过");
            }
            return TaskOutcome.ok(task.id() + " 干完了");
        };

        run(runner);

        assertTrue(runner.ran.indexOf(runner.stageOf("t1")) < runner.ran.indexOf(runner.stageOf("t2")),
                "t2 依赖 t1，不能先跑");
        assertTrue(runner.upstreams.get("t2").stream().anyMatch(t -> t.id().equals("t1")),
                "t2 的上游里该有 t1 的产出");
    }

    @Test
    @DisplayName("验收的材料里有方案和每个 worker 的产出，标着是谁干的")
    void reviewSeesEverything() {
        FakeRunner runner = happyRunner();

        run(runner);

        String brief = runner.stageOf("review").description();
        assertTrue(brief.contains("方案: 分两处改"), brief);
        assertTrue(brief.contains("t1"), brief);
        assertTrue(brief.contains("t1 干完了"), brief);
        assertTrue(brief.contains("t2 干完了"), brief);
    }

    @Test
    @DisplayName("验收席是 acceptor（带 bash），材料里有目标原话和程序算出来的改动清单")
    void reviewGetsGoalAndComputedChanges(@org.junit.jupiter.api.io.TempDir java.nio.file.Path ws)
            throws Exception {
        java.nio.file.Files.writeString(ws.resolve("merge.py"), "old\n");
        java.nio.file.Files.writeString(ws.resolve("tests.py"), "keep\n");
        FakeRunner runner = happyRunner();
        BiFunction<Task, List<Task>, TaskOutcome> base = runner.script;
        runner.script = (task, up) -> {
            if (task.id().equals("t1")) {
                try {
                    java.nio.file.Files.writeString(ws.resolve("merge.py"), "new\n");
                    java.nio.file.Files.writeString(ws.resolve("extra.py"), "x\n");
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return base.apply(task, up);
        };

        new Pipeline(runner).trackChanges(ws).run("给配置模块加环境变量覆盖");

        Task review = runner.stageOf("review");
        assertSame(AgentRoles.ACCEPTOR, review.role());
        String brief = review.description();
        assertTrue(brief.contains("【目标原话") && brief.contains("给配置模块加环境变量覆盖"), brief);
        assertTrue(brief.contains("### 修改 merge.py") && brief.contains("-old") && brief.contains("+new"), brief);
        assertTrue(brief.contains("### 新增 extra.py"), brief);
        assertFalse(brief.contains("tests.py"), "没改的文件不进清单");
        assertTrue(brief.indexOf("【改动清单") < brief.indexOf("【施工方案"), "事实在转述前面");
    }

    @Test
    @DisplayName("不拍快照时验收材料明说没检测到改动，而不是留白让模型猜")
    void reviewWithoutSnapshotSaysSo() {
        FakeRunner runner = happyRunner();

        run(runner);

        assertTrue(runner.stageOf("review").description().contains("没有检测到任何文件改动"));
    }

    // ── 三种结论 ────────────────────────────────────────────────

    @Test
    @DisplayName("重新执行：同一批任务按原方案再来一遍，新一轮的 id 带轮次前缀")
    void reworkRunsTheSameBlueprintAgain() {
        FakeRunner runner = new FakeRunner();
        List<String> verdicts = new ArrayList<>(List.of("漏了空值判断\n重新执行", "修好了\n通过"));
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return plans(task);
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok(verdicts.remove(0));
            }
            return TaskOutcome.ok(task.id() + " 干完了");
        };

        TeamResult result = run(runner);

        assertEquals(TeamStatus.COMPLETED, result.status());
        assertEquals(2, result.rounds());
        assertTrue(runner.ranId("plan"), "方案只拟了一次——重新执行不重拟");
        assertFalse(runner.ranId("plan2"));
        assertTrue(runner.ranId("r2.t1"), "第二轮的任务 id 带轮次前缀，座位表上不撞车");
        assertTrue(runner.ranId("r2.t2"));
        assertTrue(runner.ranId("review2"));
    }

    @Test
    @DisplayName("返工的 worker 上游里带着打回意见——不带的话它只会原样再错一遍")
    void reworkWorkersSeeTheRejection() {
        FakeRunner runner = new FakeRunner();
        List<String> verdicts = new ArrayList<>(List.of("漏了空值判断\n重新执行", "通过"));
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return plans(task);
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok(verdicts.remove(0));
            }
            return TaskOutcome.ok("干完了");
        };

        run(runner);

        assertTrue(runner.upstreams.get("r2.t1").stream()
                        .anyMatch(t -> t.id().equals("review")
                                && t.result().contains("漏了空值判断")),
                "返工任务的上游里该有上一轮验收的原话");
    }

    @Test
    @DisplayName("重新规划：开一道新的规划席，它站在打回意见上拟新方案")
    void replanSpawnsANewPlanSeat() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> {
            if (task.id().equals("plan")) {
                return TaskOutcome.planned("方案A", List.of(
                        task.subTask("t1", "走错的路", AgentRoles.CODER, List.of(), "完")));
            }
            if (task.id().equals("plan2")) {
                return TaskOutcome.planned("方案B", List.of(
                        task.subTask("n1", "换条路", AgentRoles.CODER, List.of(), "完")));
            }
            if (task.id().equals("review")) {
                return TaskOutcome.ok("方向拆错了\n重新规划");
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok("通过");
            }
            return TaskOutcome.ok("干完了");
        };

        TeamResult result = run(runner);

        assertEquals(TeamStatus.COMPLETED, result.status());
        assertEquals(2, result.rounds());
        assertTrue(runner.ranId("plan2"));
        assertTrue(runner.upstreams.get("plan2").stream()
                        .anyMatch(t -> t.id().equals("review")),
                "新规划席的上游里该有打回意见——不然它只是从零再想一遍");
        assertTrue(runner.ranId("r2.n1"), "新方案拆出的是新任务");
        assertFalse(runner.ranId("r2.t1"), "老方案的任务不该再跑");
    }

    @Test
    @DisplayName("轮次用完还打回：如实报 PARTIAL，问题清单留在 verdict 里")
    void budgetExhaustedIsPartialWithTheLastRejection() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return plans(task);
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok("还是不行\n重新执行");
            }
            return TaskOutcome.ok("干完了");
        };

        TeamResult result = run(runner);

        assertEquals(TeamStatus.PARTIAL, result.status());
        assertEquals(Pipeline.MAX_ROUNDS, result.rounds());
        assertEquals("还是不行\n重新执行", result.verdict(), "最后一轮的问题清单是最值钱的产出");
        assertFalse(runner.ranId("r" + (Pipeline.MAX_ROUNDS + 1) + ".t1"), "预算用完不再返工");
    }

    @Test
    @DisplayName("只认最后一行：过程里提到「重新规划」不算数")
    void onlyTheLastLineDecides() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return plans(task);
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok("我一度想要求重新规划，但细看方案没问题\n通过");
            }
            return TaskOutcome.ok("干完了");
        };

        assertEquals(TeamStatus.COMPLETED, run(runner).status());
    }

    @Test
    @DisplayName("一句话里俩词都有时，重的那个才是结论：重新规划优先于重新执行")
    void replanOutweighsRework() {
        assertEquals("REPLAN",
                Pipeline.verdictOf("与其重新执行不如重新规划").name());
        assertEquals("REWORK", Pipeline.verdictOf("打回").name(), "上一版的用词也认");
    }

    // ── 倒了怎么收场 ────────────────────────────────────────────

    @Test
    @DisplayName("第一轮方案就没拟成：什么都没干，FAILED")
    void failedFirstPlanIsFatal() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> TaskOutcome.failed("撞上步数上限");

        TeamResult result = run(runner);

        assertEquals(TeamStatus.FAILED, result.status());
        assertEquals(1, runner.ran.size(), "worker 和验收都不该跑");
        assertNull(result.verdict());
    }

    @Test
    @DisplayName("方案拆出来是空的按没拟成算——零个 worker 加一场对着空气的验收没有意义")
    void emptyBlueprintIsAFailedPlan() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> TaskOutcome.planned("看了一圈", List.of());

        TeamResult result = run(runner);

        assertEquals(TeamStatus.FAILED, result.status());
        assertEquals(TaskStatus.FAILED, result.stages().get(0).status());
    }

    @Test
    @DisplayName("worker 倒了不结束流水线：验收看得到失败和原因，由它决定下一步")
    void failedWorkerStillGetsReviewed() {
        FakeRunner runner = new FakeRunner();
        List<String> verdicts = new ArrayList<>(List.of("t1 没跑成是施工问题\n重新执行", "通过"));
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return plans(task);
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok(verdicts.remove(0));
            }
            if (task.id().equals("t1")) {
                return TaskOutcome.failed("写文件被拒绝");
            }
            return TaskOutcome.ok("干完了");
        };

        TeamResult result = run(runner);

        String brief = runner.stageOf("review").description();
        assertTrue(brief.contains("没跑成"), "验收材料里要点名失败: " + brief);
        assertTrue(brief.contains("写文件被拒绝"), brief);
        assertEquals(TeamStatus.COMPLETED, result.status(), "返工救回来了就是完成");
    }

    @Test
    @DisplayName("验收自己倒了：活干完了但没人担保，是 PARTIAL 而且没有 verdict")
    void failedReviewIsPartialWithoutVerdict() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return plans(task);
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.failed("超时");
            }
            return TaskOutcome.ok("干完了");
        };

        TeamResult result = run(runner);

        assertEquals(TeamStatus.PARTIAL, result.status());
        assertNull(result.verdict(), "没有结论就不给一句假结论");
    }

    @Test
    @DisplayName("重拟方案没成：不是 FAILED——上一轮的活还在，打回意见也在")
    void failedReplanKeepsTheLastVerdict() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> {
            if (task.id().equals("plan")) {
                return plans(task);
            }
            if (task.id().equals("plan2")) {
                return TaskOutcome.failed("拆子任务失败");
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok("方向不对\n重新规划");
            }
            return TaskOutcome.ok("干完了");
        };

        TeamResult result = run(runner);

        assertEquals(TeamStatus.PARTIAL, result.status());
        assertEquals("方向不对\n重新规划", result.verdict());
    }

    @Test
    @DisplayName("执行体抛异常不带走整条线：翻译成这道工序失败")
    void throwingRunnerBecomesAFailedStage() {
        FakeRunner runner = new FakeRunner();
        runner.script = (task, up) -> {
            throw new IllegalStateException("执行体自己炸了");
        };

        TeamResult result = run(runner);

        assertEquals(TeamStatus.FAILED, result.status());
        assertTrue(result.stages().get(0).error().contains("执行体自己炸了"));
    }

    // ── 取消 ────────────────────────────────────────────────────

    @Test
    @DisplayName("取消之后不再开新工序，整场 CANCELLED——已经落定的原样留着")
    void cancelStopsTheLine() {
        FakeRunner runner = new FakeRunner();
        Pipeline pipeline = new Pipeline(runner);
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return plans(task);
            }
            if (task.id().equals("t1")) {
                pipeline.cancel();      // 施工进行到一半，用户按了停
                return TaskOutcome.failed("已取消");
            }
            return TaskOutcome.ok("干完了");
        };

        TeamResult result = pipeline.run("目标");

        assertEquals(TeamStatus.CANCELLED, result.status());
        assertFalse(runner.ranId("review"), "验收不该再开跑——那是又一次完整的模型调用");
        assertEquals(TaskStatus.SUCCEEDED, result.stages().get(0).status(), "拟好的方案留着");
        assertNull(result.verdict());
        // 座位表上没有一道工序留在转圈状态
        assertTrue(result.stages().stream().allMatch(Task::isTerminal),
                "取消后每道已宣布的工序都要有终态");
    }

    // ── 事件 ────────────────────────────────────────────────────

    @Test
    @DisplayName("座位表是长大的：开场只有规划席，workers 和验收随后逐批宣布")
    void seatsGrowAsTheLineUnfolds() {
        FakeRunner runner = happyRunner();
        List<String> announced = new ArrayList<>();

        new Pipeline(runner).listener(new TeamListener() {
            @Override
            public void onStarted(String goal, String reason, List<Task> stages) {
                announced.add("start:" + stages.stream().map(Task::id)
                        .reduce((a, b) -> a + "+" + b).orElse(""));
            }

            @Override
            public void onStagesAdded(List<Task> stages) {
                announced.add("add:" + stages.stream().map(Task::id)
                        .reduce((a, b) -> a + "+" + b).orElse(""));
            }
        }).run("目标");

        assertEquals(List.of("start:plan", "add:t1+t2", "add:review"), announced);
    }

    @Test
    @DisplayName("每道宣布过的工序都会走 onStageFinished——不然界面上那一道永远转圈")
    void everyAnnouncedStageReportsATerminalState() {
        FakeRunner runner = happyRunner();
        List<String> finished = new java.util.concurrent.CopyOnWriteArrayList<>();

        new Pipeline(runner).listener(new TeamListener() {
            @Override
            public void onStageFinished(Task stage) {
                finished.add(stage.id());
            }
        }).run("目标");

        assertEquals(java.util.Set.of("plan", "t1", "t2", "review"), java.util.Set.copyOf(finished));
    }

    // ── 点名返工与保底 ────────────────────────────────────────

    /** 规划拆两个任务，t2 用 t1 的产出。 */
    private static TaskOutcome chainedPlan(Task plan) {
        return TaskOutcome.planned("方案: 先 t1 后 t2", List.of(
                plan.subTask("t1", "改读取", AgentRoles.CODER, List.of(), "改完"),
                plan.subTask("t2", "按 {{t1}} 改调用方", AgentRoles.CODER, List.of("t1"), "改完")));
    }

    private static FakeRunner scripted(java.util.function.Function<Task, TaskOutcome> planner,
                                       List<String> verdicts) {
        FakeRunner runner = new FakeRunner();
        List<String> left = new ArrayList<>(verdicts);
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return planner.apply(task);
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok(left.remove(0));
            }
            return TaskOutcome.ok(task.id() + " 干完了");
        };
        return runner;
    }

    @Test
    @DisplayName("点名返工：只重跑点到的那道，没点到的原样保留——返工不把做对的改坏")
    void reworkOnlyTheNamedStage() {
        FakeRunner runner = scripted(PipelineTest::plans,
                List.of("t2 漏了调用点\n返工: t2\n重新执行", "通过"));

        TeamResult result = run(runner);

        assertEquals(TeamStatus.COMPLETED, result.status());
        assertTrue(runner.ranId("r2.t2"));
        assertFalse(runner.ranId("r2.t1"), "没点名的 t1 不该重跑");
        assertTrue(runner.stageOf("review2").description().contains("t1 干完了"),
                "第二轮验收看的仍是整份活：t1 上一轮的产出也在材料里");
    }

    @Test
    @DisplayName("上游被点名，下游跟着重跑；下游被点名，上游作锚点把产出递给它")
    void reworkFollowsDependencies() {
        FakeRunner upstream = scripted(PipelineTest::chainedPlan, List.of("返工: t1\n重新执行", "通过"));
        run(upstream);
        assertTrue(upstream.ranId("r2.t1"));
        assertTrue(upstream.ranId("r2.t2"), "t1 改了，t2 拿的是过期材料，得跟着重来");

        FakeRunner downstream = scripted(PipelineTest::chainedPlan, List.of("返工: r1.t2 t2\n重新执行", "通过"));
        run(downstream);
        assertFalse(downstream.ranId("r2.t1"));
        assertTrue(downstream.stageOf("r2.t2").description().contains("{{t1}}"),
                "引用指向上一轮的 t1（执行时由 TaskPrompt 换成产出）");
        assertTrue(downstream.upstreams.get("r2.t2").stream()
                        .anyMatch(t -> t.id().equals("t1") && "t1 干完了".equals(t.result())),
                "不重跑的上游作锚点，把上一轮的产出递给下游");
    }

    @Test
    @DisplayName("没点名或点的名对不上：退回整份重跑，宁可多跑也不能一个都不修")
    void unknownNamesFallBackToFullRework() {
        FakeRunner runner = scripted(PipelineTest::plans, List.of("返工: t9\n重新执行", "通过"));

        run(runner);

        assertTrue(runner.ranId("r2.t1"));
        assertTrue(runner.ranId("r2.t2"));
    }

    @Test
    @DisplayName("返工把测试改少了：工作区退回成绩最好的那一轮，不再接着返工")
    void regressionRestoresTheBestRound() throws Exception {
        java.nio.file.Path ws = java.nio.file.Files.createTempDirectory("guard-ws");
        java.nio.file.Files.writeString(ws.resolve("a.py"), "v0\n");
        FakeRunner runner = new FakeRunner();
        List<String> verdicts = new ArrayList<>(List.of(
                "测试: 通过 5 / 共 10\n重新执行",
                "测试: 通过 3 / 共 10\n重新执行",
                "通过"));
        runner.script = (task, up) -> {
            if (task.id().startsWith("plan")) {
                return plans(task);
            }
            if (task.id().startsWith("review")) {
                return TaskOutcome.ok(verdicts.remove(0));
            }
            try {
                if (task.id().equals("t1")) {
                    java.nio.file.Files.writeString(ws.resolve("a.py"), "round1\n");
                } else if (task.id().equals("r2.t1")) {
                    java.nio.file.Files.writeString(ws.resolve("a.py"), "round2-worse\n");
                    java.nio.file.Files.writeString(ws.resolve("junk.py"), "x\n");
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return TaskOutcome.ok(task.id() + " 干完了");
        };

        TeamResult result = new Pipeline(runner).trackChanges(ws).run("改 a.py");

        assertEquals(TeamStatus.PARTIAL, result.status());
        assertEquals("round1\n", java.nio.file.Files.readString(ws.resolve("a.py")), "退回第一轮");
        assertFalse(java.nio.file.Files.exists(ws.resolve("junk.py")), "第二轮新建的文件一并撤掉");
        assertFalse(runner.ranId("review3"), "退步了就收手，不再开第三轮");
    }

    @Test
    @DisplayName("两行程序读的格式：成绩取最后一次，点名认中英文逗号和空格")
    void parsesScoreAndReworkLines() {
        assertArrayEquals(new int[]{133, 202},
                Pipeline.testScoreOf("先跑了一半 测试: 通过 10 / 共 202\n全跑完\n测试：通过 133/共 202\n重新执行"));
        assertNull(Pipeline.testScoreOf("没跑测试\n通过"));
        assertEquals(List.of("t2", "r2.t5", "t7"), Pipeline.reworkIdsOf("返工: t2，r2.t5、t7\n重新执行"));
        assertEquals(List.of(), Pipeline.reworkIdsOf("通过"));
        assertEquals("t5", Pipeline.blueprintId("r3.t5"));
        assertEquals("t1.2", Pipeline.blueprintId("t1.2"));
    }
}
