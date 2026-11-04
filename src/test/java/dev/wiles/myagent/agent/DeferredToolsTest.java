package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.agent.SubAgentsTest.Script;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.ToolSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** agent 这一侧的延迟加载：菜单上有没有、能不能调，跟着「这段对话里拉过没有」走。 */
class DeferredToolsTest {

    static Tool tool(String name) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return name + " 的说明"; }
            public String parametersSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolResult execute(JsonNode args) { return ToolResult.ok(name + " ok"); }
        };
    }

    static ToolRegistry registry() {
        ToolRegistry tools = new ToolRegistry()
                .register(tool("read_file")).register(tool("web_fetch")).register(tool("mcp__chrome__click"));
        tools.defer(List.of("web_fetch", "mcp__chrome__click"));
        return tools.register(new ToolSearchTool(tools));
    }

    /** 第 request 次请求里最后一条 tool 结果。 */
    static String lastToolResult(Script llm, int request) {
        List<ChatMessage> results = llm.seen.get(request).stream().filter(m -> "tool".equals(m.role())).toList();
        return results.get(results.size() - 1).content();
    }

    @Test
    @DisplayName("没拉过的延迟工具不在菜单上，硬点被挡回去并教它怎么拉；拉过之后下一步就能调")
    void menuFollowsWhatWasLoaded() {
        Script llm = new Script()
                .call("web_fetch", "{}")
                .call("tool_search", "{\"query\":\"select:web_fetch\"}")
                .call("web_fetch", "{}")
                .reply("抓到了");

        new Agent(llm, registry(), "SYSTEM").run("看看那个网页");

        assertTrue(llm.menus.get(0).containsAll(List.of("read_file", "tool_search")));
        assertFalse(llm.menus.get(0).contains("web_fetch"));
        String refused = lastToolResult(llm, 1);
        assertTrue(refused.contains("select:web_fetch"), refused);
        assertTrue(llm.menus.get(2).contains("web_fetch"), "拉过之后进了菜单");
        assertFalse(llm.menus.get(2).contains("mcp__chrome__click"), "没拉的照样不在");
        assertEquals("web_fetch ok", lastToolResult(llm, 3));
    }

    @Test
    @DisplayName("恢复会话：之前拉过的照样认得")
    void loadedSurvivesRestore() {
        Script llm = new Script().reply("好");
        Agent agent = new Agent(llm, registry(), "SYSTEM");
        agent.restore(List.of(
                ChatMessage.user("看网页"),
                ChatMessage.assistant("", List.of(ToolCall.of("s1", "tool_search", "{}"))),
                ChatMessage.toolResult("s1", ToolSearchTool.LOADED + "web_fetch\n..."),
                ChatMessage.assistant("拉好了")));

        agent.run("接着");

        assertTrue(llm.menus.get(0).contains("web_fetch"));
        assertFalse(llm.menus.get(0).contains("mcp__chrome__click"));
    }

    @Test
    @DisplayName("不存在的工具名照旧由注册表回那条带全部名字的错误，不被闸门吞掉")
    void unknownToolStillGetsRegistryError() {
        Script llm = new Script().call("no_such_tool", "{}").reply("好");
        new Agent(llm, registry(), "SYSTEM").withToolGate(name -> false).run("x");

        assertTrue(lastToolResult(llm, 1).contains("没有名为 no_such_tool 的工具"));
    }
}
