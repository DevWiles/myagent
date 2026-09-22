package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.plan.AgentRoles;
import dev.wiles.myagent.team.TeamResult;
import dev.wiles.myagent.team.TeamStatus;
import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskGraph;
import dev.wiles.myagent.plan.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {

    @Test
    @DisplayName("文本增量原样带过去，一个字符都不能丢")
    void textDelta() {
        ObjectNode n = Protocol.encode(new AgentEvent.TextDelta("你好 world\n"));
        assertEquals("text_delta", n.get("t").asText());
        assertEquals("你好 world\n", n.get("text").asText());
    }

    @Test
    @DisplayName("工具的开始和结束带同一个流水号，界面才能把两条配成一行")
    void toolPairsShareId() {
        // 流水号由事件自带（Agent 在发起调用时编的），不再由服务端看顺序补——
        // 并行的调用开始和结束会交错到达，只有源头发的号才配得对
        ObjectNode started = Protocol.encode(new AgentEvent.ToolStarted(7, "read_file", "{\"path\":\"a\"}"));
        ObjectNode finished = Protocol.encode(new AgentEvent.ToolFinished(7, "read_file", true, "内容"));

        assertEquals("tool_started", started.get("t").asText());
        assertEquals("tool_finished", finished.get("t").asText());
        assertEquals(7, started.get("id").asInt());
        assertEquals(7, finished.get("id").asInt());
        assertTrue(finished.get("ok").asBoolean());
    }

    @Test
    @DisplayName("工具失败要带上 ok=false 和输出——界面只显示一个叉是没法排查的")
    void toolFailureCarriesOutput() {
        ObjectNode n = Protocol.encode(
                new AgentEvent.ToolFinished(1, "bash", false, "command not found: foo"));
        assertFalse(n.get("ok").asBoolean());
        assertEquals("command not found: foo", n.get("output").asText());
    }

    @Test
    @DisplayName("压缩事件要把前后 token 数都带上，用户有权知道丢了多少")
    void compacted() {
        ObjectNode n = Protocol.encode(new AgentEvent.Compacted(50000, 12000, true));
        assertEquals("compacted", n.get("t").asText());
        assertEquals(50000, n.get("tokensBefore").asInt());
        assertEquals(12000, n.get("tokensAfter").asInt());
        assertTrue(n.get("summarized").asBoolean());
    }

    @Test
    @DisplayName("每一种 AgentEvent 都有对应的报文类型")
    void everyEventTypeIsCovered() {
        AgentEvent[] all = {
                new AgentEvent.Thinking(1),
                new AgentEvent.TextDelta("x"),
                new AgentEvent.Thought("x"),
                new AgentEvent.ToolStarted(1, "t", "{}"),
                new AgentEvent.ToolFinished(1, "t", true, "x"),
                new AgentEvent.Compacted(1, 1, false),
                new AgentEvent.Done("x", 1),
                new AgentEvent.Failed("x"),
        };
        for (AgentEvent event : all) {
            ObjectNode n = assertDoesNotThrow(() -> Protocol.encode(event),
                    () -> event.getClass().getSimpleName() + " 没有对应的报文");
            assertTrue(n.hasNonNull("t"));
        }
    }

    @Test
    @DisplayName("写文件的 diff 随 tool_finished 一起发，界面靠它画出改了哪几行")
    void toolFinishedCarriesDiff() {
        ObjectNode n = Protocol.encode(new AgentEvent.ToolFinished(
                3, "write_file", true, "已写入 a.java (12 字节, 2 行)", "@@ -1 +1 @@\n- 旧\n+ 新"));

        assertTrue(n.get("diff").asText().contains("+ 新"), n.toString());
        assertEquals("已写入 a.java (12 字节, 2 行)", n.get("output").asText());
    }

    @Test
    @DisplayName("没有 diff 就不发这个字段——每条工具结果都挂个空串只是让报文变长")
    void noDiffMeansNoField() {
        ObjectNode n = Protocol.encode(new AgentEvent.ToolFinished(3, "read_file", true, "内容"));

        assertFalse(n.has("diff"));
    }

    @Test
    @DisplayName("子任务的事件标上任务号，主对话的不标——并行时界面靠它分流")
    void taskTagTellsWhoseEventItIs() {
        ObjectNode tagged = Protocol.encode(new AgentEvent.TextDelta("嗯"), "t3");
        ObjectNode plain = Protocol.encode(new AgentEvent.TextDelta("嗯"));

        assertEquals("t3", tagged.get("task").asText());
        assertFalse(plain.has("task"), "主对话的事件不该背一个空任务号");
    }

    @Test
    @DisplayName("取字段容错：前端漏传字段不该让服务端崩")
    void lenientFieldAccess() throws Exception {
        var node = Protocol.mapper().readTree("{\"t\":\"prompt\"}");
        assertEquals("", Protocol.str(node, "text", ""));
        assertEquals(-1, Protocol.num(node, "id", -1));
    }

    // ── 计划相关报文 ────────────────────────────────────────────

    private static dev.wiles.myagent.plan.Plan samplePlan() {
        dev.wiles.myagent.plan.TaskGraph graph = dev.wiles.myagent.plan.TaskGraph.of(java.util.List.of(
                new dev.wiles.myagent.plan.Task("t1", "读 a",
                        dev.wiles.myagent.plan.TaskType.FILE_READ, java.util.List.of()),
                new dev.wiles.myagent.plan.Task("t2", "读 b",
                        dev.wiles.myagent.plan.TaskType.FILE_READ, java.util.List.of()),
                new dev.wiles.myagent.plan.Task("t3", "改 c",
                        dev.wiles.myagent.plan.TaskType.FILE_WRITE, java.util.List.of("t1", "t2"))));
        return new dev.wiles.myagent.plan.Plan("把 a 和 b 合进 c", graph);
    }

    @Test
    @DisplayName("计划报文带上整张图，还有算好的分层——别让前端再实现一遍图算法")
    void encodesPlan() {
        ObjectNode n = Protocol.encodePlan(samplePlan());

        assertEquals("plan_created", n.get("t").asText());
        assertEquals("把 a 和 b 合进 c", n.get("goal").asText());
        assertEquals(3, n.get("tasks").size());
        assertEquals("FILE_WRITE", n.get("tasks").get(2).get("type").asText());
        assertEquals(2, n.get("tasks").get(2).get("dependsOn").size());

        assertEquals(2, n.get("layers").size(), "菱形收口：两层");
        assertEquals(2, n.get("layers").get(0).size());
        assertEquals("t3", n.get("layers").get(1).get(0).asText());
    }

    @Test
    @DisplayName("任务开始和结束的报文字段齐全")
    void encodesTaskLifecycle() {
        dev.wiles.myagent.plan.Task task = new dev.wiles.myagent.plan.Task(
                "t1", "读 a", dev.wiles.myagent.plan.TaskType.FILE_READ, java.util.List.of());

        ObjectNode started = Protocol.encodeTaskStarted(task);
        assertEquals("plan_task_started", started.get("t").asText());
        assertEquals("t1", started.get("taskId").asText());
        assertEquals("FILE_READ", started.get("taskType").asText());

        task.start();
        task.succeed("Java 17");
        ObjectNode finished = Protocol.encodeTaskFinished(task);
        assertEquals("SUCCEEDED", finished.get("status").asText());
        assertEquals("Java 17", finished.get("result").asText());
        assertTrue(finished.get("durationMs").asLong() >= 0);
        assertTrue(finished.get("error").isNull(), "没出错时 error 要是 null，不是空串");
    }

    @Test
    @DisplayName("动态扩图的报文标明是谁拆出来的，前端才能就地插进去")
    void encodesAddedTasks() {
        dev.wiles.myagent.plan.Task parent = new dev.wiles.myagent.plan.Task(
                "t1", "探路", dev.wiles.myagent.plan.TaskType.PLANNING, java.util.List.of());
        ObjectNode n = Protocol.encodeAddedTasks("t1", java.util.List.of(
                parent.subTask("t1.1", "改 A",
                        dev.wiles.myagent.plan.TaskType.FILE_WRITE, java.util.List.of())));

        assertEquals("plan_tasks_added", n.get("t").asText());
        assertEquals("t1", n.get("parentId").asText());
        assertEquals("t1.1", n.get("tasks").get(0).get("id").asText());
    }

    @Test
    @DisplayName("布尔字段漏传时退回默认值，不能让前端一个笔误把服务端搞崩")
    void readsBooleansDefensively() {
        com.fasterxml.jackson.databind.node.ObjectNode cmd = Protocol.event("plan_confirm_reply");

        assertFalse(Protocol.bool(cmd, "approved", false));
        cmd.put("approved", true);
        assertTrue(Protocol.bool(cmd, "approved", false));
    }
    // ── 新增字段与新事件 ────────────────────────────────────────

    @Test
    @DisplayName("任务带上 acceptance——界面要让用户看到「做到什么算完」")
    void taskCarriesAcceptance() {
        Task t1 = new Task("t1", "读 pom.xml", TaskType.FILE_READ, List.of(), "说清 Java 版本");
        Plan plan = new Plan("目标", TaskGraph.of(List.of(t1)));

        JsonNode task = Protocol.encodePlan(plan).get("tasks").get(0);

        assertEquals("说清 Java 版本", task.get("acceptance").asText());
    }

    @Test
    @DisplayName("没写 acceptance 时字段是 null，不是漏掉——界面照约定自己判空")
    void acceptanceIsNullNotMissing() {
        Task t1 = new Task("t1", "干活", TaskType.FILE_READ, List.of());
        Plan plan = new Plan("目标", TaskGraph.of(List.of(t1)));

        JsonNode task = Protocol.encodePlan(plan).get("tasks").get(0);

        assertTrue(task.has("acceptance"));
        assertTrue(task.get("acceptance").isNull());
    }

    @Test
    @DisplayName("plan_revised：说清哪一步倒了、补出了哪几步")
    void encodesReplanned() {
        Task failed = new Task("t2", "改文件", TaskType.FILE_WRITE, List.of());
        Task recovery = new Task("r1", "换条路", TaskType.FILE_WRITE, List.of(), "改完了");

        JsonNode n = Protocol.encodeReplanned(failed, List.of(recovery));

        assertEquals("plan_revised", n.get("t").asText());
        assertEquals("t2", n.get("failedTaskId").asText());
        assertEquals(1, n.get("tasks").size());
        assertEquals("r1", n.get("tasks").get(0).get("id").asText());
        assertEquals("改完了", n.get("tasks").get(0).get("acceptance").asText());
    }

    @Test
    @DisplayName("自动改走规划要说一声，不然界面只看到「发的是 prompt，回的是 plan_created」")
    void encodesModeSwitch() {
        JsonNode n = Protocol.encodeModeSwitch("范围铺得比较开");

        assertEquals("mode_switched", n.get("t").asText());
        assertEquals("plan", n.get("mode").asText());
        assertEquals("范围铺得比较开", n.get("reason").asText());
    }

    @Test
    @DisplayName("新能力要在 features 里宣告，界面才知道会有这些消息")
    void announcesFeatures() {
        assertTrue(Protocol.FEATURES.contains("replan"), Protocol.FEATURES.toString());
        assertTrue(Protocol.FEATURES.contains("resume"), Protocol.FEATURES.toString());
        assertTrue(Protocol.FEATURES.contains("auto_mode"), Protocol.FEATURES.toString());
        assertTrue(Protocol.FEATURES.contains("roles"), Protocol.FEATURES.toString());
        assertTrue(Protocol.FEATURES.contains("team"), Protocol.FEATURES.toString());
        assertTrue(Protocol.FEATURES.contains("events_resume"), Protocol.FEATURES.toString());
        assertTrue(Protocol.FEATURES.contains("plan"), "别把老能力弄丢了");
    }

    @Test
    @DisplayName("每个任务都带上派了谁——type 也留着，老界面照样看得懂")
    void encodesRoleAlongsideType() {
        Plan plan = new Plan("挑毛病", TaskGraph.of(List.of(
                new Task("t1", "审一遍", AgentRoles.REVIEWER, List.of(), "给出清单"))));

        JsonNode task = Protocol.encodePlan(plan).get("tasks").get(0);

        assertEquals("reviewer", task.get("role").asText());
        assertEquals("审查", task.get("roleTitle").asText());
        assertEquals("FILE_READ", task.get("type").asText(),
                "type 不能因为有了 role 就消失——删字段是破坏性改动，得升协议版本");
    }

    @Test
    @DisplayName("任务开始那条也带角色：界面画「正在做第几步」时就得知道是谁在做")
    void taskStartedCarriesRole() {
        ObjectNode started = Protocol.encodeTaskStarted(
                new Task("t1", "跑测试", AgentRoles.VERIFIER, List.of(), "全绿"));

        assertEquals("verifier", started.get("role").asText());
        assertEquals("验证", started.get("roleTitle").asText());
        assertEquals("VERIFICATION", started.get("taskType").asText());
    }

    // ── 流水线 ──────────────────────────────────────────────────

    @Test
    @DisplayName("开场那帧带三道工序和分工理由——这是花钱之前用户唯一一眼")
    void teamStartedCarriesTheStages() {
        Task plan = new Task("plan", "拟定施工方案: 修配置", AgentRoles.SCOUT, List.of(), "有序步骤");
        Task work = new Task("work", "按方案施工: 修配置", AgentRoles.CODER, List.of("plan"), "施工报告");
        Task review = new Task("review", "验收这次施工: 修配置", AgentRoles.REVIEWER,
                List.of("plan", "work"), "末行通过或打回");

        ObjectNode n = Protocol.encodeTeamStarted("修配置", "探路拟方案 → 编码施工 → 审查验收",
                List.of(plan, work, review));

        assertEquals("team_started", n.get("t").asText());
        assertEquals("修配置", n.get("goal").asText());
        assertEquals("探路拟方案 → 编码施工 → 审查验收", n.get("reason").asText());
        assertEquals(3, n.get("seats").size());
        // id 取任务自己的，后续 team_seat_started 报的是同一批——界面按它对行
        assertEquals("plan", n.get("seats").get(0).get("id").asText());
        assertEquals("scout", n.get("seats").get(0).get("role").asText());
        assertEquals("work", n.get("seats").get(1).get("id").asText());
        assertEquals("review", n.get("seats").get(2).get("id").asText());
    }

    @Test
    @DisplayName("一道工序落定带上正文和状态，界面才能折叠、回看")
    void stageFinishedCarriesTheOutput() {
        Task stage = new Task("review", "验收这次施工", AgentRoles.REVIEWER, List.of(), "末行结论");
        stage.start();
        stage.succeed("发现 3 处问题\n打回");

        ObjectNode n = Protocol.encodeStageFinished(stage);

        assertEquals("team_seat_finished", n.get("t").asText());
        assertEquals("review", n.get("seatId").asText());
        assertEquals("SUCCEEDED", n.get("status").asText());
        assertEquals("发现 3 处问题\n打回", n.get("opinion").asText());
        assertTrue(n.get("error").isNull());
    }

    @Test
    @DisplayName("倒了的工序也发结束帧，带上原因——不然界面上它永远转圈")
    void failedStageStillReportsWhy() {
        Task stage = new Task("work", "按方案施工", AgentRoles.CODER, List.of(), "施工报告");
        stage.start();
        stage.fail("写文件被拒绝");

        ObjectNode n = Protocol.encodeStageFinished(stage);

        assertEquals("FAILED", n.get("status").asText());
        assertEquals("写文件被拒绝", n.get("error").asText());
        assertTrue(n.get("opinion").isNull());
    }

    @Test
    @DisplayName("座位表长大的那帧和开场帧长一个样，界面追加即可")
    void seatsAddedSharesTheSeatShape() {
        Task worker = new Task("t1", "改配置读取", AgentRoles.CODER, List.of(), "读到环境变量");

        ObjectNode n = Protocol.encodeStagesAdded(List.of(worker));

        assertEquals("team_seats_added", n.get("t").asText());
        assertEquals(1, n.get("seats").size());
        assertEquals("t1", n.get("seats").get(0).get("id").asText());
        assertEquals("coder", n.get("seats").get(0).get("role").asText());
        assertEquals("PENDING", n.get("seats").get(0).get("status").asText());
    }

    @Test
    @DisplayName("收工帧：打回是 PARTIAL 且问题清单在 verdict 里——那是最值钱的一份产出")
    void teamFinishedCarriesTheRejectionList() {
        Task plan = new Task("plan", "拟定施工方案", AgentRoles.SCOUT, List.of(), "步骤");
        plan.start();
        plan.succeed("方案");
        Task work = new Task("t1", "改配置读取", AgentRoles.CODER, List.of("plan"), "报告");
        work.start();
        work.succeed("改了两处");
        Task review = new Task("review", "验收第 1 轮施工", AgentRoles.REVIEWER,
                List.of(), "末行结论");
        review.start();
        review.succeed("漏了空值判断\n重新执行");

        ObjectNode n = Protocol.encodeTeamFinished(new TeamResult("修配置", "拟方案→施工→验收",
                List.of(plan, work, review), review, 3, TeamStatus.PARTIAL));

        assertEquals("PARTIAL", n.get("status").asText());
        assertEquals(3, n.get("spoke").asInt());
        assertEquals(0, n.get("silent").asInt());
        assertEquals(3, n.get("rounds").asInt());
        assertEquals("漏了空值判断\n重新执行", n.get("verdict").asText());
    }

    @Test
    @DisplayName("验收自己没跑成的 PARTIAL：verdict 是 null——没有结论就别发一句假结论")
    void unreviewedPartialHasNoVerdict() {
        Task plan = new Task("plan", "拟定施工方案", AgentRoles.SCOUT, List.of(), "步骤");
        plan.start();
        plan.succeed("方案");
        Task work = new Task("t1", "改配置读取", AgentRoles.CODER, List.of("plan"), "报告");
        work.start();
        work.succeed("改了两处");
        Task review = new Task("review", "验收第 1 轮施工", AgentRoles.REVIEWER,
                List.of(), "末行结论");
        review.start();
        review.fail("超时");

        ObjectNode n = Protocol.encodeTeamFinished(new TeamResult("修配置", "拟方案→施工→验收",
                List.of(plan, work, review), review, 1, TeamStatus.PARTIAL));

        assertEquals("PARTIAL", n.get("status").asText());
        assertTrue(n.get("verdict").isNull());
    }

    @Test
    @DisplayName("界面传来的角色数组：缺了、类型不对、混了空串，都不能让服务端崩")
    void roleArrayIsParsedDefensively() {
        ObjectNode cmd = Protocol.mapper().createObjectNode();
        assertEquals(List.of(), Protocol.strings(cmd, "roles"), "没这个字段就是没点人");

        cmd.put("roles", "reviewer");
        assertEquals(List.of(), Protocol.strings(cmd, "roles"), "不是数组也不该崩");

        cmd.putArray("roles").add("reviewer").add("").add(" verifier ");
        assertEquals(List.of("reviewer", "verifier"), Protocol.strings(cmd, "roles"));
    }

    @Test
    @DisplayName("首帧把整份名册发过去，界面不用自己维护一张角色表")
    void readyCarriesTheRoster() {
        JsonNode roles = Protocol.encodeRoles();

        assertEquals(AgentRoles.all().size(), roles.size());
        JsonNode first = roles.get(0);
        for (String field : List.of("id", "title", "summary", "type", "mutates", "tools")) {
            assertTrue(first.has(field), "名册里缺字段 " + field + ": " + first);
        }
        // 界面要靠 mutates 之类的字段做区分，不能全靠 id 硬编码
        assertTrue(roles.findValuesAsText("id").contains("reviewer"), roles.toString());
    }
}
