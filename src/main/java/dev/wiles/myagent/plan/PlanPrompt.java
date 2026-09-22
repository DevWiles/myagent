package dev.wiles.myagent.plan;

import dev.wiles.myagent.prompt.PromptAssembler;
import dev.wiles.myagent.prompt.PromptContext;
import dev.wiles.myagent.prompt.PromptMode;
import dev.wiles.myagent.prompt.PromptRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 规划阶段的提示词。
 *
 * <p>数量上限、层级上限这些数字全部从 {@link PlanLimits} 插值进来，
 * 绝不在这里手写——提示词说「最多 10 个」而校验按 12 放行（或者反过来）这种不同步，
 * 症状是「模型明明照做了却被程序打回」，很难联想到是两处常量对不上。
 *
 * <h3>示例必须自洽于规则</h3>
 * 这里的 JSON 范例<b>本身就得是一份合格的计划</b>：任务数落在 {@link PlanLimits} 的区间里，
 * 有真正并行的一层，有占位符，每条都带验收标准。理由是模型对示例的模仿远强于对文字规则的服从——
 * 曾经这里给的是一个两任务的骨架，而规则要求至少 {@value PlanLimits#MIN_TASKS} 个，
 * 于是每次规划都要先被打回一轮，白烧一次调用。<b>示例和规则打架时，输的总是规则。</b>
 *
 * <h3>现场信息放 system、目标放 user</h3>
 * 工具清单和目录树对同一个工作区是稳定的，跟在系统提示词里能吃到服务端的前缀缓存；
 * 目标每次都不一样，放 user 才不会把前缀打断。
 */
public final class PlanPrompt {

    private static final PromptRepository REPO = PromptRepository.createDefault();
    private static final PromptAssembler ASSEMBLER = new PromptAssembler(REPO);

    private PlanPrompt() {
    }

    public static String system() {
        return system(false, PlanContext.none());
    }

    public static String system(boolean readOnly) {
        return system(readOnly, PlanContext.none());
    }

    /**
     * @param readOnly 当前是只读模式（{@code --readonly}）。必须告诉模型，
     *                 否则它会规划出一堆改文件、跑命令的任务，跑到那儿才发现没工具可用——
     *                 这是第一次真机跑 /plan 时实际踩到的。
     * @param context  现场信息：仓库长什么样、有哪些工具、哪些命令绝对不许用。
     *                 缺了它模型只能靠编和靠探路任务凑，见 {@link PlanContext}。
     */
    public static String system(boolean readOnly, PlanContext context) {
        return ASSEMBLER.assemble(PromptMode.PLANNER, PromptContext.builder()
                .variable("minTasks", String.valueOf(PlanLimits.MIN_TASKS))
                .variable("maxTasks", String.valueOf(PlanLimits.MAX_TASKS))
                .variable("roles", roleMenu(staffed(AgentRoles.runnable(readOnly), context)))
                .build())
                + readOnlyRule(readOnly) + forbiddenRule(context) + situation(context);
    }

    /**
     * 角色菜单：这份提示词里唯一由代码生成的部分。
     *
     * <p>不手写在 md 里，理由和 {@link PlanLimits} 那几个数字一样——
     * <b>名册多一个角色而提示词忘了改，症状是模型永远不会派它</b>，
     * 而那个角色在代码里、在测试里、在界面上都好端端的，
     * 只有对着真实规划输出一条条数才看得出来。生成的东西不会忘。
     *
     * <p>连工具一起列出来：模型选角色的依据是「这一步要干的活」，
     * 而「这个角色够不够得着那件活」最硬的证据就是它手上有什么。
     */
    private static String roleMenu(List<AgentRole> roles) {
        return roles.stream()
                .map(role -> "- `" + role.id() + "`（" + role.title() + "）"
                        + role.summary() + "。工具: "
                        + (role.tools().isEmpty() ? "一个都没有" : String.join(" ", role.tools())))
                .collect(Collectors.joining("\n"));
    }

    /** 第二层和补救都不许再摸底，所以菜单里拿掉规划角色。 */
    private static List<AgentRole> nonPlanningRoles(boolean readOnly, PlanContext context) {
        return staffed(AgentRoles.runnable(readOnly), context).stream()
                .filter(role -> role.taskType() != TaskType.PLANNING)
                .toList();
    }

    /**
     * 剔掉「要工具却一个也没有」的角色。
     *
     * <h3>为什么这一步非做不可</h3>
     * 角色的白名单是<b>写死的名册</b>，而工具是<b>现场注册的</b>——两边对不上时，
     * 规划器照样会把这个角色派出去，执行器却给它一份空菜单。实测过一次代价：
     * 计划写得完美（七步全用 {@code mcp__chrome__*}），执行时每一步都回
     * 「我的工具集里没有这个工具」，而计划收在 {@code COMPLETED ok=7 fail=0}。
     * 界面全绿，一步没做。
     *
     * <p>判据是「要了却没兑现」：{@code analyst} 本来就一个工具都不要
     * （它该基于上游材料想），那不是没兑现，不能剔。
     *
     * <p>{@link PlanContext#none()} 这种没带现场信息的调用<b>一个都不剔</b>：
     * 不知道注册了什么，就不该替调用方下判断——那会把「没告诉我」当成「没有」。
     */
    private static List<AgentRole> staffed(List<AgentRole> roles, PlanContext context) {
        if (context == null || !context.hasTools()) {
            return roles;
        }
        List<String> available = context.tools().stream().map(PlanContext.ToolBrief::name).toList();
        return roles.stream()
                .filter(role -> role.tools().isEmpty()
                        || available.stream().anyMatch(role::matches))
                .toList();
    }

    /** 只读模式下，一半的角色根本派不出去，规划时就得知道。 */
    private static String readOnlyRule(boolean readOnly) {
        return readOnly
                ? "\n" + REPO.loadRequired("policy/plan-readonly.md")
                        .replace("{{@readOnlyRoles}}",
                                String.join(" / ", AgentRoles.runnableIds(true)))
                        + "\n"
                : "";
    }

    /**
     * 把「怎么都不会放行的命令」前置到规划阶段。
     *
     * <p>不写这条的代价是具体的：模型规划出一个 {@code sudo apt install} 任务，
     * 跑到那儿被 {@code CommandGuard} 拦死，这一步失败，下游全被连累成 SKIPPED。
     * 一次白跑的规划加一条废掉的依赖链，只为了省这几十个 token。
     */
    private static String forbiddenRule(PlanContext context) {
        return context.hasForbidden()
                ? "\n" + REPO.loadRequired("policy/plan-forbidden.md")
                        .replace("{{@forbiddenCommands}}", String.join(" ", context.forbiddenCommands()))
                        + "\n"
                : "";
    }

    /** 现场信息。没有就整段不出现——空标题比没标题更让模型困惑。 */
    private static String situation(PlanContext context) {
        StringBuilder sb = new StringBuilder();
        if (context.workspaceRoot() != null) {
            sb.append("\n当前工作区: ").append(context.workspaceRoot()).append('\n');
        }
        if (context.hasTools()) {
            sb.append("\n执行器手上有这些工具（只有这些，别规划用不到的能力）:\n")
                    .append(context.tools().stream()
                            .map(t -> "- " + t.name() + ": " + t.summary())
                            .collect(Collectors.joining("\n")))
                    .append('\n');
        }
        if (context.hasTree()) {
            sb.append("""

                    工作区的结构（只列了前几层，看不到的地方安排 FILE_READ 去查，别猜）:

                    ```
                    %s
                    ```
                    """.formatted(context.tree()));
        }
        return sb.toString();
    }

    public static String user(String goal) {
        return "目标: " + goal;
    }

    /** 子规划的系统提示词：同一套格式，但不要 goal、不许再派 scout。 */
    public static String subPlanSystem() {
        return subPlanSystem(false);
    }

    public static String subPlanSystem(boolean readOnly) {
        return subPlanSystem(readOnly, PlanContext.none());
    }

    public static String subPlanSystem(boolean readOnly, PlanContext context) {
        return ASSEMBLER.assemble(PromptMode.PLANNER_SUB, PromptContext.builder()
                .variable("maxSubTasks", String.valueOf(PlanLimits.MAX_SUB_TASKS))
                .variable("roles", roleMenu(nonPlanningRoles(readOnly, context)))
                .build())
                + readOnlyRule(readOnly);
    }

    // ── 重规划 ──────────────────────────────────────────────────

    /**
     * 计划跑到一半出岔子时的补救提示词。
     *
     * <p><b>为什么重规划是「续」而不是「重来」</b>：失败之前那些成功的步骤是真金白银换来的，
     * 推倒重来等于把它们全部作废，再花一遍钱买同样的东西。所以这里给模型的姿势是
     * 「在已有成果之上接着走」——原计划的产出全部摊给它看，只让它补接下来该做的几步。
     *
     * <p><b>为什么允许它认输</b>：缺权限、缺依赖、需求本身矛盾这类失败，
     * 换几个步骤是绕不过去的。不给认输的出口，模型就会硬凑一批注定跑不通的任务，
     * 白烧一轮调用，还让用户以为系统在努力。明确告诉它可以交白卷，
     * 这时候把「跑到哪儿、为什么停」如实报给用户，比假装还有救有用得多。
     */
    public static String replanSystem() {
        return replanSystem(false);
    }

    public static String replanSystem(boolean readOnly) {
        return replanSystem(readOnly, PlanContext.none());
    }

    public static String replanSystem(boolean readOnly, PlanContext context) {
        return ASSEMBLER.assemble(PromptMode.PLANNER_REPLAN, PromptContext.builder()
                .variable("maxReplanTasks", String.valueOf(PlanLimits.MAX_REPLAN_TASKS))
                .variable("roles", roleMenu(nonPlanningRoles(readOnly, context)))
                .build())
                + readOnlyRule(readOnly);
    }

    /**
     * 把现场原样摊给模型：原目标、做成了什么、哪一步倒了、还有哪些活没干。
     *
     * <p>四样缺一不可。缺了「做成了什么」它会重做；缺了「为什么倒的」它会原样再试一遍；
     * 缺了「还有哪些活没干」它只会修那一步，忘掉被连累跳过的那一串。
     */
    public static String replanUser(Plan plan, Task failed, String error) {
        StringBuilder sb = new StringBuilder("原目标: ").append(plan.goal()).append('\n');

        List<Task> succeeded = plan.graph().tasks().stream()
                .filter(t -> t.status() == TaskStatus.SUCCEEDED)
                .toList();
        if (succeeded.isEmpty()) {
            sb.append("\n还没有任何一步跑成功。\n");
        } else {
            sb.append("\n已经做成的步骤（别重做，产出可以直接用）:\n");
            for (Task task : succeeded) {
                sb.append("\n【").append(task.id()).append(' ').append(task.description())
                        .append("】\n")
                        .append(task.result() == null ? "（没有留下产出）" : task.result())
                        .append('\n');
            }
        }

        sb.append("\n失败的步骤: ").append(failed.id()).append(' ')
                .append(failed.description()).append('\n')
                .append("失败原因: ").append(error == null ? "（没有留下原因）" : error).append('\n');

        List<Task> skipped = plan.graph().tasks().stream()
                .filter(t -> t.status() == TaskStatus.SKIPPED)
                .toList();
        if (!skipped.isEmpty()) {
            sb.append("\n被连累跳过、还没干的活:\n");
            for (Task task : skipped) {
                sb.append("- ").append(task.id()).append(' ').append(task.description())
                        .append(task.acceptance() == null ? "" : "（要求: " + task.acceptance() + "）")
                        .append('\n');
            }
        }

        sb.append("\n给出接下来该做的几步。做不成就输出 {\"tasks\": []}。");
        return sb.toString();
    }

    public static String subPlanUser(Task parent, String findings) {
        return """
                你负责的这一步是: %s

                你刚才摸到的情况:
                %s

                把接下来要做的事拆成具体步骤。""".formatted(parent.description(), findings);
    }
}
