package dev.wiles.myagent.team;

import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskStatus;
import dev.wiles.myagent.prompt.PromptRepository;

import java.util.List;

/**
 * 流水线两道自有工序（规划、验收）的文字。worker 没有份——
 * 它们的 description/acceptance 是规划拆出来的，那正是规划工序的产出。
 *
 * <h3>为什么交代进的是 acceptance，不是 system 也不是 description</h3>
 * 每道工序的 system 已经由它的<b>角色</b>定了（{@code TaskPrompt.system}）——那是「你是谁」。
 * 工序的纪律（拆到能开工、末行三选一）本来就是「做到什么样算完成」，那是 acceptance 的位置。
 * 例外是验收的<b>材料</b>（方案 + 各工序结果）：它走 description——上游里有失败的任务，
 * 而 {@code TaskPrompt.user} 只拼 result 非空的上游，失败的会被静默漏掉；
 * 验收恰恰最需要看到失败。所以材料由 {@link #reviewBrief} 自己拼，不走上游机制。
 *
 * <h3>为什么末行判定的措辞写死在 reviewer.md 里</h3>
 * {@link Pipeline#verdictOf} 只认最后一行的「通过 / 重新执行 / 重新规划」。
 * 判定规则和要求模型遵守的措辞必须指着同一组词——改词只改一头，
 * 验收就永远通过或永远打回，而且没有任何报错。
 */
public final class TeamPrompt {

    private static final PromptRepository REPO = PromptRepository.createDefault();

    static {
        // 和 AgentRoles 静态块同一个道理：模板漏打包不会有任何症状，
        // 只会在用户敲下 /team、座位表都画到屏幕上之后，才蹦出一句「流水线没跑成」。
        // 宁可启动就炸
        REPO.loadRequired("team/planner.md");
        REPO.loadRequired("team/reviewer.md");
    }

    private TeamPrompt() {
    }

    // ── 规划 ────────────────────────────────────────────────────

    /** 抬头：界面那一行、日志、上游材料的标题，三处共用，所以短。 */
    public static String plannerTitle(String goal, int round) {
        return (round == 1 ? "拟定施工方案: " : "重新拟定施工方案（第 " + round + " 版）: ") + goal;
    }

    /**
     * @param steps 这道工序能调几轮工具。必须告诉它：撞上限的代价不是少查两个文件，
     *              是这道工序整个作废（见 {@code AgentTaskRunner.stepsFor}）
     */
    public static String plannerAcceptance(int steps) {
        return REPO.loadRequired("team/planner.md")
                .replace("{{@steps}}", String.valueOf(steps));
    }

    // ── 验收 ────────────────────────────────────────────────────

    /**
     * 验收要看的全部材料：目标 + 方案 + 每道工序的结果，<b>包括没跑成的</b>。
     *
     * <p>失败必须点名列出来——验收的一半职责就是判断「失败是施工问题还是方案问题」，
     * 那正是重新执行和重新规划的分水岭。漏掉失败的验收会对着半份现场说通过。
     */
    public static String reviewBrief(int round, String goal, Task plan, List<Task> workers) {
        StringBuilder sb = new StringBuilder("验收第 ").append(round).append(" 轮施工: ")
                .append(goal)
                .append("\n\n【施工方案】\n").append(plan.result())
                .append("\n\n【各道工序的结果】\n");
        long failed = 0;
        for (Task worker : workers) {
            sb.append("\n【").append(worker.id()).append(" ").append(worker.description())
                    .append(" · ").append(statusWord(worker.status())).append("】\n");
            if (worker.status() == TaskStatus.SUCCEEDED) {
                sb.append(worker.result()).append('\n');
            } else {
                failed++;
                sb.append(worker.error() == null ? "（没留下原因）" : worker.error()).append('\n');
            }
        }
        if (failed > 0) {
            sb.append("\n**有 ").append(failed)
                    .append(" 道工序没跑成**。验收时要判断这是施工问题（按原方案重做能好）")
                    .append("还是方案问题（方案本身要重拟）。\n");
        }
        return sb.toString();
    }

    public static String reviewerAcceptance(int steps) {
        return REPO.loadRequired("team/reviewer.md")
                .replace("{{@steps}}", String.valueOf(steps));
    }

    private static String statusWord(TaskStatus status) {
        return switch (status) {
            case SUCCEEDED -> "完成";
            case FAILED -> "没跑成";
            case SKIPPED -> "被跳过";
            default -> status.name();
        };
    }
}
