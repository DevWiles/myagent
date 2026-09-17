package dev.wiles.myagent.context;

import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    @TempDir
    Path tmp;

    private SessionStore store() {
        return new SessionStore(tmp.resolve("sessions"));
    }

    private Path ws() {
        return tmp.resolve("proj");
    }

    /** 一段带 system 的完整历史，就是 Agent.history() 的形状。 */
    private static List<ChatMessage> history() {
        return List.of(
                ChatMessage.system("你是 myagent。工作区: /tmp/proj"),
                ChatMessage.user("这个项目怎么跑测试"),
                ChatMessage.assistant("我看一下",
                        List.of(ToolCall.of("c1", "read_file", "{\"path\":\"pom.xml\"}"))),
                ChatMessage.toolResult("c1", "<project>…</project>"),
                ChatMessage.assistant("mvn test 就行"));
    }

    @Test
    @DisplayName("存了能读回来，消息逐条相等")
    void savesAndLoads() {
        SessionStore store = store();
        store.save(history(), ws(), "deepseek-chat");

        SessionSnapshot snapshot = store.peek(ws()).orElseThrow();
        assertEquals(4, snapshot.messages().size());
        assertEquals(history().subList(1, 5), snapshot.messages());
        assertEquals("deepseek-chat", snapshot.model());
        assertEquals(1, snapshot.turns());
        assertEquals("这个项目怎么跑测试", snapshot.firstUserMessage());
    }

    @Test
    @DisplayName("存档里不含 system——从结构上杜绝提示词漂移")
    void neverStoresSystemMessage() {
        SessionStore store = store();
        store.save(history(), ws(), "deepseek-chat");

        SessionSnapshot snapshot = store.peek(ws()).orElseThrow();
        assertTrue(snapshot.messages().stream()
                        .noneMatch(m -> ChatMessage.ROLE_SYSTEM.equals(m.role())),
                "存了 system 的话，恢复时会把上次的记忆索引一起带回来");
    }

    @Test
    @DisplayName("恢复用的是当前的 system，不是上次那条——这轮新记的记忆才看得见")
    void restoreUsesCurrentSystemPrompt() {
        SessionStore store = store();
        store.save(history(), ws(), "deepseek-chat");

        // 下次启动，系统提示词变了（比如中间 remember 了一条，索引多了一行）
        String nowPrompt = "你是 myagent。\n## 你记住的事\n- mvn-offline — 跑测试要加 -o";
        Agent agent = new Agent(null, new ToolRegistry(), nowPrompt);
        agent.restore(store.peek(ws()).orElseThrow().restorable());

        assertEquals(nowPrompt, agent.history().get(0).content(),
                "拿存档里那条 system 的话，新记的东西永远不生效");
        assertEquals(5, agent.history().size());
    }

    @Test
    @DisplayName("存档停在「工具点了、结果没回来」，恢复后历史是自洽的")
    void repairsTruncatedArchive() {
        SessionStore store = store();
        List<ChatMessage> dying = new ArrayList<>(history());
        dying.add(ChatMessage.user("再改个文件"));
        dying.add(ChatMessage.assistant("好",
                List.of(ToolCall.of("c2", "write_file", "{}"))));   // 进程死在这儿

        store.save(dying, ws(), "deepseek-chat");

        Agent agent = new Agent(null, new ToolRegistry(), "系统提示词");
        int restored = agent.restore(store.peek(ws()).orElseThrow().restorable());

        assertEquals(4, restored, "残缺的那两条该被截掉");
        assertTrue(HistoryRepair.isIntact(agent.conversation()));
        assertEquals("mvn test 就行",
                agent.history().get(agent.history().size() - 1).content());
    }

    @Test
    @DisplayName("换个工作区不给恢复——A 项目的上下文接着聊 B 项目会给出很笃定的错结论")
    void refusesWrongWorkspace() {
        SessionStore store = store();
        store.save(history(), ws(), "deepseek-chat");

        assertTrue(store.peek(tmp.resolve("another-proj")).isEmpty());
    }

    @Test
    @DisplayName("存档损坏当作没有，不抛")
    void corruptedArchiveIsIgnored() throws IOException {
        SessionStore store = store();
        store.save(history(), ws(), "deepseek-chat");

        Files.writeString(store.pathFor(ws()), "{ 这不是合法 JSON", StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> store.peek(ws()));
        assertTrue(store.peek(ws()).isEmpty());
    }

    @Test
    @DisplayName("格式版本对不上就忽略——宁可重来，也不能按旧结构解析出错的历史")
    void ignoresOldFormat() throws IOException {
        SessionStore store = store();
        store.save(history(), ws(), "deepseek-chat");

        String raw = Files.readString(store.pathFor(ws()));
        Files.writeString(store.pathFor(ws()),
                raw.replace("\"format\":" + SessionSnapshot.FORMAT, "\"format\":999"));

        assertTrue(store.peek(ws()).isEmpty());
    }

    @Test
    @DisplayName("只有 system（还没聊过）不留档，免得下次提示恢复一段空对话")
    void emptyConversationDeletesArchive() {
        SessionStore store = store();
        store.save(history(), ws(), "deepseek-chat");
        assertTrue(store.peek(ws()).isPresent());

        store.save(List.of(ChatMessage.system("只剩系统提示词")), ws(), "deepseek-chat");
        assertTrue(store.peek(ws()).isEmpty());
        assertFalse(Files.exists(store.pathFor(ws())));
    }

    @Test
    @DisplayName("修补之后一条不剩的存档也当作没有")
    void unrepairableArchiveIsIgnored() {
        SessionStore store = store();
        store.save(List.of(
                ChatMessage.system("系统"),
                ChatMessage.user("问了但没答")), ws(), "deepseek-chat");

        assertTrue(store.peek(ws()).isEmpty());
    }

    @Test
    @DisplayName("delete 之后就没了")
    void deleteRemovesArchive() {
        SessionStore store = store();
        store.save(history(), ws(), "deepseek-chat");
        store.delete(ws());
        assertTrue(store.peek(ws()).isEmpty());
    }

    @Test
    @DisplayName("盘写不了也不抛")
    void neverThrowsOnUnusableDisk() throws IOException {
        Path blocked = tmp.resolve("blocked");
        Files.writeString(blocked, "我是个文件");
        SessionStore store = new SessionStore(blocked);

        assertDoesNotThrow(() -> store.save(history(), ws(), "deepseek-chat"));
        assertDoesNotThrow(() -> store.peek(ws()));
        assertTrue(store.peek(ws()).isEmpty());
    }

    @Test
    @DisplayName("不同路径的同名目录不会串档")
    void sameDirNameDifferentPathsDoNotCollide() {
        SessionStore store = store();
        Path a = tmp.resolve("a").resolve("demo");
        Path b = tmp.resolve("b").resolve("demo");

        store.save(history(), a, "deepseek-chat");
        assertTrue(store.peek(a).isPresent());
        assertTrue(store.peek(b).isEmpty());
    }

    @Test
    @DisplayName("Agent.conversation() 摘掉 system，正好是要存的那部分")
    void conversationExcludesSystem() {
        Agent agent = new Agent(null, new ToolRegistry(), "系统提示词");
        assertTrue(agent.conversation().isEmpty());

        agent.restore(history().subList(1, 5));
        assertEquals(4, agent.conversation().size());
        assertTrue(agent.conversation().stream()
                .noneMatch(m -> ChatMessage.ROLE_SYSTEM.equals(m.role())));
    }
}
