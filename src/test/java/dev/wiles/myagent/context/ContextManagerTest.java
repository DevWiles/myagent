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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 压缩：map-reduce、三条线、降级阶梯。
 *
 * 贯穿始终的一条不变量——<b>记录只追加，压缩只产出派生物</b>。
 * 每个用例结束时原始记录都必须一个字没变。
 */
class ContextManagerTest {

    /** 固定返回一句摘要的假模型，顺便数一数被调了几次。 */
    static class CountingLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        private final String reply;

        CountingLlm(String reply) {
            this.reply = reply;
        }

        public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
            calls.incrementAndGet();
            assertNull(t, "生成摘要时不该给工具，否则模型可能又去调工具");
            return new ChatResponse("x", "f", List.of(new ChatResponse.Choice(
                    0, ChatMessage.assistant(reply), "stop")), null);
        }

        public String modelName() {
            return "fake";
        }
    }

    static LlmClient failingSummarizer() {
        return new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                throw new LlmException("限流了");
            }

            public String modelName() {
                return "fake";
            }
        };
    }

    /** 第 n 次调用之前都成功，之后全失败——用来测「部分失败只降级那一块」。 */
    static LlmClient failAfter(int n) {
        AtomicInteger seen = new AtomicInteger();
        return new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                if (seen.incrementAndGet() > n) {
                    throw new LlmException("这一块挂了");
                }
                return new ChatResponse("x", "f", List.of(new ChatResponse.Choice(
                        0, ChatMessage.assistant("第 " + seen.get() + " 块的摘要"), "stop")), null);
            }

            public String modelName() {
                return "fake";
            }
        };
    }

    /** 一轮：user → assistant(点工具) → tool → assistant(说完)。记录<b>不含</b> system。 */
    private static List<ChatMessage> record(int turns, int toolOutputSize) {
        List<ChatMessage> record = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            record.add(ChatMessage.user("第 " + i + " 个问题"));
            record.add(ChatMessage.assistant(null,
                    List.of(ToolCall.of("call_" + i, "read_file", "{\"path\":\"f" + i + "\"}"))));
            record.add(ChatMessage.toolResult("call_" + i, "文件内容 ".repeat(toolOutputSize)));
            record.add(ChatMessage.assistant("第 " + i + " 个回答"));
        }
        return record;
    }

    // ------------------------------------------------------------------
    // token 估算
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // 三条线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("有实测值时用 80 / 93.5 / 98.7 三条线")
    void measuredThresholds() {
        ContextManager cm = new ContextManager(10_000);

        assertEquals(ContextManager.Stage.STEADY, cm.stageFor(7_000, true));
        assertEquals(ContextManager.Stage.PRECOMPUTE, cm.stageFor(8_000, true));
        assertEquals(ContextManager.Stage.PRECOMPUTE, cm.stageFor(9_300, true));
        assertEquals(ContextManager.Stage.APPLY, cm.stageFor(9_350, true));
        assertEquals(ContextManager.Stage.APPLY, cm.stageFor(9_860, true));
        assertEquals(ContextManager.Stage.FORCE, cm.stageFor(9_870, true));
    }

    @Test
    @DisplayName("拿不到实测值就退保守档——估算器偏差 10~20%，93.5% 那条线卡不得")
    void estimatedThresholdsAreConservative() {
        ContextManager cm = new ContextManager(10_000);

        assertEquals(ContextManager.Stage.PRECOMPUTE, cm.stageFor(6_500, false),
                "估算档要更早开始算");
        assertEquals(ContextManager.Stage.APPLY, cm.stageFor(8_000, false));
        assertEquals(ContextManager.Stage.FORCE, cm.stageFor(8_800, false));
        assertEquals(ContextManager.Stage.STEADY, cm.stageFor(7_900, true),
                "同一个数，实测档下还在 STEADY；估算档下已经该开始算了");
        assertEquals(ContextManager.Stage.PRECOMPUTE, cm.stageFor(7_900, false));
    }

    // ------------------------------------------------------------------
    // map
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不够三轮就没有可压的区间，一次调用都不发")
    void nothingToCompactWhenShort() {
        CountingLlm llm = new CountingLlm("摘要");

        assertNull(new ContextManager(4_000).compute(record(3, 5), List.of(), llm));
        assertEquals(0, llm.calls.get());
    }

    @Test
    @DisplayName("map 只算新增的块——算过的段永远有效，因为记录只追加")
    void mapReusesKnownSegments() {
        ContextManager cm = new ContextManager(64_000);
        List<ChatMessage> rec = record(8, 5);
        CountingLlm first = new CountingLlm("摘要");

        var one = cm.compute(rec, List.of(), first);
        int firstCalls = first.calls.get();
        assertTrue(firstCalls >= 1);

        CountingLlm second = new CountingLlm("摘要");
        var two = cm.compute(rec, one.segments(), second);

        assertEquals(0, second.calls.get(), "同一份记录、同样的段，一次调用都不该再发");
        assertEquals(one.segments().size(), two.segments().size());
    }

    @Test
    @DisplayName("分块对齐轮边界——每块都从一条 user 消息开始")
    void chunksAlignToTurnBoundaries() {
        ContextManager cm = new ContextManager(4_000);
        List<ChatMessage> rec = record(12, 40);
        int upTo = ContextWindow.startOfLastTurns(rec, ContextWindow.KEEP_RECENT_TURNS);

        List<int[]> chunks = cm.chunks(rec, 0, upTo);

        assertTrue(chunks.size() > 1, "这么长应该切成好几块");
        for (int[] chunk : chunks) {
            assertEquals(ChatMessage.ROLE_USER, rec.get(chunk[0]).role(),
                    "块的起点应当是一轮的开头");
        }
        assertEquals(0, chunks.get(0)[0]);
        assertEquals(upTo, chunks.get(chunks.size() - 1)[1], "块要拼满整个压缩区，不能漏");
    }

    @Test
    @DisplayName("单轮就超预算时按消息切开——map 的输入是纯文本，那样切不会有孤儿问题")
    void splitsOversizedTurn() {
        ContextManager cm = new ContextManager(4_000);
        List<ChatMessage> rec = new ArrayList<>();
        rec.add(ChatMessage.user("读个大文件"));
        rec.add(ChatMessage.assistant(null,
                List.of(ToolCall.of("c0", "read_file", "{\"path\":\"big\"}"))));
        rec.add(ChatMessage.toolResult("c0", "巨".repeat(20_000)));
        rec.add(ChatMessage.assistant("读完了"));
        for (int i = 1; i <= 3; i++) {
            rec.add(ChatMessage.user("问题 " + i));
            rec.add(ChatMessage.assistant("回答 " + i));
        }
        int upTo = ContextWindow.startOfLastTurns(rec, 3);

        List<int[]> chunks = cm.chunks(rec, 0, upTo);

        assertTrue(chunks.size() > 1, "那一轮自己就该被切开");
        assertEquals(upTo, chunks.get(chunks.size() - 1)[1]);
    }

    @Test
    @DisplayName("某一块摘要失败只降级那一块，其余块照常——比全有全无严格更好")
    void partialMapFailureDegradesOnlyThatChunk() {
        ContextManager cm = new ContextManager(4_000);
        List<ChatMessage> rec = record(12, 40);
        int upTo = ContextWindow.startOfLastTurns(rec, 3);
        assertTrue(cm.chunks(rec, 0, upTo).size() >= 2, "前提：这份记录会被切成多块");

        List<SummarySegment> segments = cm.map(rec, upTo, List.of(), failAfter(1));

        assertTrue(segments.stream().anyMatch(s -> s.text().startsWith("第 1 块的摘要")),
                "成功的那块要留住内容");
        assertTrue(segments.stream().anyMatch(s -> s.text().contains("摘要没能生成")),
                "失败的那块降级成占位符");
        assertEquals(upTo, SummarySegment.coveredUpTo(segments), "失败也要把区间覆盖满");
    }

    // ------------------------------------------------------------------
    // reduce
    // ------------------------------------------------------------------

    @Test
    @DisplayName("只有一段时直接返回那一段，不发调用——再摘一次就是摘要的摘要")
    void singleSegmentSkipsReduce() {
        ContextManager cm = new ContextManager(64_000);
        CountingLlm llm = new CountingLlm("不该被调到");

        String text = cm.reduce(List.of(new SummarySegment(0, 4, "原本的摘要")), llm);

        assertEquals("原本的摘要", text, "一段就是结论本身，原样返回");
        assertEquals(0, llm.calls.get(), "这一步花钱是纯亏——白花一次钱换信息损耗");
    }

    @Test
    @DisplayName("多段才合并，合并失败就原样拼起来——信息一点没少，只是不连贯")
    void reduceFallsBackToConcatenation() {
        ContextManager cm = new ContextManager(64_000);

        String text = cm.reduce(List.of(
                new SummarySegment(0, 4, "改了 pom.xml"),
                new SummarySegment(4, 8, "跑通了测试")), failingSummarizer());

        assertTrue(text.contains("改了 pom.xml"));
        assertTrue(text.contains("跑通了测试"));
    }

    @Test
    @DisplayName("没有段就没有摘要")
    void reduceOfNothingIsNull() {
        ContextManager cm = new ContextManager(64_000);
        assertNull(cm.reduce(List.of(), new CountingLlm("x")));
        assertNull(cm.reduce(null, new CountingLlm("x")));
    }

    // ------------------------------------------------------------------
    // 窗口组装与降级阶梯
    // ------------------------------------------------------------------

    @Test
    @DisplayName("尾巴从 coversUpTo 开始，不是从「最近三轮」开始——中间那段增量不能丢")
    void tailStartsAtCoverage() {
        ContextManager cm = new ContextManager(64_000);
        List<ChatMessage> rec = record(10, 5);

        var result = cm.assemble(ChatMessage.system("s"), null, "早先的摘要", rec, 8, List.of());

        // system + 摘要 + 记录第 8 条起的全部
        assertEquals(2 + (rec.size() - 8), result.window().size(),
                "两次压缩之间长出来的那几轮，既不在摘要里也不能丢");
    }

    @Test
    @DisplayName("组装结果永远没有无主的 tool 消息——这是最要命的一条")
    void neverLeavesOrphanToolMessages() {
        ContextManager cm = new ContextManager(1_500);

        for (int turns = 4; turns <= 12; turns++) {
            List<ChatMessage> rec = record(turns, 150);
            int upTo = ContextWindow.startOfLastTurns(rec, ContextWindow.KEEP_RECENT_TURNS);
            var result = cm.assemble(ChatMessage.system("s"), null, "摘要", rec, upTo, List.of());

            assertTrue(ContextWindow.isSound(result.window()), "turns=" + turns);
        }
    }

    @Test
    @DisplayName("降级阶梯：装不下时先裁较早那些轮，最后一轮的工具输出始终原样")
    void ladderProtectsLastTurn() {
        ContextManager cm = new ContextManager(4_000);
        List<ChatMessage> rec = record(9, 200);
        int upTo = ContextWindow.startOfLastTurns(rec, ContextWindow.KEEP_RECENT_TURNS);

        var result = cm.assemble(ChatMessage.system("s"), null, "摘要", rec, upTo, List.of());

        List<ChatMessage> tools = result.window().stream()
                .filter(m -> ChatMessage.ROLE_TOOL.equals(m.role())).toList();
        assertFalse(tools.isEmpty());
        assertFalse(tools.get(tools.size() - 1).content().contains("已省略"),
                "最后一轮的工具输出不为预算让路");
    }

    @Test
    @DisplayName("三级降级走完仍超软目标就照发——超预算不是错误，只是没压那么干净")
    void overSoftTargetIsNotAnError() {
        ContextManager cm = new ContextManager(4_000);
        // 最后一轮塞一个巨无霸，三级裁完也压不下去
        List<ChatMessage> rec = record(5, 5);
        rec.add(ChatMessage.user("这句话本身就很长：" + "字".repeat(3_000)));
        rec.add(ChatMessage.assistant("嗯"));

        var result = cm.assemble(ChatMessage.system("s"), null, "摘要", rec,
                ContextWindow.startOfLastTurns(rec, 3), List.of());

        assertNotNull(result.window(), "不许因为压不下去就拒绝组装");
        assertTrue(ContextWindow.isSound(result.window()));
    }

    @Test
    @DisplayName("装得下就一个字都不许裁——用户刚读回来的文件不能被系统扔掉")
    void doesNotTrimWhatWouldFit() {
        ContextManager cm = new ContextManager(4_000);
        // 一轮，读了个约 2,600 token 的文件。4,000 的窗口装得下，就该原样发
        List<ChatMessage> rec = new ArrayList<>();
        rec.add(ChatMessage.user("读一下 notes.md"));
        rec.add(ChatMessage.assistant(null,
                List.of(ToolCall.of("c1", "read_file", "{\"path\":\"notes.md\"}"))));
        String fileBody = "这是文件里的一行内容。".repeat(200);
        rec.add(ChatMessage.toolResult("c1", fileBody));

        var result = cm.assemble(ChatMessage.system("s"), null, null, rec, 0, List.of());

        assertEquals(0, result.level(), "装得下就不该走阶梯");
        assertEquals(fileBody, result.window().get(result.window().size() - 1).content(),
                "模型必须看到完整的文件内容，否则它会说「我没有读到完整内容」");
    }

    @Test
    @DisplayName("tokensRaw 记的是「不裁的话多大」——判线要用它，不能用裁完的数")
    void reportsUntrimmedSizeForPressure() {
        ContextManager cm = new ContextManager(4_000);
        // 尾巴（最近三轮）本身就得够大，压力才真的存在
        List<ChatMessage> rec = record(9, 500);
        int upTo = ContextWindow.startOfLastTurns(rec, ContextWindow.KEEP_RECENT_TURNS);

        var result = cm.assemble(ChatMessage.system("s"), null, "摘要", rec, upTo, List.of());

        assertTrue(result.level() > 0, "前提：这份确实走到了降级阶梯");
        assertTrue(result.tokensRaw() > result.tokensAfter(),
                "裁前必须大于裁后，否则「压力」这个信号就是假的");
        // 这条是整个平衡态的命门：拿裁后的数判线，占用率永远到不了 80%，
        // 于是摘要一次都不会自动发生，而工具输出被反复裁掉
        assertEquals(ContextManager.Stage.FORCE, cm.stageFor(result.tokensRaw(), true));
        assertEquals(ContextManager.Stage.STEADY, cm.stageFor(result.tokensAfter(), true),
                "同一次组装，两个数判出来完全不同——所以必须挑对");
    }

    @Test
    @DisplayName("没走阶梯时裁前裁后一样大")
    void rawEqualsSentWhenNothingTrimmed() {
        ContextManager cm = new ContextManager(64_000);
        List<ChatMessage> rec = record(6, 5);

        var result = cm.assemble(ChatMessage.system("s"), null, null, rec, 0, List.of());

        assertEquals(0, result.level());
        assertEquals(result.tokensRaw(), result.tokensAfter());
    }

    @Test
    @DisplayName("超过硬上限要能判出来，让调用方把话对用户说清楚")
    void detectsHardLimit() {
        ContextManager cm = new ContextManager(MIN());
        List<ChatMessage> huge = List.of(ChatMessage.user("字".repeat(50_000)));

        var result = cm.assemble(ChatMessage.system("s"), null, null, huge, 0, List.of());

        assertTrue(cm.exceedsHardLimit(result.window(), List.of()));
        assertFalse(cm.exceedsHardLimit(List.of(ChatMessage.user("短")), List.of()));
    }

    private static int MIN() {
        return ContextManager.MIN_MAX_TOKENS;
    }

    // ------------------------------------------------------------------
    // 不变量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("记录只追加：压缩全程一个字都不改它")
    void neverMutatesTheRecord() {
        ContextManager cm = new ContextManager(4_000);
        List<ChatMessage> rec = record(10, 100);
        List<ChatMessage> before = List.copyOf(rec);

        var compaction = cm.compute(rec, List.of(), new CountingLlm("摘要"));
        cm.assemble(ChatMessage.system("s"), null, compaction.summary(), rec,
                compaction.coversUpTo(), List.of());

        assertEquals(before, rec, "原文永远在——这是整个重构的地基");
    }

    @Test
    @DisplayName("摘要覆盖的终点必须是一条 user 消息，否则尾巴会从半轮开始")
    void coverageEndsOnTurnBoundary() {
        ContextManager cm = new ContextManager(4_000);
        List<ChatMessage> rec = record(9, 60);

        var compaction = cm.compute(rec, List.of(), new CountingLlm("摘要"));

        assertEquals(ChatMessage.ROLE_USER, rec.get(compaction.coversUpTo()).role());
    }

    @Test
    @DisplayName("client 传 null 也能产出待用件，只是内容是占位符")
    void worksWithoutClient() {
        ContextManager cm = new ContextManager(4_000);

        var compaction = cm.compute(record(9, 60), List.of(), null);

        assertNotNull(compaction);
        assertFalse(compaction.isEmpty());
        assertTrue(compaction.summary().contains("摘要没能生成"));
    }
}
