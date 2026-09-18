package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 窗口组装：纯函数，<b>整个测试类不发一次模型调用</b>。
 *
 * 这正是把它从 ContextManager 里拆出来的理由——「最近三轮原样」
 * 和「结果里没有无主的 tool 消息」这两条是压缩的命门，
 * 不该测一次就得起一个模型。
 */
class ContextWindowTest {

    /** 一轮：user → assistant(点工具) → tool → assistant(说完)。 */
    private static void appendTurn(List<ChatMessage> record, int i, int toolOutputSize) {
        record.add(ChatMessage.user("第 " + i + " 个问题"));
        record.add(ChatMessage.assistant(null,
                List.of(ToolCall.of("call_" + i, "read_file", "{\"path\":\"f" + i + "\"}"))));
        record.add(ChatMessage.toolResult("call_" + i, "结果 ".repeat(toolOutputSize)));
        record.add(ChatMessage.assistant("第 " + i + " 个回答"));
    }

    private static List<ChatMessage> record(int turns, int toolOutputSize) {
        List<ChatMessage> record = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            appendTurn(record, i, toolOutputSize);
        }
        return record;
    }

    @Test
    @DisplayName("最近三轮的起点是倒数第三条 user 消息")
    void findsStartOfLastThreeTurns() {
        List<ChatMessage> record = record(5, 1);

        int start = ContextWindow.startOfLastTurns(record, 3);

        assertEquals(ChatMessage.ROLE_USER, record.get(start).role(), "切点必须是 user 消息");
        assertEquals("第 2 个问题", record.get(start).content());
        assertEquals(8, start, "每轮 4 条，5 轮共 20 条，留最后 3 轮 = 从第 8 条起");
    }

    @Test
    @DisplayName("不够三轮就全留，不能返回负数下标")
    void keepsEverythingWhenFewerTurns() {
        assertEquals(0, ContextWindow.startOfLastTurns(record(2, 1), 3));
        assertEquals(0, ContextWindow.startOfLastTurns(List.of(), 3));
        assertEquals(0, ContextWindow.startOfLastTurns(null, 3));
    }

    @Test
    @DisplayName("尾巴一定从 user 开始，组装出来就不会有无主的 tool 消息")
    void assembledWindowIsSound() {
        List<ChatMessage> record = record(6, 1);
        int start = ContextWindow.startOfLastTurns(record, ContextWindow.KEEP_RECENT_TURNS);

        List<ChatMessage> window = ContextWindow.assemble(
                ChatMessage.system("你是 myagent"),
                new PinnedFacts("目标", List.of("a.java")).render(),
                ContextWindow.summaryMessage("早先聊了些什么"),
                record.subList(start, record.size()));

        assertTrue(ContextWindow.isSound(window), "每条 tool 都要对得上前面的 tool_call");
        assertEquals(ChatMessage.ROLE_SYSTEM, window.get(0).role(), "system 永远是第 0 条");
    }

    @Test
    @DisplayName("最近三轮原样——工具输出一个字都不许动")
    void recentTurnsStayVerbatim() {
        List<ChatMessage> record = record(6, 200);
        int start = ContextWindow.startOfLastTurns(record, 3);

        List<ChatMessage> window = ContextWindow.assemble(
                ChatMessage.system("s"), null, ContextWindow.summaryMessage("摘要"),
                record.subList(start, record.size()));

        List<ChatMessage> tools = window.stream()
                .filter(m -> ChatMessage.ROLE_TOOL.equals(m.role())).toList();
        assertEquals(3, tools.size());
        tools.forEach(m -> assertFalse(m.content().contains("已省略"),
                "最近三轮是硬约束，不为预算让路"));
    }

    @Test
    @DisplayName("钉住块和摘要块传 null 时整块略过，不留空消息")
    void skipsNullBlocks() {
        List<ChatMessage> window = ContextWindow.assemble(
                ChatMessage.system("s"), null, null, List.of(ChatMessage.user("问")));

        assertEquals(2, window.size());
        assertEquals(ChatMessage.ROLE_SYSTEM, window.get(0).role());
        assertEquals("问", window.get(1).content());
    }

    @Test
    @DisplayName("空摘要不渲染——没压出东西就别在窗口里占位置")
    void blankSummaryRendersNothing() {
        assertNull(ContextWindow.summaryMessage(null));
        assertNull(ContextWindow.summaryMessage("   "));
    }

    @Test
    @DisplayName("裁剪只动 protectFrom 之前的，且留个开头说明原长")
    void trimsOnlyBeforeProtectPoint() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.toolResult("c1", "老".repeat(1000)));
        messages.add(ChatMessage.toolResult("c2", "新".repeat(1000)));

        List<ChatMessage> trimmed = ContextWindow.trimToolOutputs(messages, 1);

        assertTrue(trimmed.get(0).content().contains("已省略"));
        assertTrue(trimmed.get(0).content().contains("1000 字符"), "要说清原来多长");
        assertEquals("新".repeat(1000), trimmed.get(1).content(), "受保护的一条不许动");
    }

    @Test
    @DisplayName("裁剪保留 tool_call_id，否则裁完就成了无主消息")
    void trimKeepsToolCallId() {
        List<ChatMessage> trimmed = ContextWindow.trimToolOutputs(
                List.of(ChatMessage.toolResult("call_7", "x".repeat(1000))), 1);

        assertEquals("call_7", trimmed.get(0).toolCallId());
    }

    @Test
    @DisplayName("短输出不值得裁，原样留着")
    void leavesShortOutputsAlone() {
        String short_ = "x".repeat(ContextWindow.TOOL_OUTPUT_TRIM_THRESHOLD);
        List<ChatMessage> trimmed = ContextWindow.trimToolOutputs(
                List.of(ChatMessage.toolResult("c1", short_)), 1);

        assertEquals(short_, trimmed.get(0).content());
    }

    @Test
    @DisplayName("无主的 tool 消息要能被认出来——这种窗口发出去每次都 400")
    void detectsOrphanToolMessage() {
        List<ChatMessage> broken = List.of(
                ChatMessage.system("s"),
                ChatMessage.user("问"),
                ChatMessage.toolResult("没人点过的 id", "结果"));

        assertFalse(ContextWindow.isSound(broken));
    }

    @Test
    @DisplayName("段的下标越界就是指错了原文，只能丢")
    void segmentBoundsAreChecked() {
        assertTrue(new SummarySegment(0, 10, "摘要").fitsIn(10));
        assertFalse(new SummarySegment(0, 11, "摘要").fitsIn(10), "越界");
        assertFalse(new SummarySegment(5, 5, "摘要").fitsIn(10), "空区间");
        assertFalse(new SummarySegment(0, 10, "  ").fitsIn(10), "空摘要");
        assertEquals(10, SummarySegment.coveredUpTo(
                List.of(new SummarySegment(0, 4, "a"), new SummarySegment(4, 10, "b"))));
        assertEquals(0, SummarySegment.coveredUpTo(null));
    }
}
