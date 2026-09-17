package dev.wiles.myagent.context;

import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.memory.MemoryEntry;
import dev.wiles.myagent.memory.MemoryIndex;
import dev.wiles.myagent.memory.MemoryScope;
import dev.wiles.myagent.memory.MemorySource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 长期记忆的索引住在 system 消息里，而 system 消息要经受上下文压缩。
 *
 * <p>压缩有三条路径（裁工具输出 / 摘要 / 兜底全裁），任何一条把 system 动了，
 * 后果都是<b>长期记忆静默失效</b>：文件还在盘上，日志也没有报错，
 * 模型就是突然不知道那些事了——而且只在长对话里发生，短对话复现不出来。
 *
 * <p>读代码能确认当前三条路径都保住了 system（{@code merged.add(work.get(0))}），
 * 但这是地基，不能只靠「我读过代码」。这个测试就是钉子：
 * 将来谁动压缩逻辑，先在这儿绊一跤。
 */
class MemorySurvivesCompactionTest {

    private static final String MEMORY_NAME = "mvn-offline";

    /** 带三条记忆索引的系统提示词，就是真实运行时第 0 条的样子。 */
    private static String promptWithMemory() {
        return SystemPrompt.forWorkspace(Path.of("/tmp/proj"), MemoryIndex.of(List.of(
                mem(MEMORY_NAME, "跑测试要加 -o，这台机器连 central 很慢"),
                mem("no-agent-framework", "这个项目禁止引入任何 agent 框架"),
                mem("prefers-cn", "始终用中文回复"))));
    }

    private static MemoryEntry mem(String name, String description) {
        return new MemoryEntry(name, description, "正文", MemoryScope.PROJECT,
                MemorySource.USER_CORRECTION, LocalDate.now());
    }

    private static LlmClient summarizer() {
        return new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                return new ChatResponse("x", "f", List.of(new ChatResponse.Choice(
                        0, ChatMessage.assistant("这是摘要"), "stop")), null);
            }
            public String modelName() { return "fake"; }
        };
    }

    private static List<ChatMessage> conversation(int rounds, int toolOutputSize) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system(promptWithMemory()));
        for (int i = 0; i < rounds; i++) {
            history.add(ChatMessage.user("第 " + i + " 个问题"));
            history.add(ChatMessage.assistant(null,
                    List.of(ToolCall.of("c" + i, "read_file", "{\"path\":\"f.txt\"}"))));
            history.add(ChatMessage.toolResult("c" + i, "文件内容 ".repeat(toolOutputSize)));
            history.add(ChatMessage.assistant("第 " + i + " 个回答"));
        }
        return history;
    }

    /** 三条路径共用的断言：第 0 条还是那条 system，而且记忆索引一个字没少。 */
    private static void assertMemoryIntact(ContextManager.Result result) {
        ChatMessage first = result.messages().get(0);
        assertEquals(ChatMessage.ROLE_SYSTEM, first.role(), "第 0 条必须还是 system");
        assertEquals(promptWithMemory(), first.content(), "system 必须一字未改");
        assertTrue(first.content().contains(MEMORY_NAME), "记忆索引没了，长期记忆会静默失效");
        assertTrue(first.content().contains("recall"), "让模型去读正文的那句也不能丢");
    }

    @Test
    @DisplayName("第一条路径（只裁旧工具输出）：记忆索引原样保留")
    void survivesToolOutputTrimming() {
        // 超标全靠工具输出撑着、对话本身很轻，第一刀裁完就够——这正是最常见的情形
        ContextManager context = new ContextManager(20_000, 0.5);
        List<ChatMessage> history = conversation(10, 500);

        assertTrue(context.needsCompaction(history, List.of()));
        ContextManager.Result result = context.compact(history, List.of(), summarizer());

        assertFalse(result.summarized(), "这一刀不该走到摘要");
        assertMemoryIntact(result);
    }

    @Test
    @DisplayName("第二条路径（摘要压缩）：记忆索引原样保留")
    void survivesSummarization() {
        // 内容多到裁工具输出不够，必须摘要
        ContextManager context = new ContextManager(3_000, 0.3);
        List<ChatMessage> history = conversation(20, 60);

        ContextManager.Result result = context.compact(history, List.of(), summarizer());

        assertTrue(result.summarized(), "这个规模该走到摘要那一刀");
        assertMemoryIntact(result);
    }

    @Test
    @DisplayName("第三条路径（兜底全裁）：记忆索引仍然原样保留")
    void survivesFallbackTrimming() {
        // 单轮里读了个超大文件，摘要之后还是超——走兜底
        ContextManager context = new ContextManager(1_000, 0.1);
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system(promptWithMemory()));
        history.add(ChatMessage.user("读一下那个巨大的日志"));
        history.add(ChatMessage.assistant(null,
                List.of(ToolCall.of("c1", "read_file", "{\"path\":\"huge.log\"}"))));
        history.add(ChatMessage.toolResult("c1", "日志行 ".repeat(5_000)));
        history.add(ChatMessage.assistant("看完了"));

        ContextManager.Result result = context.compact(history, List.of(), summarizer());

        assertMemoryIntact(result);
    }

    @Test
    @DisplayName("摘要生成失败退化时，记忆索引照样保留")
    void survivesFailedSummarizer() {
        ContextManager context = new ContextManager(3_000, 0.3);
        List<ChatMessage> history = conversation(20, 60);

        LlmClient broken = new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                throw new dev.wiles.myagent.llm.LlmException("限流了");
            }
            public String modelName() { return "fake"; }
        };

        assertMemoryIntact(context.compact(history, List.of(), broken));
    }

    @Test
    @DisplayName("压缩之后历史仍然自洽——不能切出无主的 tool 消息")
    void compactedHistoryStaysWellFormed() {
        ContextManager context = new ContextManager(3_000, 0.3);
        ContextManager.Result result =
                context.compact(conversation(20, 60), List.of(), summarizer());

        List<ChatMessage> body = result.messages().subList(1, result.messages().size());
        assertTrue(HistoryRepair.repair(body).size() > 0, "压完不该一条都不剩");
        for (int i = 0; i < body.size(); i++) {
            if (ChatMessage.ROLE_TOOL.equals(body.get(i).role())) {
                assertTrue(i > 0 && body.get(i - 1).hasToolCalls(),
                        "第 " + i + " 条 tool 消息前面没有对应的 tool_calls，下一次请求就会 400");
            }
        }
    }
}
