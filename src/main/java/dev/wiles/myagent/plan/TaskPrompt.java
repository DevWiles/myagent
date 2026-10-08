package dev.wiles.myagent.plan;

import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.prompt.PromptAssembler;
import dev.wiles.myagent.prompt.PromptContext;
import dev.wiles.myagent.prompt.PromptMode;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 子任务的提示词。
 *
 * <p>和 {@link SystemPrompt} 一样，这里的文字和代码是平级的：
 * 模型会不会越界干别的任务的活、会不会把执行过程原样吐出来淹掉下游，
 * 全看这几段话写得清不清楚。
 *
 * <p>拆成纯函数是为了能单测——不用发一次模型调用，就能断言上游产出确实拼进去了。
 */
public final class TaskPrompt {

    /**
     * 验证角色必须以这一行收尾，{@link AgentTaskRunner} 靠它判定成败。
     *
     * <p>要求这一行的那段话由 {@link #contractFor} <b>从这两个常量拼出来</b>，
     * 填进 {@code prompts/modes/task.md} 的占位符。原先写在
     * {@code prompts/roles/verifier.md} 里当字面量，代价是「代码判的」和
     * 「提示词要的」在两个文件里，改一处忘另一处没有任何症状——
     * 现在同一个常量出两份，对不上这件事在语法上就不成立。
     */
    public static final String VERDICT_PASS = "结论: 通过";
    public static final String VERDICT_FAIL = "结论: 不通过";

    /**
     * 别的角色必须以这一行收尾，回答的是<b>另一个问题</b>。
     *
     * <h3>为什么不和结论行共用一句话</h3>
     * 两句话问的不是一件事：{@code 结论:} 问「被验的东西过了吗」，
     * {@code 完成:} 问「你这一步做到了吗」。同一个验证任务完全可能
     * 顺利跑完（完成了）却报告测试挂了（不通过）——合成一句就只能和稀泥，
     * 而和稀泥的代价在 {@code explorer}/{@code reviewer} 那儿已经付过一次了。
     *
     * <p>加这一行是因为实测到一种最坏的失败：子任务如实说「我的工具集里没有
     * 这个工具，这步没做成」，而程序没有任何地方读这句话，七步全判成功，
     * 计划收在 {@code COMPLETED ok=7 fail=0}。<b>界面全绿，一步没做。</b>
     */
    public static final String DONE_YES = "完成: 是";
    public static final String DONE_NO = "完成: 否";

    private static final PromptAssembler ASSEMBLER = PromptAssembler.createDefault();

    private TaskPrompt() {
    }

    /**
     * 子任务执行体的 system。
     *
     * <p>正文在 {@code prompts/modes/task.md}，这里只负责把这一步的角色和纪律填进去。
     * 搬出去的理由见 {@code PromptRepository}——这段文字会被反复调，
     * 而「调一次要重新编译」等于没人会去调。
     *
     * <p><b>纪律那几句来自角色，不再是这里的一个 switch</b>（见 {@link AgentRole#rules()}）。
     * 写成 switch 的时候，「同一种工具、两种干法」根本表达不出来——
     * {@code explorer} 和 {@code reviewer} 会被迫共用一段和稀泥的话。
     *
     * <p><b>不带记忆索引</b>：角色的工具白名单里没有 {@code recall}，
     * 交代「你没有记忆」纯属噪音，甚至会勾着模型去找一个它调不到的工具。
     */
    public static String system(Task task, Path workspaceRoot) {
        // 不再拼 SystemPrompt.forWorkspace()：那一份里已经含 base.md 和**主循环**的
        // 模式段，再拼一次 assemble(TASK) 会把 base 装两遍，还让模型同时收到
        // 「按用户目标自己决定要不要用工具」和「只做这一步别越界」两条相反的指令。
        // 模式本来就是用来选的，不是用来叠的
        AgentRole role = task.role();
        return ASSEMBLER.assemble(PromptMode.TASK, PromptContext.builder()
                .variable("workspace", String.valueOf(workspaceRoot))
                .variable("role", role.title())
                .variable("taskRules", role.rules())
                .variable("completionContract", contractFor(role))
                .build());
    }

    /**
     * 收尾那一行的要求。由代码按角色生成，不写进各自的 md。
     *
     * <p>理由和 {@code PlanPrompt#roleMenu} 一样：<b>代码判的和提示词要的，
     * 必须同一个地方出</b>。分开写的症状是某个角色的 md 忘了加这句，
     * 于是它永远收不到结论行——而代码那边照判，这个角色就莫名其妙地全成功
     * 或者全失败，从日志里完全看不出来。
     */
    private static String contractFor(AgentRole role) {
        if (role == AgentRoles.ACCEPTOR) {
            // 验收席的结论是三选一，由 Pipeline.verdictOf 判；措辞必须和那边指着同一组词
            return "**回答的最后一行只写下面三个词之一，别带别的字**——这一行是程序读的：\n"
                    + "- 通过——没有必须修的问题\n"
                    + "- 重新执行——方案没错，是施工没做对或没做完，按原方案重做能好\n"
                    + "- 重新规划——方案本身有问题（方向拆错、漏了目标里的要求），要重拟";
        }
        if (role.requiresVerdict()) {
            return "**回答的最后一行必须是** `" + VERDICT_PASS + "` 或 `" + VERDICT_FAIL
                    + "`，前面可以先说过程。\n"
                    + "这一行是程序判定成败的唯一依据，少了它这一步会被当成失败。";
        }
        return "**回答的最后一行必须是** `" + DONE_YES + "` 或 `" + DONE_NO + "`。\n"
                + "它回答的只有一件事：**这一步要求的事你做到了没有**。"
                + "做不到就写 `" + DONE_NO + "` 并说清卡在哪（缺工具、缺权限、上游没给够材料），"
                + "这比写一段漂亮的交代有用得多——程序靠这一行决定后面的步骤还要不要跑。"
                + "不要用它评价东西好不好，那是验证角色的事。";
    }

    /**
     * 这一步要交给子模型的全部材料：任务 + 完成标准 + 上游产出。
     *
     * <p>{@code {{t1}}} 这类占位符在这里被换成上游真正的产出（见 {@link TaskRefs}）——
     * <b>规划阶段写下的是「填谁的产出」，执行阶段才知道那产出具体是什么</b>。
     *
     * <p>被占位符引用过的上游<b>不再进下面的产出区块</b>：它的内容已经内联到句子里了，
     * 再抄一遍就是把同一段文字塞进上下文两次，纯烧 token。
     */
    public static String user(Task task, List<Task> upstream) {
        return user(task, upstream, TaskBrief.NONE);
    }

    /**
     * 带交底的版本：用户原话和需求清单排在最前面，见 {@link TaskBrief}。
     *
     * <p>排在前面而不是后面：任务描述是对原话的转述，先读原话再读转述，
     * 转述漏掉的东西才有机会被注意到；反过来，读完一段具体指令再看到一大段背景，
     * 模型多半已经按指令定好了做法。
     */
    public static String user(Task task, List<Task> upstream, TaskBrief brief) {
        List<Task> sources = upstream == null ? List.of() : upstream;
        String acceptance = Objects.toString(task.acceptance(), "");
        Set<String> inlined = new LinkedHashSet<>(
                TaskRefs.referencedIn(task.description() + "\n" + acceptance));

        StringBuilder sb = new StringBuilder(brief == null ? "" : brief.render())
                .append("你这一步的任务: ")
                .append(TaskRefs.resolve(task.description(), sources));

        if (!acceptance.isEmpty()) {
            sb.append("\n\n做到这样就算完成: ").append(TaskRefs.resolve(acceptance, sources));
        }

        List<Task> rest = sources.stream()
                .filter(t -> t.result() != null)
                .filter(t -> !inlined.contains(t.id()))
                .toList();
        if (!rest.isEmpty()) {
            sb.append("\n\n上游任务的产出:\n");
            for (Task up : rest) {
                sb.append("\n【").append(up.id()).append(" ").append(up.description()).append("】\n")
                        .append(up.result()).append('\n');
            }
        }
        return sb.toString();
    }
}
