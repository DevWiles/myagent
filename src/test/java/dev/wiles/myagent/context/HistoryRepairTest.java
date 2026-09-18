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

    // ---------- seal：把刚断掉的这一轮封口，而不是截掉 ----------

    @Test
    @DisplayName("没等到结果的 tool_call 被补上说明，现场一条不丢")
    void fillsInMissingToolResults() {
        List<ChatMessage> broken = new java.util.ArrayList<>();
        broken.add(ChatMessage.user("改三个文件"));
        broken.add(callsTools("c1", "c2"));
        broken.add(ChatMessage.toolResult("c1", "第一个改好了"));   // c2 的结果永远不会来

        List<ChatMessage> sealed = HistoryRepair.seal(broken, "网络错误");

        assertTrue(HistoryRepair.isIntact(sealed), "封口之后必须自洽，否则下一句话发不出去");
        assertEquals("改三个文件", sealed.get(0).content());
        assertEquals("第一个改好了", sealed.get(2).content(), "已经做完的那一步得留着");
        ChatMessage filled = sealed.get(3);
        assertEquals(ChatMessage.ROLE_TOOL, filled.role());
        assertEquals("c2", filled.toolCallId());
        assertTrue(filled.content().contains("没有执行"),
                "内容要说清这一步是被打断的，不是做过的——不然模型会当成已完成");
    }

    @Test
    @DisplayName("末尾收成一条 assistant：用户下一句才不会变成连续两条 user")
    void endsOnAnAssistantMessage() {
        List<ChatMessage> nothingHappened = List.of(ChatMessage.user("干活"));

        List<ChatMessage> sealed = HistoryRepair.seal(nothingHappened, "连不上");

        assertEquals(2, sealed.size());
        assertEquals(ChatMessage.ROLE_ASSISTANT, sealed.get(1).role());
        assertTrue(sealed.get(1).content().contains("连不上"));
        assertFalse(sealed.get(1).hasToolCalls());
    }

    @Test
    @DisplayName("已经收口的历史不再多加一句——没断的东西不用封")
    void doesNotDoubleSealAFinishedTurn() {
        List<ChatMessage> done = completeTurn("call-1");
        assertEquals(done, HistoryRepair.seal(done, "网络错误"));
    }

    @Test
    @DisplayName("补完还是不自洽（残缺在中间）就退回截断——丢现场也比留个定时炸弹好")
    void fallsBackToTruncationWhenSealingCannotFixIt() {
        List<ChatMessage> weird = new java.util.ArrayList<>(completeTurn("call-1"));
        weird.add(callsTools("call-2"));          // 点了工具
        weird.add(ChatMessage.user("又说了一句"));  // 结果没回来就接了别的，残缺卡在中间
        weird.add(ChatMessage.assistant("好"));

        List<ChatMessage> sealed = HistoryRepair.seal(weird, "网络错误");

        assertTrue(HistoryRepair.isIntact(sealed));
        assertTrue(sealed.size() < weird.size() + 2, "这种情况不该靠往后补来硬凑");
        assertEquals(ChatMessage.ROLE_ASSISTANT, sealed.get(sealed.size() - 1).role());
    }

    @Test
    @DisplayName("空的、null 的都不抛")
    void sealHandlesNothing() {
        assertTrue(HistoryRepair.seal(null, "网络错误").isEmpty());
        assertTrue(HistoryRepair.seal(List.of(), "网络错误").isEmpty());
        assertDoesNotThrow(() -> HistoryRepair.seal(completeTurn("c1"), null));
    }

    @Test
    @DisplayName("不改传进来的那个 list——调用方手里可能是不可变的")
    void sealDoesNotMutateTheInput() {
        List<ChatMessage> immutable = List.of(ChatMessage.user("干活"), callsTools("c1"));

        assertDoesNotThrow(() -> HistoryRepair.seal(immutable, "网络错误"));
        assertEquals(2, immutable.size());
    }
}
