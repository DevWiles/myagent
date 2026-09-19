package dev.wiles.myagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @TempDir
    Path tmp;

    private MemoryStore store() {
        return new MemoryStore(tmp.resolve("memory"));
    }

    private Path ws() {
        return tmp.resolve("proj");
    }

    private static MemoryEntry entry(String name, MemoryScope scope) {
        return new MemoryEntry(name, "摘要 " + name, "正文 " + name,
                scope, MemorySource.OBSERVATION, LocalDate.of(2026, 9, 17));
    }

    @Test
    @DisplayName("存了能读回来，五个字段一个不丢")
    void savesAndLoads() {
        MemoryStore store = store();
        MemoryEntry written = new MemoryEntry("mvn-offline", "跑测试要加 -o", "因为这台机器连 central 很慢",
                MemoryScope.PROJECT, MemorySource.USER_CORRECTION, LocalDate.of(2026, 9, 17));

        assertTrue(store.save(written, ws()));

        MemoryEntry read = store.load("mvn-offline", ws()).orElseThrow();
        assertEquals("mvn-offline", read.name());
        assertEquals("跑测试要加 -o", read.description());
        assertEquals("因为这台机器连 central 很慢", read.content());
        assertEquals(MemoryScope.PROJECT, read.scope());
        assertEquals(MemorySource.USER_CORRECTION, read.source());
        assertEquals(LocalDate.of(2026, 9, 17), read.created());
    }

    @Test
    @DisplayName("项目记忆换个目录就看不见了，全局记忆到哪都在")
    void scopeDecidesVisibility() {
        MemoryStore store = store();
        Path a = tmp.resolve("proj-a");
        Path b = tmp.resolve("proj-b");

        store.save(entry("a-only", MemoryScope.PROJECT), a);
        store.save(entry("everywhere", MemoryScope.GLOBAL), a);

        assertEquals(List.of("a-only", "everywhere"),
                store.scan(a).stream().map(MemoryEntry::name).toList());
        // b 目录看不到 a 的项目记忆，但看得到全局的
        assertEquals(List.of("everywhere"),
                store.scan(b).stream().map(MemoryEntry::name).toList());
        assertTrue(store.load("a-only", b).isEmpty());
    }

    @Test
    @DisplayName("同名时项目级压过全局级——具体的那条就是用来推翻通用规则的")
    void projectBeatsGlobal() {
        MemoryStore store = store();
        Path ws = ws();
        store.save(new MemoryEntry("pkg-manager", "一般用 npm", "通用默认",
                MemoryScope.GLOBAL, MemorySource.USER_STATED, LocalDate.now()), ws);
        store.save(new MemoryEntry("pkg-manager", "这个项目用 bun", "用户纠正过",
                MemoryScope.PROJECT, MemorySource.USER_CORRECTION, LocalDate.now()), ws);

        assertEquals("这个项目用 bun", store.load("pkg-manager", ws).orElseThrow().description());
    }

    @Test
    @DisplayName("手删正文文件，索引里立刻就没了——这是索引不落盘换来的")
    void indexNeverDriftsFromFiles() throws IOException {
        MemoryStore store = store();
        Path ws = ws();
        store.save(entry("gone-soon", MemoryScope.PROJECT), ws);
        store.save(entry("stays", MemoryScope.PROJECT), ws);
        assertEquals(2, store.index(ws).size());

        // 用户直接拿编辑器/rm 删掉一条，不经过任何 API
        Files.delete(store.pathFor("gone-soon", MemoryScope.PROJECT, ws));

        MemoryIndex index = store.index(ws);
        assertEquals(1, index.size());
        assertFalse(index.render().contains("gone-soon"));
    }

    @Test
    @DisplayName("坏文件被跳过，不连累同目录里好的那些")
    void brokenFileDoesNotKillTheScan() throws IOException {
        MemoryStore store = store();
        Path ws = ws();
        store.save(entry("good-one", MemoryScope.PROJECT), ws);

        Path dir = store.dirFor(MemoryScope.PROJECT, ws);
        Files.writeString(dir.resolve("no-frontmatter.md"), "光秃秃的正文，没有 ---");
        Files.writeString(dir.resolve("unterminated.md"), "---\nname: x\n还没闭合就结束了");
        Files.write(dir.resolve("not-utf8.md"), new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0x80});

        assertEquals(List.of("good-one"), store.scan(ws).stream().map(MemoryEntry::name).toList());
    }

    @Test
    @DisplayName("frontmatter 的值里带冒号不会被切坏")
    void valueMayContainColon() throws IOException {
        MemoryStore store = store();
        Path ws = ws();
        Path dir = store.dirFor(MemoryScope.PROJECT, ws);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("api-base.md"), """
                ---
                name: api-base
                description: baseUrl 是 https://api.deepseek.com，别改成 v1 结尾的
                source: user_stated
                created: 2026-09-17
                ---

                正文
                """, StandardCharsets.UTF_8);

        assertEquals("baseUrl 是 https://api.deepseek.com，别改成 v1 结尾的",
                store.load("api-base", ws).orElseThrow().description());
    }

    @Test
    @DisplayName("文件名才是权威的 name——用户重命名文件之后 recall 还能打中")
    void fileNameWinsOverFrontmatter() throws IOException {
        MemoryStore store = store();
        Path ws = ws();
        store.save(entry("old-name", MemoryScope.PROJECT), ws);

        Path dir = store.dirFor(MemoryScope.PROJECT, ws);
        Files.move(dir.resolve("old-name.md"), dir.resolve("new-name.md"));

        // 文件里的 name: 还写着 old-name，但按新文件名就该能查到
        assertTrue(store.load("new-name", ws).isPresent());
        assertEquals("new-name", store.load("new-name", ws).orElseThrow().name());
        assertTrue(store.load("old-name", ws).isEmpty());
    }

    @Test
    @DisplayName("盘写不了就返 false，绝不抛——不能为了记笔记让对话挂掉")
    void neverThrowsWhenDiskIsUnusable() throws IOException {
        // 把 memory 根目录做成一个普通文件，往下建目录必然失败
        Path blocked = tmp.resolve("blocked");
        Files.writeString(blocked, "我是个文件，不是目录");
        MemoryStore store = new MemoryStore(blocked);

        assertFalse(store.save(entry("whatever", MemoryScope.PROJECT), ws()));
        assertDoesNotThrow(() -> store.scan(ws()));
        assertDoesNotThrow(() -> store.load("whatever", ws()));
        assertTrue(store.scan(ws()).isEmpty());
    }

    @Test
    @DisplayName("同名覆盖，不留两份")
    void saveOverwrites() {
        MemoryStore store = store();
        Path ws = ws();
        store.save(new MemoryEntry("x", "旧的", "旧正文", MemoryScope.PROJECT, null, null), ws);
        store.save(new MemoryEntry("x", "新的", "新正文", MemoryScope.PROJECT, null, null), ws);

        assertEquals(1, store.scan(ws).size());
        assertEquals("新的", store.load("x", ws).orElseThrow().description());
    }

    @Test
    @DisplayName("forget 两个 scope 都清")
    void forgetRemovesBothScopes() {
        MemoryStore store = store();
        Path ws = ws();
        store.save(entry("dup", MemoryScope.PROJECT), ws);
        store.save(entry("dup", MemoryScope.GLOBAL), ws);

        assertTrue(store.forget("dup", ws));
        assertTrue(store.scan(ws).isEmpty());
        assertFalse(store.forget("dup", ws));   // 已经没了，第二次返 false
    }

    @Test
    @DisplayName("超过上限只放前 MAX_ENTRIES 条")
    void capsTheIndex() {
        MemoryStore store = store();
        Path ws = ws();
        for (int i = 0; i < MemoryStore.MAX_ENTRIES + 5; i++) {
            store.save(entry(String.format("m%03d", i), MemoryScope.PROJECT), ws);
        }
        assertEquals(MemoryStore.MAX_ENTRIES, store.scan(ws).size());
    }

    @Test
    @DisplayName("正文过长会截断——正文按需读可以长，但不能没边")
    void truncatesOversizedContent() {
        MemoryStore store = store();
        Path ws = ws();
        String huge = "x".repeat(MemoryStore.MAX_CONTENT * 2);
        store.save(new MemoryEntry("big", "很大一条", huge, MemoryScope.PROJECT, null, null), ws);

        String read = store.load("big", ws).orElseThrow().content();
        assertTrue(read.length() < huge.length());
        assertTrue(read.endsWith("（正文过长已截断）"));
    }

    @Test
    @DisplayName("存的文件是人能直接改的 Markdown")
    void fileIsHumanEditable() throws IOException {
        MemoryStore store = store();
        Path ws = ws();
        store.save(new MemoryEntry("readable", "给人看的", "正文在这儿",
                MemoryScope.PROJECT, MemorySource.USER_STATED, LocalDate.of(2026, 9, 17)), ws);

        String raw = Files.readString(store.pathFor("readable", MemoryScope.PROJECT, ws));
        assertTrue(raw.startsWith("---\n"));
        assertTrue(raw.contains("name: readable"));
        assertTrue(raw.contains("scope: project"));
        assertTrue(raw.contains("source: user_stated"));
        assertTrue(raw.contains("created: 2026-09-17"));
        assertTrue(raw.contains("正文在这儿"));
    }

    @Test
    @DisplayName("不同路径的同名目录不会互相覆盖")
    void sameDirNameDifferentPathsDoNotCollide() {
        MemoryStore store = store();
        Path a = tmp.resolve("a").resolve("demo");
        Path b = tmp.resolve("b").resolve("demo");

        store.save(new MemoryEntry("who", "我是 a", "a 的正文", MemoryScope.PROJECT, null, null), a);
        store.save(new MemoryEntry("who", "我是 b", "b 的正文", MemoryScope.PROJECT, null, null), b);

        assertEquals("我是 a", store.load("who", a).orElseThrow().description());
        assertEquals("我是 b", store.load("who", b).orElseThrow().description());
    }

    @Test
    @DisplayName("查不存在的记忆返空，不抛")
    void missingIsEmptyNotThrow() {
        MemoryStore store = store();
        assertEquals(Optional.empty(), store.load("nope", ws()));
        assertEquals(Optional.empty(), store.load("", ws()));
        assertEquals(Optional.empty(), store.load(null, ws()));
    }

    // ─────────────────────────── 取代与撤销 ───────────────────────────

    private static MemoryEntry topical(String name, String topic, MemorySource source) {
        return new MemoryEntry(name, "摘要 " + name, "正文 " + name, MemoryScope.PROJECT,
                source, LocalDate.of(2026, 9, 17),
                java.util.Map.of(MemoryMetadata.TOPIC_KEY, topic));
    }

    @Test
    @DisplayName("同主题的新事实把旧的顶掉——但旧文件留着，用户要能看见系统记过什么")
    void newFactSupersedesSameTopic() {
        MemoryStore store = store();
        store.save(topical("uses-npm", "包管理器", MemorySource.OBSERVATION), ws());

        store.save(topical("uses-bun", "包管理器", MemorySource.USER_CORRECTION), ws());

        MemoryEntry old = store.scan(ws()).stream()
                .filter(e -> e.name().equals("uses-npm")).findFirst().orElseThrow();
        assertTrue(old.isSuperseded(), "旧事实必须退场，否则模型同时收到两条自相矛盾的上下文");
        assertEquals("uses-bun", old.supersededBy());
        assertEquals(2, store.scan(ws()).size(), "审计视角要能看见历史条目");
        assertEquals(1, store.scanActive(ws()).size(), "模型视角只该看见生效的那条");
    }

    @Test
    @DisplayName("被取代的条目不进索引、也 recall 不到")
    void supersededIsInvisibleToTheModel() {
        MemoryStore store = store();
        store.save(topical("uses-npm", "包管理器", MemorySource.OBSERVATION), ws());
        store.save(topical("uses-bun", "包管理器", MemorySource.USER_CORRECTION), ws());

        assertFalse(store.index(ws()).render().contains("uses-npm"),
                "注入了过期事实，模型没有任何办法判断哪条新");
        assertTrue(store.load("uses-npm", ws()).isEmpty(),
                "索引里早没它了，还来问多半是上一段会话记住的名字——这时候给过期正文正是要防的");
        assertTrue(store.load("uses-bun", ws()).isPresent());
    }

    @Test
    @DisplayName("没写主题词的条目一律不参与取代——存量数据行为完全不变")
    void entriesWithoutTopicAreNeverTouched() {
        MemoryStore store = store();
        store.save(entry("old-one", MemoryScope.PROJECT), ws());

        store.save(topical("new-one", "某主题", MemorySource.USER_STATED), ws());

        assertEquals(2, store.scanActive(ws()).size(), "两条都该还生效");
    }

    @Test
    @DisplayName("主题词靠子串兜底：模型把同一件事叫成两个名字时也能对上")
    void topicMatchingToleratesDrift() {
        assertTrue(MemoryStore.isSameTopic("测试命令", "构建测试命令"),
                "模型实测会给出这两种叫法，精确匹配下取代永远不触发");
        assertTrue(MemoryStore.isSameTopic(" 包管理器 ", "包管理器"));
        assertFalse(MemoryStore.isSameTopic("命令", "测试命令"),
                "太短的词会把不同主题合并，把过度取代变成新的问题");
        assertFalse(MemoryStore.isSameTopic("测试命令", "包管理器"));
        assertFalse(MemoryStore.isSameTopic(null, "x"));
    }

    @Test
    @DisplayName("撤销自动写入时，被它取代的旧事实要跟着复活——不然用户「两条都没了」")
    void undoRestoresWhatItSuperseded() {
        MemoryStore store = store();
        store.save(topical("uses-npm", "包管理器", MemorySource.USER_STATED), ws());
        store.save(topical("uses-bun", "包管理器", MemorySource.OBSERVATION), ws());
        assertEquals(1, store.scanActive(ws()).size());

        MemoryEntry undone = store.undoLastAutoWrite(ws()).orElseThrow();

        assertEquals("uses-bun", undone.name());
        assertTrue(store.load("uses-bun", ws()).isEmpty(), "撤销了就该没了");
        assertTrue(store.load("uses-npm", ws()).isPresent(),
                "旧事实必须回来。少了这一步，撤销之后新旧都不生效，比不给撤销更糟");
        assertEquals(1, store.scanActive(ws()).size());
    }

    @Test
    @DisplayName("撤销只认自动写入的，用户自己记的一条都不碰")
    void undoNeverTouchesManualMemories() {
        MemoryStore store = store();
        store.save(new MemoryEntry("by-hand", "用户亲口说的", "正文", MemoryScope.PROJECT,
                MemorySource.USER_STATED, LocalDate.of(2026, 9, 18)), ws());

        assertTrue(store.undoLastAutoWrite(ws()).isEmpty(), "没有自动写入可撤销");
        assertTrue(store.load("by-hand", ws()).isPresent(), "他记的时候是清醒的");
    }

    @Test
    @DisplayName("批量清理同样只删自动记的")
    void clearAutoKeepsManualOnes() {
        MemoryStore store = store();
        store.save(entry("auto-1", MemoryScope.PROJECT), ws());          // OBSERVATION
        store.save(entry("auto-2", MemoryScope.PROJECT), ws());
        store.save(new MemoryEntry("manual", "用户记的", "正文", MemoryScope.PROJECT,
                MemorySource.USER_CORRECTION, LocalDate.of(2026, 9, 18)), ws());

        assertEquals(2, store.clearAutoMemories(ws()));

        List<MemoryEntry> left = store.scan(ws());
        assertEquals(1, left.size());
        assertEquals("manual", left.get(0).name());
    }

    @Test
    @DisplayName("主题词清单只给生效的那些，喂回模型让它复用而不是每次凭空取名")
    void activeTopicKeysExcludeSuperseded() {
        MemoryStore store = store();
        store.save(topical("uses-npm", "包管理器", MemorySource.OBSERVATION), ws());
        store.save(topical("uses-bun", "包管理器", MemorySource.USER_CORRECTION), ws());
        store.save(topical("test-cmd", "测试命令", MemorySource.USER_STATED), ws());

        List<String> topics = store.activeTopicKeys(ws());

        assertEquals(2, topics.size(), "同主题只剩一条，不该报两遍");
        assertTrue(topics.containsAll(List.of("包管理器", "测试命令")));
    }

    @Test
    @DisplayName("frontmatter 里我们不认识的键要原样留着——这是个用户能直接编辑的文件")
    void unknownFrontmatterKeysSurviveARoundTrip() throws IOException {
        MemoryStore store = store();
        store.save(new MemoryEntry("x", "摘要", "正文", MemoryScope.PROJECT,
                MemorySource.USER_STATED, LocalDate.of(2026, 9, 17),
                java.util.Map.of("我自己加的", "别删我")), ws());

        MemoryEntry read = store.load("x", ws()).orElseThrow();
        assertEquals("别删我", read.metadata().get("我自己加的"));

        // 再存一遍：经手一次保存也不能把人家写的东西抹了
        store.save(read, ws());
        assertEquals("别删我", store.load("x", ws()).orElseThrow().metadata().get("我自己加的"));
    }

    @Test
    @DisplayName("类型化字段不会被重复写进 frontmatter——写两遍的话文件每存一次长一行")
    void reservedKeysAreNotDuplicated() throws IOException {
        MemoryStore store = store();
        store.save(new MemoryEntry("y", "摘要", "正文", MemoryScope.PROJECT,
                MemorySource.USER_STATED, LocalDate.of(2026, 9, 17),
                java.util.Map.of("name", "冒充的", "source", "冒充的")), ws());

        String raw = Files.readString(
                tmp.resolve("memory").resolve(WorkspaceKeyProbe.of(ws())).resolve("y.md"),
                StandardCharsets.UTF_8);

        assertEquals(1, raw.lines().filter(l -> l.startsWith("name:")).count());
        assertEquals(1, raw.lines().filter(l -> l.startsWith("source:")).count());
    }

    /** 测试要按工作区分片去找文件，算法在 WorkspaceKey 里。 */
    private static final class WorkspaceKeyProbe {
        static String of(Path workspace) {
            return dev.wiles.myagent.tool.WorkspaceKey.of(workspace);
        }
    }
}
