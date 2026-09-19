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

    /** 验证任务必须以这一行收尾，执行器靠它判定成败。 */
    public static final String VERDICT_PASS = "结论: 通过";
    public static final String VERDICT_FAIL = "结论: 不通过";

    private static final PromptAssembler ASSEMBLER = PromptAssembler.createDefault();

    private TaskPrompt() {
    }

    /**
     * 子任务执行体的 system。
     *
     * <p>正文在 {@code prompts/modes/task.md}，这里只负责把这一步的类型和纪律填进去。
     * 搬出去的理由见 {@code PromptRepository}——这段文字会被反复调，
     * 而「调一次要重新编译」等于没人会去调。
     *
     * <p><b>不带记忆索引</b>：{@code TaskTools} 的白名单里没有 {@code recall}，
     * 交代「你没有记忆」纯属噪音，甚至会勾着模型去找一个它调不到的工具。
     */
    public static String system(Task task, Path workspaceRoot) {
        // 不再拼 SystemPrompt.forWorkspace()：那一份里已经含 base.md 和**主循环**的
        // 模式段，再拼一次 assemble(TASK) 会把 base 装两遍，还让模型同时收到
        // 「按用户目标自己决定要不要用工具」和「只做这一步别越界」两条相反的指令。
        // 模式本来就是用来选的，不是用来叠的
        return ASSEMBLER.assemble(PromptMode.TASK, PromptContext.builder()
                .variable("workspace", String.valueOf(workspaceRoot))
                .variable("taskType", typeName(task.type()))
                .variable("taskRules", rules(task.type()))
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

    private static String typeName(TaskType type) {
        return switch (type) {
            case PLANNING -> "规划";
            case FILE_READ -> "读取文件";
            case FILE_WRITE -> "写入文件";
            case COMMAND -> "执行命令";
            case ANALYSIS -> "分析";
            case VERIFICATION -> "验证";
        };
    }

    /**
     * 类型专属的规矩。
     *
     * <p>这些话和 {@link TaskTools} 给的工具菜单是一套说辞的两面：菜单决定它<b>能</b>做什么，
     * 这几句决定它<b>该</b>做什么。两边必须对得上，不然模型会去够一个根本不存在的工具，
     * 白白浪费一轮。
     */
    private static String rules(TaskType type) {
        return switch (type) {
            case PLANNING -> """
                    你只有只读工具。先摸清情况，然后说明这件事该怎么拆成几个更小的步骤。
                    不要动手改任何东西。""";
            case FILE_READ -> """
                    你只有只读工具。把要查的东西查清楚，用事实回答，绝不要猜测文件内容。""";
            case FILE_WRITE -> """
                    修改任何文件之前**必须先读它**，避免覆盖掉不该动的内容。
                    改完说清楚改了哪个文件的哪一处。""";
            case COMMAND -> """
                    你可以执行命令。命令失败时先看清错误信息再调整，不要用同样的参数反复重试。""";
            case ANALYSIS -> """
                    你**没有任何工具**，只能基于上游任务交过来的材料分析，缺什么就直说缺什么，
                    不要编造没见过的内容。""";
            case VERIFICATION -> ("""
                    跑测试或命令来验证，看清楚真实输出，不要因为「改过了」就认为一定通过。

                    **回答的最后一行必须是** `%s` 或 `%s`，前面可以先说过程。
                    这一行是程序判定成败的唯一依据，少了它这一步会被当成失败。""")
                    .formatted(VERDICT_PASS, VERDICT_FAIL);
        };
    }
}
