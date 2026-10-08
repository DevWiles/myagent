package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.StreamListener;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.context.Precompute;
import dev.wiles.myagent.context.HistoryRepair;
import dev.wiles.myagent.context.Interruption;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.builtin.ReadFileTool;
import dev.wiles.myagent.tool.builtin.WriteFileTool;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import dev.wiles.myagent.tool.safety.FileWriteGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
        int reduceRequests = 0;

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            // 压缩时发来的请求走单独分支，不占用剧本。
            // 两种都要认：map 一块一次，reduce 把多块并成一段（只有一块时它压根不发请求）。
            // 只认 map 的话，多段场景下 reduce 那次会漏进剧本，
            // 表现成「剧本演完了，agent 却还在请求第 N 次」——和被测行为毫无关系
            String system = messages.get(0).content();
            if (system.contains("压缩对话历史")) {
                summaryRequests++;
                return response(ChatMessage.assistant("[摘要] 之前读过几个文件"), "stop");
            }
            if (system.contains("分段摘要合并")) {
                reduceRequests++;
                return response(ChatMessage.assistant("[合并摘要] 之前读写过几个文件"), "stop");
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
    @DisplayName("单轮撑破窗口：说清出路，且**不提示**「说一句继续」——继续会原地撞同一堵墙")
    void oversizedTurnGivesAWayOut() {
        FakeLlm llm = new FakeLlm();      // 剧本是空的：这一轮压根不该发出去
        Agent agent = new Agent(llm, new ToolRegistry(), "sys", 25, new ContextManager(4_000));

        String answer = agent.run("请总结：" + "这是一段很长的粘贴内容。".repeat(600));

        assertTrue(llm.received.isEmpty(), "明知道发不出去就别发，白烧一次失败的请求");
        assertTrue(answer.contains("超过了上下文窗口"));
        assertTrue(answer.contains("contextTokens"), "得告诉用户怎么办");
        assertFalse(answer.contains("/compact"), "压缩救不了这种情况，提了就是误导");
        // 断点的全部意义是「说一句继续就接着干」。这种失败重试一万次还是同一个结果，
        // 撑破窗口的那条消息还在记录里（记录只追加），提示续做等于让用户无限循环
        assertTrue(agent.unfinished().isEmpty(), "不该留下断点");
        assertTrue(HistoryRepair.isIntact(agent.conversation()), "封口了，下一句话还发得出去");
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
    @DisplayName("/compact 全程报进度，而且收尾那一条无条件发")
    void compactNowReportsProgressAndAlwaysFinishes() {
        FakeLlm llm = new FakeLlm().says("好").says("好").says("好").says("好");
        Agent agent = new Agent(llm, new ToolRegistry(), "sys", 25, new ContextManager(4_000));
        for (int i = 0; i < 4; i++) {
            agent.run("第 " + i + " 个问题");
        }

        List<AgentEvent> events = new ArrayList<>();
        agent.onEvent(events::add);
        assertNotNull(agent.compactNow(), "前提：这段记录压得动");

        List<AgentEvent.CompactProgress> progress = events.stream()
                .filter(e -> e instanceof AgentEvent.CompactProgress)
                .map(e -> (AgentEvent.CompactProgress) e)
                .toList();
        assertFalse(progress.isEmpty(), "一条进度都没有，界面上就是一块不动的屏幕");
        // 少了这一条，那根条就永远停在 87% 不动，而后台其实早就结束了
        assertEquals(AgentEvent.CompactProgress.PHASE_DONE,
                progress.get(progress.size() - 1).phase(),
                "最后一条必须是收尾，界面靠它撤进度条");
    }

    @Test
    @DisplayName("压不动的时候也要发收尾，否则进度条永远挂着")
    void finishIsSentEvenWhenNothingGetsCompacted() {
        FakeLlm llm = new FakeLlm().says("好");
        Agent agent = new Agent(llm, new ToolRegistry(), "sys", 25, new ContextManager(4_000));
        agent.run("就说一句");          // 一轮而已，凑不出可压的区间

        List<AgentEvent> events = new ArrayList<>();
        agent.onEvent(events::add);

        assertNull(agent.compactNow(), "前提：这段记录压不动");
        // 这一路走的是 canAdvance() == false 的提前返回。它同样不能留下一根挂着的条
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.CompactProgress),
                "压根没开始压，就不该建进度条——闪一下再消失比没有更让人疑惑");
    }

    @Test
    @DisplayName("按 Esc 取消压缩：一个段都不留，下次重来")
    void cancelledCompactionKeepsNothing() {
        FakeLlm llm = new FakeLlm().says("好").says("好").says("好").says("好");
        Agent[] holder = new Agent[1];
        // Esc 必须发生在压缩**途中**——压缩一开始会把上一轮的残留标志清掉
        // （和 run() 一样），所以提前按是不算数的
        LlmClient cancelsMidway = new LlmClient() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                if (messages.get(0).content().contains("压缩对话历史")) {
                    holder[0].cancel();
                }
                return llm.chat(messages, tools);
            }

            @Override
            public String modelName() {
                return "fake";
            }
        };
        Agent agent = new Agent(cancelsMidway, new ToolRegistry(), "sys", 25,
                new ContextManager(4_000));
        holder[0] = agent;
        for (int i = 0; i < 4; i++) {
            agent.run("第 " + i + " 个问题");
        }

        assertNull(agent.compactNow(), "取消了就不该落地");
        // 段是**一辈子缓存**的，落地了就再也不会重算。取消时留下降级出来的占位文本，
        // 等于把「（摘要没能生成）」永久焊死在这段历史上
        assertTrue(agent.segments().isEmpty(), "取消之后一个段都不该留下");
        assertFalse(agent.hasCompacted(), "取消之后不该显示成已压缩");
    }

    @Test
    @DisplayName("过期的待用件不许落地——边界只能往前，不能倒退")
    void staleWorkInHandIsRejected() {
        FakeLlm llm = new FakeLlm();
        for (int i = 0; i < 9; i++) {
            llm.says("好");
        }
        ContextManager context = new ContextManager(4_000);
        Agent agent = new Agent(llm, new ToolRegistry(), "sys", 25, context);
        for (int i = 0; i < 5; i++) {
            agent.run("第 " + i + " 个问题");
        }
        // 定格一份**当下**的记录，待会儿拿它算一份边界靠前的待用件。
        // 必须够 5 轮：不够的话算出来是 null，这个用例就成了空跑
        List<ChatMessage> early = List.copyOf(agent.conversation());

        for (int i = 5; i < 9; i++) {
            agent.run("第 " + i + " 个问题");
        }
        assertNotNull(agent.compactNow(), "前提：先正常压一次，把边界推上去");
        int landed = agent.compaction().coversUpTo();
        assertTrue(landed > 0);

        // 现在挂上一份照老边界算的现货。Precompute.start 在有任务在途时是空操作，
        // 所以这种「好几轮之前算的、一直没人取走」的待用件是真实存在的状态
        Precompute stale = new Precompute(context, () -> llm);
        stale.start(early, List.of());
        while (!stale.settled()) {
            Thread.onSpinWait();
        }
        assertTrue(stale.ready(), "前提：这份过期货真的算出东西了，否则下面全是空跑");
        agent.withPrecompute(stale);

        assertNull(agent.compactNow(), "过期货落地会让摘要边界倒退，得当没有");
        assertEquals(landed, agent.compaction().coversUpTo(), "边界被推回去了");
    }

    @Test
    @DisplayName("接回压缩过的会话：摘要和钉住块都还在，不是从 0 条重新开始")
    void restoredSessionKeepsItsCompaction() {
        String huge = "很长的文件内容 ".repeat(400);
        ToolRegistry tools = new ToolRegistry()
                .register(tool("write_file", a -> ToolResult.ok(huge)));
        FakeLlm first = new FakeLlm();
        for (int i = 1; i <= 4; i++) {
            first.calls(null, ToolCall.of("c" + i, "write_file", "{\"path\":\"f" + i + ".java\"}"))
                    .says("第 " + i + " 个写完了");
        }

        Agent before = new Agent(first, tools, "sys", 25, new ContextManager(3_000))
                .withWritingTools(java.util.Set.of("write_file"));
        before.run("写第 1 个文件");
        before.run("写第 2 个文件");
        before.run("写第 3 个文件");
        before.run("写第 4 个文件");

        assertTrue(before.hasCompacted(), "前提没成立：这段会话根本没压过，后面的断言就不成立了");
        ContextManager.Compaction saved = before.compaction();

        // 存盘 → 退出 → 重进 → 接回来
        FakeLlm second = new FakeLlm().says("接着聊");
        Agent after = new Agent(second, tools, "sys", 25, new ContextManager(3_000))
                .withWritingTools(java.util.Set.of("write_file"));
        after.restore(before.conversation(), saved);

        assertTrue(after.hasCompacted(), "压过这件事不该因为重启就忘了");

        after.run("接着说");

        List<ChatMessage> window = second.received.get(0);
        assertTrue(window.stream().anyMatch(m -> m.content() != null
                        && m.content().startsWith("[以下是早先对话的摘要")),
                "摘要块必须跟着回来——旧实现这里是空的，整段历史原样进窗口，"
                        + "下一轮直接判到 FORCE，在主线程白跑一次 reduce");
        assertTrue(window.stream().anyMatch(m -> m.content() != null
                        && m.content().startsWith("[本次会话的固定事实")),
                "钉住块跟着 coversUpTo 走，边界没接回来它就不生成——"
                        + "而 /resume 正是最需要「别忘了在干什么」的时刻");
        assertTrue(window.stream().anyMatch(m -> m.content() != null
                        && m.content().contains("f1.java")),
                "最初那几个文件是从记录里算出来的，压缩丢不掉它们");
    }

    @Test
    @DisplayName("接回来的压缩状态下标对不上就整份丢掉，段留着")
    void restoreDropsCompactionThatDoesNotFitTheRecord() {
        Agent agent = new Agent(new FakeLlm(), new ToolRegistry(), "sys");

        agent.restore(List.of(ChatMessage.user("问"), ChatMessage.assistant("答")),
                new ContextManager.Compaction(
                        List.of(new dev.wiles.myagent.context.SummarySegment(0, 2, "对得上的段")),
                        "说自己覆盖到第 99 条的摘要", 99));

        assertFalse(agent.hasCompacted(), "边界越界，摘要不许认——尾巴会切在不存在的位置上");
        assertEquals(1, agent.segments().size(), "段本身对得上，留着下次省一次 map");
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
    @DisplayName("等审批时按停，再说「继续」：模型看到的是「没执行」，不是「用户拒绝」")
    void continueAfterCancelDuringApproval(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Main.java"), "原有代码");
        FakeLlm llm = new FakeLlm()
                .calls("改一下再读回来",
                        ToolCall.of("c1", "write_file", "{\"path\":\"Main.java\",\"content\":\"新代码\"}"),
                        ToolCall.of("c2", "read_file", "{\"path\":\"Main.java\"}"))
                .says("接着改完了");
        Agent[] holder = new Agent[1];
        // 照 RemoteApproval 的真实顺序：用户按停 → cancel() 先到 → 卡着的审批被放行成「取消」
        ApprovalPolicy stoppedWhileAsking = request -> {
            holder[0].cancel();
            return ApprovalVerdict.interrupted();
        };
        Workspace workspace = new Workspace(dir);
        ToolRegistry tools = new ToolRegistry()
                .register(new WriteFileTool(workspace, new FileWriteGuard(), stoppedWhileAsking))
                .register(new ReadFileTool(workspace));
        Agent agent = agent(llm, tools);
        holder[0] = agent;

        assertEquals("已取消。", agent.run("把 Main 改了"));
        agent.run("继续");

        List<ChatMessage> sent = llm.received.get(1);
        String c1 = resultOf(sent, "c1");
        String c2 = resultOf(sent, "c2");
        assertFalse(c1.contains("拒绝"),
                "用户只是按了停。写成拒绝，模型听到「继续」会绕开这次修改: " + c1);
        assertTrue(c1.contains("已取消") && c1.contains("没有执行"), c1);
        assertTrue(c2.contains("已取消") && c2.contains("没有执行"), "排在后面的调用是封口补的: " + c2);
        assertTrue(sent.get(sent.size() - 1).content().endsWith("\n\n继续"),
                "「继续」不认关键词，原话原样留着，只是前面拼了中断说明");
        assertEquals("原有代码", Files.readString(dir.resolve("Main.java")));
    }

    // ---------- 流式输出到一半断掉 ----------

    /** 能控制流式分片的假模型：每一步拿到 listener，自己决定吐几个字、然后成功还是断掉。 */
    static final class StreamingLlm implements LlmClient {
        final List<java.util.function.Function<StreamListener, ChatResponse>> script = new ArrayList<>();
        final List<List<ChatMessage>> received = new ArrayList<>();

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            throw new AssertionError("Agent 应当走流式那条路");
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools, StreamListener listener) {
            received.add(List.copyOf(messages));
            return script.remove(0).apply(listener);
        }

        @Override
        public String modelName() {
            return "fake";
        }

        StreamingLlm then(java.util.function.Function<StreamListener, ChatResponse> step) {
            script.add(step);
            return this;
        }

        static ChatResponse reply(ChatMessage message) {
            return new ChatResponse("id", "fake", List.of(new ChatResponse.Choice(0, message,
                    message.hasToolCalls() ? "tool_calls" : "stop")), null);
        }
    }

    @Test
    @DisplayName("说到一半按停：屏幕上出过的半段话留在历史里，接着做时模型知道自己说到哪了")
    void partialTextSurvivesCancel() {
        Agent[] holder = new Agent[1];
        StreamingLlm llm = new StreamingLlm()
                .then(listener -> {
                    listener.onTextDelta("问题出在 Parser 第 42 行，");
                    listener.onTextDelta("空指针是因为");
                    holder[0].cancel();                       // 用户按停，请求被掐断
                    throw new LlmException("请求已取消");
                })
                .then(listener -> StreamingLlm.reply(ChatMessage.assistant("接着说：token 为 null 时没判空")));
        Agent agent = new Agent(llm, new ToolRegistry(), "sys");
        holder[0] = agent;

        assertEquals("已取消。", agent.run("为什么崩了"));
        agent.run("继续");

        List<ChatMessage> sent = llm.received.get(1);
        assertTrue(HistoryRepair.isIntact(sent.subList(1, sent.size() - 1)), "去掉 system 和刚说的「继续」，前面得自洽");
        ChatMessage partial = sent.get(sent.size() - 2);
        assertEquals(ChatMessage.ROLE_ASSISTANT, partial.role());
        assertTrue(partial.content().startsWith("问题出在 Parser 第 42 行，空指针是因为"),
                "用户看着它说了半句，模型自己却不知道——「继续」时它只能从头再分析一遍: " + partial.content());
        assertTrue(partial.content().contains("已取消") && partial.content().contains("没说完"),
                "得标明是被打断的，否则模型会把半句话当成说完了的结论: " + partial.content());
        assertEquals(1, sent.stream().filter(m -> ChatMessage.ROLE_ASSISTANT.equals(m.role())).count(),
                "半段话自己就是封口，不该再多补一条「上一轮没有做完」");
    }

    @Test
    @DisplayName("说到一半断网：半段话同样留下，标的是断网的原因")
    void partialTextSurvivesNetworkFailure() {
        StreamingLlm llm = new StreamingLlm()
                .then(listener -> {
                    listener.onTextDelta("我的方案是先改接口，");
                    throw new LlmException("流式请求失败: connection reset");
                });
        Agent agent = new Agent(llm, new ToolRegistry(), "sys");

        agent.run("怎么改");

        ChatMessage last = agent.history().get(agent.history().size() - 1);
        assertTrue(last.content().startsWith("我的方案是先改接口，"), last.content());
        assertTrue(last.content().contains("网络或模型调用失败"), last.content());
        assertTrue(agent.unfinished().isPresent(), "断网要能接着做");
    }

    @Test
    @DisplayName("上一个请求说完整了的话不能被当成半段话再补一遍")
    void completedTextIsNotDuplicated() {
        Agent[] holder = new Agent[1];
        StreamingLlm llm = new StreamingLlm()
                .then(listener -> {
                    listener.onTextDelta("我先看看配置");
                    return StreamingLlm.reply(ChatMessage.assistant("我先看看配置",
                            List.of(ToolCall.of("c1", "t", "{}"))));
                });
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> {
            holder[0].cancel();                               // 工具跑着时按停，此刻没有请求在途
            return ToolResult.ok("ok");
        }));
        Agent agent = new Agent(llm, tools, "sys");
        holder[0] = agent;

        agent.run("看看");

        long mentions = agent.history().stream()
                .filter(m -> m.content() != null && m.content().contains("我先看看配置")).count();
        assertEquals(1, mentions, "那句话已经随 assistant 消息进了历史，再补一遍模型会以为自己说了两次");
        assertEquals("(上一轮已取消，没有做完)",
                agent.history().get(agent.history().size() - 1).content());
    }

    @Test
    @DisplayName("被打断时发出的 failed 带着断点，正常说完后断点清掉")
    void failedEventCarriesInterruption() {
        Agent[] holder = new Agent[1];
        FakeLlm llm = new FakeLlm()
                .calls(null, ToolCall.of("c1", "t", "{}"))
                .says("接上了");
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> {
            holder[0].cancel();
            return ToolResult.ok("ok");
        }));
        List<AgentEvent> events = new ArrayList<>();
        Agent agent = agent(llm, tools).onEvent(events::add);
        holder[0] = agent;

        agent.run("把 Main 改了");

        AgentEvent.Failed failed = events.stream()
                .filter(AgentEvent.Failed.class::isInstance).map(AgentEvent.Failed.class::cast)
                .findFirst().orElseThrow();
        assertNotNull(failed.interrupted(), "界面全靠它决定要不要提示「继续」");
        assertEquals("把 Main 改了", failed.interrupted().userInput());

        agent.run("继续");
        assertTrue(agent.unfinished().isEmpty(), "接上并说完了，再提示「继续」就是误导");
    }

    // ---------- 断点之后的第一句话：前面拼上中断说明 ----------

    /** 跑一轮、在第一个工具上按停；返回 agent，下一句话由调用方说。 */
    private Agent interruptedAgent(FakeLlm llm, long[] now) {
        Agent[] holder = new Agent[1];
        ToolRegistry tools = new ToolRegistry().register(tool("t", a -> {
            holder[0].cancel();
            return ToolResult.ok("ok");
        }));
        Agent agent = agent(llm, tools).clock(() -> now[0]);
        holder[0] = agent;
        agent.run("把 Parser 的空指针修了");
        return agent;
    }

    private static String lastUser(List<ChatMessage> sent) {
        return sent.get(sent.size() - 1).content();
    }

    @Test
    @DisplayName("断点之后说「继续」：前面拼上中断说明，以用户的口吻交代现场怎么读")
    void resumeNotePrefixesNextMessage() {
        long[] now = {1_000_000L};
        FakeLlm llm = new FakeLlm().calls(null, ToolCall.of("c1", "t", "{}")).says("接上了");
        Agent agent = interruptedAgent(llm, now);

        agent.run("继续");

        String sent = lastUser(llm.received.get(1));
        assertTrue(sent.endsWith("\n\n继续"), "用户的原话得原样留在最后: " + sent);
        assertTrue(sent.startsWith("<turn_interrupted>"), "要和用户的话分得开: " + sent);
        assertTrue(sent.contains("第 2 步断了") && sent.contains("叫停"),
                "第 1 步的工具跑完了，是在第 2 步开头叫停的: " + sent);
        assertTrue(sent.contains("没有执行"), "得告诉模型怎么读封口补的那几条: " + sent);
        assertTrue(sent.contains("先检查"), "执行到一半的可能已部分生效（照 Codex）: " + sent);
        assertTrue(sent.contains("不要重做"), sent);
        assertFalse(sent.contains("把 Parser 的空指针修了"),
                "不重发原任务——Cline 踩过：原话再念一遍，模型会把做完的部分从头再做（#12975）");
    }

    @Test
    @DisplayName("说明只拼一次：接上并说完之后，再下一句就是原话")
    void resumeNoteOnlyOnce() {
        long[] now = {1_000_000L};
        FakeLlm llm = new FakeLlm().calls(null, ToolCall.of("c1", "t", "{}")).says("接上了").says("好");
        Agent agent = interruptedAgent(llm, now);

        agent.run("继续");
        agent.run("再看看测试");

        assertEquals("再看看测试", lastUser(llm.received.get(2)));
    }

    @Test
    @DisplayName("没断过就不拼——平常的每一句话都原样发")
    void noNoteWithoutInterruption() {
        FakeLlm llm = new FakeLlm().says("好");
        agent(llm, new ToolRegistry()).run("在吗");

        assertEquals("在吗", lastUser(llm.received.get(0)));
    }

    @Test
    @DisplayName("隔了很久才接：提醒文件可能被改过（照 Cline）；刚断就接不啰嗦")
    void longGapWarnsAboutStaleWorkspace() {
        long[] now = {1_000_000L};
        FakeLlm llm = new FakeLlm().calls(null, ToolCall.of("c1", "t", "{}")).says("接上了");
        Agent agent = interruptedAgent(llm, now);
        now[0] += 45 * 60_000L;

        agent.run("继续");

        String sent = lastUser(llm.received.get(1));
        assertTrue(sent.contains("45 分钟") && sent.contains("核对"), sent);

        long[] soon = {1_000_000L};
        FakeLlm quick = new FakeLlm().calls(null, ToolCall.of("c1", "t", "{}")).says("接上了");
        Agent fast = interruptedAgent(quick, soon);
        soon[0] += 30_000L;
        fast.run("继续");
        assertFalse(lastUser(quick.received.get(1)).contains("核对"), "刚断就接，提醒就是噪音");
    }

    @Test
    @DisplayName("从存档接回来的断点照样生效：下一句前面拼说明，按真实的间隔提醒核对")
    void restoredInterruptionStillPrefixes() {
        long[] now = {1_000_000L + 2 * 3_600_000L};
        FakeLlm llm = new FakeLlm().says("接上了");
        Agent agent = agent(llm, new ToolRegistry()).clock(() -> now[0]);
        Interruption cut = new Interruption("已取消", "把 Parser 修了", 3, 1_000_000L, 2);

        agent.restore(List.of(
                        ChatMessage.user("把 Parser 修了"),
                        ChatMessage.assistant("(上一轮已取消，没有做完)")),
                null, cut);
        assertEquals(cut, agent.unfinished().orElseThrow(), "界面靠它提示「继续」");

        agent.run("继续");
        String sent = lastUser(llm.received.get(0));
        assertTrue(sent.startsWith("<turn_interrupted>") && sent.contains("约 2 小时"), sent);
    }

    @Test
    @DisplayName("存档里的断点要对得上历史：条数不一样（被回退过、手改过）就不认")
    void restoredInterruptionMustMatchHistory() {
        Agent agent = agent(new FakeLlm(), new ToolRegistry());

        // 断点记的是 4 条，存档里只有 2 条——中间被回退过，那个断点指的是另一段现场
        agent.restore(List.of(ChatMessage.user("在吗"), ChatMessage.assistant("在的，有什么事")),
                null, new Interruption("已取消", "在吗", 1, 1L, 4));

        assertTrue(agent.unfinished().isEmpty(),
                "那一轮明明说完了，还提示「继续」就是在误导");
    }

    private static String resultOf(List<ChatMessage> messages, String toolCallId) {
        return messages.stream()
                .filter(m -> toolCallId.equals(m.toolCallId()))
                .map(ChatMessage::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError(toolCallId + " 没有结果，下一次请求会 400"));
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
