package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.context.HistoryRepair;
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

        int summaryRequests = 0;

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            // 压缩时发来的摘要请求走单独分支，不占用剧本
            if (messages.get(0).content().contains("压缩对话历史")) {
                summaryRequests++;
                return response(ChatMessage.assistant("[摘要] 之前读过几个文件"), "stop");
            }
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
    @DisplayName("断网：不回滚，把现场封口留在历史里——一句「继续」就能接着干")
    void sealsTheSceneOnLlmFailure() {
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("call_1", "t", "{}"))
                .fails("限流了")
                .says("这次好了");
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> ToolResult.ok("ok")));
        Agent agent = agent(llm, tools);

        String first = agent.run("干活");
        assertTrue(first.contains("限流了"));

        // 现场留着：用户原话、点过的工具、已经拿到的结果，一样都没少。
        // 回滚也能保证自洽，但那样「继续」就无从接起——模型什么都看不见
        List<ChatMessage> h = agent.history();
        assertEquals("干活", h.get(1).content());
        assertTrue(h.get(2).hasToolCalls());
        assertEquals("ok", h.get(3).content(), "工具真的跑完了，结果不该被抹掉");
        assertTrue(HistoryRepair.isIntact(agent.conversation()),
                "留现场的前提是历史仍然自洽，否则下一句话根本发不出去");
        assertEquals(ChatMessage.ROLE_ASSISTANT, h.get(h.size() - 1).role(),
                "末尾要收成 assistant，用户下一句才不会变成连续两条 user");

        Interruption broke = agent.unfinished().orElseThrow();
        assertEquals("干活", broke.userInput());
        assertEquals(2, broke.steps());
        assertTrue(broke.reason().contains("限流了"));

        // 一句「继续」就接着干，而且这次请求里带着上一轮的工具结果
        assertEquals("这次好了", agent.run("继续"));
        assertTrue(llm.received.get(2).stream().anyMatch(m -> "ok".equals(m.content())),
                "续做那次请求必须带着上一轮的现场，不然模型只能从头再猜一遍");
        assertTrue(agent.unfinished().isEmpty(), "这轮跑完了，断点不该还挂着");
    }

    @Test
    @DisplayName("错误体是一整页 HTML 也不整页塞进历史——那东西会跟着每一次请求重发")
    void clipsHugeErrorBodies() {
        String wholePage = "<html><body>" + "x".repeat(5000) + "</body></html>";
        FakeLlm llm = new FakeLlm().fails(wholePage).says("好了");
        Agent agent = agent(llm, new ToolRegistry());

        agent.run("干活");

        ChatMessage note = agent.history().get(agent.history().size() - 1);
        assertTrue(note.content().length() < 200,
                "封口消息长到 " + note.content().length() + " 字，等于每轮白烧一遍 token");
        assertEquals("好了", agent.run("继续"));
    }

    @Test
    @DisplayName("请求都没发出去就断：末尾也要收成 assistant，不留连续两条 user")
    void sealsEvenWhenNothingHappenedYet() {
        FakeLlm llm = new FakeLlm().fails("连不上").says("这次好了");
        Agent agent = agent(llm, new ToolRegistry());

        agent.run("干活");

        List<ChatMessage> h = agent.history();
        assertEquals(3, h.size());
        assertEquals(ChatMessage.ROLE_USER, h.get(1).role());
        assertEquals(ChatMessage.ROLE_ASSISTANT, h.get(2).role());

        assertEquals("这次好了", agent.run("继续"));
        List<ChatMessage> resumed = llm.received.get(1);
        assertEquals("干活", resumed.get(1).content(), "原本要干的活还在上下文里");
        assertEquals(ChatMessage.ROLE_ASSISTANT, resumed.get(2).role());
        assertEquals(ChatMessage.ROLE_USER, resumed.get(3).role(),
                "user / assistant 的交替没被破坏——reasoner 一类模型对这个有硬要求");
    }

    @Test
    @DisplayName("超限收尾那句「不要再调用工具」不留在历史里，否则续做时模型迟迟不动手")
    void dropsTheForceConclusionScaffold() {
        FakeLlm llm = new FakeLlm();
        for (int i = 0; i < 2; i++) {
            llm.calls(null, ToolCall.of("c" + i, "t", "{}"));
        }
        llm.says("我卡住了");          // 收尾请求的回答
        llm.says("接着干完了");        // 用户说「继续」之后
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> ToolResult.ok("ok")));

        Agent agent = new Agent(llm, tools, "sys", 2);
        agent.run("干个没完的活");

        assertTrue(agent.history().stream().noneMatch(
                        m -> m.content() != null && m.content().contains("请不要再调用工具")),
                "那是一次性脚手架。留着的话，续做时模型仍然看着这条禁令，"
                        + "只描述要做什么而不动手——现象长得像「模型变笨了」");
        assertTrue(HistoryRepair.isIntact(agent.conversation()));
        assertEquals("达到最大步数 2", agent.unfinished().orElseThrow().reason());

        agent.run("继续");
        assertTrue(llm.received.get(3).stream().noneMatch(
                        m -> m.content() != null && m.content().contains("请不要再调用工具")),
                "续做那次请求里也不该有它");
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

        // 不支持流式的客户端会退化成「一次性把全文当作一个增量发出」，
        // 所以这里也能看到 TextDelta——上层只需要认这一种事件
        assertEquals(List.of("思考1", "文本:我先读一下", "想法:我先读一下",
                "调用:read_file", "结果:true",
                "思考2", "文本:读完了", "完成2"), seen);
    }

    /** switch 的模式匹配要 Java 21 才转正，17 上只能用 instanceof 链。 */
    private static String describe(AgentEvent e) {
        if (e instanceof AgentEvent.Thinking t) return "思考" + t.step();
        if (e instanceof AgentEvent.TextDelta d) return "文本:" + d.text();
        if (e instanceof AgentEvent.Thought t) return "想法:" + t.text();
        if (e instanceof AgentEvent.ToolStarted t) return "调用:" + t.tool();
        if (e instanceof AgentEvent.ToolFinished t) return "结果:" + t.ok();
        if (e instanceof AgentEvent.Compacted c) return "压缩" + c.tokensBefore() + "->" + c.tokensAfter();
        if (e instanceof AgentEvent.Done d) return "完成" + d.steps();
        if (e instanceof AgentEvent.Failed f) return "失败";
        throw new IllegalStateException("未处理的事件类型: " + e);
    }

    @Test
    @DisplayName("上下文涨太大时自动压缩，压缩后对话照常进行")
    void compactsWhenContextGrows() {
        String huge = "很长的文件内容 ".repeat(400);
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("c1", "read_file", "{}"))
                .calls(null, ToolCall.of("c2", "read_file", "{}"))
                .calls(null, ToolCall.of("c3", "read_file", "{}"))
                .says("三个文件都看完了");
        ToolRegistry tools = new ToolRegistry().register(tool("read_file", a -> ToolResult.ok(huge)));

        List<AgentEvent> events = new ArrayList<>();
        Agent agent = new Agent(llm, tools, "sys", 25, new ContextManager(3_000))
                .onEvent(events::add);

        String answer = agent.run("读三个文件");

        assertEquals("三个文件都看完了", answer, "压缩之后对话必须还能正常走完");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Compacted),
                "上下文撑大了就该触发压缩");

        // 每一条都得是「变小了」。两个数的口径必须是同一把尺子——
        // 记录的大小不含 system、窗口含，混着用就会打出「1302 → 1598」这种越压越大的行
        events.stream().filter(e -> e instanceof AgentEvent.Compacted)
                .map(e -> (AgentEvent.Compacted) e)
                .forEach(c -> assertTrue(c.tokensAfter() < c.tokensBefore(),
                        "压完必须真的变小了，实际是 " + c.tokensBefore() + " -> " + c.tokensAfter()));
    }

    @Test
    @DisplayName("压缩后发给模型的消息序列依然合法，没有无主的 tool 消息")
    void compactedHistoryStaysValid() {
        String huge = "很长的文件内容 ".repeat(400);
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("c1", "read_file", "{}"))
                .calls(null, ToolCall.of("c2", "read_file", "{}"))
                .calls(null, ToolCall.of("c3", "read_file", "{}"))
                .says("好了");
        ToolRegistry tools = new ToolRegistry().register(tool("read_file", a -> ToolResult.ok(huge)));

        new Agent(llm, tools, "sys", 25, new ContextManager(3_000)).run("读文件");

        for (List<ChatMessage> sent : llm.received) {
            java.util.Set<String> pending = new java.util.HashSet<>();
            for (ChatMessage m : sent) {
                if (m.hasToolCalls()) {
                    m.toolCallsOrEmpty().forEach(x -> pending.add(x.id()));
                } else if (ChatMessage.ROLE_TOOL.equals(m.role())) {
                    assertTrue(pending.remove(m.toolCallId()),
                            "发出去的历史里出现无主 tool 消息: " + m.toolCallId());
                }
            }
        }
    }

    @Test
    @DisplayName("任务跑到一半被取消：立刻停下，历史回滚干净")
    void cancelStopsTheLoop() {
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("c1", "slow", "{}"))
                .calls(null, ToolCall.of("c2", "slow", "{}"))
                .says("不该走到这里");
        Agent[] holder = new Agent[1];
        // 第一个工具执行时用户按下了 Ctrl-C
        ToolRegistry tools = new ToolRegistry().register(tool("slow", a -> {
            holder[0].cancel();
            return ToolResult.ok("做完了");
        }));
        Agent agent = agent(llm, tools);
        holder[0] = agent;

        String answer = agent.run("干个长活");

        assertEquals("已取消。", answer);
        assertTrue(HistoryRepair.isIntact(agent.conversation()),
                "取消常发生在「要求调工具但结果还没回填」的当口，"
                        + "残缺序列留下会让之后每次请求都 400");
        assertTrue(agent.history().stream().anyMatch(m -> "做完了".equals(m.content())),
                "第一个工具确实执行完了，按 Ctrl-C 多半是嫌它跑偏，不是要作废已经做完的部分");
        assertEquals("已取消", agent.unfinished().orElseThrow().reason());
    }

    @Test
    @DisplayName("取消会掐断正在进行的请求，不是干等模型说完")
    void cancelAlsoCancelsTheRequest() {
        boolean[] requestCancelled = {false};
        LlmClient client = new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                return new ChatResponse("x", "f", List.of(new ChatResponse.Choice(
                        0, ChatMessage.assistant("好"), "stop")), null);
            }

            public String modelName() {
                return "fake";
            }

            @Override
            public void cancel() {
                requestCancelled[0] = true;
            }
        };

        new Agent(client, new ToolRegistry(), "sys").cancel();

        assertTrue(requestCancelled[0], "光置标志没用，主线程还阻塞在读响应上");
    }

    @Test
    @DisplayName("取消之后还能继续正常对话")
    void worksAfterCancel() {
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("c1", "t", "{}"))
                .says("这次好了");
        Agent[] holder = new Agent[1];
        boolean[] first = {true};
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> {
            if (first[0]) {
                first[0] = false;
                holder[0].cancel();
            }
            return ToolResult.ok("ok");
        }));
        Agent agent = agent(llm, tools);
        holder[0] = agent;

        assertEquals("已取消。", agent.run("第一次"));
        assertEquals("这次好了", agent.run("第二次"), "取消不该把会话搞坏");
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
