package dev.wiles.myagent.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 这个文件用户随时会手改——撤销授权的唯一办法就是打开它删一行。
 * 所以「改坏了会怎样」比「正常情况对不对」更值得测：
 * 坏掉的后果必须是退回「每次都问」，不能是崩溃，更不能是静默放行。
 */
class PolicyStoreTest {

    @TempDir
    Path tmp;

    private PolicyStore store() {
        return new PolicyStore(tmp.resolve("policy.json"));
    }

    private void writeRaw(String json) throws IOException {
        Files.writeString(tmp.resolve("policy.json"), json);
    }

    @Test
    @DisplayName("存了能读回来")
    void roundTrip() {
        PolicyStore store = store();
        assertTrue(store.save(ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "mvn test *", "2026-09-20")),
                List.of(ApprovalRule.of(ApprovalKind.BASH, "git push *", "2026-09-20")))));

        ApprovalRules read = store.load();
        assertEquals(1, read.allow().size());
        assertEquals("mvn test *", read.allow().get(0).match());
        assertEquals("2026-09-20", read.allow().get(0).added());
        assertEquals(1, read.deny().size());
    }

    @Test
    @DisplayName("文件不在就是「还没批过任何东西」，不是错误")
    void missingFileIsEmpty() {
        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("写成的 JSON 要能看懂——撤销授权靠的就是手改它")
    void fileIsHumanReadable() throws IOException {
        store().save(ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "mvn test *", "2026-09-20")), List.of()));

        String text = Files.readString(tmp.resolve("policy.json"));
        assertTrue(text.contains("\n"), "挤成一行没法手改");
        assertTrue(text.contains("\"tool\" : \"bash\""), text);
        assertTrue(text.contains("mvn test *"), text);
        assertTrue(text.contains("2026-09-20"), "没有日期，用户翻到这行不知道是什么时候批的");
    }

    @Test
    @DisplayName("JSON 坏了就当没有规则——退回每次都问，不能崩也不能放行")
    void brokenJsonFallsBackToAsking() throws IOException {
        writeRaw("{ 这不是 JSON");

        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("根节点不是对象也一样")
    void nonObjectRootFallsBack() throws IOException {
        writeRaw("[1, 2, 3]");

        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("一条坏规则只毁掉它自己，不牵连整份文件")
    void oneBadRuleDoesNotKillTheRest() throws IOException {
        // 全文件作废的话，用户手改时敲错一个字，丢掉的是全部授权
        writeRaw("""
                {"version":1,"allow":[
                  {"tool":"bash","match":"mvn test *"},
                  {"tool":"没这种","match":"x"},
                  {"tool":"bash","match":""},
                  {"tool":"write","match":"src/**"}
                ]}""");

        ApprovalRules rules = store().load();
        assertEquals(2, rules.allow().size(), rules.allow().toString());
    }

    @Test
    @DisplayName("version 比本程序新就整份不认——漏一条 deny 比漏一条 allow 危险")
    void newerVersionIsRejectedWholesale() throws IOException {
        writeRaw("{\"version\":99,\"allow\":[{\"tool\":\"bash\",\"match\":\"rm *\"}]}");

        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("存盘不留半个文件：写临时文件再原子替换")
    void doesNotLeaveTempFiles() throws IOException {
        store().save(ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "ls *", "d")), List.of()));

        try (var entries = Files.list(tmp)) {
            assertEquals(List.of("policy.json"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    @DisplayName("父目录不存在会自己建——第一次按「以后都同意」时 ~/.myagent 可能还没有")
    void createsParentDirectory() {
        PolicyStore store = new PolicyStore(tmp.resolve("nested/deeper/policy.json"));

        assertTrue(store.save(ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "ls *", "d")), List.of())));
        assertEquals(1, store.load().allow().size());
    }

    @Test
    @DisplayName("手改过之后 lastModified 会变——PolicyGate 靠它决定重新装载")
    void tracksModificationTime() throws Exception {
        PolicyStore store = store();
        assertEquals(0L, store.lastModified(), "文件不在时该是 0");

        store.save(ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "ls *", "d")), List.of()));
        assertTrue(store.lastModified() > 0);
    }
}
