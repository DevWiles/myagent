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
     * <p>要求这一行的那段话在 {@code prompts/roles/verifier.md} 里<b>写成字面量</b>，
     * 而不是从这两个常量插值进去：角色正文是通过变量填进模板的，
     * 而装配器的占位符替换只扫一遍模板（见 {@code PromptAssembler}），
     * 填进去的内容里再有 <code>{{@…}}</code> 不会被替换，也不会报错——
     * 模型收到的就是字面的占位符。两处对不上的风险改由单测钉住。
     */
    public static final String VERDICT_PASS = "结论: 通过";
    public static final String VERDICT_FAIL = "结论: 不通过";

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
                .build());
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
        List<Task> sources = upstream == null ? List.of() : upstream;
        String acceptance = Objects.toString(task.acceptance(), "");
        Set<String> inlined = new LinkedHashSet<>(
                TaskRefs.referencedIn(task.description() + "\n" + acceptance));

        StringBuilder sb = new StringBuilder("任务: ")
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
