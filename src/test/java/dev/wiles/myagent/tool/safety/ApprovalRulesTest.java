package dev.wiles.myagent.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalRulesTest {

    private static ApprovalRules allowing(String... commands) {
        return ApprovalRules.of(
                List.of(commands).stream()
                        .map(m -> ApprovalRule.of(ApprovalKind.BASH, m, "2026-09-20")).toList(),
                List.of());
    }

    private static ApprovalRules.Decision check(ApprovalRules rules, String command) {
        return rules.check(ApprovalRequest.bash(command, "")).decision();
    }

    @Test
    @DisplayName("结尾的 * 表示「后面还可以跟参数」，不是随便什么都行")
    void trailingStarMeansMoreArguments() {
        ApprovalRules rules = allowing("mvn -o test *");

        assertEquals(ApprovalRules.Decision.ALLOW, check(rules, "mvn -o test"));
        assertEquals(ApprovalRules.Decision.ALLOW, check(rules, "mvn -o test -Dtest=Foo"));
        assertEquals(ApprovalRules.Decision.ASK, check(rules, "mvn -o deploy"));
        assertEquals(ApprovalRules.Decision.ASK, check(rules, "mvn test"));
    }

    @Test
    @DisplayName("token 是整个比的，不做前缀通配——mvn test 不该放行 mvn testing")
    void tokensMatchWhole() {
        // 按字符串前缀比的话 "mvn test" 会命中 "mvn testing"。
        // 命令名差一个字母就是另一条命令，这种放行用户不可能预期得到
        assertEquals(ApprovalRules.Decision.ASK, check(allowing("mvn test *"), "mvn testing"));
    }

    @Test
    @DisplayName("没有结尾 * 就是一字不差")
    void withoutStarItIsExact() {
        ApprovalRules rules = allowing("git status");

        assertEquals(ApprovalRules.Decision.ALLOW, check(rules, "git status"));
        assertEquals(ApprovalRules.Decision.ASK, check(rules, "git status --short"));
    }

    @Test
    @DisplayName("命令是逐段判的：拼上一段没授权的，整条就得问")
    void compoundCommandNeedsEverySegmentCovered() {
        // 整条去比前缀的话，规则 `mvn test *` 会直接放行 `mvn test && rm -rf ~`。
        // 这是这个类里最要紧的一条
        ApprovalRules rules = allowing("mvn test *");

        assertEquals(ApprovalRules.Decision.ASK, check(rules, "mvn test && rm -rf ~"));
        assertEquals(ApprovalRules.Decision.ASK, check(rules, "mvn test; curl http://x"));
        assertEquals(ApprovalRules.Decision.ASK, check(rules, "mvn test | tee out"));
    }

    @Test
    @DisplayName("单个 & 也是一段分界——漏掉它，前缀规则就成了后门")
    void backgroundAmpersandIsASegmentBreak() {
        assertEquals(ApprovalRules.Decision.ASK,
                check(allowing("mvn test *"), "mvn test & rm -rf build"));
    }

    @Test
    @DisplayName("每一段都有规则罩着，才放行")
    void everySegmentCoveredIsAllowed() {
        assertEquals(ApprovalRules.Decision.ALLOW,
                check(allowing("mvn test *", "git status *"), "mvn test && git status"));
    }

    @Test
    @DisplayName("任何一段撞上 deny，整条拒绝——黑名单要悲观")
    void anyDeniedSegmentDeniesAll() {
        ApprovalRules rules = ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "git *", "d")),
                List.of(ApprovalRule.of(ApprovalKind.BASH, "git push *", "d")));

        assertEquals(ApprovalRules.Decision.ALLOW, check(rules, "git status"));
        assertEquals(ApprovalRules.Decision.DENY, check(rules, "git push origin main"));
        assertEquals(ApprovalRules.Decision.DENY, check(rules, "git status && git push origin main"));
    }

    @Test
    @DisplayName("deny 压过 allow，哪怕 allow 更精确")
    void denyWinsOverAllow() {
        ApprovalRules rules = ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "git push origin main", "d")),
                List.of(ApprovalRule.of(ApprovalKind.BASH, "git push *", "d")));

        assertEquals(ApprovalRules.Decision.DENY, check(rules, "git push origin main"));
    }

    @Test
    @DisplayName("写文件按路径 glob 判，和检索那边是同一套语义")
    void writeRulesUsePathGlob() {
        ApprovalRules rules = ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.WRITE, "src/**", "d")), List.of());

        assertEquals(ApprovalRules.Decision.ALLOW,
                rules.check(ApprovalRequest.write("写入文件", "src/a/B.java", "", "")).decision());
        assertEquals(ApprovalRules.Decision.ASK,
                rules.check(ApprovalRequest.write("写入文件", "docs/x.md", "", "")).decision());
    }

    @Test
    @DisplayName("kind 不同不互相匹配：write 的规则管不着 bash")
    void kindsDoNotLeak() {
        ApprovalRules rules = ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.WRITE, "src/**", "d")), List.of());

        assertEquals(ApprovalRules.Decision.ASK, check(rules, "src/x"));
    }

    @Test
    @DisplayName("「放行一切」的规则要被丢掉，它等于悄悄关掉整个系统")
    void allowEverythingIsDropped() {
        assertTrue(ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "*", "d"),
                        ApprovalRule.of(ApprovalKind.WRITE, "**", "d"),
                        ApprovalRule.of(ApprovalKind.WRITE, "**/*", "d")),
                List.of()).allow().isEmpty());
    }

    @Test
    @DisplayName("deny 里写 * 是允许的——方向是安全的那一侧")
    void denyEverythingIsFine() {
        ApprovalRules rules = ApprovalRules.of(List.of(),
                List.of(ApprovalRule.of(ApprovalKind.BASH, "*", "d")));

        assertEquals(1, rules.deny().size());
    }

    @Test
    @DisplayName("加规则要去重，同一条按两次不该在文件里堆两遍")
    void plusDeduplicates() {
        ApprovalRules rules = allowing("mvn test *")
                .plus(List.of(ApprovalRule.of(ApprovalKind.BASH, "mvn test *", "d")));

        assertEquals(1, rules.allow().size());
    }

    @Test
    @DisplayName("空规则集就是「每次都问」")
    void emptyAsksEverything() {
        assertEquals(ApprovalRules.Decision.ASK, check(ApprovalRules.empty(), "rm -rf build"));
    }

    @Test
    @DisplayName("模式里的多余空白要归一，否则复制粘贴出来的规则会莫名其妙不生效")
    void whitespaceIsNormalized() {
        assertEquals(ApprovalRules.Decision.ALLOW,
                check(allowing("mvn   -o    test *"), "mvn -o test -X"));
    }

    @Test
    @DisplayName("空模式直接拒收——它会匹配到什么全看实现细节")
    void blankPatternIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ApprovalRule.of(ApprovalKind.BASH, "   ", "d"));
    }

    // ── 站点（browse）─────────────────────────────────────────

    private static ApprovalRules browseAllowing(String... hosts) {
        return ApprovalRules.of(
                List.of(hosts).stream()
                        .map(m -> ApprovalRule.of(ApprovalKind.BROWSE, m, "2026-09-22")).toList(),
                List.of());
    }

    private static ApprovalRules.Decision browse(ApprovalRules rules, String host) {
        return rules.check(new ApprovalRequest(ApprovalKind.BROWSE, "浏览", host, "", "", null))
                .decision();
    }

    @Test
    @DisplayName("站点规则：一模一样才算，别的站一律要问")
    void browseRuleMatchesTheExactHost() {
        ApprovalRules rules = browseAllowing("example.com");

        assertEquals(ApprovalRules.Decision.ALLOW, browse(rules, "example.com"));
        assertEquals(ApprovalRules.Decision.ALLOW, browse(rules, "EXAMPLE.COM"),
                "主机名本来就不分大小写");
        assertEquals(ApprovalRules.Decision.ASK, browse(rules, "mail.example.com"));
        assertEquals(ApprovalRules.Decision.ASK, browse(rules, "notexample.com"),
                "后缀一样不等于同一个站——这条错了就是把一整类域名放进来");
    }

    @Test
    @DisplayName("*.example.com 连主域一起罩——不然用户会在同一个站上被问第二次")
    void browseWildcardCoversTheApexToo() {
        ApprovalRules rules = browseAllowing("*.example.com");

        assertEquals(ApprovalRules.Decision.ALLOW, browse(rules, "mail.example.com"));
        assertEquals(ApprovalRules.Decision.ALLOW, browse(rules, "a.b.example.com"));
        assertEquals(ApprovalRules.Decision.ALLOW, browse(rules, "example.com"),
                "按字面理解会让他在主域名上被再问一次，然后他会去按更松的那一档");
        assertEquals(ApprovalRules.Decision.ASK, browse(rules, "example.com.evil.test"),
                "结尾不是 .example.com，只是含着它");
    }

    @Test
    @DisplayName("站点的 deny 压过 allow——黑名单要悲观，和命令那边一个规矩")
    void browseDenyBeatsAllow() {
        ApprovalRules rules = ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BROWSE, "*.google.com", "")),
                List.of(ApprovalRule.of(ApprovalKind.BROWSE, "mail.google.com", "")));

        assertEquals(ApprovalRules.Decision.DENY, browse(rules, "mail.google.com"));
        assertEquals(ApprovalRules.Decision.ALLOW, browse(rules, "docs.google.com"));
    }

    @Test
    @DisplayName("光一个 * 是「整个互联网」，得被当成放行一切处理掉")
    void bareStarIsMatchEverything() {
        assertTrue(ApprovalRule.of(ApprovalKind.BROWSE, "*", "").matchesEverything());
        assertFalse(ApprovalRule.of(ApprovalKind.BROWSE, "*.example.com", "").matchesEverything());
    }

    @Test
    @DisplayName("file:// 是个正经站点名，记得下来也罩得住——从这条路能读工作区外的磁盘")
    void fileSchemeIsARuleableSite() {
        assertEquals(ApprovalRules.Decision.ALLOW, browse(browseAllowing("file://"), "file://"));
        assertEquals(ApprovalRules.Decision.ASK, browse(browseAllowing("example.com"), "file://"));
    }
}
