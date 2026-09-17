package dev.wiles.myagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.config.Config;
import dev.wiles.myagent.context.ContextManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流式输出：SSE 解析 + 把一堆增量拼回完整消息。
 *
 * 最容易出错的是工具调用——参数是切碎了传的，而且多个工具的分片会交替到达。
 */
class StreamingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private MockWebServer server;
    private DeepSeekClient client;
    private final List<String> deltas = new ArrayList<>();
    private final List<String> toolStarts = new ArrayList<>();

    private final StreamListener listener = new StreamListener() {
        public void onTextDelta(String delta) {
            deltas.add(delta);
        }

        public void onToolCallStarted(String name) {
            toolStarts.add(name);
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DeepSeekClient(
                new Config("sk-test", server.url("/").toString(), "deepseek-chat",
                        ContextManager.DEFAULT_MAX_TOKENS),
                new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void enqueue(String... dataLines) {
        StringBuilder body = new StringBuilder();
        for (String line : dataLines) {
            body.append("data: ").append(line).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body.toString()));
    }

    @Test
    @DisplayName("文本一段段到达，实时回调，最后拼成完整回答")
    void streamsText() {
        enqueue(
                "{\"id\":\"c1\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"这个\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"项目\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"用了 Maven\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");

        ChatResponse response = client.chat(List.of(ChatMessage.user("问题")), null, listener);

        assertEquals(List.of("这个", "项目", "用了 Maven"), deltas, "要逐段回调，不能攒到最后");
        assertEquals("这个项目用了 Maven", response.text());
        assertEquals("stop", response.finishReason());
        assertEquals("c1", response.id());
    }

    @Test
    @DisplayName("请求体里 stream 为 true")
    void sendsStreamFlag() throws Exception {
        enqueue("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}");

        client.chat(List.of(ChatMessage.user("问题")), null, listener);

        var sent = M.readTree(server.takeRequest().getBody().readUtf8());
        assertTrue(sent.get("stream").asBoolean());
    }

    @Test
    @DisplayName("工具参数是切碎传的，要拼起来才是完整 JSON")
    void assemblesToolArguments() throws Exception {
        enqueue(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"我看一下\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"pa\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"pom.xml\\\"}\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");

        ChatResponse response = client.chat(List.of(ChatMessage.user("看看")), null, listener);

        assertEquals("tool_calls", response.finishReason());
        List<ToolCall> calls = response.message().toolCallsOrEmpty();
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).id());
        assertEquals("read_file", calls.get(0).name());
        assertEquals("pom.xml", M.readTree(calls.get(0).argumentsJson()).get("path").asText(),
                "分片没拼对的话这里解析不出来");
        assertEquals("我看一下", response.text());
        assertEquals(List.of("read_file"), toolStarts, "名字一到手就该通知界面");
    }

    @Test
    @DisplayName("多个工具的分片交替到达，靠 index 区分而不是 id")
    void separatesToolsByIndex() throws Exception {
        enqueue(
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"list_dir\",\"arguments\":\"\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"b\\\"}\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");

        ChatResponse response = client.chat(List.of(ChatMessage.user("看看")), null, listener);

        List<ToolCall> calls = response.message().toolCallsOrEmpty();
        assertEquals(2, calls.size());
        assertEquals("call_a", calls.get(0).id());
        assertEquals("read_file", calls.get(0).name());
        assertEquals("a", M.readTree(calls.get(0).argumentsJson()).get("path").asText());
        assertEquals("call_b", calls.get(1).id());
        assertEquals("list_dir", calls.get(1).name());
        assertEquals("b", M.readTree(calls.get(1).argumentsJson()).get("path").asText(),
                "两个工具的参数串到一起就说明没按 index 分开");
    }

    @Test
    @DisplayName("只发工具调用、没有文本时，content 为 null")
    void toolCallWithoutText() {
        enqueue(
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"list_dir\",\"arguments\":\"{}\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");

        ChatResponse response = client.chat(List.of(ChatMessage.user("看看")), null, listener);

        assertNull(response.message().content());
        assertEquals("", response.text());
        assertTrue(deltas.isEmpty());
    }

    @Test
    @DisplayName("读到 [DONE] 就停，后面的内容不再处理")
    void stopsAtDone() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"前\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"后\"}}]}\n\n"));

        assertEquals("前", client.chat(List.of(ChatMessage.user("x")), null, listener).text());
    }

    @Test
    @DisplayName("坏掉的分片跳过就好，不能让整次请求失败")
    void skipsMalformedChunks() {
        enqueue(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好的\"}}]}",
                "{ 这不是合法 JSON",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"继续\"},\"finish_reason\":\"stop\"}]}");

        assertEquals("好的继续", client.chat(List.of(ChatMessage.user("x")), null, listener).text());
    }

    @Test
    @DisplayName("流式请求失败时抛 LlmException，不重试（内容可能已经打到屏幕上了）")
    void failsFastOnError() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"));

        LlmException e = assertThrows(LlmException.class,
                () -> client.chat(List.of(ChatMessage.user("x")), null, listener));

        assertEquals(401, e.statusCode());
        assertEquals(1, server.getRequestCount(), "流式不该重试，否则前半段会重复输出");
    }

    @Test
    @DisplayName("不支持流式的实现会退化成一次性回调，上层代码不用改")
    void defaultImplementationFallsBack() {
        LlmClient plain = new LlmClient() {
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                return new ChatResponse("x", "m", List.of(new ChatResponse.Choice(
                        0, ChatMessage.assistant("完整回答"), "stop")), null);
            }

            public String modelName() {
                return "plain";
            }
        };

        assertEquals("完整回答", plain.chat(List.of(ChatMessage.user("x")), null, listener).text());
        assertEquals(List.of("完整回答"), deltas, "退化时把全文当成一个增量发出");
    }
}
