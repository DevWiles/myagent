package dev.wiles.myagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 延迟加载：平时只露名字，拉过之后进菜单；拉没拉过从对话里读。 */
class ToolSearchToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    static Tool tool(String name, String description) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public String parametersSchema() { return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}"; }
            public ToolResult execute(JsonNode args) { return ToolResult.ok(name + " ok"); }
        };
    }

    static ToolRegistry registry() {
        ToolRegistry tools = new ToolRegistry()
                .register(tool("read_file", "读文件"))
                .register(tool("mcp__chrome__navigate", "打开一个网页"))
                .register(tool("mcp__chrome__screenshot", "给当前页面截图"))
                .register(tool("web_fetch", "抓网页正文"));
        tools.defer(List.of("mcp__chrome__navigate", "mcp__chrome__screenshot", "web_fetch", "不存在的"));
        tools.register(new ToolSearchTool(tools));
        return tools;
    }

    static String search(ToolRegistry tools, String query) throws Exception {
        return tools.execute(ToolCall.of("s", "tool_search", M.writeValueAsString(java.util.Map.of("query", query))))
                .content();
    }

    @Test
    @DisplayName("说明里只列延迟工具的名字；没注册的名字 defer 时忽略")
    void descriptionListsDeferredNames() {
        ToolRegistry tools = registry();
        String desc = tools.get("tool_search").description();
        assertTrue(desc.contains("- mcp__chrome__navigate"));
        assertTrue(desc.contains("- web_fetch"));
        assertFalse(desc.contains("- read_file"), "常驻的不用列");
        assertFalse(tools.isDeferred("不存在的"));
        assertFalse(desc.contains("打开一个网页"), "只给名字，说明要拉了才给");
    }

    @Test
    @DisplayName("select: 精确拉取；结果第一行记下拉了谁，后面是完整说明和参数")
    void selectLoadsExactTools() throws Exception {
        String out = search(registry(), "select:web_fetch, mcp__chrome__navigate,read_file");
        assertTrue(out.startsWith(ToolSearchTool.LOADED + "web_fetch, mcp__chrome__navigate\n"), out);
        assertTrue(out.contains("打开一个网页"));
        assertTrue(out.contains("\"url\""));
    }

    @Test
    @DisplayName("关键词：名字命中排在说明命中前面；+词 是名字里必须有的")
    void keywordSearchRanksByName() throws Exception {
        String out = search(registry(), "截图 screenshot");
        assertTrue(out.startsWith(ToolSearchTool.LOADED + "mcp__chrome__screenshot"), out);

        String required = search(registry(), "+chrome 网页");
        assertTrue(required.contains("mcp__chrome__navigate"));
        assertFalse(required.split("\n")[0].contains("web_fetch"), "名字里没有 chrome 的不要");

        assertTrue(search(registry(), "数据库").startsWith("错误: "));
    }

    @Test
    @DisplayName("拉过哪些从对话里读出来：别的工具结果、用户消息里同样的字样不算")
    void loadedIsReadFromToolResults() {
        Set<String> loaded = ToolSearchTool.loadedIn(List.of(
                ChatMessage.user(ToolSearchTool.LOADED + "bash"),
                ChatMessage.toolResult("a", ToolSearchTool.LOADED + "web_fetch, mcp__x\n说明"),
                ChatMessage.toolResult("b", "read_file ok")));
        assertEquals(Set.of("web_fetch", "mcp__x"), loaded);
    }
}
