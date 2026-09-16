package dev.wiles.myagent.plan;

import dev.wiles.myagent.agent.SystemPrompt;

import java.nio.file.Path;
import java.util.List;

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

    private TaskPrompt() {
    }

    public static String system(Task task, Path workspaceRoot) {
        return SystemPrompt.forWorkspace(workspaceRoot) + """

                ---

                当前你在执行一个更大计划中的**一个子任务**，任务类型是「%s」。

                %s

                这一步的纪律:
                - 只做这个子任务要求的事。计划里别的活有别的步骤负责，**不要顺手做掉**——\
                你以为的顺手，在别人那儿是重复劳动或者冲突。
                - 做完用几句话交代产出。下游任务只能看到你这段交代，看不到你的执行过程，\
                所以关键结论（读到了什么、改了哪个文件、命令输出说明了什么）必须写进去。
                - 交代要短。它会被原样塞进下游任务的上下文，写成长篇大论就是在烧后面每一步的额度。
                """.formatted(typeName(task.type()), rules(task.type()));
    }

    public static String user(Task task, List<Task> upstream) {
        StringBuilder sb = new StringBuilder("任务: ").append(task.description());

        List<Task> withResult = upstream.stream().filter(t -> t.result() != null).toList();
        if (!withResult.isEmpty()) {
            sb.append("\n\n上游任务的产出:\n");
            for (Task up : withResult) {
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
