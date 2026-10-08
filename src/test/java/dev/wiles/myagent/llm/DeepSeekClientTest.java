package dev.wiles.myagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.config.Config;
import dev.wiles.myagent.context.ContextManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用 MockWebServer 把真实 HTTP 收发跑通，验证报文格式两个方向都对：
 * 我们发出去的 JSON 符合 OpenAI 兼容协议，DeepSeek 返回的 JSON 能被正确解析。
 * 不需要真实 API key。
 */
class DeepSeekClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;
    private DeepSeekClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Config config = new Config("sk-test-key", server.url("/").toString(), "deepseek-chat",
                ContextManager.DEFAULT_MAX_TOKENS);
        OkHttpClient http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(5))
                .build();
        client = new DeepSeekClient(config, http);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("普通对话：请求不带 tools，响应文本被正确解析")
    void plainChat() throws Exception {
        server.enqueue(jsonResponse("""
                {
                  "id": "chat-1",
                  "model": "deepseek-chat",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "你好，我是 myagent。"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19}
                }
                """));

        ChatResponse response = client.chat(List.of(
                ChatMessage.system("你是一个编码助手"),
                ChatMessage.user("你好")));

        assertEquals("你好，我是 myagent。", response.text());
        assertEquals("stop", response.finishReason());
        assertFalse(response.wantsToolCall());
        assertEquals(19, response.usage().totalTokens());

        RecordedRequest request = server.takeRequest();
        assertEquals("/chat/completions", request.getPath());
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"));

        JsonNode sent = MAPPER.readTree(request.getBody().readUtf8());
        assertEquals("deepseek-chat", sent.get("model").asText());
        assertEquals(2, sent.get("messages").size());
        assertEquals("system", sent.get("messages").get(0).get("role").asText());
        assertEquals("你好", sent.get("messages").get(1).get("content").asText());
        assertFalse(sent.get("stream").asBoolean());
        // 没有工具时这两个字段必须整个消失，不能是 null 或空数组
        assertFalse(sent.has("tools"), "不传工具时不应出现 tools 字段");
        assertFalse(sent.has("tool_choice"));
        // 未设置的可选参数同样不该出现
        assertFalse(sent.has("temperature"));
        assertFalse(sent.has("max_tokens"));
    }

    @Test
    @DisplayName("开了 JSON 模式，报文里带上 response_format，让服务端保证输出合法")
    void jsonModeIsSentToTheServer() throws Exception {
        server.enqueue(jsonResponse("""
                {"id": "c", "model": "deepseek-chat",
                 "choices": [{"index": 0,
                   "message": {"role": "assistant", "content": "{\\"goal\\":\\"x\\"}"},
                   "finish_reason": "stop"}]}
                """));

        client.jsonMode(true).temperature(0.2).maxTokens(4096)
                .chat(List.of(ChatMessage.user("输出一个 JSON")));

        JsonNode sent = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("json_object", sent.get("response_format").get("type").asText());
        assertEquals(0.2, sent.get("temperature").asDouble(), 1e-9);
        assertEquals(4096, sent.get("max_tokens").asInt());
    }

    @Test
    @DisplayName("没开 JSON 模式时 response_format 整个字段都不出现——有网关不认 type:text")
    void responseFormatAbsentByDefault() throws Exception {
        server.enqueue(jsonResponse("""
                {"id": "c", "model": "deepseek-chat",
                 "choices": [{"index": 0,
                   "message": {"role": "assistant", "content": "好"},
                   "finish_reason": "stop"}]}
                """));

        client.chat(List.of(ChatMessage.user("你好")));

        JsonNode sent = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertFalse(sent.has("response_format"), sent.toString());
    }

    @Test
    @DisplayName("JSON 模式关得掉——同一个客户端换个用途还能用")
    void jsonModeCanBeTurnedOff() throws Exception {
        for (int i = 0; i < 2; i++) {
            server.enqueue(jsonResponse("""
                    {"id": "c", "model": "deepseek-chat",
                     "choices": [{"index": 0,
                       "message": {"role": "assistant", "content": "好"},
                       "finish_reason": "stop"}]}
                    """));
        }

        client.jsonMode(true).chat(List.of(ChatMessage.user("一")));
        server.takeRequest();

        client.jsonMode(false).chat(List.of(ChatMessage.user("二")));
        JsonNode sent = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertFalse(sent.has("response_format"), sent.toString());
    }

    @Test
    @DisplayName("工具调用：工具清单正确发出，模型返回的 tool_calls 被正确解析")
    void toolCall() throws Exception {
        server.enqueue(jsonResponse("""
                {
                  "id": "chat-2",
                  "model": "deepseek-chat",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_abc123",
                        "type": "function",
                        "function": {"name": "read_file", "arguments": "{\\"path\\": \\"pom.xml\\"}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 88, "completion_tokens": 21, "total_tokens": 109}
                }
                """));

        ToolSpec readFile = ToolSpec.of("read_file", "读取一个文件的全部内容", """
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "description": "相对于项目根目录的路径"}
                  },
                  "required": ["path"]
                }
                """);

        ChatResponse response = client.chat(
                List.of(ChatMessage.user("看看 pom.xml 里用了哪些依赖")),
                List.of(readFile));

        assertEquals("tool_calls", response.finishReason());
        assertTrue(response.wantsToolCall());
        assertEquals("", response.text(), "只发工具调用时 content 为 null，应归一成空串");

        List<ToolCall> calls = response.message().toolCallsOrEmpty();
        assertEquals(1, calls.size());
        ToolCall call = calls.get(0);
        assertEquals("call_abc123", call.id());
        assertEquals("function", call.type());
        assertEquals("read_file", call.name());
        // arguments 是 JSON 字符串，需要二次解析
        assertEquals("pom.xml", MAPPER.readTree(call.argumentsJson()).get("path").asText());

        JsonNode sent = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("auto", sent.get("tool_choice").asText());
        JsonNode tool = sent.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("read_file", tool.get("function").get("name").asText());
        assertEquals("读取一个文件的全部内容", tool.get("function").get("description").asText());
        assertEquals("string",
                tool.get("function").get("parameters").get("properties").get("path").get("type").asText());
    }

    @Test
    @DisplayName("工具结果回传：tool 消息带上 tool_call_id，assistant 消息保留 tool_calls")
    void toolResultRoundTrip() throws Exception {
        server.enqueue(jsonResponse("""
                {
                  "id": "chat-3",
                  "model": "deepseek-chat",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "这个项目用了 Jackson、OkHttp 和 slf4j。"},
                    "finish_reason": "stop"
                  }]
                }
                """));

        ToolCall call = ToolCall.of("call_abc123", "read_file", "{\"path\":\"pom.xml\"}");
        ChatResponse response = client.chat(List.of(
                ChatMessage.user("看看 pom.xml"),
                ChatMessage.assistant(null, List.of(call)),
                ChatMessage.toolResult("call_abc123", "<pom.xml 的内容>")));

        assertEquals("这个项目用了 Jackson、OkHttp 和 slf4j。", response.text());
        assertNull(response.usage(), "响应里没有 usage 时应为 null，不该报错");

        JsonNode messages = MAPPER.readTree(server.takeRequest().getBody().readUtf8()).get("messages");
        JsonNode assistant = messages.get(1);
        assertEquals("assistant", assistant.get("role").asText());
        assertFalse(assistant.has("content"), "content 为 null 时应省略");
        assertEquals("call_abc123", assistant.get("tool_calls").get(0).get("id").asText());
        assertEquals("{\"path\":\"pom.xml\"}",
                assistant.get("tool_calls").get(0).get("function").get("arguments").asText());

        JsonNode toolMsg = messages.get(2);
        assertEquals("tool", toolMsg.get("role").asText());
        assertEquals("call_abc123", toolMsg.get("tool_call_id").asText());
        assertEquals("<pom.xml 的内容>", toolMsg.get("content").asText());
    }

    @Test
    @DisplayName("鉴权失败：抛出带状态码的 LlmException，不重试")
    void authFailure() {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"Authentication Fails\"}}"));

        LlmException e = assertThrows(LlmException.class,
                () -> client.chat(List.of(ChatMessage.user("你好"))));

        assertEquals(401, e.statusCode());
        assertTrue(e.getMessage().contains("鉴权失败"));
        assertEquals(1, server.getRequestCount(), "4xx 不该重试");
    }

    @Test
    @DisplayName("服务端 5xx：自动重试后成功")
    void retriesOnServerError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("upstream busy"));
        server.enqueue(jsonResponse("""
                {"id":"chat-4","model":"deepseek-chat",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                """));

        assertEquals("ok", client.chat(List.of(ChatMessage.user("在吗"))).text());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    @DisplayName("重试等待期间按停：当场收手，不再发下一次请求——原来这一下会被吞掉，白等一整次回答")
    @org.junit.jupiter.api.Timeout(10)
    void cancelDuringBackoffStops() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("upstream busy"));
        server.enqueue(jsonResponse("""
                {"id":"x","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"不该拿到"},"finish_reason":"stop"}]}
                """));
        cancelAfter(client, 300);                // 第一次 503 早就回来了，此刻正睡在 1 秒的退避里

        long t0 = System.nanoTime();
        LlmException e = assertThrows(LlmException.class,
                () -> client.chat(List.of(ChatMessage.user("在吗"))));

        assertTrue(e.getMessage().contains("取消"), e.getMessage());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 800, "按停了还睡满了退避");
        assertEquals(1, server.getRequestCount(), "按停之后不该再发请求");
    }

    @Test
    @DisplayName("请求进行中被掐断：不当成网络错误去重试——原来会睡一觉再发一次")
    @org.junit.jupiter.api.Timeout(10)
    void cancelledRequestIsNotRetried() {
        server.enqueue(jsonResponse("{}").setHeadersDelay(5, java.util.concurrent.TimeUnit.SECONDS));
        server.enqueue(jsonResponse("{}").setHeadersDelay(5, java.util.concurrent.TimeUnit.SECONDS));
        cancelAfter(client, 300);

        long t0 = System.nanoTime();
        LlmException e = assertThrows(LlmException.class,
                () -> client.chat(List.of(ChatMessage.user("在吗"))));

        assertTrue(e.getMessage().contains("取消"), e.getMessage());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1500, "掐断后又重试了");
        assertEquals(1, server.getRequestCount());
    }

    @Test
    @DisplayName("上一次按停不连累下一次请求：客户端是跨轮复用的")
    void cancelDoesNotPoisonNextCall() {
        client.cancel();                         // 上一轮按过停，那时没有请求在途
        server.enqueue(jsonResponse("""
                {"id":"x","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                """));

        assertEquals("ok", client.chat(List.of(ChatMessage.user("在吗"))).text());
    }

    /** 从另一个线程（监听 Esc 的那个）按停。 */
    static void cancelAfter(LlmClient client, long millis) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ignored) {
            }
            client.cancel();
        });
        t.setDaemon(true);
        t.start();
    }

    @Test
    @DisplayName("没有 API key 时构造即失败，提示怎么配")
    void missingApiKey() {
        Config empty = new Config(null, Config.DEFAULT_BASE_URL, Config.DEFAULT_MODEL,
                ContextManager.DEFAULT_MAX_TOKENS);
        LlmException e = assertThrows(LlmException.class, () -> new DeepSeekClient(empty));
        assertTrue(e.getMessage().contains("DEEPSEEK_API_KEY"));
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
