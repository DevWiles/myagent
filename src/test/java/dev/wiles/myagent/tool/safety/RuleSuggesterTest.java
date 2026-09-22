package dev.wiles.myagent.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleSuggesterTest {

    private static List<String> labels(ApprovalRequest request) {
        return RuleSuggester.forRequest(request).levels().stream()
                .map(RuleSuggester.Level::label).toList();
    }

    private static String preferred(ApprovalRequest request) {
        RuleSuggester.Suggestions s = RuleSuggester.forRequest(request);
        return s.levels().get(s.preferred()).label();
    }

    @Test
    @DisplayName("命令给三档：一字不差 / 到子命令为止 / 只认命令名")
    void commandHasThreeTightnessLevels() {
        assertEquals(List.of("mvn -o test -Dtest=Foo", "mvn -o test *", "mvn *"),
                labels(ApprovalRequest.bash("mvn -o test -Dtest=Foo", "")));
    }

    @Test
    @DisplayName("默认选中「到子命令为止」——够用，又不至于把整个命令放开")
    void prefersTheSubcommandLevel() {
        assertEquals("mvn -o test *", preferred(ApprovalRequest.bash("mvn -o test -Dtest=Foo", "")));
        assertEquals("npm run *", preferred(ApprovalRequest.bash("npm run build", "")));
    }

    @Test
    @DisplayName("第二个 token 是 URL 或路径时不给「子命令」那一档，默认退回最紧的")
    void urlIsNotASubcommand() {
        // curl https://x.com 的第二个 token 是个 URL。按子命令处理会推出
        // `curl https://x.com *`——比精确匹配松，又没松到有用，还可能被默认选走
        assertEquals(List.of("curl https://x.com", "curl *"),
                labels(ApprovalRequest.bash("curl https://x.com", "")));
        assertEquals("curl https://x.com", preferred(ApprovalRequest.bash("curl https://x.com", "")));
    }

    @Test
    @DisplayName("没有子命令的命令只给两档，不能重复")
    void singleTokenCommandCollapses() {
        assertEquals(List.of("ls", "ls *"), labels(ApprovalRequest.bash("ls", "")));
    }

    @Test
    @DisplayName("多段命令推出多条规则——少推一条，用户下次还得再批一遍")
    void compoundCommandSuggestsOneRulePerSegment() {
        RuleSuggester.Suggestions s = RuleSuggester.forRequest(
                ApprovalRequest.bash("mvn test && git status", ""));

        RuleSuggester.Level level = s.levels().get(s.preferred());
        assertEquals(List.of("mvn test *", "git status *"), level.patterns());
        assertTrue(level.label().contains("mvn test *"), level.label());
        assertTrue(level.label().contains("git status *"), level.label());
    }

    @Test
    @DisplayName("推出来的规则必须真能罩住这次的动作——否则用户按了同意却没生效")
    void suggestedRulesActuallyCoverTheRequest() {
        // 这条是推导和匹配之间的闭环。两边任何一侧改了语义，这里都会红
        for (String command : new String[]{
                "mvn -o test -Dtest=Foo", "npm run build", "git status",
                "ls", "curl https://x.com", "mvn test && git status"}) {
            ApprovalRequest request = ApprovalRequest.bash(command, "");
            for (RuleSuggester.Level level : RuleSuggester.forRequest(request).levels()) {
                ApprovalRules rules = ApprovalRules.of(
                        level.patterns().stream()
                                .map(p -> ApprovalRule.of(ApprovalKind.BASH, p, "d")).toList(),
                        List.of());
                assertEquals(ApprovalRules.Decision.ALLOW, rules.check(request).decision(),
                        () -> "规则 " + level.label() + " 罩不住它自己推导的来源: " + command);
            }
        }
    }

    @Test
    @DisplayName("路径从精确一路放宽到顶层目录")
    void pathWidensTowardsTheTop() {
        List<String> got = labels(ApprovalRequest.write(
                "写入文件", "src/main/java/dev/wiles/Foo.java", "", ""));

        assertEquals("src/main/java/dev/wiles/Foo.java", got.get(0));
        assertEquals("src/main/java/dev/wiles/*", got.get(1));
        assertTrue(got.contains("src/**"), got.toString());
        assertTrue(got.size() <= RuleSuggester.MAX_LEVELS, got.toString());
    }

    @Test
    @DisplayName("路径默认选「同一层目录」")
    void pathPrefersSameDirectory() {
        assertEquals("src/main/*",
                preferred(ApprovalRequest.write("写入文件", "src/main/A.java", "", "")));
    }

    @Test
    @DisplayName("根目录下的文件只能精确授权——否则那一档就是「所有文件」")
    void rootLevelFileOnlyGetsExact() {
        assertEquals(List.of("README.md"),
                labels(ApprovalRequest.write("写入文件", "README.md", "", "")));
    }

    @Test
    @DisplayName("推出来的路径规则也得真能罩住来源")
    void suggestedPathRulesCoverTheRequest() {
        ApprovalRequest request = ApprovalRequest.write(
                "写入文件", "src/main/java/dev/wiles/Foo.java", "", "");
        for (RuleSuggester.Level level : RuleSuggester.forRequest(request).levels()) {
            ApprovalRules rules = ApprovalRules.of(
                    List.of(ApprovalRule.of(ApprovalKind.WRITE, level.patterns().get(0), "d")),
                    List.of());
            assertEquals(ApprovalRules.Decision.ALLOW, rules.check(request).decision(),
                    () -> "规则 " + level.label() + " 罩不住 " + request.subject());
        }
    }

    @Test
    @DisplayName("没有 subject 就没有规则可推，界面据此不显示「以后都同意」")
    void blankSubjectSuggestsNothing() {
        assertTrue(RuleSuggester.forRequest(ApprovalRequest.bash("", "")).isEmpty());
        assertTrue(RuleSuggester.forRequest(null).isEmpty());
    }

    @Test
    @DisplayName("任何一档都不许是「放行一切」")
    void neverSuggestsAllowEverything() {
        for (String subject : new String[]{"a.txt", "x/y.txt", "deep/a/b/c.txt"}) {
            for (RuleSuggester.Level level : RuleSuggester
                    .forRequest(ApprovalRequest.write("写入文件", subject, "", "")).levels()) {
                for (String pattern : level.patterns()) {
                    assertFalse(new ApprovalRule(ApprovalKind.WRITE, pattern, "").matchesEverything(),
                            "推出了一条放行一切的规则: " + pattern);
                }
            }
        }
    }

    @Test
    @DisplayName("会毁东西的命令只给「一字不差」——结尾的 * 对它们意味着又一个删除目标")
    void destructiveCommandsGetExactOnly() {
        // 实测出来的：默认推荐 `rm -rf target *`，而它会静默放行 `rm -rf target src`。
        // 用户点头时看到的只有 target。mvn test -X 多出来的是选项，rm 多出来的是目标，
        // 同一个通配在两处的含义完全不同
        assertEquals(List.of("rm -rf target"), labels(ApprovalRequest.bash("rm -rf target", "")));
        assertEquals(List.of("mv a b"), labels(ApprovalRequest.bash("mv a b", "")));
        assertEquals(List.of("chmod 755 x"), labels(ApprovalRequest.bash("chmod 755 x", "")));
    }

    @Test
    @DisplayName("一整条里只要有一段是破坏性的，整条都只给精确档")
    void anyDestructiveSegmentTightensTheWholeCommand() {
        assertEquals(List.of("mvn test  ＋  rm -rf target"),
                labels(ApprovalRequest.bash("mvn test && rm -rf target", "")));
    }

    @Test
    @DisplayName("精确档存下来之后，多加一个删除目标要重新问")
    void exactRuleDoesNotCoverAnExtraTarget() {
        ApprovalRequest request = ApprovalRequest.bash("rm -rf target", "");
        String pattern = RuleSuggester.forRequest(request).levels().get(0).patterns().get(0);
        ApprovalRules rules = ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, pattern, "d")), List.of());

        assertEquals(ApprovalRules.Decision.ALLOW, rules.check(request).decision());
        assertEquals(ApprovalRules.Decision.ASK,
                rules.check(ApprovalRequest.bash("rm -rf target src", "")).decision());
    }

    private static List<List<String>> browsePatterns(String host) {
        return RuleSuggester.forRequest(new ApprovalRequest(
                        ApprovalKind.BROWSE, "浏览", host, "", "", null))
                .levels().stream().map(RuleSuggester.Level::patterns).toList();
    }

    @Test
    @DisplayName("站点给两档：就这个主机名、连子域")
    void browseSuggestsTheHostAndItsParent() {
        assertEquals(List.of(List.of("mail.google.com"), List.of("*.google.com")),
                browsePatterns("mail.google.com"));
    }

    @Test
    @DisplayName("没有比「连子域」更松的一档——再往上是 *.com，那不是放宽是整个互联网")
    void browseNeverSuggestsATopLevelDomain() {
        assertEquals(List.of(List.of("example.com")), browsePatterns("example.com"),
                "example.com 的父域是 com，那一档不该存在");
    }

    @Test
    @DisplayName("IP 和单段主机名不脱层——192.168.1.7 的「父域」是 168.1.7，纯属胡说")
    void addressesAndSingleLabelHostsGetOneLevelOnly() {
        for (String host : List.of("127.0.0.1", "192.168.1.7", "localhost")) {
            assertEquals(List.of(List.of(host)), browsePatterns(host), host + " 不该被脱掉一层");
        }
    }
}
