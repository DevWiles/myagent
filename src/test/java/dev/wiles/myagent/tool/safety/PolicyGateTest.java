package dev.wiles.myagent.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PolicyGateTest {

    @TempDir
    Path tmp;

    private final List<String> asked = new ArrayList<>();

    private PolicyStore store() {
        return new PolicyStore(tmp.resolve("policy.json"));
    }

    /** 一个会记账的「人」：每被问一次就记一笔。 */
    private ApprovalPolicy human(ApprovalVerdict answer) {
        return request -> {
            asked.add(request.subject());
            return answer;
        };
    }

    @Test
    @DisplayName("规则罩得住就不问人——这才是治「被问烦」的那一层")
    void matchedRuleSkipsTheHuman() {
        store().save(ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "mvn test *", "d")), List.of()));
        PolicyGate gate = new PolicyGate(human(ApprovalVerdict.no()), store());

        assertTrue(gate.decide(ApprovalRequest.bash("mvn test -X", "")).approved());
        assertTrue(asked.isEmpty(), "已经有规则了还去问人: " + asked);
    }

    @Test
    @DisplayName("规则里没有就照常问人")
    void unmatchedGoesToTheHuman() {
        PolicyGate gate = new PolicyGate(human(ApprovalVerdict.yes()), store());

        assertTrue(gate.decide(ApprovalRequest.bash("rm -rf build", "")).approved());
        assertEquals(List.of("rm -rf build"), asked);
    }

    @Test
    @DisplayName("撞上 deny 规则直接拒，也不问人，而且要说清是哪条挡的")
    void deniedRuleNeverAsks() {
        store().save(ApprovalRules.of(List.of(),
                List.of(ApprovalRule.of(ApprovalKind.BASH, "git push *", "d"))));
        PolicyGate gate = new PolicyGate(human(ApprovalVerdict.yes()), store());

        ApprovalVerdict verdict = gate.decide(ApprovalRequest.bash("git push origin main", ""));
        assertFalse(verdict.approved());
        assertTrue(asked.isEmpty());
        assertTrue(verdict.note().contains("git push *"), verdict.note());
        assertTrue(verdict.note().contains("policy.json"), "没说清是哪个文件挡的: " + verdict.note());
    }

    @Test
    @DisplayName("人说「以后都同意」，规则要落盘，而且下次不再问")
    void rememberedRulePersistsAndSuppressesTheNextAsk() {
        PolicyGate gate = new PolicyGate(
                human(ApprovalVerdict.yes(List.of("mvn test *"))), store());

        gate.decide(ApprovalRequest.bash("mvn test", ""));
        assertEquals(1, asked.size());

        // 落盘了
        assertEquals("mvn test *", store().load().allow().get(0).match());
        // 而且这一份在内存里也立刻生效
        gate.decide(ApprovalRequest.bash("mvn test -X", ""));
        assertEquals(1, asked.size(), "已经批过的又问了一遍: " + asked);
    }

    @Test
    @DisplayName("存下来的规则带上日期，用户翻文件时才知道是什么时候批的")
    void rememberedRuleCarriesTheDate() {
        new PolicyGate(human(ApprovalVerdict.yes(List.of("ls *"))), store())
                .decide(ApprovalRequest.bash("ls", ""));

        assertFalse(store().load().allow().get(0).added().isBlank());
    }

    @Test
    @DisplayName("拒绝时不记规则，哪怕界面回了几条")
    void rejectionNeverRemembers() {
        PolicyGate gate = new PolicyGate(
                request -> new ApprovalVerdict(false, "", List.of("mvn test *")), store());

        gate.decide(ApprovalRequest.bash("mvn test", ""));

        assertTrue(store().load().isEmpty(), "拒绝了还把规则存下来了");
    }

    @Test
    @DisplayName("手改文件立刻生效——撤销授权就只有这一条路，不能要求重启")
    void reloadsWhenTheFileChangesOnDisk() throws Exception {
        store().save(ApprovalRules.of(
                List.of(ApprovalRule.of(ApprovalKind.BASH, "mvn test *", "d")), List.of()));
        PolicyGate gate = new PolicyGate(human(ApprovalVerdict.no()), store());
        gate.decide(ApprovalRequest.bash("mvn test", ""));
        assertTrue(asked.isEmpty());

        // 用户打开文件把那条删了
        touchWithNewMtime("{\"version\":1,\"allow\":[]}");

        gate.decide(ApprovalRequest.bash("mvn test", ""));
        assertEquals(List.of("mvn test"), asked, "改完文件还在按老规则放行");
    }

    @Test
    @DisplayName("反过来也一样：手工加一条，下一次就不问了")
    void handEditedRuleTakesEffect() throws Exception {
        PolicyGate gate = new PolicyGate(human(ApprovalVerdict.no()), store());
        gate.decide(ApprovalRequest.bash("mvn test", ""));
        assertEquals(1, asked.size());

        touchWithNewMtime("{\"version\":1,\"allow\":[{\"tool\":\"bash\",\"match\":\"mvn test *\"}]}");

        gate.decide(ApprovalRequest.bash("mvn test", ""));
        assertEquals(1, asked.size(), "手加的规则没生效");
    }

    @Test
    @DisplayName("存盘失败也要让这一次的授权在内存里生效——用户已经表过态了")
    void inMemoryRulesSurviveAFailedSave() {
        // 目录被占成文件，写不进去
        PolicyStore broken = new PolicyStore(tmp.resolve("nope").resolve("x").resolve("policy.json"));
        try {
            Files.writeString(tmp.resolve("nope"), "我是个文件，不是目录");
        } catch (IOException e) {
            fail(e);
        }
        PolicyGate gate = new PolicyGate(human(ApprovalVerdict.yes(List.of("ls *"))), broken);

        gate.decide(ApprovalRequest.bash("ls", ""));
        gate.decide(ApprovalRequest.bash("ls -la", ""));

        assertEquals(1, asked.size(), "存盘失败不该让用户在同一个会话里被问第二遍");
    }

    @Test
    @DisplayName("界面回来的规则不合法就跳过，但这一次照样放行")
    void illegalRememberedRuleIsSkipped() {
        PolicyGate gate = new PolicyGate(
                human(ApprovalVerdict.yes(List.of("   "))), store());

        assertTrue(gate.decide(ApprovalRequest.bash("ls", "")).approved());
        assertTrue(store().load().isEmpty());
    }

    private void touchWithNewMtime(String json) throws Exception {
        Path file = tmp.resolve("policy.json");
        Files.writeString(file, json);
        // 文件系统的时间戳粒度可能是秒级，同一秒内改两次会看不出变化
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
    }
}
