package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 压缩时顺手捡长期记忆。
 *
 * <p>这一组测试的重点全在一件事上：<b>捡记忆绝不能伤到压缩本身</b>。
 * 压缩是长对话能不能继续下去的关键路径，而捡记忆只是顺带——
 * 模型不给、格式写歪、下游存盘炸了，都必须只是「这次没捡到」。
 *
 * <p>捡记忆挂在 <b>map</b> 上而不是 reduce 上：单段时根本不跑 reduce
 * （跑了就成了摘要的摘要），挂那儿就永远捡不到；而且 map 读的是原文，
 * reduce 只看得到摘要，抽跨会话事实当然看原文更准。封顶是整次压缩 3 条，跨块共享。
 */
class CompactionHarvestTest {

    /** 回一段指定文本的假模型。 */
    private static LlmClient saying(String text) {
        return new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                return new ChatResponse("x", "f", List.of(new ChatResponse.Choice(
                        0, ChatMessage.assistant(text), "stop")), null);
            }

            public String modelName() { return "fake"; }
        };
    }

    /** 记录<b>不含</b> system。 */
    private static List<ChatMessage> conversation(int turns, int toolOutputSize) {
        List<ChatMessage> record = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            record.add(ChatMessage.user("第 " + i + " 个问题"));
            record.add(ChatMessage.assistant(null,
                    List.of(ToolCall.of("c" + i, "read_file", "{}"))));
            record.add(ChatMessage.toolResult("c" + i, "文件内容 ".repeat(toolOutputSize)));
            record.add(ChatMessage.assistant("第 " + i + " 个回答"));
        }
        return record;
    }

    /** 窗口开得大 = 块预算大 = 整段只切成一块，于是摘要调用次数是确定的 1 次。 */
    private static List<ChatMessage> oneChunk() {
        return conversation(6, 5);
    }

    private static ContextManager manager() {
        return new ContextManager(64_000);
    }

    @Test
    @DisplayName("模型给了「值得记住的」，逐条递出来，而且不混进摘要里")
    void harvestsWhatModelMarked() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        ContextManager context = manager().onHarvest(caught::add);

        var compaction = context.compute(oneChunk(), List.of(), saying("""
                用户在重构规划器，已经改了 PlanPrompt.java。

                ===值得记住===
                mvn-offline | 跑测试要加 -o | 这台机器连 central 很慢，依赖已经下全了
                no-framework | 禁止引入 agent 框架 | 只允许 Jackson/OkHttp/slf4j
                """));

        assertEquals(2, caught.size());
        assertEquals("mvn-offline", caught.get(0).name());
        assertEquals("跑测试要加 -o", caught.get(0).description());
        assertTrue(caught.get(0).content().contains("central"));

        // 摘要里不能带着「值得记住的」那段——它会被当成对话内容重新发给模型
        assertTrue(compaction.summary().contains("重构规划器"));
        assertFalse(compaction.summary().contains("===值得记住==="));
        assertFalse(compaction.summary().contains("mvn-offline"));
    }

    @Test
    @DisplayName("模型没给那段时，摘要照常，什么都不记")
    void noMarkerMeansNoHarvest() {
        List<ContextManager.Harvested> caught = new ArrayList<>();

        var compaction = manager().onHarvest(caught::add)
                .compute(oneChunk(), List.of(), saying("就是一段普通摘要，没有标记"));

        assertTrue(caught.isEmpty());
        assertTrue(compaction.summary().contains("就是一段普通摘要"));
    }

    @Test
    @DisplayName("那段写歪了，歪的行跳过，好的行照收")
    void skipsMalformedLines() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        manager().onHarvest(caught::add).compute(oneChunk(), List.of(), saying("""
                摘要正文

                ===值得记住===
                这行没有分隔符
                只有 | 两段
                 | 空名字 | 正文
                good-one | 这条是好的 | 详细说明
                """));

        assertEquals(1, caught.size());
        assertEquals("good-one", caught.get(0).name());
    }

    @Test
    @DisplayName("模型带了 markdown 列表符号也认")
    void toleratesListMarkers() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        manager().onHarvest(caught::add).compute(oneChunk(), List.of(), saying("""
                摘要正文

                ===值得记住===
                - mvn-offline | 跑测试要加 -o | 详细说明
                """));

        assertEquals(1, caught.size());
        assertEquals("mvn-offline", caught.get(0).name());
    }

    @Test
    @DisplayName("模型一口气给十条也只收前三条——顺带捡的东西不该喧宾夺主")
    void capsHarvestCount() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        StringBuilder text = new StringBuilder("摘要正文\n\n===值得记住===\n");
        for (int i = 0; i < 10; i++) {
            text.append("name-").append(i).append(" | 摘要 ").append(i).append(" | 正文\n");
        }

        manager().onHarvest(caught::add).compute(oneChunk(), List.of(), saying(text.toString()));

        assertEquals(3, caught.size());
    }

    @Test
    @DisplayName("四段格式：主题词收下来，取代机制全靠它")
    void capturesTopicKey() {
        List<ContextManager.Harvested> caught = new ArrayList<>();

        manager().onHarvest(caught::add).compute(oneChunk(), List.of(),
                saying("摘要正文\n\n===值得记住===\nuses-bun | 包管理器 | 这项目用 bun | 不是 npm\n"));

        assertEquals(1, caught.size());
        assertEquals("uses-bun", caught.get(0).name());
        assertEquals("包管理器", caught.get(0).topicKey(),
                "主题词丢了的话，同一件事的后续纠正永远顶不掉旧的");
        assertEquals("这项目用 bun", caught.get(0).description());
        assertEquals("不是 npm", caught.get(0).content());
        assertNotNull(caught.get(0).turnId(), "要能指回是从哪一段原文里捡的");
    }

    @Test
    @DisplayName("模型少写一段时整条仍然收下，只是不参与取代——不是把这条丢掉")
    void threeFieldLineStillWorks() {
        List<ContextManager.Harvested> caught = new ArrayList<>();

        manager().onHarvest(caught::add).compute(oneChunk(), List.of(),
                saying("摘要正文\n\n===值得记住===\nuses-bun | 这项目用 bun | 不是 npm\n"));

        assertEquals(1, caught.size());
        assertEquals("这项目用 bun", caught.get(0).description());
        assertEquals("不是 npm", caught.get(0).content());
        assertNull(caught.get(0).topicKey());
    }

    @Test
    @DisplayName("已经在用的主题词要出现在提示词里，让模型复用而不是另起近义词")
    void feedsExistingTopicsBackToTheModel() {
        List<String> seen = new ArrayList<>();
        var recorder = new RecordingClient(seen);

        manager().withTopicHints(() -> List.of("包管理器", "测试命令"))
                .compute(oneChunk(), List.of(), recorder);

        assertTrue(seen.stream().anyMatch(p -> p.contains("包管理器") && p.contains("测试命令")),
                "不喂回去的话模型每次凭空取名，主题匹配就成了碰运气");
    }

    /** 把发出去的提示词原文记下来。 */
    private static final class RecordingClient implements dev.wiles.myagent.llm.LlmClient {
        private final List<String> seen;

        RecordingClient(List<String> seen) {
            this.seen = seen;
        }

        @Override
        public dev.wiles.myagent.llm.ChatResponse chat(
                List<dev.wiles.myagent.llm.ChatMessage> messages,
                List<dev.wiles.myagent.llm.ToolSpec> tools) {
            messages.stream().map(dev.wiles.myagent.llm.ChatMessage::content)
                    .filter(java.util.Objects::nonNull).forEach(seen::add);
            return new dev.wiles.myagent.llm.ChatResponse("id", "fake",
                    List.of(new dev.wiles.myagent.llm.ChatResponse.Choice(0,
                            dev.wiles.myagent.llm.ChatMessage.assistant("摘要"), "stop")), null);
        }

        @Override
        public String modelName() {
            return "fake";
        }
    }

    @Test
    @DisplayName("封顶是整次压缩三条，不是每块三条——多块时预算跨块共享")
    void harvestBudgetIsSharedAcrossChunks() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        ContextManager context = new ContextManager(4_000).onHarvest(caught::add);
        List<ChatMessage> record = conversation(12, 40);
        int upTo = ContextWindow.startOfLastTurns(record, ContextWindow.KEEP_RECENT_TURNS);
        assertTrue(context.chunks(record, 0, upTo).size() >= 2, "前提：这份记录会切成多块");

        context.compute(record, List.of(), saying("""
                摘要正文

                ===值得记住===
                a | 一 | 正文
                b | 二 | 正文
                """));

        assertEquals(3, caught.size(), "两块各给两条，总共也只收三条");
    }

    @Test
    @DisplayName("下游存盘炸了，压缩结果照样是好的")
    void sinkFailureDoesNotBreakCompaction() {
        ContextManager context = manager().onHarvest(h -> {
            throw new IllegalStateException("盘满了");
        });

        var compaction = assertDoesNotThrow(() ->
                context.compute(oneChunk(), List.of(), saying("""
                        摘要正文

                        ===值得记住===
                        x | 摘要 | 正文
                        """)));

        assertFalse(compaction.isEmpty());
        assertTrue(compaction.summary().contains("摘要正文"));
    }

    @Test
    @DisplayName("不接 onHarvest 时，行为和加这个功能之前一字不差")
    void withoutSinkBehavesExactlyAsBefore() {
        String answer = """
                摘要正文

                ===值得记住===
                x | 摘要 | 正文
                """;

        var withSink = manager().onHarvest(h -> { })
                .compute(oneChunk(), List.of(), saying(answer));
        var without = manager().compute(oneChunk(), List.of(), saying(answer));

        assertEquals(withSink.summary(), without.summary());
        assertEquals(withSink.segments().size(), without.segments().size());
    }

    @Test
    @DisplayName("只有「值得记住的」没有摘要正文时，这一块降级成占位符，不返回空摘要")
    void emptySummaryFallsBack() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        var compaction = manager().onHarvest(caught::add)
                .compute(oneChunk(), List.of(), saying("""
                        ===值得记住===
                        x | 摘要 | 正文
                        """));

        assertFalse(compaction.summary().isBlank(), "空摘要会让那一段历史彻底没有交代");
        assertTrue(compaction.summary().contains("摘要没能生成"));
    }
}
