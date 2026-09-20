package dev.wiles.myagent.team;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.plan.AgentRole;
import dev.wiles.myagent.plan.AgentRoles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 挑人这一步的翻车方式和规划是同一批（不是 JSON、编了个不存在的名字、给多了给少了），
 * 但<b>处理方式刻意不一样</b>：规划错了要打回重写，挑人错了直接退到默认组合。
 * 这些用例钉的就是这条取舍——任何一条走成「抛异常」，用户看到的都是圆桌开不起来。
 */
class ModelCastPickerTest {

    static final class FakeLlm implements LlmClient {
        String reply = "{\"members\":[\"explorer\",\"reviewer\"],\"reason\":\"先摸清再挑毛病\"}";
        RuntimeException blowUp;
        final List<List<ChatMessage>> received = new ArrayList<>();

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            received.add(List.copyOf(messages));
            if (blowUp != null) {
                throw blowUp;
            }
            return new ChatResponse("x", "fake", List.of(new ChatResponse.Choice(
                    0, ChatMessage.assistant(reply), "stop")), null);
        }

        @Override
        public String modelName() {
            return "fake";
        }

        String systemPrompt() {
            return received.get(0).stream().filter(m -> "system".equals(m.role()))
                    .findFirst().orElseThrow().content();
        }
    }

    private static final List<AgentRole> AVAILABLE = Roundtable.seatable(false);

    private static Cast pick(FakeLlm llm) {
        return new ModelCastPicker(llm).pick("这块代码有没有毛病", AVAILABLE);
    }

    @Test
    @DisplayName("正常一份名单：角色和理由都解出来")
    void parsesMembersAndReason() {
        Cast cast = pick(new FakeLlm());

        assertEquals(List.of("explorer", "reviewer"), cast.ids());
        assertEquals("先摸清再挑毛病", cast.reason());
    }

    @Test
    @DisplayName("菜单是从名册生成的：能坐上桌的角色都在提示词里，坐不上的一个都不在")
    void promptListsExactlyTheSeatableRoles() {
        FakeLlm llm = new FakeLlm();
        new ModelCastPicker(llm).pick("议题", Roundtable.seatable(false));

        String prompt = llm.systemPrompt();
        for (AgentRole role : Roundtable.seatable(false)) {
            assertTrue(prompt.contains(role.id()), "菜单里没有 " + role.id());
        }
        assertFalse(prompt.contains("`coder`"), "coder 坐不上桌，就不该出现在菜单上");
    }

    @Test
    @DisplayName("大小写不敏感，模型写 Explorer 也认")
    void roleNamesAreCaseInsensitive() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "{\"members\":[\"Explorer\",\"REVIEWER\"],\"reason\":\"随便\"}";

        assertEquals(List.of("explorer", "reviewer"), pick(llm).ids());
    }

    @Test
    @DisplayName("编了个不存在的角色：只丢它，对的那几个留着")
    void unknownRolesAreDroppedNotTheWholeList() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "{\"members\":[\"reviewer\",\"code_monkey\",\"verifier\"],\"reason\":\"x\"}";

        assertEquals(List.of("reviewer", "verifier"), pick(llm).ids());
    }

    @Test
    @DisplayName("只挑了一个：补齐到下限——一个人不叫圆桌，那是点名")
    void padsUpToTheMinimum() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "{\"members\":[\"reviewer\"],\"reason\":\"就看毛病\"}";

        Cast cast = pick(llm);

        assertEquals(Roundtable.MIN_MEMBERS, cast.size());
        assertTrue(cast.ids().contains("reviewer"), "模型挑对的那个要留着，不能整份换掉");
    }

    @Test
    @DisplayName("挑太多了：砍到上限——合议读不完几份意见就只能开始概括")
    void clampsToTheMaximum() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "{\"members\":[\"scout\",\"explorer\",\"reviewer\",\"analyst\",\"verifier\"],"
                + "\"reason\":\"人多力量大\"}";

        assertEquals(Roundtable.MAX_MEMBERS, pick(llm).size());
    }

    @Test
    @DisplayName("模型挑了个这次坐不上桌的角色（只读模式），跳过它")
    void rolesOutsideTheMenuAreSkipped() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "{\"members\":[\"explorer\",\"verifier\"],\"reason\":\"跑个测试\"}";

        Cast cast = new ModelCastPicker(llm).pick("议题", Roundtable.seatable(true));

        assertFalse(cast.ids().contains("verifier"), "只读模式下它根本派不出去");
        assertEquals(Roundtable.MIN_MEMBERS, cast.size(), "跳过之后要补齐");
    }

    @Test
    @DisplayName("回的不是 JSON：退到默认组合，别让圆桌开不成")
    void garbageFallsBackInsteadOfThrowing() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "我觉得这个得看情况，你想让谁看？";

        Cast cast = pick(llm);

        assertEquals(Roundtable.MIN_MEMBERS, cast.size());
        assertTrue(cast.reason().contains("默认"), cast.reason());
    }

    @Test
    @DisplayName("调用直接挂了也一样退到默认——用户要的是圆桌，不是完美的挑人")
    void callFailureFallsBack() {
        FakeLlm llm = new FakeLlm();
        llm.blowUp = new LlmException("限流");

        Cast cast = assertDoesNotThrow(() -> pick(llm));

        assertEquals(Roundtable.MIN_MEMBERS, cast.size());
    }

    @Test
    @DisplayName("默认组合按当前能坐的角色取，不写死 id")
    void fallbackRespectsWhoCanBeSeated() {
        FakeLlm llm = new FakeLlm();
        llm.blowUp = new LlmException("挂了");

        Cast cast = new ModelCastPicker(llm).pick("议题", Roundtable.seatable(true));

        assertTrue(Roundtable.seatable(true).containsAll(cast.members()),
                "只读模式下退回一个派不出去的角色，等于把失败从挑人挪到了开会");
    }

    @Test
    @DisplayName("没给理由也不能空着——那行字是用户判断值不值得花这几次调用的依据")
    void missingReasonGetsAPlaceholder() {
        FakeLlm llm = new FakeLlm();
        llm.reply = "{\"members\":[\"explorer\",\"reviewer\"]}";

        assertFalse(pick(llm).reason().isBlank());
    }

    @Test
    @DisplayName("一个角色都派不出去时，老老实实说没人，不硬凑")
    void noAvailableRolesMeansEmptyCast() {
        Cast cast = new ModelCastPicker(new FakeLlm()).pick("议题", List.of());

        assertTrue(cast.isEmpty());
    }
}
