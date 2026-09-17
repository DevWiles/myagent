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

    /** 大到必须走摘要那一刀的历史。 */
    private static List<ChatMessage> bigConversation() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system("你是 myagent"));
        for (int i = 0; i < 20; i++) {
            history.add(ChatMessage.user("第 " + i + " 个问题"));
            history.add(ChatMessage.assistant(null,
                    List.of(ToolCall.of("c" + i, "read_file", "{}"))));
            history.add(ChatMessage.toolResult("c" + i, "文件内容 ".repeat(60)));
            history.add(ChatMessage.assistant("第 " + i + " 个回答"));
        }
        return history;
    }

    private static ContextManager small() {
        return new ContextManager(3_000, 0.3);
    }

    @Test
    @DisplayName("模型给了「值得记住的」，逐条递出来，而且不混进摘要里")
    void harvestsWhatModelMarked() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        ContextManager context = small().onHarvest(caught::add);

        ContextManager.Result result = context.compact(bigConversation(), List.of(), saying("""
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
        String summary = result.messages().get(1).content();
        assertTrue(summary.contains("重构规划器"));
        assertFalse(summary.contains("===值得记住==="));
        assertFalse(summary.contains("mvn-offline"));
    }

    @Test
    @DisplayName("模型没给那段时，摘要照常，什么都不记")
    void noMarkerMeansNoHarvest() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        ContextManager context = small().onHarvest(caught::add);

        ContextManager.Result result =
                context.compact(bigConversation(), List.of(), saying("就是一段普通摘要，没有标记"));

        assertTrue(caught.isEmpty());
        assertTrue(result.summarized());
        assertTrue(result.messages().get(1).content().contains("就是一段普通摘要"));
    }

    @Test
    @DisplayName("那段写歪了，歪的行跳过，好的行照收")
    void skipsMalformedLines() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        small().onHarvest(caught::add).compact(bigConversation(), List.of(), saying("""
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
        small().onHarvest(caught::add).compact(bigConversation(), List.of(), saying("""
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

        small().onHarvest(caught::add).compact(bigConversation(), List.of(), saying(text.toString()));

        assertEquals(3, caught.size());
    }

    @Test
    @DisplayName("下游存盘炸了，压缩结果照样是好的")
    void sinkFailureDoesNotBreakCompaction() {
        ContextManager context = small().onHarvest(h -> {
            throw new IllegalStateException("盘满了");
        });

        ContextManager.Result result = assertDoesNotThrow(() ->
                context.compact(bigConversation(), List.of(), saying("""
                        摘要正文

                        ===值得记住===
                        x | 摘要 | 正文
                        """)));

        assertTrue(result.summarized());
        assertTrue(result.messages().get(1).content().contains("摘要正文"));
        assertEquals(ChatMessage.ROLE_SYSTEM, result.messages().get(0).role());
    }

    @Test
    @DisplayName("不接 onHarvest 时，行为和加这个功能之前一字不差")
    void withoutSinkBehavesExactlyAsBefore() {
        String answer = """
                摘要正文

                ===值得记住===
                x | 摘要 | 正文
                """;

        ContextManager.Result withSink =
                small().onHarvest(h -> { }).compact(bigConversation(), List.of(), saying(answer));
        ContextManager.Result without =
                small().compact(bigConversation(), List.of(), saying(answer));

        assertEquals(withSink.messages().size(), without.messages().size());
        assertEquals(withSink.messages().get(1).content(), without.messages().get(1).content());
    }

    @Test
    @DisplayName("只有「值得记住的」没有摘要正文时，退化成兜底说明，不返回空摘要")
    void emptySummaryFallsBack() {
        List<ContextManager.Harvested> caught = new ArrayList<>();
        ContextManager.Result result = small().onHarvest(caught::add)
                .compact(bigConversation(), List.of(), saying("""
                        ===值得记住===
                        x | 摘要 | 正文
                        """));

        String summary = result.messages().get(1).content();
        assertFalse(summary.isBlank(), "空摘要会让那一段历史彻底没有交代");
        assertTrue(summary.contains("已被省略"));
    }
}
