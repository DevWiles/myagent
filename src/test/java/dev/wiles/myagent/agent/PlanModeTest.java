package dev.wiles.myagent.agent;

import dev.wiles.myagent.agent.PlanModeTools.Answer;
import dev.wiles.myagent.agent.PlanModeTools.Ask;
import dev.wiles.myagent.agent.SubAgentsTest.Script;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static dev.wiles.myagent.agent.SubAgentsTest.stub;
import static org.junit.jupiter.api.Assertions.*;

/** plan 模式：菜单跟着模式变，进出都要用户点头，否决的话原样交还给模型。 */
class PlanModeTest {

    /** 一套主 agent，确认框的答复按顺序从 {@code answers} 里取。 */
    static final class Rig {
        final Script llm = new Script();
        final PlanMode mode = new PlanMode();
        final Deque<Answer> answers = new ArrayDeque<>();
        final List<String> asked = new ArrayList<>();
        final Agent agent;

        Rig() {
            ToolRegistry tools = new ToolRegistry()
                    .register(stub("read_file")).register(stub("write_file")).register(stub("bash"));
            tools.registerAll(PlanModeTools.create(mode, (ask, plan) -> {
                asked.add(ask + ":" + plan);
                return answers.poll();
            }));
            agent = new Agent(llm, tools, "SYSTEM").withToolGate(mode::allows);
        }

        /** 第 i 次请求时模型手上的工具名。 */
        List<String> menu(int i) {
            return llm.menus.get(i);
        }

        /** 最后一次请求里，第 n 条（倒数）tool 结果。 */
        String toolResult(int request, int fromEnd) {
            List<ChatMessage> seen = llm.seen.get(request);
            List<ChatMessage> results = seen.stream().filter(m -> "tool".equals(m.role())).toList();
            return results.get(results.size() - fromEnd).content();
        }
    }

    @Test
    @DisplayName("模型提议进 plan 模式、用户同意：菜单只剩看的；交计划、用户批了：写工具回来，同一个 agent 接着做")
    void enterThenExitSwapsTheMenu() {
        Rig rig = new Rig();
        rig.answers.add(new Answer(true, ""));
        rig.answers.add(new Answer(true, ""));
        rig.llm.call("enter_plan_mode", "{}")
                .call("write_file", "{}")
                .call("exit_plan_mode", "{\"plan\":\"1. 改 a.py\"}")
                .call("write_file", "{}")
                .reply("改好了");

        assertEquals("改好了", rig.agent.run("给 a 加重试"));

        assertTrue(rig.menu(0).contains("enter_plan_mode"));
        assertFalse(rig.menu(0).contains("exit_plan_mode"), "不在 plan 模式时没有可交的计划");
        assertTrue(rig.menu(1).containsAll(List.of("read_file", "exit_plan_mode")));
        for (String hidden : List.of("write_file", "bash", "enter_plan_mode")) {
            assertFalse(rig.menu(1).contains(hidden), "plan 模式下不该看到 " + hidden);
        }
        assertTrue(rig.toolResult(1, 1).contains("## plan 模式"), "进模式时把规矩交给模型");

        assertTrue(rig.toolResult(2, 1).contains("plan 模式"), "照着旧菜单硬点写工具，要被挡回去");
        assertTrue(rig.menu(3).contains("write_file"), "批准之后写工具回来");
        assertFalse(rig.mode.on());
        assertEquals(List.of("ENTER:", "EXIT:1. 改 a.py"), rig.asked);
    }

    @Test
    @DisplayName("计划被否决：还在 plan 模式，用户的意见原话交给模型；改好再交")
    void rejectedPlanStaysInPlanModeWithFeedback() {
        Rig rig = new Rig();
        rig.mode.set(true);
        rig.answers.add(new Answer(false, "别动数据库那层"));
        rig.answers.add(new Answer(true, ""));
        rig.llm.call("exit_plan_mode", "{\"plan\":\"重写 db.py\"}")
                .call("exit_plan_mode", "{\"plan\":\"只改 service.py\"}")
                .reply("好");

        rig.agent.run("优化查询");

        String rejected = rig.toolResult(1, 1);
        assertTrue(rejected.contains("没有批准"), rejected);
        assertTrue(rejected.contains("别动数据库那层"), rejected);
        assertTrue(rig.menu(1).contains("exit_plan_mode"), "否决之后仍在 plan 模式");
        assertFalse(rig.menu(1).contains("write_file"));
        assertTrue(rig.menu(2).contains("write_file"));
    }

    @Test
    @DisplayName("用户不想进 plan 模式：不切，模型被告知直接做")
    void declinedEnterKeepsNormalMode() {
        Rig rig = new Rig();
        rig.answers.add(new Answer(false, "小改动，直接改"));
        rig.llm.call("enter_plan_mode", "{}").reply("好");

        rig.agent.run("改个拼写");

        assertFalse(rig.mode.on());
        String result = rig.toolResult(1, 1);
        assertTrue(result.contains("直接做") && result.contains("小改动，直接改"), result);
        assertTrue(rig.menu(1).contains("write_file"));
    }

    @Test
    @DisplayName("模式切换通知监听器，和眼下一样时不重复通知")
    void listenersFireOnlyOnChange() {
        PlanMode mode = new PlanMode();
        List<Boolean> seen = new ArrayList<>();
        mode.onChange(seen::add);

        mode.set(false);
        mode.set(true);
        mode.set(true);
        mode.set(false);

        assertEquals(List.of(true, false), seen);
    }

    @Test
    @DisplayName("plan 模式的白名单：只放看的；MCP 工具和 bash 一律不放")
    void planModeAllowsOnlyReadingTools() {
        PlanMode mode = new PlanMode();
        mode.set(true);
        for (String ok : List.of("read_file", "grep", "glob", "list_dir", "web_fetch", "skill", "tool_search",
                "spawn_agent", "exit_plan_mode")) {
            assertTrue(mode.allows(ok), ok);
        }
        for (String no : List.of("write_file", "edit_file", "bash", "run_tests", "create_project", "remember",
                "mcp__chrome__click", "enter_plan_mode")) {
            assertFalse(mode.allows(no), no);
        }
        mode.set(false);
        assertTrue(mode.allows("write_file"));
        assertFalse(mode.allows("exit_plan_mode"));
    }

    @Test
    @DisplayName("确认框没人答（界面全断了）就按否决：不能照一份没人看过的计划动手")
    void noAnswerMeansNo() {
        Rig rig = new Rig();
        rig.mode.set(true);
        rig.answers.add(new Answer(false, "没有界面连着，没人能确认"));
        rig.llm.call("exit_plan_mode", "{\"plan\":\"改\"}").reply("等用户回来");

        rig.agent.run("改");

        assertTrue(rig.mode.on());
        assertEquals(Ask.EXIT + ":改", rig.asked.get(0));
    }
}
