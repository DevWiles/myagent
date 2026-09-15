package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理：在撞上模型的上下文上限之前，把历史压小。
 *
 * <h3>为什么非做不可</h3>
 * 每一轮都要把完整历史重发一遍，而工具输出往往一次就是几千字符。
 * 读三个文件、跑两次测试，几万 token 就没了。撞上限的后果不是变慢，
 * 是请求直接失败——整个会话卡死在那里，用户还不知道为什么。
 *
 * <h3>两阶段策略：先扔便宜的，再压贵的</h3>
 * <ol>
 *   <li><b>裁剪旧的工具输出</b>。这是性价比最高的一刀：工具结果通常又长又已经被
 *       模型消化过了（它早就根据文件内容做完了判断），保留原文的价值很低。
 *       只留最近几条不动，更早的换成一行占位。</li>
 *   <li><b>摘要压缩</b>。第一刀还不够就动真格：把最早的一段对话交给模型总结成一段话，
 *       用这段摘要替换掉原文。代价是要多花一次 API 调用。</li>
 * </ol>
 *
 * <h3>最容易踩的坑：切的时候不能把 tool 消息切成孤儿</h3>
 * 每条 role=tool 的消息都必须紧跟在那条带 tool_calls 的 assistant 消息后面。
 * 随手从中间截断，极可能留下一条无主的 tool 消息，之后**每一次**请求都会 400。
 * 所以切点只能选在 role=user 的消息上——那才是一轮对话真正的起点。
 */
public final class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    /** DeepSeek 对话模型的上下文窗口。换模型记得改这里。 */
    public static final int DEFAULT_MAX_TOKENS = 64_000;
    /** 用到这个比例就动手。留足余量，因为 token 是估出来的，不是数出来的。 */
    public static final double DEFAULT_COMPACT_RATIO = 0.75;
    /** 压缩后希望降到的比例。降得太浅会导致刚压完没几轮又要压。 */
    private static final double TARGET_RATIO = 0.4;
    /** 最近这么多条消息里的工具输出不动——它们很可能正是模型当前在用的。 */
    private static final int KEEP_RECENT_TOOL_OUTPUTS = 6;
    /** 短于这个长度的工具输出没有裁剪价值。 */
    private static final int TOOL_OUTPUT_TRIM_THRESHOLD = 300;

    private final int maxTokens;
    private final double compactRatio;

    public ContextManager() {
        this(DEFAULT_MAX_TOKENS, DEFAULT_COMPACT_RATIO);
    }

    public ContextManager(int maxTokens, double compactRatio) {
        this.maxTokens = maxTokens;
        this.compactRatio = compactRatio;
    }

    /** 一次压缩的结果。 */
    public record Result(List<ChatMessage> messages, int tokensBefore, int tokensAfter,
                         boolean summarized) {
        public int saved() {
            return tokensBefore - tokensAfter;
        }
    }

    /** 上下文窗口上限，界面要用它算占用比例。 */
    public int maxTokens() {
        return maxTokens;
    }

    public int estimate(List<ChatMessage> history, List<ToolSpec> tools) {
        return TokenEstimator.estimate(history) + TokenEstimator.estimateTools(tools);
    }

    public boolean needsCompaction(List<ChatMessage> history, List<ToolSpec> tools) {
        return estimate(history, tools) > maxTokens * compactRatio;
    }

    /**
     * 压缩历史。传入的 list 不会被修改，返回一份新的。
     *
     * @param summarizer 用来生成摘要的模型客户端；传 null 或调用失败时退化为直接丢弃
     */
    public Result compact(List<ChatMessage> history, List<ToolSpec> tools, LlmClient summarizer) {
        int before = estimate(history, tools);
        int budget = (int) (maxTokens * TARGET_RATIO) - TokenEstimator.estimateTools(tools);

        // ---- 第一刀：裁掉旧的工具输出，最近几条先留着 ----
        List<ChatMessage> work = trimOldToolOutputs(history, KEEP_RECENT_TOOL_OUTPUTS);
        if (estimate(work, tools) <= budget) {
            return finish(work, tools, before, false, "裁剪旧工具输出");
        }

        // ---- 第二刀：把最早的一段摘要掉 ----
        boolean summarized = false;
        int cut = findSafeCutPoint(work, budget);
        if (cut > 1) {
            List<ChatMessage> discarded = work.subList(1, cut);
            List<ChatMessage> kept = work.subList(cut, work.size());
            List<ChatMessage> merged = new ArrayList<>();
            merged.add(work.get(0));                          // system 永远留着
            merged.add(summarize(discarded, summarizer));     // 摘要顶替被丢掉的部分
            merged.addAll(kept);
            work = merged;
            summarized = true;

            if (estimate(work, tools) <= budget) {
                return finish(work, tools, before, true, "摘要压缩");
            }
        }

        // ---- 第三刀：兜底。到这一步说明留下来的内容本身就太大了，
        // 典型情况是单轮里读了个超大文件。此时连最近的工具输出也得裁，
        // 否则会话会永久卡在「压了等于没压」的状态上。 ----
        work = trimOldToolOutputs(work, 0);
        return finish(work, tools, before, summarized, "裁剪全部工具输出");
    }

    private Result finish(List<ChatMessage> messages, List<ToolSpec> tools, int before,
                          boolean summarized, String how) {
        int after = estimate(messages, tools);
        log.info("上下文压缩({}): {} -> {} tokens", how, before, after);
        return new Result(messages, before, after, summarized);
    }

    /**
     * 把较长的工具输出换成一行占位。
     *
     * @param protectRecent 最近这么多条消息不动（它们很可能正是模型当前在用的）；
     *                      传 0 表示全部都裁——兜底时才这么干
     */
    private List<ChatMessage> trimOldToolOutputs(List<ChatMessage> history, int protectRecent) {
        List<ChatMessage> result = new ArrayList<>(history);
        int protectedFrom = Math.max(0, result.size() - protectRecent);

        for (int i = 0; i < protectedFrom; i++) {
            ChatMessage m = result.get(i);
            if (!ChatMessage.ROLE_TOOL.equals(m.role()) || m.content() == null
                    || m.content().length() <= TOOL_OUTPUT_TRIM_THRESHOLD) {
                continue;
            }
            // 留个开头，模型还能记得这次调用大致拿到了什么
            String head = m.content().substring(0, 120).replace('\n', ' ');
            result.set(i, ChatMessage.toolResult(m.toolCallId(),
                    head + "… (较早的工具输出已省略，原长 " + m.content().length() + " 字符)"));
        }
        return result;
    }

    /**
     * 找一个安全的截断位置：只能是 role=user 的消息，它是一轮对话的起点，
     * 从这里切绝不会留下无主的 tool 消息。
     *
     * @return 保留起点的下标；返回 <=1 表示没有可用切点
     */
    private int findSafeCutPoint(List<ChatMessage> history, int budget) {
        List<Integer> userTurns = new ArrayList<>();
        for (int i = 1; i < history.size(); i++) {   // 跳过 index 0 的 system
            if (ChatMessage.ROLE_USER.equals(history.get(i).role())) {
                userTurns.add(i);
            }
        }
        if (userTurns.isEmpty()) {
            return -1;
        }
        // 从最早的切点开始试，取第一个能满足预算的——这样能保留最多的原始对话
        for (int index : userTurns) {
            if (TokenEstimator.estimate(history.subList(index, history.size())) <= budget) {
                return index;
            }
        }
        // 全都超预算，那就只留最后一轮
        return userTurns.get(userTurns.size() - 1);
    }

    /** 让模型把一段历史总结成一条消息；失败就退化成一句说明，绝不让压缩本身把会话搞挂。 */
    private ChatMessage summarize(List<ChatMessage> discarded, LlmClient summarizer) {
        if (summarizer == null) {
            return fallbackSummary(discarded);
        }
        StringBuilder transcript = new StringBuilder();
        for (ChatMessage m : discarded) {
            transcript.append('[').append(m.role()).append("] ");
            if (m.hasToolCalls()) {
                m.toolCallsOrEmpty().forEach(c ->
                        transcript.append("调用 ").append(c.name()).append(' ')
                                .append(c.argumentsJson()).append(' '));
            }
            if (m.content() != null) {
                transcript.append(m.content());
            }
            transcript.append('\n');
        }

        try {
            var response = summarizer.chat(List.of(
                    ChatMessage.system("你负责压缩对话历史。输出要点式摘要，不要寒暄。"),
                    ChatMessage.user("""
                            把下面这段编码助手的工作记录压缩成简洁摘要，必须保留：
                            1. 用户最初的目标和后续追加的要求
                            2. 已经改过哪些文件，改了什么（文件路径必须原样保留）
                            3. 已经查明的关键事实（配置值、报错原因、命令结果等）
                            4. 还没做完的事

                            不要保留寒暄和推理过程，只留结论。

                            ----- 工作记录 -----
                            %s
                            """.formatted(transcript))), null);   // 摘要时不给工具

            String summary = response.text();
            if (summary.isBlank()) {
                return fallbackSummary(discarded);
            }
            return ChatMessage.user("[以下是早先对话的摘要，原文已省略]\n" + summary);

        } catch (LlmException e) {
            log.warn("生成摘要失败，退化为直接丢弃: {}", e.getMessage());
            return fallbackSummary(discarded);
        }
    }

    private ChatMessage fallbackSummary(List<ChatMessage> discarded) {
        return ChatMessage.user("[早先的 " + discarded.size() + " 条对话因超出上下文长度已被省略，"
                + "如需相关信息请重新查看文件或询问用户]");
    }
}
