package dev.wiles.myagent.plan;

import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 名册的不变量。
 *
 * <p>这些断言看着都很朴素，但它们守的是同一类问题：<b>加一个角色的时候忘了某处</b>。
 * 角色名册是会长的（以后多半还会有 doc-writer、migrator 之类），
 * 而每一处遗漏的症状都很安静——工具名打错就是那个角色少一件工具，
 * 只读标错就是只读模式下真的写了文件。
 */
class AgentRolesTest {

    /** 会改变世界的工具。只读角色一个都不许有。 */
    private static final Set<String> DANGEROUS =
            Set.of("write_file", "create_project", "bash");

    /** 真实注册的内置工具名——不是手抄一份清单，抄的那份迟早和实际对不上。 */
    private static Set<String> realToolNames(Path workspace) {
        Set<String> names = new HashSet<>();
        for (Tool tool : BuiltinTools.createDefault(new Workspace(workspace)).all()) {
            names.add(tool.name());
        }
        return names;
    }

    @Test
    @DisplayName("每个角色写的内置工具名都真的存在——打错一个字母就是少一件工具，而且不报错")
    void everyRoleReferencesRealTools(@TempDir Path workspace) {
        Set<String> real = realToolNames(workspace);

        for (AgentRole role : AgentRoles.all()) {
            for (String name : role.tools()) {
                if (name.startsWith("mcp__")) {
                    // 外挂工具在运行时才存在，这里对不上表是正常的。
                    // 但它必须是通配写法——写死全名等于假设用户的 mcp.json
                    // 里那个 server 一定叫某个名字，而那个 key 是用户自己起的
                    assertTrue(name.endsWith("*"),
                            "角色 " + role.id() + " 把外挂工具写成了全名 " + name
                                    + "：server 那一截是用户在 mcp.json 里自己起的 key，"
                                    + "写死等于假设所有人都叫这个名字");
                    continue;
                }
                assertTrue(real.contains(name),
                        "角色 " + role.id() + " 要的工具 " + name + " 在内置工具里不存在");
            }
        }
    }

    @Test
    @DisplayName("说自己不改东西的角色，手上不能有会改东西的工具")
    void readOnlyRolesCarryNoDangerousTools() {
        for (AgentRole role : AgentRoles.all()) {
            if (role.mutates()) {
                continue;
            }
            for (String name : role.tools()) {
                assertFalse(DANGEROUS.contains(name),
                        "角色 " + role.id() + " 声明 mutates=false，却拿着 " + name
                                + "。只读模式靠这个标志过滤角色，标错等于只读模式下真的动了手");
            }
        }
    }

    @Test
    @DisplayName("反过来也要成立：声明会改东西，就得真有那样的工具")
    void mutatingRolesActuallyHaveThePower() {
        for (AgentRole role : AgentRoles.all()) {
            if (!role.mutates()) {
                continue;
            }
            // 外挂工具一律算危险：远端那头做什么我们无从判断，
            // 它叫 query 也可能在写库——和 McpTool 不覆盖 parallelSafe 是同一条理由
            assertTrue(role.tools().stream()
                            .anyMatch(name -> DANGEROUS.contains(name) || name.startsWith("mcp__")),
                    "角色 " + role.id() + " 声明 mutates=true 却一件危险工具都没有，"
                            + "白白在只读模式下被过滤掉");
        }
    }

    @Test
    @DisplayName("六种类型都有默认角色，而且那个角色认这个类型")
    void everyTypeHasAConsistentDefault() {
        for (TaskType type : TaskType.values()) {
            AgentRole role = AgentRoles.defaultFor(type);
            assertNotNull(role, type + " 没有默认角色");
            assertEquals(type, role.taskType(),
                    type + " 的默认角色是 " + role.id() + "，但它自称是 " + role.taskType());
        }
    }

    @Test
    @DisplayName("每个角色都有自己的一段话，而且两两不同")
    void everyRoleHasItsOwnRules() {
        Set<String> seen = new HashSet<>();
        for (AgentRole role : AgentRoles.all()) {
            String rules = role.rules();
            assertFalse(rules.isBlank(), "角色 " + role.id() + " 的提示词是空的");
            assertTrue(seen.add(rules),
                    "角色 " + role.id() + " 的提示词和别人一模一样——"
                            + "那它就不是一个角色，只是一个标签");
        }
    }

    @Test
    @DisplayName("explorer 和 reviewer 工具完全一样，靠提示词区分——这是角色存在的理由")
    void sameToolsDifferentRules() {
        assertEquals(AgentRoles.EXPLORER.tools(), AgentRoles.REVIEWER.tools());
        assertEquals(AgentRoles.EXPLORER.taskType(), AgentRoles.REVIEWER.taskType());
        assertNotEquals(AgentRoles.EXPLORER.rules(), AgentRoles.REVIEWER.rules(),
                "工具一样、类型一样，只剩提示词能把这两件事分开——"
                        + "它俩要是连提示词都一样，角色这层就白加了");
    }

    @Test
    @DisplayName("验证角色要结论行，而且代码真的会去检查它")
    void verifierDemandsTheVerdictLines() {
        // 那两行的字面量已经不在 verifier.md 里了——改由 TaskPrompt 从常量拼进
        // modes/task.md 的占位符，所以「提示词要的」和「代码判的」必然同源。
        // 这里只钉住标志位：提示词要求了结论行、代码却不检查，等于要求了个寂寞
        assertTrue(AgentRoles.VERIFIER.requiresVerdict());
        assertFalse(AgentRoles.VERIFIER.rules().contains(TaskPrompt.VERDICT_PASS),
                "结论行的措辞该只有一处出处（TaskPrompt），角色正文里再写一遍就会两处对不上");
    }

    @Test
    @DisplayName("只有验证角色收「结论行」——别的角色收的是「完成行」，问的不是一件事")
    void onlyVerifierRequiresAVerdict() {
        List<AgentRole> withVerdict = AgentRoles.all().stream()
                .filter(AgentRole::requiresVerdict).toList();

        assertEquals(List.of(AgentRoles.VERIFIER), withVerdict);
    }

    @Test
    @DisplayName("白名单里的通配只对上 MCP 工具，一个内置工具都不许漏进来")
    void wildcardOnlyReachesMcpTools() {
        AgentRole browser = AgentRoles.BROWSER;

        assertTrue(browser.matches("mcp__chrome__click"));
        assertTrue(browser.matches("mcp__chrome-devtools__take_snapshot"),
                "server 那一截是用户自己起的 key，chrome 开头的都该认");
        assertFalse(browser.matches("bash"));
        assertFalse(browser.matches("write_file"));
        assertFalse(browser.matches("mcp__fs__write_file"),
                "别家 server 的工具不该跟着浏览器角色进来");
        assertFalse(browser.matches(null));
    }

    @Test
    @DisplayName("非 MCP 的条目不许写通配——放开了，一个「b*」就能把 bash 递给只读角色")
    void wildcardIsRefusedForBuiltinTools() {
        IllegalArgumentException boom = assertThrows(IllegalArgumentException.class,
                () -> new AgentRole("sloppy", "手滑", "随便写写",
                        TaskType.FILE_READ, List.of("b*"), 5, false, false));

        assertTrue(boom.getMessage().contains("b*"));
        assertTrue(boom.getMessage().contains("mcp__"), "报错要说清哪种条目才许用 *");
    }

    @Test
    @DisplayName("精确条目还是精确的——通配是加进来的能力，不是把匹配整个放松了")
    void exactEntriesStayExact() {
        assertTrue(AgentRoles.OPERATOR.matches("bash"));
        assertFalse(AgentRoles.OPERATOR.matches("bash_extra"));
        assertFalse(AgentRoles.OPERATOR.matches("mcp__chrome__click"),
                "执行角色不该顺手拿到浏览器——那是另一种姿势，见 BROWSER 的注释");
    }

    @Test
    @DisplayName("只读模式下，会动手的角色一个都不出现在菜单里")
    void readOnlyFiltersMutatingRoles() {
        List<AgentRole> runnable = AgentRoles.runnable(true);

        assertFalse(runnable.isEmpty(), "全过滤光了的话只读模式下压根没法规划");
        assertTrue(runnable.stream().noneMatch(AgentRole::mutates));
        assertTrue(runnable.contains(AgentRoles.EXPLORER));
        assertFalse(runnable.contains(AgentRoles.CODER));
        assertEquals(AgentRoles.all(), AgentRoles.runnable(false), "不是只读就全都能派");
    }

    @Test
    @DisplayName("角色名认不出来时，报错要列出全部角色——这句话是发回给模型的")
    void unknownRoleErrorListsEveryRole() {
        String msg = assertThrows(IllegalArgumentException.class,
                () -> AgentRoles.require("code_monkey")).getMessage();

        assertTrue(msg.contains("code_monkey"), msg);
        for (AgentRole role : AgentRoles.all()) {
            assertTrue(msg.contains(role.id()), "报错里没提 " + role.id() + ": " + msg);
        }
    }

    @Test
    @DisplayName("没说类型也没说角色，报的是参数错，不是 NPE")
    void nullTypeIsAnArgumentError() {
        assertThrows(IllegalArgumentException.class, () -> AgentRoles.defaultFor(null));
    }

    @Test
    @DisplayName("id 是模型要填进 JSON 的东西，得是老实的小写下划线")
    void idsAreModelFriendly() {
        for (AgentRole role : AgentRoles.all()) {
            assertTrue(role.id().matches("[a-z][a-z0-9_]*"),
                    "角色 id " + role.id() + " 不是小写下划线风格，模型很容易写错");
            assertFalse(role.summary().isBlank(),
                    "角色 " + role.id() + " 没有一句话说明，模型选它只能靠猜 id");
        }
    }
}
