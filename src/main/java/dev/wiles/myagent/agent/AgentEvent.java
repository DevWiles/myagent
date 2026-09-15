package dev.wiles.myagent.agent;

/**
 * agent 运行过程中发出的事件，供界面实时展示。
 *
 * 为什么需要它：一次 run() 可能跑十几轮、花上一分钟，
 * 期间终端如果一个字都不打，用户会以为程序卡死了。
 * 把过程暴露出来，界面才能一步步显示「正在读文件…」「正在跑测试…」。
 *
 * 用 sealed interface 是为了把「事件一共就这几种」这件事写进类型里：
 * 实现类被限定在本文件内，读代码的人一眼能数清全集。
 * （注：switch 的模式匹配要 Java 21 才转正，在 17 上还得用 instanceof 链来分派，
 * 所以暂时享受不到「漏处理一种就编译失败」的待遇。）
 */
public sealed interface AgentEvent {

    /** 第 step 轮开始，正在等模型响应。 */
    record Thinking(int step) implements AgentEvent {
    }

    /** 模型在调用工具之前说的话，相当于 ReAct 里的 Thought。 */
    record Thought(String text) implements AgentEvent {
    }

    /** 开始执行某个工具。 */
    record ToolStarted(String tool, String arguments) implements AgentEvent {
    }

    /** 工具执行完毕，相当于 ReAct 里的 Observation。 */
    record ToolFinished(String tool, boolean ok, String output) implements AgentEvent {
    }

    /** 模型给出最终答复，本次 run 结束。 */
    record Done(String text, int steps) implements AgentEvent {
    }

    /** 跑不下去了（模型调用失败、超出步数上限）。 */
    record Failed(String reason) implements AgentEvent {
    }
}
