package dev.wiles.myagent.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.wiles.myagent.llm.ChatResponse;

/**
 * 一次会话到目前为止<b>真正发出去</b>的量。
 *
 * <h3>它和「上下文占用」是两件事，而且差得很远</h3>
 * 状态栏上那个百分比说的是「这一份窗口有多大」，是个<b>瞬时快照</b>。
 * 而 ReAct 循环每走一步都要把整个窗口<b>重发一遍</b>：
 *
 * <pre>
 *   一轮跑 5 步、窗口 50k
 *     上下文占用 = 50k      ← 状态栏显示的
 *     实际发出去 = 250k     ← 这个账本记的
 * </pre>
 *
 * 用户看着状态栏写着 50k，以为花了 50k。这大概是这套系统里最容易不留意、
 * 也最容易出事的一处，而在有这个账本之前我们一个字都没告诉他。
 *
 * <h3>为什么是实测而不是估算</h3>
 * 这里的数全部来自响应里的 {@code usage}，不经过 {@link TokenEstimator}。
 * 估算器自述偏差 10~20%，用来判压缩的线够用（那只要求量级对），
 * 但用来回答「我一共花了多少」就不行——那是个会被用户拿去对账的数字，
 * 差两成就成了假话。拿不到 usage 时宁可不记（见 {@link #missingUsage}），
 * 也不拿估算值去凑。
 *
 * @param promptTokens     累计发出去的提示词 token
 * @param completionTokens 累计收到的生成 token
 * @param requests         发了多少次请求（含压缩用的那些）
 * @param missingUsage     有多少次响应没带 usage——这个数不为 0 时，
 *                         上面几项就是<b>低报</b>的，界面该说明白，不能装作精确
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenLedger(long promptTokens, long completionTokens,
                          int requests, int missingUsage) {

    public static final TokenLedger EMPTY = new TokenLedger(0, 0, 0, 0);

    /**
     * 记一次响应。
     *
     * <p>没有 usage 的响应只计入 {@link #missingUsage}，不猜它有多大——
     * 账本宁可承认自己不全，也不能给一个看起来精确的假数。
     */
    public TokenLedger plus(ChatResponse response) {
        if (response == null) {
            return this;
        }
        ChatResponse.Usage usage = response.usage();
        if (usage == null) {
            return new TokenLedger(promptTokens, completionTokens,
                    requests + 1, missingUsage + 1);
        }
        return new TokenLedger(
                promptTokens + Math.max(0, usage.promptTokens()),
                completionTokens + Math.max(0, usage.completionTokens()),
                requests + 1, missingUsage);
    }

    /** 续聊时把存档里的账接上。 */
    public TokenLedger plus(TokenLedger other) {
        if (other == null) {
            return this;
        }
        return new TokenLedger(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                requests + other.requests, missingUsage + other.missingUsage);
    }

    @JsonIgnore
    public long total() {
        return promptTokens + completionTokens;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return requests == 0;
    }

    /** 这些数是完整的吗——有响应没带 usage 时就不是，界面得说明白。 */
    @JsonIgnore
    public boolean isComplete() {
        return missingUsage == 0;
    }

    /**
     * 平均每次请求发出去多少提示词。
     *
     * <p>这个数比总量更能说明问题：它约等于「一次请求的上下文有多大」，
     * 涨起来说明该压缩了，而总量涨只说明聊得久。
     */
    @JsonIgnore
    public long averagePrompt() {
        return requests == 0 ? 0 : promptTokens / requests;
    }
}
