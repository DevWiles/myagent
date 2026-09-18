package dev.wiles.myagent.context;

import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
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
 * 长期记忆的索引住在 system 消息里，而 system 每一轮都要跟着窗口重发。
 *
 * <p>降级阶梯有三级，任何一级把 system 动了，后果都是<b>长期记忆静默失效</b>：
 * 文件还在盘上，日志也没有报错，模型就是突然不知道那些事了——
 * 而且只在长对话里发生，短对话复现不出来。
 *
 * <p>新的结构里 system 是<b>传进来的参数</b>，由 {@link ContextWindow#assemble}
 * 无条件放在第 0 条，压缩连碰都碰不到它（压缩只产出摘要段，改不了窗口的骨架）。
 * 这比旧实现的「记得保留 {@code work.get(0)}」强：不是靠人记得，是结构上就够不着。
 * 这个测试是钉子：将来谁动组装逻辑，先在这儿绊一跤。
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

    private static ChatMessage system() {
        return ChatMessage.system(promptWithMemory());
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

    /** 记录<b>不含</b> system——system 由窗口组装时现场带上。 */
    private static List<ChatMessage> conversation(int turns, int toolOutputSize) {
        List<ChatMessage> record = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            record.add(ChatMessage.user("第 " + i + " 个问题"));
            record.add(ChatMessage.assistant(null,
                    List.of(ToolCall.of("c" + i, "read_file", "{\"path\":\"f.txt\"}"))));
            record.add(ChatMessage.toolResult("c" + i, "文件内容 ".repeat(toolOutputSize)));
            record.add(ChatMessage.assistant("第 " + i + " 个回答"));
        }
        return record;
    }

    /** 各级共用的断言：第 0 条还是那条 system，而且记忆索引一个字没少。 */
    private static void assertMemoryIntact(ContextManager.Result result) {
        ChatMessage first = result.window().get(0);
        assertEquals(ChatMessage.ROLE_SYSTEM, first.role(), "第 0 条必须还是 system");
        assertEquals(promptWithMemory(), first.content(), "system 必须一字未改");
        assertTrue(first.content().contains(MEMORY_NAME), "记忆索引没了，长期记忆会静默失效");
        assertTrue(first.content().contains("recall"), "让模型去读正文的那句也不能丢");
    }

    /** 走到哪一级由内容体积决定，这里只关心每一级都保住 system。 */
    private static ContextManager.Result assembleWith(int maxTokens, int turns, int toolSize) {
        ContextManager context = new ContextManager(maxTokens);
        List<ChatMessage> record = conversation(turns, toolSize);
        var compaction = context.compute(record, List.of(), summarizer());
        return context.assemble(system(), null,
                compaction == null ? null : compaction.summary(), record,
                compaction == null ? 0 : compaction.coversUpTo(), List.of());
    }

    @Test
    @DisplayName("一级（摘要 + 最近几轮原样）：记忆索引原样保留")
    void survivesPlainAssembly() {
        assertMemoryIntact(assembleWith(64_000, 8, 20));
    }

    @Test
    @DisplayName("二级（裁较早轮的工具输出）：记忆索引原样保留")
    void survivesPartialTrimming() {
        assertMemoryIntact(assembleWith(6_000, 10, 200));
    }

    @Test
    @DisplayName("三级（裁全部工具输出）：记忆索引仍然原样保留")
    void survivesFullTrimming() {
        assertMemoryIntact(assembleWith(4_000, 12, 500));
    }

    @Test
    @DisplayName("单轮里读了个超大文件、怎么裁都装不下时，记忆索引照样保留")
    void survivesOversizedSingleTurn() {
        ContextManager context = new ContextManager(4_000);
        List<ChatMessage> record = new ArrayList<>();
        record.add(ChatMessage.user("读一下那个巨大的日志"));
        record.add(ChatMessage.assistant(null,
                List.of(ToolCall.of("c1", "read_file", "{\"path\":\"huge.log\"}"))));
        record.add(ChatMessage.toolResult("c1", "日志行 ".repeat(5_000)));
        record.add(ChatMessage.assistant("看完了"));

        assertMemoryIntact(context.assemble(system(), null, null, record, 0, List.of()));
    }

    @Test
    @DisplayName("摘要生成失败退化时，记忆索引照样保留")
    void survivesFailedSummarizer() {
        ContextManager context = new ContextManager(4_000);
        List<ChatMessage> record = conversation(10, 60);
        LlmClient broken = new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                throw new LlmException("限流了");
            }

            public String modelName() { return "fake"; }
        };

        var compaction = context.compute(record, List.of(), broken);

        assertMemoryIntact(context.assemble(system(), null, compaction.summary(), record,
                compaction.coversUpTo(), List.of()));
    }

    @Test
    @DisplayName("组装出来的窗口仍然自洽——不能切出无主的 tool 消息")
    void assembledWindowStaysWellFormed() {
        var result = assembleWith(4_000, 12, 60);
        List<ChatMessage> body = result.window().subList(1, result.window().size());

        assertFalse(body.isEmpty(), "压完不该一条都不剩");
        for (int i = 0; i < body.size(); i++) {
            if (ChatMessage.ROLE_TOOL.equals(body.get(i).role())) {
                assertTrue(i > 0 && body.get(i - 1).hasToolCalls(),
                        "第 " + i + " 条 tool 消息前面没有对应的 tool_calls，下一次请求就会 400");
            }
        }
    }
}
