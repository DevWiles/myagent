package dev.wiles.myagent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.WorkspaceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    /** 一次会话的 id。平时由 {@link SessionId#create} 生成，测试里写死好断言。 */
    private static final String ID = "20260918-143210-abcd";
    /** 这段对话是什么时候开始的。 */
    private static final long STARTED = 1758175930000L;

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

    private static List<ChatMessage> history(String firstUserLine) {
        return List.of(
                ChatMessage.system("你是 myagent。"),
                ChatMessage.user(firstUserLine),
                ChatMessage.assistant("好"));
    }

    @Test
    @DisplayName("存了能读回来，消息逐条相等")
    void savesAndLoads() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        SessionSnapshot snapshot = store.latest(ws()).orElseThrow();
        assertEquals(ID, snapshot.id());
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
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        SessionSnapshot snapshot = store.latest(ws()).orElseThrow();
        assertTrue(snapshot.messages().stream()
                        .noneMatch(m -> ChatMessage.ROLE_SYSTEM.equals(m.role())),
                "存了 system 的话，恢复时会把上次的记忆索引一起带回来");
    }

    @Test
    @DisplayName("恢复用的是当前的 system，不是上次那条——这轮新记的记忆才看得见")
    void restoreUsesCurrentSystemPrompt() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        // 下次启动，系统提示词变了（比如中间 remember 了一条，索引多了一行）
        String nowPrompt = "你是 myagent。\n## 你记住的事\n- mvn-offline — 跑测试要加 -o";
        Agent agent = new Agent(null, new ToolRegistry(), nowPrompt);
        agent.restore(store.latest(ws()).orElseThrow().restorable());

        assertEquals(nowPrompt, agent.history().get(0).content(),
                "拿存档里那条 system 的话，新记的东西永远不生效");
        assertEquals(5, agent.history().size());
    }

    @Test
    @DisplayName("存档停在「工具点了、结果没回来」，恢复时封口而不是截断——现场要留住")
    void sealsTruncatedArchive() {
        SessionStore store = store();
        List<ChatMessage> dying = new ArrayList<>(history());
        dying.add(ChatMessage.user("再改个文件"));
        dying.add(ChatMessage.assistant("好",
                List.of(ToolCall.of("c2", "write_file", "{}"))));   // 进程死在这儿

        store.save(ID, dying, ws(), "deepseek-chat", STARTED);

        Agent agent = new Agent(null, new ToolRegistry(), "系统提示词");
        agent.restore(store.latest(ws()).orElseThrow().restorable());

        assertTrue(HistoryRepair.isIntact(agent.conversation()), "封完必须自洽，否则每次请求都 400");
        // 记录只追加，截掉的东西再也回不来——所以「再改个文件」这个现场必须还在
        assertTrue(agent.conversation().stream()
                        .anyMatch(m -> "再改个文件".equals(m.content())),
                "断在半路的那一轮是最值钱的现场，不能为了自洽把它切掉");
        assertTrue(agent.conversation().stream()
                        .anyMatch(m -> ChatMessage.ROLE_TOOL.equals(m.role())
                                && m.content().contains("没有执行")),
                "没回来的工具要补一条说明，模型才知道这步没做");
        assertEquals(ChatMessage.ROLE_ASSISTANT,
                agent.conversation().get(agent.conversation().size() - 1).role(),
                "末尾要收成一条 assistant，下一句用户输入才接得上");
    }

    @Test
    @DisplayName("换个工作区不给恢复——A 项目的上下文接着聊 B 项目会给出很笃定的错结论")
    void refusesWrongWorkspace() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        assertTrue(store.latest(tmp.resolve("another-proj")).isEmpty());
        assertTrue(store.list(tmp.resolve("another-proj")).isEmpty());
    }

    @Test
    @DisplayName("存档损坏当作没有，不抛")
    void corruptedArchiveIsIgnored() throws IOException {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        Files.writeString(store.pathFor(ws(), ID), "{ 这不是合法 JSON", StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> store.latest(ws()));
        assertTrue(store.latest(ws()).isEmpty());
        assertTrue(store.list(ws()).isEmpty(), "读不出来的那份不该占着列表里一行");
    }

    @Test
    @DisplayName("格式版本对不上就忽略——宁可重来，也不能按旧结构解析出错的历史")
    void ignoresOldFormat() throws IOException {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        String raw = Files.readString(store.pathFor(ws(), ID));
        Files.writeString(store.pathFor(ws(), ID),
                raw.replace("\"format\":" + SessionSnapshot.FORMAT, "\"format\":999"));

        assertTrue(store.latest(ws()).isEmpty());
    }

    @Test
    @DisplayName("只有 system（还没聊过）不留档，免得历史里多一行什么都没说的会话")
    void emptyConversationDeletesArchive() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);
        assertTrue(store.latest(ws()).isPresent());

        store.save(ID, List.of(ChatMessage.system("只剩系统提示词")), ws(), "deepseek-chat", STARTED);
        assertTrue(store.latest(ws()).isEmpty());
        assertFalse(Files.exists(store.pathFor(ws(), ID)));
    }

    @Test
    @DisplayName("只问了一句就断电的存档照样接得回来——用户的问题不该被悄悄丢掉")
    void questionOnlyArchiveIsStillRestorable() {
        SessionStore store = store();
        store.save(ID, List.of(
                ChatMessage.system("系统"),
                ChatMessage.user("问了但没答")), ws(), "deepseek-chat", STARTED);

        var snapshot = store.latest(ws()).orElseThrow();
        assertEquals("问了但没答", snapshot.firstUserMessage());

        Agent agent = new Agent(null, new ToolRegistry(), "系统提示词");
        agent.restore(snapshot.restorable());

        assertTrue(HistoryRepair.isIntact(agent.conversation()));
        assertTrue(agent.conversation().stream()
                .anyMatch(m -> "问了但没答".equals(m.content())));
    }

    @Test
    @DisplayName("摘要段跟着存档一起存——续聊时不用重新 map 一遍")
    void roundTripsSegments() {
        SessionStore store = store();
        List<SummarySegment> segments = List.of(
                new SummarySegment(0, 2, "前两条聊了怎么跑测试"));

        store.save(ID, history(), ws(), "deepseek-chat", STARTED, segments, TokenLedger.EMPTY);

        var loaded = store.latest(ws()).orElseThrow();
        assertEquals(segments, loaded.segments());
        assertEquals(segments, loaded.usableSegments());
    }

    @Test
    @DisplayName("下标越界的段要丢掉——那是指错了原文，比没有更危险")
    void dropsOutOfBoundsSegments() {
        SessionStore store = store();
        // history() 去掉 system 之后是 4 条，这个段说自己覆盖到第 99 条
        store.save(ID, history(), ws(), "deepseek-chat", STARTED,
                List.of(new SummarySegment(0, 99, "指向不存在的消息")), TokenLedger.EMPTY);

        var loaded = store.latest(ws()).orElseThrow();
        assertEquals(1, loaded.segments().size(), "原样读回来");
        assertTrue(loaded.usableSegments().isEmpty(), "但不许拿去用");
    }

    @Test
    @DisplayName("reduce 的产物和覆盖边界也要存——不然恢复后要在主线程白跑一次 reduce")
    void roundTripsSummaryAndCoverage() {
        SessionStore store = store();
        var compaction = new ContextManager.Compaction(
                List.of(new SummarySegment(0, 2, "前两条聊了怎么跑测试")),
                "用户要改 README 的测试数字，已经改了 3 处", 2);

        store.save(ID, history(), ws(), "deepseek-chat", STARTED, compaction, TokenLedger.EMPTY);

        var loaded = store.latest(ws()).orElseThrow();
        assertEquals("用户要改 README 的测试数字，已经改了 3 处", loaded.summary());
        assertEquals(2, loaded.coversUpTo());

        var usable = loaded.usableCompaction();
        assertEquals("用户要改 README 的测试数字，已经改了 3 处", usable.summary());
        assertEquals(2, usable.coversUpTo());
        assertEquals(1, usable.segments().size());
    }

    @Test
    @DisplayName("覆盖边界越界时整份压缩状态作废，但段留着——丢了只是重算，认错了是张冠李戴")
    void dropsCompactionWhenCoverageOutOfBounds() {
        SessionStore store = store();
        // history() 去掉 system 之后是 4 条，这里说摘要覆盖到第 99 条
        store.save(ID, history(), ws(), "deepseek-chat", STARTED,
                new ContextManager.Compaction(
                        List.of(new SummarySegment(0, 2, "还对得上的段")),
                        "覆盖边界对不上的摘要", 99),
                TokenLedger.EMPTY);

        var loaded = store.latest(ws()).orElseThrow();
        assertEquals(99, loaded.coversUpTo(), "原样读回来");

        var usable = loaded.usableCompaction();
        assertNull(usable.summary(), "摘要不许拿去用");
        assertEquals(0, usable.coversUpTo());
        assertEquals(1, usable.segments().size(), "段本身还对得上，留着下次省一次 map");
    }

    @Test
    @DisplayName("只有摘要没有边界（或反过来）一律作废——半份状态比没有更危险")
    void halfACompactionIsDiscarded() {
        SessionStore store = store();

        store.save(ID, history(), ws(), "deepseek-chat", STARTED,
                new ContextManager.Compaction(List.of(), "有摘要，但不知道覆盖到哪", 0),
                TokenLedger.EMPTY);
        var noCoverage = store.latest(ws()).orElseThrow();
        assertNull(noCoverage.summary(), "不知道尾巴从哪接，这条摘要就用不了");

        store.save(ID, history(), ws(), "deepseek-chat", STARTED,
                new ContextManager.Compaction(List.of(), null, 3),
                TokenLedger.EMPTY);
        var noSummary = store.latest(ws()).orElseThrow();
        assertEquals(0, noSummary.coversUpTo(), "没有摘要却把尾巴切掉，那段历史就凭空没了");
    }

    @Test
    @DisplayName("老存档没有这两个字段，读出来是空的压缩状态，不炸")
    void oldArchiveWithoutSummaryStillLoads() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        var loaded = store.latest(ws()).orElseThrow();
        assertNull(loaded.summary());
        assertEquals(0, loaded.coversUpTo());
        assertTrue(loaded.usableCompaction().isEmpty());
    }

    @Test
    @DisplayName("压缩过的会话，列表里显示的仍然是用户真说的第一句")
    void compactedSessionStillShowsRealFirstMessage() {
        SessionStore store = store();
        List<ChatMessage> withSummaryShaped = new ArrayList<>();
        withSummaryShaped.add(ChatMessage.system("系统"));
        withSummaryShaped.add(ChatMessage.user("帮我把 README 的测试数改过来"));
        withSummaryShaped.add(ChatMessage.assistant("好"));
        for (int i = 0; i < 6; i++) {
            withSummaryShaped.add(ChatMessage.user("接着问 " + i));
            withSummaryShaped.add(ChatMessage.assistant("答 " + i));
        }

        // 摘要覆盖了前 4 条，但它只活在 segments 里，记录一个字没动
        store.save(ID, withSummaryShaped, ws(), "deepseek-chat", STARTED,
                List.of(new SummarySegment(0, 4, "[以下是早先对话的摘要，原文已省略] 用户要改 README")),
                TokenLedger.EMPTY);

        var summary = store.list(ws()).get(0);

        assertEquals("帮我把 README 的测试数改过来", summary.title(),
                "旧实现会在这儿显示「[以下是早先对话的摘要，原文已省略]」");
    }

    @Test
    @DisplayName("delete 之后就没了")
    void deleteRemovesArchive() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);
        store.delete(ws(), ID);
        assertTrue(store.latest(ws()).isEmpty());
    }

    @Test
    @DisplayName("盘写不了也不抛")
    void neverThrowsOnUnusableDisk() throws IOException {
        Path blocked = tmp.resolve("blocked");
        Files.writeString(blocked, "我是个文件");
        SessionStore store = new SessionStore(blocked);

        assertDoesNotThrow(() -> store.save(ID, history(), ws(), "deepseek-chat", STARTED));
        assertDoesNotThrow(() -> store.latest(ws()));
        assertDoesNotThrow(() -> store.list(ws()));
        assertTrue(store.latest(ws()).isEmpty());
    }

    @Test
    @DisplayName("不同路径的同名目录不会串档")
    void sameDirNameDifferentPathsDoNotCollide() {
        SessionStore store = store();
        Path a = tmp.resolve("a").resolve("demo");
        Path b = tmp.resolve("b").resolve("demo");

        store.save(ID, history(), a, "deepseek-chat", STARTED);
        assertTrue(store.latest(a).isPresent());
        assertTrue(store.latest(b).isEmpty());
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

    // ---------- 多会话 ----------

    @Test
    @DisplayName("同一个工作区能并排放多份会话，列表最近的在最前面")
    void keepsManySessionsNewestFirst() {
        SessionStore store = store();
        store.save("20260916-090000-aaaa", history("周一聊的"), ws(), "deepseek-chat", STARTED);
        store.save("20260917-090000-bbbb", history("周二聊的"), ws(), "deepseek-chat", STARTED);
        store.save("20260918-090000-cccc", history("周三聊的"), ws(), "deepseek-chat", STARTED);

        List<SessionSummary> list = store.list(ws());
        assertEquals(3, list.size(), "新会话不该覆盖掉旧的");
        assertEquals(List.of("周三聊的", "周二聊的", "周一聊的"),
                list.stream().map(SessionSummary::title).toList());
        assertEquals("20260918-090000-cccc", store.latest(ws()).orElseThrow().id());
    }

    @Test
    @DisplayName("按 id 取某一份，拿到的是那一份的内容")
    void loadsTheOneYouAskedFor() {
        SessionStore store = store();
        store.save("20260916-090000-aaaa", history("周一聊的"), ws(), "deepseek-chat", STARTED);
        store.save("20260918-090000-cccc", history("周三聊的"), ws(), "deepseek-chat", STARTED);

        assertEquals("周一聊的",
                store.load(ws(), "20260916-090000-aaaa").orElseThrow().firstUserMessage());
        assertEquals("周三聊的",
                store.load(ws(), "20260918-090000-cccc").orElseThrow().firstUserMessage());
        assertTrue(store.load(ws(), "20260101-000000-9999").isEmpty(), "没有的那份就是没有");
    }

    @Test
    @DisplayName("删一份只删那一份，别的历史还在")
    void deleteTouchesOnlyOneSession() {
        SessionStore store = store();
        store.save("20260916-090000-aaaa", history("周一聊的"), ws(), "deepseek-chat", STARTED);
        store.save("20260918-090000-cccc", history("周三聊的"), ws(), "deepseek-chat", STARTED);

        store.delete(ws(), "20260918-090000-cccc");

        assertEquals(List.of("周一聊的"), store.list(ws()).stream()
                .map(SessionSummary::title).toList());
    }

    @Test
    @DisplayName("摘要够画一行列表：首句、轮数、模型、时间")
    void summaryCarriesWhatThePickerNeeds() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        SessionSummary row = store.list(ws()).get(0);
        assertEquals(ID, row.id());
        assertEquals("这个项目怎么跑测试", row.title());
        assertEquals(1, row.turns());
        assertEquals("deepseek-chat", row.model());
        assertEquals(STARTED, row.startedAt());
        assertTrue(row.savedAt() >= row.startedAt());
    }

    @Test
    @DisplayName("续聊时 startedAt 沿用旧的——不然列表里「什么时候开始的」每轮都被刷成现在")
    void startedAtIsNotRefreshedOnEverySave() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        assertEquals(STARTED, store.latest(ws()).orElseThrow().startedAt());
    }

    @Test
    @DisplayName("id 不合格一律不写盘——这个 id 是要拿去拼文件名的")
    void refusesToWriteWithABadId() throws IOException {
        SessionStore store = store();

        assertDoesNotThrow(() -> store.save("../../evil", history(), ws(), "deepseek-chat", STARTED));
        assertDoesNotThrow(() -> store.save(null, history(), ws(), "deepseek-chat", STARTED));

        try (Stream<Path> all = Files.walk(tmp)) {
            assertEquals(0, all.filter(p -> p.toString().endsWith(".json")).count(),
                    "一个存档文件都不该被创建出来");
        }
    }

    @Test
    @DisplayName("读的时候 id 也要先认形状，免得读到工作区外面去")
    void refusesToReadWithABadId() {
        SessionStore store = store();
        assertTrue(store.load(ws(), "../../../etc/passwd").isEmpty());
        assertTrue(store.load(ws(), "latest").isEmpty());
        assertTrue(store.load(ws(), null).isEmpty());
    }

    @Test
    @DisplayName("文件名和内容里的 id 对不上就不恢复——这种串档现象是「续聊续出了另一段对话」")
    void refusesWhenIdDoesNotMatchFileName() throws IOException {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        String raw = Files.readString(store.pathFor(ws(), ID));
        Files.writeString(store.pathFor(ws(), ID),
                raw.replace("\"" + ID + "\"", "\"20260101-000000-0001\""));

        assertTrue(store.load(ws(), ID).isEmpty());
        assertTrue(store.list(ws()).isEmpty());
    }

    @Test
    @DisplayName("写坏的文件名不占列表里的一行，也不让整个列表翻车")
    void ignoresFilesThatAreNotSessions() throws IOException {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);
        Files.writeString(store.dirFor(ws()).resolve("README.txt"), "别管我");
        Files.writeString(store.dirFor(ws()).resolve("随手存的.json"), "{}");

        assertEquals(1, store.list(ws()).size());
    }

    @Test
    @DisplayName("存档记下当时在哪个分支上——挑会话时这是个强信号")
    void recordsTheBranchItWasOn() throws IOException {
        Files.createDirectories(ws().resolve(".git"));
        Files.writeString(ws().resolve(".git/HEAD"), "ref: refs/heads/feature/picker\n");
        SessionStore store = store();

        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        assertEquals("feature/picker", store.latest(ws()).orElseThrow().branch());
        assertEquals("feature/picker", store.list(ws()).get(0).branch());
    }

    @Test
    @DisplayName("不在 git 仓库里照样存，分支是 null")
    void worksOutsideAGitRepo() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        assertNull(store.latest(ws()).orElseThrow().branch());
        assertNull(store.list(ws()).get(0).branch());
    }

    @Test
    @DisplayName("摘要带存档大小——它是「这段聊得有多沉」的便宜代理")
    void summaryCarriesFileSize() {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-chat", STARTED);

        long bytes = store.list(ws()).get(0).bytes();
        assertTrue(bytes > 100, "至少得是这份存档真实的字节数，拿到的是 " + bytes);
    }

    // ---------- 老存档（格式 1，一个工作区平铺一份） ----------

    private Path legacyFile() {
        return tmp.resolve("sessions").resolve(WorkspaceKey.of(ws()) + ".json");
    }

    private void writeLegacy(String body) throws IOException {
        Files.createDirectories(legacyFile().getParent());
        Files.writeString(legacyFile(), body, StandardCharsets.UTF_8);
    }

    private void writeLegacyArchive(long savedAt) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("format", 1);
        node.put("workspace", ws().toAbsolutePath().normalize().toString());
        node.put("model", "deepseek-chat");
        node.put("savedAt", savedAt);
        node.set("messages", mapper.valueToTree(history().subList(1, 5)));
        writeLegacy(mapper.writeValueAsString(node));
    }

    @Test
    @DisplayName("老存档被收成历史里的一条会话，不是升个版本就弄丢")
    void adoptsLegacyArchive() throws IOException {
        SessionStore store = store();
        writeLegacyArchive(STARTED);

        List<SessionSummary> list = store.list(ws());
        assertEquals(1, list.size());
        assertEquals("这个项目怎么跑测试", list.get(0).title());
        assertEquals(SessionId.forLegacy(STARTED), list.get(0).id());
        assertEquals(STARTED, list.get(0).savedAt());
        assertFalse(Files.exists(legacyFile()), "收进来之后老文件该没了，不然每次都要再收一遍");
        assertTrue(Files.exists(store.pathFor(ws(), SessionId.forLegacy(STARTED))));
    }

    @Test
    @DisplayName("收进来的老会话能直接接着聊")
    void adoptedLegacyArchiveIsRestorable() throws IOException {
        SessionStore store = store();
        writeLegacyArchive(STARTED);

        SessionSnapshot snapshot = store.latest(ws()).orElseThrow();
        Agent agent = new Agent(null, new ToolRegistry(), "此刻的系统提示词");
        assertEquals(4, agent.restore(snapshot.restorable()));
    }

    @Test
    @DisplayName("看不懂的老档留在原地——收不进来就算了，但不能顺手删掉一个没看懂的文件")
    void leavesUnreadableLegacyArchiveAlone() throws IOException {
        SessionStore store = store();
        writeLegacy("{ 这不是合法 JSON");

        assertTrue(store.list(ws()).isEmpty());
        assertTrue(Files.exists(legacyFile()));
    }

    @Test
    @DisplayName("不是格式 1 的平铺档也留在原地")
    void leavesUnknownLegacyFormatAlone() throws IOException {
        SessionStore store = store();
        writeLegacy("{\"format\":42,\"messages\":[]}");

        assertTrue(store.list(ws()).isEmpty());
        assertTrue(Files.exists(legacyFile()));
    }

    @Test
    @DisplayName("老档记的工作区对不上，不收")
    void refusesLegacyArchiveFromAnotherWorkspace() throws IOException {
        SessionStore store = store();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("format", 1);
        node.put("workspace", tmp.resolve("别的项目").toString());
        node.put("model", "deepseek-chat");
        node.put("savedAt", STARTED);
        node.set("messages", mapper.valueToTree(history().subList(1, 5)));
        writeLegacy(mapper.writeValueAsString(node));

        assertTrue(store.list(ws()).isEmpty());
        assertTrue(Files.exists(legacyFile()));
    }

    @Test
    @DisplayName("老档和新会话并存时，两份都在列表里")
    void legacyAndNewSessionsCoexist() throws IOException {
        SessionStore store = store();
        writeLegacyArchive(STARTED);
        store.save("20260918-090000-cccc", history("今天聊的"), ws(), "deepseek-chat", STARTED);

        assertEquals(2, store.list(ws()).size());
        assertEquals("今天聊的", store.list(ws()).get(0).title(), "新的排前面");
    }

    @Test
    @DisplayName("用量账本跟着存档走——续聊之后「这次会话花了多少」不该归零")
    void roundTripsLedger() {
        SessionStore store = store();
        TokenLedger spent = new TokenLedger(156_800, 3_100, 42, 1);

        store.save(ID, history(), ws(), "deepseek-flash", STARTED, List.of(), spent);

        assertEquals(spent, store.latest(ws()).orElseThrow().ledger());
    }

    @Test
    @DisplayName("没有账本字段的老存档照样读得出来——加字段不该把已有会话作废")
    void oldArchiveWithoutLedgerStillLoads() throws IOException {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-flash", STARTED, List.of(),
                new TokenLedger(100, 10, 1, 0));

        // 把字段整个删掉，模拟这次改动之前存下来的档
        String raw = Files.readString(store.pathFor(ws(), ID));
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(raw);
        node.remove("ledger");
        Files.writeString(store.pathFor(ws(), ID), node.toString(), StandardCharsets.UTF_8);

        var loaded = store.latest(ws());
        assertTrue(loaded.isPresent(), "少一个可选字段不该让整份存档读不出来");
        assertEquals(TokenLedger.EMPTY, loaded.get().ledger(), "当空账本处理");
    }

    // ---------- 断点跟着存档走 ----------

    @Test
    @DisplayName("断点跟着存档走——关了终端再 /continue，照样知道上一轮断在半路")
    void roundTripsInterruption() {
        SessionStore store = store();
        Interruption cut = new Interruption("已取消", "把 Parser 修了", 3, 1758100000000L, 4);

        store.save(ID, history(), ws(), "deepseek-flash", STARTED,
                (ContextManager.Compaction) null, TokenLedger.EMPTY, cut);

        assertEquals(cut, store.latest(ws()).orElseThrow().interrupted());
    }

    @Test
    @DisplayName("没断过就不写这个字段；老存档没有它也照样读得出来")
    void noInterruptionMeansNoField() throws IOException {
        SessionStore store = store();
        store.save(ID, history(), ws(), "deepseek-flash", STARTED);

        String raw = Files.readString(store.pathFor(ws(), ID));
        assertFalse(new ObjectMapper().readTree(raw).hasNonNull("interrupted"), raw);
        assertNull(store.latest(ws()).orElseThrow().interrupted());
    }
}
