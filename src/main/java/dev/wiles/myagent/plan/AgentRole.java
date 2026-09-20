package dev.wiles.myagent.plan;

import dev.wiles.myagent.prompt.PromptRepository;

import java.util.List;
import java.util.Objects;

/**
 * 一个角色：派谁去干这一步。
 *
 * <h3>为什么要在 {@link TaskType} 之外再有角色</h3>
 * 类型说的是「这一步会对世界做什么」——读、写、跑命令。它够用来排并发、上图标，
 * 但它<b>决定不了该用什么姿势去做</b>。最直接的证据是 {@code explorer} 和 {@code reviewer}：
 * 两个都只读文件，工具白名单一模一样，可一个的任务是「把事实查清楚」，
 * 另一个的任务是「挑毛病」。写进同一个 {@code FILE_READ} 里，就只能共用一段提示词，
 * 那段话必然是两头都不讨好的和稀泥。
 *
 * <p>所以分工是：<b>角色拿走提示词、工具、步数、收尾契约，类型只留调度语义和界面</b>。
 *
 * <h3>模型只选角色，类型是推导出来的</h3>
 * 规划提示词里只让模型填 {@code role}，{@link #taskType} 由角色自己声明。
 * 两个字段都让模型填的话，迟早会收到 {@code type: FILE_READ} 配 {@code role: coder}
 * 这种自相矛盾的计划——而那时候<b>没有哪个字段是可信的</b>：按类型派工具就没有写工具，
 * 按角色派就违背了它自己填的类型，两条路都得猜。让它只填一个，这种矛盾从根上不存在。
 *
 * <h3>正文为什么在资源文件里</h3>
 * 和 {@link PromptRepository} 是同一个理由：这几段话会被反复调，
 * 而「调一句措辞要重新编译打包」等于没人会去调。这里只留名册和参数——
 * 「内置」指的是<b>角色名册写死在代码里</b>，不是把文字也埋进 Java 字符串。
 *
 * @param id             角色 id，小写，模型在计划 JSON 里填的就是它
 * @param title          中文名，界面和提示词里给人看
 * @param summary        一句话说清<b>什么时候该派它</b>。这句会原样进规划提示词，
 *                       是模型选角色的唯一依据——写成「负责编码」这种废话，
 *                       模型就只能靠 id 猜
 * @param taskType       对应的任务类型。调度和界面继续用它，协议里也照发
 * @param tools          工具白名单。<b>白名单不是黑名单</b>：以后新增工具（发邮件、调 API）
 *                       默认对所有角色不可见，得显式想清楚该给谁
 * @param maxSteps       这个角色的 ReAct 步数上限。分析型的给三步足够，
 *                       编码型的要留出「读—改—再读」的来回
 * @param mutates        会不会改变世界。只读模式靠它过滤掉一半角色，
 *                       将来并发调度也要靠它分流
 * @param requiresVerdict 收尾必须给出结论行（见 {@link TaskPrompt#VERDICT_PASS}）。
 *                       目前只有验证角色要，但它是角色的属性不是类型的属性——
 *                       将来多一个「验收」角色，它也要
 */
public record AgentRole(
        String id,
        String title,
        String summary,
        TaskType taskType,
        List<String> tools,
        int maxSteps,
        boolean mutates,
        boolean requiresVerdict) {

    private static final PromptRepository REPO = PromptRepository.createDefault();

    public AgentRole {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(taskType, "taskType");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("角色 " + id + " 的步数上限必须大于 0");
        }
        tools = List.copyOf(tools == null ? List.of() : tools);
    }

    /** 这个角色的提示词正文在哪。一个角色一个文件，改哪个角色就只碰哪个文件。 */
    public String promptPath() {
        return "roles/" + id + ".md";
    }

    /**
     * 这个角色的行事规矩，会被填进 {@code prompts/modes/task.md}。
     *
     * <p>找不到就抛，不退化成空字符串——少一段规矩不会报错，
     * 只是这个角色变得和别的角色一个样，而这件事从日志里看不出来。
     */
    public String rules() {
        return REPO.loadRequired(promptPath());
    }
}
