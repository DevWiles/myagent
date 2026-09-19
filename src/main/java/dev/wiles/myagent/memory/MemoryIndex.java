package dev.wiles.myagent.memory;

import dev.wiles.myagent.context.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆索引——<b>常驻系统提示词</b>的那一段文字。
 *
 * <h3>它在召回链路里的位置</h3>
 * <pre>
 *   索引（这个类，每轮都发） ──模型觉得某条相关──▶ recall 工具 ──▶ 正文（只在需要时发）
 * </pre>
 * 这就是所谓「索引常驻 + 工具读正文」。换个说法：
 * <b>每轮都付的是摘要的钱，细节的钱只在用得上时才付</b>。
 *
 * <h3>为什么单独成类，而不是在 MemoryStore 里拼字符串</h3>
 * 这段文字是提示词，提示词的每个字都影响模型行为，所以它得能被单独断言——
 * 测「索引里确实有这一行」不该需要先造一个存储、再发一次模型调用。
 * 和 {@code PlanContext} 分担的是同一种职责：纯数据进来，排好版的文字出去。
 *
 * <h3>为什么预算按 token 记，不按条数记</h3>
 * 因为<b>条数和开销不是一回事</b>。这里原先只有「最多 200 条」一个闸，
 * 而每条的 description 可以写到 {@link MemoryEntry#MAX_DESCRIPTION} 个字——
 * 中文按 0.6 token/字算，单条能到 130 token 上下，200 条就是 27,000 token，
 * 接近 64k 窗口的一半，而那个闸<b>一声都不会响</b>：它只数条数。
 * 现在改成量真实开销，量的对象就是 {@link #render()} 吐出来的那段文字本身。
 */
public record MemoryIndex(List<MemoryEntry> entries) {

    /**
     * 索引在系统提示词里能占的 token 上限。
     *
     * <p>这是<b>每一轮都要付</b>的常驻税，所以它是个<b>绝对上限</b>，不是窗口的百分比：
     * 窗口从 64,000 涨到 1,000,000，并不意味着往提示词里堆十六倍的记忆摘要就变划算了。
     * 再多就该改成按相关性检索，而不是继续堆。
     *
     * <p>但小窗口那头要反过来防：这个数和窗口无关，而 system 消息是<b>永远不压缩</b>的，
     * 所以窗口调到 4,000 时，光索引就能把整个窗口撑爆，且没有任何机制救得回来。
     * 见 {@link #budgetFor(int)}。
     */
    public static final int MAX_TOKENS = 10_000;

    /** 小窗口下索引最多占窗口的这个比例。 */
    private static final double WINDOW_SHARE = 0.16;

    /**
     * 给定上下文窗口，索引能占多少 token。
     *
     * <p>{@code min(绝对上限, 窗口 × 16%)}——大窗口由绝对上限封顶（堆更多记忆并不更划算），
     * 小窗口由比例封顶（不能让常驻的 system 把窗口占满）。
     * 1M 下是 10,000，64,000 下还是 10,000（和以前一字不差），4,000 下缩到 640。
     */
    public static int budgetFor(int windowTokens) {
        return Math.min(MAX_TOKENS, Math.max(1, (int) (windowTokens * WINDOW_SHARE)));
    }

    /** 表头和表尾是渲染和估算<b>共用</b>的，分开写迟早会算漏一边。 */
    private static final String HEADER = "\n## 你记住的事\n\n";

    /**
     * 一条记忆都没有时说的话。
     *
     * <h3>为什么不是干脆什么都不说</h3>
     * 原来就是什么都不说，理由是「省 token」。2026-09-17 第一次真跑就翻车了：
     * 模型上来第二个动作就是 {@code recall("build-uses-bun")}——
     * 那个名字它是从 {@code RememberTool} 说明里的举例
     * 「这个项目用 bun 不用 npm」现编的，盘上根本没有这条。
     *
     * <p>原因很直白：<b>recall 工具挂在菜单上，而提示词里没有一个字交代记忆现状</b>，
     * 模型只能猜，就拿手边唯一的素材——工具说明里的例子——去猜了。
     *
     * <p>这段话约 40 token，换掉的是一次无效工具调用加一轮往返（几百 token 加几秒），
     * 而且那次翻车每个空记忆的新会话都会重演一次。省那 40 个字是亏的。
     *
     * <p>措辞是「没有可用的」而不是「一条都没记过」：
     * 预算裁到一条都放不下时走的也是这个分支，那时说「没记过」就是假话了。
     */
    private static final String EMPTY_NOTICE = HEADER + """
            你目前没有任何可用的长期记忆。recall 只能读出真实存在的记忆——
            工具说明里出现的名字都只是举例，不要照着猜一个去调。
            """;

    /**
     * 这段不能省。只给摘要行的话，模型会拿那一行当完整事实直接用——
     * 摘要是为了让它判断「相不相关」，不是为了让它照着做。
     */
    private static final String FOOTER = """

            上面每行只是一句摘要，不是完整内容。判断某条和当前的事有关时，
            先用 recall 工具读出正文再下结论，不要照着摘要行动。
            """;

    public MemoryIndex {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    /** 什么都没记过。第一次用这个 agent 的人就是这个状态。 */
    public static MemoryIndex empty() {
        return new MemoryIndex(List.of());
    }

    public static MemoryIndex of(List<MemoryEntry> entries) {
        return new MemoryIndex(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /**
     * 排版成塞进系统提示词的那段文字。
     *
     * <p>空索引<b>也要出声</b>，说明见 {@link #EMPTY_NOTICE}——
     * 原先返回空串，真跑下来那点省出的 token 远不够付一次瞎猜的 recall。
     *
     * <p>完全不接记忆功能的场合（比如计划任务，{@code TaskTools} 不给 recall）
     * 压根不该走到这里，见 {@code SystemPrompt#forWorkspace(Path)}。
     */
    public String render() {
        if (entries.isEmpty()) {
            return EMPTY_NOTICE;
        }
        StringBuilder sb = new StringBuilder(HEADER);
        for (MemoryEntry e : entries) {
            sb.append(line(e));
        }
        return sb.append(FOOTER).toString();
    }

    /** 这段索引真正要花多少 token。量的就是 {@link #render()} 发出去的那段文字。 */
    public int estimatedTokens() {
        return TokenEstimator.estimate(render());
    }

    /**
     * 裁到放得进预算为止，返回裁过的索引；本来就放得下就返回自己。
     *
     * <p><b>裁尾巴，留前缀</b>。两个理由：前面的条目是
     * {@code MemoryStore.scan} 按「项目级优先」排过的，本来就更该留；
     * 而且留前缀意味着<b>加一条新记忆不会把老的那段冲掉</b>，
     * 提示词前缀稳定，服务端的缓存才有命中的可能。
     *
     * <p>遇到放不下的条目直接停，不跳过它去找后面小的塞——
     * 那样留下的集合会随记忆长度跳变，今天在明天不在，
     * 用户完全没法预期「我记的东西到底还在不在」。
     *
     * <p>连表头表尾都放不下时返回空索引：发一段没有任何内容的框架，
     * 白花 token 不说，还在暗示模型「有记忆但没给你看」。
     */
    public MemoryIndex withinBudget(int maxTokens) {
        if (entries.isEmpty()) {
            return this;
        }
        int left = maxTokens - TokenEstimator.estimate(HEADER) - TokenEstimator.estimate(FOOTER);
        List<MemoryEntry> kept = new ArrayList<>(entries.size());
        for (MemoryEntry e : entries) {
            int cost = TokenEstimator.estimate(line(e));
            if (cost > left) {
                break;
            }
            left -= cost;
            kept.add(e);
        }
        return kept.size() == entries.size() ? this : new MemoryIndex(kept);
    }

    private static String line(MemoryEntry e) {
        return e.indexLine() + "\n";
    }
}
