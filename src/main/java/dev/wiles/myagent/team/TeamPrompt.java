package dev.wiles.myagent.team;

import dev.wiles.myagent.plan.AgentRole;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.prompt.PromptAssembler;
import dev.wiles.myagent.prompt.PromptContext;
import dev.wiles.myagent.prompt.PromptMode;
import dev.wiles.myagent.prompt.PromptRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 圆桌的三段文字：挑人、给每一席交代、合议。
 *
 * <h3>为什么席位那段是 user 而不是 system</h3>
 * 每一席的 system 已经由它的<b>角色</b>定了（{@code TaskPrompt.system}）——那是「你是谁」。
 * 这里写的是「这场会要你干什么」，属于这一次的具体交代，和计划里一个任务的
 * description 是同一种东西。塞进 system 会让角色提示词被一场会的措辞污染，
 * 而角色是跨场复用的。
 *
 * <h3>为什么合议那段要写死顺序</h3>
 * 一致 → 分歧 → 独家。这个顺序就是<b>读者该按什么顺序处理问题</b>：
 * 几个视角独立撞上的那条最可能是真的，分歧要人来判，独家最容易被漏掉。
 * 不规定顺序的话，模型会按「谁先发言」排，那个顺序对读的人没有任何意义。
 */
public final class TeamPrompt {

    private static final PromptRepository REPO = PromptRepository.createDefault();
    private static final PromptAssembler ASSEMBLER = new PromptAssembler(REPO);

    static {
        // 和 AgentRoles 静态块同一个道理：模板漏打包不会有任何症状，
        // 只会在用户敲下 /team、名单都画到屏幕上之后，才蹦出一句「圆桌没开成」。
        // 宁可启动就炸
        REPO.loadRequired("team/seat.md");
        REPO.loadRequired("team/synthesis.md");
    }

    private TeamPrompt() {
    }

    // ── 挑人 ────────────────────────────────────────────────────

    public static String castSystem(List<AgentRole> available) {
        return ASSEMBLER.assemble(PromptMode.TEAM_CAST, PromptContext.builder()
                .variable("minMembers", String.valueOf(Roundtable.MIN_MEMBERS))
                .variable("maxMembers", String.valueOf(Roundtable.MAX_MEMBERS))
                .variable("roles", menu(available))
                .build());
    }

    public static String castUser(String goal) {
        return "议题: " + goal;
    }

    /** 和规划那份菜单同一个路数：从名册生成，新增角色不用改提示词。 */
    private static String menu(List<AgentRole> roles) {
        return roles.stream()
                .map(role -> "- `" + role.id() + "`（" + role.title() + "）" + role.summary()
                        + "。工具: "
                        + (role.tools().isEmpty() ? "一个都没有" : String.join(" ", role.tools())))
                .collect(Collectors.joining("\n"));
    }

    // ── 一席 ────────────────────────────────────────────────────

    /**
     * 席位抬头，也是界面上那一行。
     *
     * <p>短，而且带角色名。界面要显示、日志要打、用户要指着它说「这一席跑砸了」——
     * 三处都受不了一整段交代。真正的交代在 {@link #seatAcceptance}：
     * 它进的是「做到这样就算完成」，而圆桌的纪律
     * （只说你这一席、别做总结、别转述常识）<b>本来就是完成标准</b>，
     * 不是背景介绍。
     */
    public static String seatTitle(AgentRole role, String goal) {
        return "以「" + role.title() + "」的视角看: " + goal;
    }

    /**
     * @param steps 这一席能调几轮工具。<b>必须告诉它</b>——撞上限的代价不是少查两个文件，
     *              是这一席整个作废（见 {@code AgentTaskRunner.stepsFor}）。
     *              模型不知道预算就没法取舍，只会一路查到被掐断
     */
    public static String seatAcceptance(AgentRole role, List<AgentRole> everyone, int steps) {
        String others = everyone.stream()
                .filter(other -> !other.equals(role))
                .map(other -> "「" + other.title() + "」")
                .collect(Collectors.joining("、"));
        return REPO.loadRequired("team/seat.md")
                .replace("{{@roleTitle}}", role.title())
                .replace("{{@others}}", others.isEmpty() ? "别的席位" : others)
                .replace("{{@steps}}", String.valueOf(steps));
    }

    // ── 合议 ────────────────────────────────────────────────────

    /**
     * 合议那一席要看的全部材料：议题 + 谁没到 + 各席原话。
     *
     * <h3>为什么材料是自己拼的，不走上游那套</h3>
     * {@code TaskPrompt.user} 会把上游产出按 {@code 【任务id 任务描述】} 拼进去——
     * 那套抬头对计划是对的（描述就是「这一步干了什么」），对圆桌是错的：
     * 圆桌几席的描述里写的都是同一个议题，抬头会变成把议题抄三遍，
     * 而<b>真正要标出来的「这话是谁说的」反倒埋在里面</b>。
     * 合议处理的全部信息就是「谁说了什么」，这个抬头不能含糊。
     *
     * <p><b>谁说了话、谁没到，由编排层判一次传进来</b>，这里不自己再判一遍。
     * 两处各判各的，迟早会出现屏幕上写着「2/2 位给了意见」而合议里说「只有一席到场」——
     * 这一条也是圆桌自己审出来的。
     *
     * @param absent 哑火的席位。<b>必须让合议者知道</b>，
     *               否则读的人会把两个人的共识当成三个人的共识
     */
    public static String synthesisBrief(String goal, List<Task> spoke, List<Task> absent) {
        return REPO.loadRequired("team/synthesis.md")
                .replace("{{@goal}}", goal)
                .replace("{{@absent}}", absentNote(absent))
                .replace("{{@opinions}}", opinions(spoke));
    }

    public static String synthesisAcceptance() {
        return "按「一致 / 分歧 / 只有一个人提到的」三段给出合议，不复述各席原话。";
    }

    private static String opinions(List<Task> spoke) {
        return spoke.stream()
                .map(seat -> "【" + seat.role().title() + "（" + seat.role().id() + "）说】\n"
                        + seat.result())
                .collect(Collectors.joining("\n\n"));
    }

    private static String absentNote(List<Task> absent) {
        if (absent == null || absent.isEmpty()) {
            return "";
        }
        return "\n**这几席没能给出意见**，合议时要把这一点说出来:\n"
                + absent.stream()
                        .map(task -> "- " + task.role().title() + ": " + firstLine(task.error()))
                        .collect(Collectors.joining("\n"))
                + "\n";
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "（没留下原因）";
        }
        String stripped = text.strip();
        int newline = stripped.indexOf('\n');
        return newline < 0 ? stripped : stripped.substring(0, newline) + "…";
    }
}
