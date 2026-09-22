package dev.wiles.myagent.prompt;

import java.util.List;

/**
 * 这一份提示词是给谁用的。
 *
 * <p>一个模式一个文件，而不是在一段大字符串里用 if-else 拼。前者改哪个模式
 * 就只碰哪个文件，diff 一眼能看懂；后者改一处要在脑子里把所有分支跑一遍，
 * 而且很容易出现「本该只影响规划的改动，把主循环也带歪了」。
 *
 * <h3>为什么「拼不拼 base」是模式的属性</h3>
 * 主循环和子任务是<b>同一个助手在干活</b>，所以共用身份、语言、工作方式那几段。
 * 规划器不是——它不动手、只输出 JSON，把「改完代码用 bash 跑一下测试」
 * 这种话塞给它纯属噪音，还会勾着它去规划自己执行。
 *
 * <h3>为什么关键段是按模式声明的</h3>
 * 缺段这道不变量的意义在于「少了它模型会静默跑偏」，而每个模式的命门不一样：
 * 主循环少了语言那段会改说英文，规划器少了输出格式那段会吐出一段散文——
 * 拿同一张清单去卡所有模式，要么卡不住规划器，要么逼着规划提示词写上
 * 它根本用不着的小标题。
 */
public enum PromptMode {

    /** 主循环：用户直接对话的那个。 */
    AGENT("modes/agent.md", true, List.of("## 身份", "## 语言", "## 工作区")),

    /** 计划里的一步：只干这一步，不越界。 */
    TASK("modes/task.md", true, List.of("## 身份", "## 语言", "## 工作区")),

    /** 规划器：把目标拆成任务依赖图。不动手。 */
    PLANNER("modes/planner.md", false, List.of("只输出一个 JSON 对象")),

    /** 子规划：PLANNING 任务跑完后再拆一层。 */
    PLANNER_SUB("modes/planner-sub.md", false, List.of("只输出一个 JSON 对象")),

    /** 重规划：某一步失败之后补一条别的路。 */
    PLANNER_REPLAN("modes/planner-replan.md", false, List.of("只输出一个 JSON 对象"));

    private final String resourcePath;
    private final boolean includesBase;
    private final List<String> requiredMarkers;

    PromptMode(String resourcePath, boolean includesBase, List<String> requiredMarkers) {
        this.resourcePath = resourcePath;
        this.includesBase = includesBase;
        this.requiredMarkers = requiredMarkers;
    }

    public String resourcePath() {
        return resourcePath;
    }

    /** 要不要在前面拼上 {@code base.md}（身份 / 语言 / 工作区 / 工作方式 / 回答风格）。 */
    public boolean includesBase() {
        return includesBase;
    }

    /**
     * 装配结果里必须出现的字样，少一个就抛。
     *
     * <p>挑的是<b>少了它会静默跑偏</b>的那几段，不是「看着重要」的那几段。
     */
    public List<String> requiredMarkers() {
        return requiredMarkers;
    }
}
