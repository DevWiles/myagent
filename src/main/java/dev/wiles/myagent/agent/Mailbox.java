package dev.wiles.myagent.agent;

import java.util.List;

/**
 * 跑循环之外送进来的话：子 agent 交的结果、主 agent 追加给子 agent 的指示。
 *
 * <p>照 Codex：子 agent 跑完，它的结果作为一条通知<b>注入</b>主 agent 的对话
 * （{@code inject_fragment_without_turn}），在主 agent 下一次请求模型时被看见——
 * 不打断主 agent 正在做的事，也不用它反复去问。{@link Agent} 每一步请求之前
 * {@link #drain()} 一次，有就作为 user 消息追加。
 *
 * <h3>为什么交卷前要等</h3>
 * Codex 的交互界面里主 agent 说完这一轮就停，子 agent 的结果等下一轮再看。
 * myagent 还要跑无头评测：主 agent 交了卷、进程退出，子 agent 改到一半的文件就留在那儿。
 * 所以 {@link #busy()} 为真时 {@link Agent} 不收口，等到有消息进来再接着干——
 * 这和 Claude Code 后台子 agent 跑完会把主 agent 叫醒是同一个效果。
 */
public interface Mailbox {

    /** 取走所有待送的消息，按到达顺序。没有就是空列表。 */
    List<String> drain();

    /** 有没有待送的消息。 */
    boolean hasMail();

    /** 还有没有在跑、结果还会送进来的。 */
    boolean busy();

    /** 等到有新消息或者状态变化，最多等这么久。 */
    void awaitChange(long millis) throws InterruptedException;

    /** 主 agent 被叫停：在跑的都停下（可以再接着派活），不送通知。 */
    void interruptAll();

    /** 对话清空：全部关掉、忘掉。 */
    void shutdown();
}
