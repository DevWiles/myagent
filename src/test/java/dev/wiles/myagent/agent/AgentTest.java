package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 循环本身的测试。模型用假的，工具用假的——
 * 这里只关心一件事：消息序列有没有按协议正确地攒起来。
 */
class AgentTest {

    // ---------- 假模型：按预先排好的剧本逐条返回 ----------

    static final class FakeLlm implements LlmClient {
        final List<Supplier<ChatResponse>> script = new ArrayList<>();
        final List<List<ChatMessage>> received = new ArrayList<>();
        final List<List<ToolSpec>> receivedTools = new ArrayList<>();

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            received.add(List.copyOf(messages));
            receivedTools.add(tools);
            if (script.isEmpty()) {
                throw new AssertionError("剧本演完了，agent 却还在请求第 " + received.size() + " 次");
            }
            return script.remove(0).get();
        }

        @Override
        public String modelName() {
            return "fake";
        }

        FakeLlm says(String text) {
            script.add(() -> response(ChatMessage.assistant(text), "stop"));
            return this;
        }

        FakeLlm calls(String thought, ToolCall... calls) {
            script.add(() -> response(ChatMessage.assistant(thought, List.of(calls)), "tool_calls"));
            return this;
        }

        FakeLlm fails(String message) {
            script.add(() -> { throw new LlmException(message); });
            return this;
        }

        private static ChatResponse response(ChatMessage message, String finishReason) {
            return new ChatResponse("id", "fake",
                    List.of(new ChatResponse.Choice(0, message, finishReason)), null);
        }
    }

    // ---------- 假工具 ----------

    static Tool tool(String name, java.util.function.Function<JsonNode, ToolResult> body) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "测试用"; }
            public String parametersSchema() { return "{\"type\":\"object\"}"; }
            public ToolResult execute(JsonNode args) { return body.apply(args); }
        };
    }

    private Agent agent(FakeLlm llm, ToolRegistry tools) {
        return new Agent(llm, tools, "你是测试用的 agent");
    }

    // ---------- 测试 ----------

    @Test
    @DisplayName("模型不调工具：一轮就结束")
    void singleTurn() {
        FakeLlm llm = new FakeLlm().says("你好呀");
        Agent agent = agent(llm, new ToolRegistry());

        assertEquals("你好呀", agent.run("在吗"));

        List<ChatMessage> history = agent.history();
        assertEquals(3, history.size());
        assertEquals(ChatMessage.ROLE_SYSTEM, history.get(0).role());
        assertEquals(ChatMessage.ROLE_USER, history.get(1).role());
        assertEquals(ChatMessage.ROLE_ASSISTANT, history.get(2).role());
    }

    @Test
    @DisplayName("一次工具调用：历史按 user → assistant(tool_calls) → tool → assistant 攒起来")
    void oneToolCall() {
        FakeLlm llm = new FakeLlm()
                .calls("我先看看这个文件", ToolCall.of("call_1", "read_file", "{\"path\":\"a.txt\"}"))
                .says("文件里写的是 hello");
        ToolRegistry tools = new ToolRegistry().register(tool("read_file", a -> ToolResult.ok("hello")));

        assertEquals("文件里写的是 hello", agent(llm, tools).run("a.txt 里是什么"));

        List<ChatMessage> h = llm.received.get(1);   // 第二次请求时带的历史
        assertEquals(4, h.size());
        assertEquals(ChatMessage.ROLE_USER, h.get(1).role());
        assertTrue(h.get(2).hasToolCalls(), "assistant 消息必须原样带着 tool_calls 进历史");
        assertEquals(ChatMessage.ROLE_TOOL, h.get(3).role());
        assertEquals("call_1", h.get(3).toolCallId(), "tool 消息的 id 必须和点单对上");
        assertEquals("hello", h.get(3).content());
    }

    @Test
    @DisplayName("模型一次点多个工具：必须全部执行、全部回传，一条都不能少")
    void multipleToolCallsInOneTurn() {
        FakeLlm llm = new FakeLlm()
                .calls(null,
                        ToolCall.of("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                        ToolCall.of("call_2", "read_file", "{\"path\":\"b.txt\"}"),
                        ToolCall.of("call_3", "list_dir", "{}"))
                .says("看完了");
        ToolRegistry tools = new ToolRegistry()
                .register(tool("read_file", a -> ToolResult.ok("内容")))
                .register(tool("list_dir", a -> ToolResult.ok("a.txt\nb.txt")));

        agent(llm, tools).run("看看这几个");

        List<ChatMessage> h = llm.received.get(1);
        List<ChatMessage> toolMsgs = h.stream()
                .filter(m -> ChatMessage.ROLE_TOOL.equals(m.role())).toList();
        assertEquals(3, toolMsgs.size(), "点了 3 个工具就得回 3 条结果，少一条下轮请求必然 400");
        assertEquals(List.of("call_1", "call_2", "call_3"),
                toolMsgs.stream().map(ChatMessage::toolCallId).toList());
    }

    @Test
    @DisplayName("工具执行失败：错误信息照样回传给模型，循环继续")
    void toolFailureIsFedBack() {
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("call_1", "read_file", "{\"path\":\"nope.txt\"}"))
                .calls(null, ToolCall.of("call_2", "read_file", "{\"path\":\"a.txt\"}"))
                .says("第一个文件不存在，第二个内容是 hello");
        ToolRegistry tools = new ToolRegistry().register(tool("read_file", a ->
                a.get("path").asText().equals("a.txt")
                        ? ToolResult.ok("hello")
                        : ToolResult.error("文件不存在")));

        String answer = agent(llm, tools).run("读这两个文件");

        assertTrue(answer.contains("hello"));
        assertTrue(llm.received.get(1).get(3).content().contains("文件不存在"),
                "工具的错误必须原样交给模型，它才知道换个路径重试");
    }

    @Test
    @DisplayName("每一轮都把完整历史重发一遍——所谓记忆就是这个")
    void historyGrowsEveryRound() {
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("call_1", "t", "{}"))
                .calls(null, ToolCall.of("call_2", "t", "{}"))
                .says("好了");
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> ToolResult.ok("ok")));

        agent(llm, tools).run("干活");

        assertEquals(2, llm.received.get(0).size());   // system + user
        assertEquals(4, llm.received.get(1).size());   // + assistant + tool
        assertEquals(6, llm.received.get(2).size());   // + assistant + tool
    }

    @Test
    @DisplayName("每轮都带上工具菜单")
    void toolsSentEveryRound() {
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("call_1", "t", "{}"))
                .says("好了");
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> ToolResult.ok("ok")));

        agent(llm, tools).run("干活");

        assertEquals(1, llm.receivedTools.get(0).size());
        assertEquals(1, llm.receivedTools.get(1).size());
    }

    @Test
    @DisplayName("模型死循环时被步数上限拦住，最后一次请求不带工具逼它收口")
    void stopsAtMaxSteps() {
        FakeLlm llm = new FakeLlm();
        for (int i = 0; i < 3; i++) {
            llm.calls(null, ToolCall.of("call_" + i, "t", "{}"));   // 三轮都在调工具，不肯收敛
        }
        llm.says("我卡住了，目前只读到了一半");                        // 收尾请求的回答
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> ToolResult.ok("ok")));

        Agent agent = new Agent(llm, tools, "sys", 3);
        String answer = agent.run("干个没完的活");

        assertEquals(4, llm.received.size(), "3 轮循环 + 1 次收尾请求");
        assertNull(llm.receivedTools.get(3), "收尾那次不能给工具，否则它还会接着调");
        assertTrue(answer.contains("我卡住了"));
        assertTrue(answer.contains("最大步数"), "要明确告诉用户是被上限截断的，不是真做完了");
    }

    @Test
    @DisplayName("模型调用失败：历史回滚干净，不留下残缺消息毁掉整个会话")
    void rollsBackOnLlmFailure() {
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("call_1", "t", "{}"))
                .fails("限流了")
                .says("这次好了");
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> ToolResult.ok("ok")));
        Agent agent = agent(llm, tools);

        String first = agent.run("干活");
        assertTrue(first.contains("限流了"));
        assertEquals(1, agent.history().size(),
                "失败后历史里只该剩 system —— 留下孤儿 tool_calls 会让之后每次请求都 400");

        // 会话没被毁掉，还能接着用
        assertEquals("这次好了", agent.run("再试一次"));
    }

    @Test
    @DisplayName("事件按 思考→想法→工具开始→工具结束→完成 的顺序发出")
    void emitsEventsInOrder() {
        FakeLlm llm = new FakeLlm()
                .calls("我先读一下", ToolCall.of("call_1", "read_file", "{\"path\":\"a.txt\"}"))
                .says("读完了");
        ToolRegistry tools = new ToolRegistry().register(tool("read_file", a -> ToolResult.ok("hello")));

        List<String> seen = new ArrayList<>();
        agent(llm, tools).onEvent(e -> seen.add(describe(e))).run("读 a.txt");

        assertEquals(List.of("思考1", "想法:我先读一下", "调用:read_file", "结果:true",
                "思考2", "完成2"), seen);
    }

    /** switch 的模式匹配要 Java 21 才转正，17 上只能用 instanceof 链。 */
    private static String describe(AgentEvent e) {
        if (e instanceof AgentEvent.Thinking t) return "思考" + t.step();
        if (e instanceof AgentEvent.Thought t) return "想法:" + t.text();
        if (e instanceof AgentEvent.ToolStarted t) return "调用:" + t.tool();
        if (e instanceof AgentEvent.ToolFinished t) return "结果:" + t.ok();
        if (e instanceof AgentEvent.Done d) return "完成" + d.steps();
        if (e instanceof AgentEvent.Failed f) return "失败";
        throw new IllegalStateException("未处理的事件类型: " + e);
    }

    @Test
    @DisplayName("reset 后回到只有 system 的状态")
    void resetClearsHistory() {
        FakeLlm llm = new FakeLlm().says("好");
        Agent agent = agent(llm, new ToolRegistry());
        agent.run("你好");
        assertEquals(3, agent.history().size());

        agent.reset();
        assertEquals(1, agent.history().size());
    }
}
