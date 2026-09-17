package dev.wiles.myagent.cli;

import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.PlanResult;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskStatus;
import dev.wiles.myagent.plan.TaskType;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一份计划画到终端上。
 *
 * <p>计划面板是这个特性里<b>唯一要用户真正读一遍</b>的东西：模型理解对了吗、
 * 拆得合理吗、哪些步骤其实可以并行——用户看完才决定要不要放它跑。
 * 所以这里的取舍一律偏向「一眼能看懂」：按层缩进、标出依赖、类型用中文短标签。
 *
 * <p>纯函数、不打印，是为了能单测——终端渲染最容易在窄屏和长文本上出问题，
 * 那两种情况用断言比肉眼盯可靠。
 */
public final class PlanView {

    /** 状态符号。宽度都是 1，对齐才不会乱。 */
    private static final String PENDING = "○";
    private static final String OK = "✓";
    private static final String FAILED = "✗";
    private static final String SKIPPED = "⊘";

    private PlanView() {
    }

    /** 类型的中文短标签。定长两字，任务行才能对齐。 */
    private static String label(TaskType type) {
        return switch (type) {
            case PLANNING -> "规划";
            case FILE_READ -> "读取";
            case FILE_WRITE -> "写入";
            case COMMAND -> "命令";
            case ANALYSIS -> "分析";
            case VERIFICATION -> "验证";
        };
    }

    /**
     * 计划面板：目标 + 按拓扑层排开的任务。
     *
     * <p>按层而不是按顺序列，是为了让「第 2 层这三个任务互不依赖」这件事看得见。
     * 现在虽然还是串行执行，但用户理解这张图的方式就该是这样。
     */
    public static List<String> plan(Plan plan, int width) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(Ansi.bold("执行计划") + Ansi.dim("  " + plan.graph().size() + " 个任务"));
        lines.add(Ansi.cyan("目标: ") + clip(plan.goal(), width - 6));
        lines.add("");

        List<List<Task>> layers = plan.graph().layers();
        for (int i = 0; i < layers.size(); i++) {
            List<Task> layer = layers.get(i);
            String header = "第 " + (i + 1) + " 步"
                    + (layer.size() > 1 ? "（" + layer.size() + " 个任务互不依赖）" : "");
            lines.add(Ansi.dim("  " + header));
            for (Task task : layer) {
                lines.add(taskLine(PENDING, task, width));
            }
        }
        return lines;
    }

    /** 某个任务开跑。 */
    public static String taskStarted(Task task) {
        return Ansi.cyan("▸ ") + Ansi.dim("[" + label(task.type()) + " " + task.id() + "] ")
                + task.description();
    }

    /** 某个任务了结。失败和跳过要把原因带出来，否则用户只看到一个叉。 */
    public static String taskFinished(Task task) {
        String mark = switch (task.status()) {
            case SUCCEEDED -> Ansi.green(OK);
            case FAILED -> Ansi.red(FAILED);
            case SKIPPED -> Ansi.yellow(SKIPPED);
            default -> PENDING;
        };
        StringBuilder sb = new StringBuilder(mark)
                .append(" ").append(Ansi.dim("[" + task.id() + "] ")).append(task.description());

        if (task.status() == TaskStatus.SUCCEEDED && task.durationMillis() >= 0) {
            sb.append(Ansi.dim("  " + task.durationMillis() / 1000 + "s"));
        } else if (task.error() != null) {
            sb.append(Ansi.dim("  " + firstLine(task.error())));
        }
        return sb.toString();
    }

    /**
     * 跑完的小结。
     *
     * <p>只报有数的项：全成功时不提「失败 0 个 / 跳过 0 个」——
     * 报告里塞满零，真正有问题的那次反而不显眼了。
     */
    public static List<String> summary(Plan plan, PlanResult result) {
        StringBuilder sb = new StringBuilder("✻ ");
        sb.append(switch (plan.status()) {
            case COMPLETED -> Ansi.green("计划完成");
            case FAILED -> Ansi.red("计划未完成");
            case CANCELLED -> Ansi.yellow("已取消");
            default -> "计划" + plan.status();
        });
        sb.append(Ansi.dim("  成功 " + result.succeeded()));
        if (result.failed() > 0) {
            sb.append(Ansi.dim(" · 失败 " + result.failed()));
        }
        if (result.skipped() > 0) {
            sb.append(Ansi.dim(" · 跳过 " + result.skipped()));
        }
        // 一次跑通和「中途换了条路才跑通」都是 COMPLETED，但后者说明原计划某处想岔了，
        // 是下次该复盘的地方——不报出来的话，这条信息就永远没人看见
        if (result.wasReplanned()) {
            sb.append(Ansi.dim(" · 重规划 " + result.replans() + " 次"));
        }
        if (plan.durationMillis() >= 0) {
            sb.append(Ansi.dim(" · 用时 " + plan.durationMillis() / 1000 + "s"));
        }
        return List.of("", sb.toString());
    }

    private static String taskLine(String mark, Task task, int width) {
        String prefix = "    " + mark + " " + Ansi.dim("[" + label(task.type()) + " "
                + task.id() + "] ");
        String deps = task.dependencies().isEmpty() ? ""
                : Ansi.dim("  ← " + String.join(", ", task.dependencies()));
        // 预算里要把前缀和依赖那一段的可见宽度扣掉，剩下的才是描述能占的
        int budget = width - visibleLength(prefix) - visibleLength(deps);
        return prefix + clip(task.description(), budget) + deps;
    }

    /**
     * 按可见宽度截断。
     *
     * <p>用字符数而不是列宽：中文在终端里占两列，严格算的话得引入宽度表。
     * 这里宁可短一点也不能长——画面被撑破比留白难看得多。
     */
    private static String clip(String text, int max) {
        if (max <= 1) {
            return "";
        }
        String oneLine = firstLine(text);
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String firstLine(String text) {
        String stripped = text == null ? "" : text.strip();
        int newline = stripped.indexOf('\n');
        return newline < 0 ? stripped : stripped.substring(0, newline) + "…";
    }

    /** 关了颜色时字符串里没有转义序列，长度就是它本身；开了的话要把转义序列扣掉。 */
    private static int visibleLength(String text) {
        return text.replaceAll("\\[[0-9;]*m", "").length();
    }
}
