package dev.wiles.myagent.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 流式请求的附加选项。目前只用来把 usage 要回来。
 *
 * <h3>为什么非要这个字段不可</h3>
 * OpenAI 系的流式响应<b>默认不带 usage</b>——一路 delta 到 [DONE]，
 * 一个 token 数都不给。而 agent 主循环走的正是流式，于是
 * {@code StreamAccumulator.toResponse()} 只能把 usage 填成 null。
 *
 * <p>后果是上下文占用率只能靠 {@link TokenEstimator} 估，而它自述偏差 10~20%。
 * 用估算值去卡「到 93.5% 就压缩」这种线是不安全的：估低 15% 就是实际 107%，
 * 请求直接失败。带上这个字段之后，服务端会在 [DONE] 之前多发一个
 * {@code choices} 为空、只有 usage 的 chunk，那里面的 {@code prompt_tokens}
 * 是<b>实测值</b>，不是估的。
 *
 * <h3>网关不认怎么办</h3>
 * 不认的网关会忽略这个字段（而不是报错），表现为最后那个 usage chunk 不出现，
 * usage 仍然是 null。调用方据此退回纯估算，并把压缩阈值压到保守档——
 * 见 {@code ContextManager}。所以这里不需要探测，也不需要开关。
 */
public record StreamOptions(@JsonProperty("include_usage") boolean includeUsage) {

    /** 流式请求一律用它——我们没有「不想要 usage」的场景。 */
    public static final StreamOptions INCLUDE_USAGE = new StreamOptions(true);
}
