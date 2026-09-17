package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryRepairTest {

    private static ChatMessage callsTools(String... ids) {
        return ChatMessage.assistant("我看一下",
                java.util.Arrays.stream(ids)
                        .map(id -> ToolCall.of(id, "read_file", "{\"path\":\"x\"}"))
                        .toList());
    }

    /** 一轮完整的工具往返：用户问 → 模型点工具 → 结果回来 → 模型作答。 */
    private static List<ChatMessage> completeTurn(String id) {
        return List.of(
                ChatMessage.user("问题"),
                callsTools(id),
                ChatMessage.toolResult(id, "文件内容"),
                ChatMessage.assistant("答复"));
    }

    @Test
    @DisplayName("本来就自洽的历史一条不动")
    void intactHistoryIsUntouched() {
        List<ChatMessage> history = completeTurn("call-1");
        assertEquals(history, HistoryRepair.repair(history));
        assertTrue(HistoryRepair.isIntact(history));
    }

    @Test
    @DisplayName("停在「工具点了、结果没回来」——kill -9 最典型的现场")
    void dropsDanglingToolCalls() {
        List<ChatMessage> broken = new java.util.ArrayList<>(completeTurn("call-1"));
        broken.add(ChatMessage.user("第二个问题"));
        broken.add(callsTools("call-2"));       // 点了，但结果永远不会来了

        List<ChatMessage> repaired = HistoryRepair.repair(broken);
        assertEquals(completeTurn("call-1"), repaired);
        assertFalse(HistoryRepair.isIntact(broken));
    }

    @Test
    @DisplayName("模型点了三个工具只回来两个，整轮都不要")
    void partialToolResultsDropTheWholeTurn() {
        List<ChatMessage> broken = new java.util.ArrayList<>(completeTurn("c0"));
        broken.add(ChatMessage.user("再来"));
        broken.add(callsTools("a", "b", "c"));
        broken.add(ChatMessage.toolResult("a", "结果 a"));
        broken.add(ChatMessage.toolResult("b", "结果 b"));
        // c 的结果没了

        assertEquals(completeTurn("c0"), HistoryRepair.repair(broken));
    }

    @Test
    @DisplayName("无主的 tool 消息——前面那条 assistant 丢了")
    void dropsOrphanToolMessage() {
        List<ChatMessage> broken = List.of(
                ChatMessage.user("问题"),
                ChatMessage.toolResult("nobody-called-me", "结果"));

        assertTrue(HistoryRepair.repair(broken).isEmpty());
    }

    @Test
    @DisplayName("tool 消息的 id 对不上前面点的那个")
    void dropsMismatchedToolCallId() {
        List<ChatMessage> broken = List.of(
                ChatMessage.user("问题"),
                callsTools("call-1"),
                ChatMessage.toolResult("call-999", "对不上的结果"));

        assertTrue(HistoryRepair.repair(broken).isEmpty());
    }

    @Test
    @DisplayName("末尾停在工具结果上（模型还没说话）也要退回上一轮")
    void trailingToolResultRollsBack() {
        List<ChatMessage> broken = new java.util.ArrayList<>(completeTurn("c0"));
        broken.add(ChatMessage.user("再来"));
        broken.add(callsTools("c1"));
        broken.add(ChatMessage.toolResult("c1", "结果"));
        // 模型还没来得及作答

        assertEquals(completeTurn("c0"), HistoryRepair.repair(broken));
    }

    @Test
    @DisplayName("历史里混进 system 就地截断——存档来路不对")
    void stopsAtUnexpectedSystemMessage() {
        List<ChatMessage> broken = new java.util.ArrayList<>(completeTurn("c0"));
        broken.add(ChatMessage.system("不该出现在这儿"));
        broken.add(ChatMessage.user("后面的也不要"));

        assertEquals(completeTurn("c0"), HistoryRepair.repair(broken));
    }

    @Test
    @DisplayName("空的、null 的都返空，不抛")
    void handlesEmptyInput() {
        assertTrue(HistoryRepair.repair(null).isEmpty());
        assertTrue(HistoryRepair.repair(List.of()).isEmpty());
        assertTrue(HistoryRepair.isIntact(null));
    }

    @Test
    @DisplayName("只有一句用户提问、模型还没回答——留不下东西，但不能抛")
    void unansweredQuestionIsDropped() {
        assertTrue(HistoryRepair.repair(List.of(ChatMessage.user("问了但没答"))).isEmpty());
    }

    @Test
    @DisplayName("多轮完整对话全部保留")
    void keepsAllCompleteTurns() {
        List<ChatMessage> history = new java.util.ArrayList<>();
        history.addAll(completeTurn("a"));
        history.add(ChatMessage.user("第二轮"));
        history.add(ChatMessage.assistant("直接答，没调工具"));
        history.addAll(completeTurn("b"));

        assertEquals(history, HistoryRepair.repair(history));
    }

    @Test
    @DisplayName("原 list 不被改动")
    void doesNotMutateInput() {
        List<ChatMessage> broken = new java.util.ArrayList<>(completeTurn("c0"));
        broken.add(callsTools("dangling"));
        int before = broken.size();

        HistoryRepair.repair(broken);
        assertEquals(before, broken.size());
    }
}
