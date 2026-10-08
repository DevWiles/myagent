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

    /**
     * 模型新吐出的一小段文本（增量，不是全文）。
     * 界面靠它做到边生成边显示，而不是等整段完成再一次性蹦出来。
     */
    record TextDelta(String text) implements AgentEvent {
    }

    /** 模型在调用工具之前说的话，相当于 ReAct 里的 Thought。 */
    record Thought(String text) implements AgentEvent {
    }

    /**
     * 开始执行某个工具。
     *
     * @param id 这一次调用的流水号，进程内全局唯一（见 {@link Agent#nextToolId()}）。
     *           并行安全的调用会同时执行，「开始」和「结束」在事件流里不再相邻，
     *           界面只能靠它把两头配成同一行。以前这个号是服务端看着事件顺序补的，
     *           那套编号法建立在「工具严格串行」上，并发一开就配错对
     */
    record ToolStarted(int id, String tool, String arguments) implements AgentEvent {
    }

    /**
     * 工具执行完毕，相当于 ReAct 里的 Observation。id 和对应的 {@link ToolStarted} 一致。
     *
     * @param diff <b>只给界面看的</b>那份改动（统一 diff），没有就是空串。
     *             它不进对话历史——模型刚写完那段内容，再喂它一遍 diff 是白花钱；
     *             而用户没看过，「改了什么」正是他唯一想知道的事。
     *             见 {@link dev.wiles.myagent.tool.ToolResult#diff()}
     */
    record ToolFinished(int id, String tool, boolean ok, String output, String diff)
            implements AgentEvent {

        /** 不带 diff 的那种，绝大多数工具都是。 */
        public ToolFinished(int id, String tool, boolean ok, String output) {
            this(id, tool, ok, output, "");
        }
    }

    /**
     * 压缩正在进行。
     *
     * <h3>为什么需要这个事件</h3>
     * 压缩是一次（或几次）完整的模型调用，几十秒起步。在此之前界面上什么都没有：
     * 用户敲了 {@code /compact}，然后对着一个不动的屏幕，无从判断是在干活还是按键丢了。
     *
     * <p><b>只有用户等得到的那些压缩才发这个事件</b>。后台预压缩（80% 那条线）
     * 刻意不发——它的全部价值就在于无感，弹一条「正在压缩」等于把它的意义抵消掉。
     *
     * @param phase   {@code map}（逐块摘要）还是 {@code reduce}（合并成一份）；
     *                {@code done} 是收尾信号，界面收到它就把进度条撤掉
     * @param done    走完几步
     * @param total   一共几步
     * @param percent 0~100。<b>内层是估的</b>——当前这一步的进度按「摘要已经吐了多少字
     *                / 按输入规模估出来的预期长度」算，所以条可能提前走完或者顶在 99%
     *                等收尾。界面要把「估算」这件事说出来
     */
    record CompactProgress(String phase, int done, int total, int percent)
            implements AgentEvent {

        /** 收尾。进度条该撤了——不管是压完了、压失败了，还是用户按了 Esc。 */
        public static final String PHASE_DONE = "done";

        public static CompactProgress finished(int total) {
            return new CompactProgress(PHASE_DONE, total, total, 100);
        }
    }

    /** 上下文被压缩了。用户有权知道——摘要意味着早期细节已经丢失。 */
    record Compacted(int tokensBefore, int tokensAfter, boolean summarized) implements AgentEvent {
    }

    /** 模型给出最终答复，本次 run 结束。 */
    record Done(String text, int steps) implements AgentEvent {
    }

    /**
     * 跑不下去了（模型调用失败、被取消、超出步数上限）。
     *
     * @param interrupted 现场封口留着、说一句「继续」就能接上时才有；
     *                    续了也白续的失败（比如最近几轮本身撑破了窗口）为 null
     */
    record Failed(String reason, Interruption interrupted) implements AgentEvent {

        public Failed(String reason) {
            this(reason, null);
        }
    }
}
