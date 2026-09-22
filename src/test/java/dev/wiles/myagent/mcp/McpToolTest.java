package dev.wiles.myagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 不碰网络的三个方法，用一台哑 server 垫底就够。 */
    private static McpTool tool(String server, String name, String description, String schema)
            throws Exception {
        FakeMcpServer fake = new FakeMcpServer(m -> List.of());
        return new McpTool(fake.client(1_000), new McpClient.RemoteTool(
                name, description, schema == null ? null : MAPPER.readTree(schema)));
    }

    @Test
    @DisplayName("名字带 mcp__server__tool 前缀：撞不了内置工具，也撞不了别家 server")
    void namesArePrefixed() throws Exception {
        assertEquals("mcp__fake__read_file", tool("fs", "read_file", "d", null).name());
    }

    @Test
    @DisplayName("非法字符换下划线，超长截断——模型侧的名字规范比 MCP 严")
    void sanitizesNames() {
        assertEquals("get_weather", McpTool.sanitize("get.weather"));
        assertEquals("a_b_c", McpTool.sanitize("a/b:c"));
        assertEquals(24, McpTool.sanitize("x".repeat(60)).length());
    }

    @Test
    @DisplayName("description 必须带出处——审批框和审计日志靠它回答「这是谁的动作」")
    void descriptionNamesTheServer() throws Exception {
        String description = tool("fs", "t", "读一个文件", null).description();
        assertTrue(description.contains("读一个文件"));
        assertTrue(description.contains("MCP server fake"));
    }

    @Test
    @DisplayName("schema 原样透传；server 没给就用空对象 schema——无参工具是合法的")
    void schemaPassesThrough() throws Exception {
        String schema = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";
        assertEquals(MAPPER.readTree(schema),
                MAPPER.readTree(tool("s", "t", "d", schema).parametersSchema()));
        assertEquals(MAPPER.readTree("{\"type\":\"object\",\"properties\":{}}"),
                MAPPER.readTree(tool("s", "t", "d", null).parametersSchema()));
    }

    @Test
    @DisplayName("远端工具默认不许并行——它叫 query 也可能在写库")
    void notParallelSafe() throws Exception {
        assertFalse(tool("s", "t", "d", null).parallelSafe());
    }

    @Test
    @DisplayName("协议失败（超时/断连）翻成给模型的错误反馈，不往上抛")
    void protocolFailuresBecomeFeedback() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer(m -> List.of())) {   // 已读不回 → 超时
            McpTool tool = new McpTool(fake.client(150),
                    new McpClient.RemoteTool("slow", "d", null));
            var result = tool.execute(MAPPER.readTree("{}"));

            assertFalse(result.ok());
            assertTrue(result.content().contains("没答上来"));
        }
    }

    @Test
    @DisplayName("isError 的结果翻成错误反馈，带 server 的原话")
    void toolLevelFailuresBecomeFeedback() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer(message -> {
            long id = message.path("id").asLong();
            if (!"tools/call".equals(message.path("method").asText())) return List.of();
            return List.of(FakeMcpServer.result(id,
                    "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"路径不存在\"}]}"));
        })) {
            McpTool tool = new McpTool(fake.client(1_000),
                    new McpClient.RemoteTool("stat", "d", null));
            var result = tool.execute(MAPPER.readTree("{}"));

            assertFalse(result.ok());
            assertTrue(result.content().contains("路径不存在"));
        }
    }
}
