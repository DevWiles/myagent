package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    /** 固定返回一句摘要的假模型。 */
    static LlmClient summarizer(String summary) {
        return new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                assertNull(t, "生成摘要时不该给工具，否则模型可能又去调工具");
                return new ChatResponse("x", "f", List.of(new ChatResponse.Choice(
                        0, ChatMessage.assistant(summary), "stop")), null);
            }
            public String modelName() { return "fake"; }
        };
    }

    static LlmClient failingSummarizer() {
        return new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                throw new LlmException("限流了");
            }
            public String modelName() { return "fake"; }
        };
    }

    private static String repeat(String s, int times) {
        return s.repeat(times);
    }

    /** 造一段「用户提问 → 模型调工具 → 工具返回一大坨 → 模型回答」的历史。 */
    private static List<ChatMessage> conversation(int rounds, int toolOutputSize) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system("你是 myagent"));
        for (int i = 0; i < rounds; i++) {
            history.add(ChatMessage.user("第 " + i + " 个问题"));
            history.add(ChatMessage.assistant(null,
                    List.of(ToolCall.of("call_" + i, "read_file", "{\"path\":\"f" + i + ".txt\"}"))));
            history.add(ChatMessage.toolResult("call_" + i, repeat("文件内容 ", toolOutputSize)));
            history.add(ChatMessage.assistant("第 " + i + " 个回答"));
        }
        return history;
    }

    @Test
    @DisplayName("token 估算：中文比英文更费 token")
    void estimatesTokens() {
        assertTrue(TokenEstimator.estimate("你好世界") > 0);
        assertTrue(TokenEstimator.estimate("你好世界") > TokenEstimator.estimate("abcd"),
                "同样 4 个字符，中文占的 token 更多");
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(0, TokenEstimator.estimate((String) null));
    }

    @Test
    @DisplayName("工具菜单也要算进预算——它每轮都重发")
    void countsToolSpecs() {
        ToolSpec spec = ToolSpec.of("read_file", "读取文件内容",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}");
        assertTrue(TokenEstimator.estimateTools(List.of(spec)) > 0);
        assertEquals(0, TokenEstimator.estimateTools(null));
    }

    @Test
    @DisplayName("没超阈值时原样返回，不做任何压缩")
    void noCompactionWhenSmall() {
        ContextManager cm = new ContextManager(64_000, 0.75);
        List<ChatMessage> history = conversation(2, 10);

        assertFalse(cm.needsCompaction(history, List.of()));
    }

    @Test
    @DisplayName("第一刀：裁掉较早的长工具输出，最近的保持原样")
    void trimsOldToolOutputsFirst() {
        ContextManager cm = new ContextManager(4_000, 0.5);
        List<ChatMessage> history = conversation(6, 200);
        assertTrue(cm.needsCompaction(history, List.of()));

        ContextManager.Result result = cm.compact(history, List.of(), summarizer("摘要"));

        assertTrue(result.tokensAfter() < result.tokensBefore());
        assertTrue(result.saved() > 0);

        // 最后一条工具输出必须还是原文——模型很可能正要用它
        List<ChatMessage> messages = result.messages();
        ChatMessage lastTool = null;
        for (ChatMessage m : messages) {
            if (ChatMessage.ROLE_TOOL.equals(m.role())) {
                lastTool = m;
            }
        }
        assertNotNull(lastTool);
        assertFalse(lastTool.content().contains("已省略"), "最近的工具输出不能动");
    }

    @Test
    @DisplayName("第二刀：裁剪还不够时做摘要，system 永远保留在第一条")
    void summarizesWhenTrimmingIsNotEnough() {
        ContextManager cm = new ContextManager(2_000, 0.5);
        List<ChatMessage> history = conversation(10, 100);

        ContextManager.Result result = cm.compact(history, List.of(), summarizer("用户问了 10 个问题"));

        assertTrue(result.summarized());
        assertEquals(ChatMessage.ROLE_SYSTEM, result.messages().get(0).role(), "system 必须还在第一条");
        assertTrue(result.messages().get(1).content().contains("用户问了 10 个问题"));
        assertTrue(result.messages().size() < history.size());
        assertTrue(result.tokensAfter() < result.tokensBefore());
    }

    @Test
    @DisplayName("压缩后绝不能留下无主的 tool 消息——这是最要命的一条")
    void neverLeavesOrphanToolMessages() {
        ContextManager cm = new ContextManager(1_500, 0.5);

        for (int rounds = 3; rounds <= 12; rounds++) {
            List<ChatMessage> history = conversation(rounds, 150);
            List<ChatMessage> result = cm.compact(history, List.of(), summarizer("摘要")).messages();
            assertValidSequence(result, "rounds=" + rounds);
        }
    }

    /** 每条 tool 消息前面都必须有一条带对应 tool_call_id 的 assistant 消息。 */
    private static void assertValidSequence(List<ChatMessage> messages, String hint) {
        java.util.Set<String> pending = new java.util.HashSet<>();
        for (ChatMessage m : messages) {
            if (m.hasToolCalls()) {
                m.toolCallsOrEmpty().forEach(c -> pending.add(c.id()));
            } else if (ChatMessage.ROLE_TOOL.equals(m.role())) {
                assertTrue(pending.remove(m.toolCallId()),
                        hint + ": 出现了无主的 tool 消息 " + m.toolCallId() + "，这会让之后每次请求都 400");
            }
        }
    }

    @Test
    @DisplayName("摘要请求失败时降级成一句说明，不能让压缩本身把会话搞挂")
    void fallsBackWhenSummarizerFails() {
        ContextManager cm = new ContextManager(2_000, 0.5);
        List<ChatMessage> history = conversation(10, 100);

        ContextManager.Result result = cm.compact(history, List.of(), failingSummarizer());

        assertEquals(ChatMessage.ROLE_SYSTEM, result.messages().get(0).role());
        assertTrue(result.messages().get(1).content().contains("已被省略"));
        assertValidSequence(result.messages(), "降级路径");
    }

    @Test
    @DisplayName("summarizer 传 null 也能工作")
    void worksWithoutSummarizer() {
        ContextManager cm = new ContextManager(2_000, 0.5);
        ContextManager.Result result = cm.compact(conversation(10, 100), List.of(), null);
        assertValidSequence(result.messages(), "无 summarizer");
    }

    @Test
    @DisplayName("原始 history 不被修改，返回的是新列表")
    void doesNotMutateInput() {
        ContextManager cm = new ContextManager(2_000, 0.5);
        List<ChatMessage> history = conversation(10, 100);
        int sizeBefore = history.size();
        String firstToolContentBefore = history.get(3).content();

        cm.compact(history, List.of(), summarizer("摘要"));

        assertEquals(sizeBefore, history.size());
        assertEquals(firstToolContentBefore, history.get(3).content());
    }
}
