package dev.wiles.myagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {

    /** 测试里等回音的预算。假 server 在进程内，正常路径毫秒级就回。 */
    private static final long TIMEOUT = 2_000;

    /** 标准应答：initialize 和 tools/list 都答得上来。多数测试从它出发。 */
    private static List<String> standardReplies(JsonNode message) {
        long id = message.path("id").asLong();
        return switch (message.path("method").asText()) {
            case "initialize" -> List.of(FakeMcpServer.result(id,
                    "{\"protocolVersion\":\"2024-11-05\",\"serverInfo\":{\"name\":\"fake\"}}"));
            case "tools/list" -> List.of(FakeMcpServer.result(id,
                    "{\"tools\":[{\"name\":\"echo\",\"description\":\"回声\","
                            + "\"inputSchema\":{\"type\":\"object\"}}]}"));
            default -> List.of();
        };
    }

    @Test
    @DisplayName("握手三步：initialize 请求 → 收 result → 发 initialized 通知，顺序不能乱")
    void initializeHandshake() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(McpClientTest::standardReplies);
             McpClient client = server.client(TIMEOUT)) {
            client.initialize();

            // initialize() 返回时通知已写出，但假 server 的读线程可能还没消化，等一下
            waitUntil(() -> server.received.size() >= 2);
            assertEquals("initialize", server.received.get(0).path("method").asText());
            assertEquals("myagent",
                    server.received.get(0).path("params").path("clientInfo").path("name").asText());
            assertEquals("notifications/initialized", server.received.get(1).path("method").asText());
            // 通知没有 id——带了 id 的是请求，server 会等着我们收响应
            assertFalse(server.received.get(1).has("id"));
        }
    }

    @Test
    @DisplayName("tools/list 会翻页：nextCursor 非空就接着要，两页拼成一份")
    void listToolsFollowsPagination() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(message -> {
            long id = message.path("id").asLong();
            if (!"tools/list".equals(message.path("method").asText())) return List.of();
            boolean secondPage = message.path("params").path("cursor").asText("").equals("p2");
            return List.of(secondPage
                    ? FakeMcpServer.result(id, "{\"tools\":[{\"name\":\"b\"}]}")
                    : FakeMcpServer.result(id,
                            "{\"tools\":[{\"name\":\"a\"}],\"nextCursor\":\"p2\"}"));
        }); McpClient client = server.client(TIMEOUT)) {
            List<McpClient.RemoteTool> tools = client.listTools();

            assertEquals(List.of("a", "b"), tools.stream().map(McpClient.RemoteTool::name).toList());
        }
    }

    @Test
    @DisplayName("callTool 把 content 里的文本拼起来，图片给占位符——丢可以，不吭声地丢不行")
    void callToolJoinsContent() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(message -> {
            long id = message.path("id").asLong();
            if (!"tools/call".equals(message.path("method").asText())) return List.of();
            return List.of(FakeMcpServer.result(id, "{\"content\":["
                    + "{\"type\":\"text\",\"text\":\"第一段\"},"
                    + "{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"...\"},"
                    + "{\"type\":\"text\",\"text\":\"第二段\"}]}"));
        }); McpClient client = server.client(TIMEOUT)) {
            McpClient.CallResult result = client.callTool("shot", null);

            assertFalse(result.isError());
            assertTrue(result.text().contains("第一段"));
            assertTrue(result.text().contains("[图片，image/png"));
            assertTrue(result.text().contains("第二段"));
        }
    }

    @Test
    @DisplayName("isError:true 是工具级失败，正常返回给调用方，不抛")
    void callToolSurfacesToolLevelErrors() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(message -> {
            long id = message.path("id").asLong();
            if (!"tools/call".equals(message.path("method").asText())) return List.of();
            return List.of(FakeMcpServer.result(id,
                    "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"查无此物\"}]}"));
        }); McpClient client = server.client(TIMEOUT)) {
            McpClient.CallResult result = client.callTool("find", null);

            assertTrue(result.isError());
            assertEquals("查无此物", result.text());
        }
    }

    @Test
    @DisplayName("server 回 JSON-RPC error 就抛 McpException，带上 server 的原话")
    void protocolErrorsThrow() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(message ->
                List.of(FakeMcpServer.error(message.path("id").asLong(), -32601, "method not found")));
             McpClient client = server.client(TIMEOUT)) {
            McpClient.McpException e =
                    assertThrows(McpClient.McpException.class, client::listTools);
            assertTrue(e.getMessage().contains("method not found"));
        }
    }

    @Test
    @DisplayName("已读不回就超时，异常里说清等了多久")
    void timesOutWhenServerSilent() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(message -> List.of());
             McpClient client = server.client(150)) {
            McpClient.McpException e =
                    assertThrows(McpClient.McpException.class, client::listTools);
            assertTrue(e.getMessage().contains("没有回音"));
        }
    }

    @Test
    @DisplayName("响应前面插一条通知不影响配对——「下一行一定是我的回复」是错误假设")
    void ignoresInterleavedNotifications() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(message -> {
            long id = message.path("id").asLong();
            if (!"tools/list".equals(message.path("method").asText())) return List.of();
            return List.of(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}",
                    FakeMcpServer.result(id, "{\"tools\":[]}"));
        }); McpClient client = server.client(TIMEOUT)) {
            assertEquals(List.of(), client.listTools());
        }
    }

    @Test
    @DisplayName("server 猝死时，等着的请求立刻被叫醒，不干等到超时")
    void pendingRequestsFailFastOnServerDeath() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(message -> {
            try {
                server_die_holder[0].run();      // 收到请求就把输出流关掉
            } catch (Exception ignored) {
            }
            return List.of();
        }); McpClient client = server.client(10_000)) {
            server_die_holder[0] = () -> {
                try {
                    server.die();
                } catch (Exception ignored) {
                }
            };
            long start = System.currentTimeMillis();
            assertThrows(McpClient.McpException.class, client::listTools);
            // 关键断言是**快**：超时是 10 秒，死讯应当立刻传达
            assertTrue(System.currentTimeMillis() - start < 3_000, "应该立刻失败而不是等超时");
        }
    }

    private final Runnable[] server_die_holder = new Runnable[]{() -> { }};

    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("等条件成立等到超时");
            }
            Thread.sleep(10);
        }
    }
}
