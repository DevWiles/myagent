package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把会话记录投影成<b>这一次请求真正要发出去的那串消息</b>。
 *
 * <h3>记录和窗口是两样东西</h3>
 * <pre>
 *   记录（append-only，永不压缩）      窗口（每次请求现场组装）
 *   ─────────────────────────         ──────────────────────────
 *   messages: 全量原文          ──→    system（现场生成，带最新记忆索引）
 *   segments: 已落地的摘要段          + 钉住块（原始目标 + 改过的文件）
 *                                     + 摘要块（reduce(segments)）
 *                                     + 最近 3 轮原样
 * </pre>
 *
 * 旧实现把这两者做成了同一个 list：压缩直接重写历史，存盘存的也是压缩后的版本。
 * 后果是原文不可追回，而且摘要本身是 {@code role=user}，下次压缩时会被当原文<b>再压一遍</b>。
 *
 * <h3>这个类里没有一次 API 调用</h3>
 * 贵的那步（把原文摘成文字）在 {@link ContextManager}，这里只负责排版。
 * 分开是为了让它<b>能被单独断言</b>——测「最近三轮原样」「结果里没有无主的 tool 消息」
 * 不该需要先起一个模型。和 {@code MemoryIndex} 从 {@code MemoryStore} 里拆出来是同一个理由。
 *
 * <h3>为什么这里不调 {@link HistoryRepair#repair}</h3>
 * 很容易想当然地在组装时「顺手修一下」，那是错的：ReAct 循环中间发请求时，
 * 记录末尾正是刚回填的 tool 结果，而 {@code repair} 会把这一轮当成残缺<b>整个截掉</b>。
 * 记录的自洽性由写入侧保证（正常路径天然自洽，异常路径用
 * {@link HistoryRepair#seal} 封口），窗口这边只要不切坏就行。
 */
public final class ContextWindow {

    /** 最近这么多轮原样保留，不摘要、不裁剪。 */
    public static final int KEEP_RECENT_TURNS = 3;

    /** 短于这个长度的工具输出没有裁剪价值。 */
    static final int TOOL_OUTPUT_TRIM_THRESHOLD = 300;
    /** 裁剪后留这么长的开头，让模型还记得这次调用大致拿到了什么。 */
    static final int TOOL_OUTPUT_HEAD = 120;

    private ContextWindow() {
    }

    /**
     * 最近 {@code turns} 轮的起点下标。
     *
     * <p>「一轮」从一条 {@code role=user} 开始，到下一条 {@code role=user} 之前结束。
     * 从 user 消息切是<b>唯一安全</b>的切法：每条 {@code role=tool} 都必须紧跟在那条带
     * {@code tool_calls} 的 assistant 后面，从中间随便截会留下无主的 tool 消息，
     * 而那种残缺会让之后<b>每一次</b>请求都 400。
     *
     * @return 记录里的下标；不足 {@code turns} 轮时返回 0（全留）
     */
    public static int startOfLastTurns(List<ChatMessage> record, int turns) {
        if (record == null || record.isEmpty() || turns <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = record.size() - 1; i >= 0; i--) {
            if (ChatMessage.ROLE_USER.equals(record.get(i).role())) {
                seen++;
                if (seen == turns) {
                    return i;
                }
            }
        }
        return 0;   // 不够 turns 轮，全留
    }

    /**
     * 组装。{@code pinned} / {@code summary} 传 null 表示这一块整个略过。
     *
     * @param system  第 0 条，永远在
     * @param pinned  钉住块，见 {@link PinnedFacts#render()}
     * @param summary 摘要块，reduce 的产物
     * @param tail    最近几轮的原文，必须从一条 user 消息开始
     */
    public static List<ChatMessage> assemble(ChatMessage system, ChatMessage pinned,
                                             ChatMessage summary, List<ChatMessage> tail) {
        List<ChatMessage> window = new ArrayList<>();
        if (system != null) {
            window.add(system);
        }
        if (pinned != null) {
            window.add(pinned);
        }
        if (summary != null) {
            window.add(summary);
        }
        if (tail != null) {
            window.addAll(tail);
        }
        return window;
    }

    /** 摘要块的排版。前缀是给模型看的说明，别让它把摘要误当成用户说的话。 */
    public static ChatMessage summaryMessage(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return ChatMessage.user("[以下是早先对话的摘要，原文已省略]\n" + text.strip());
    }

    /**
     * 把 {@code [0, protectFrom)} 区间里较长的工具输出换成一行占位。
     *
     * <p>这是降级阶梯用的，不是常规路径：正常情况下最近三轮原样发出去。
     * 只有摘要块已经落地、窗口还是装不下时，才一级一级往回裁。
     *
     * @param protectFrom 这个下标起的消息不动；传 {@code messages.size()} 等于什么都不裁
     */
    public static List<ChatMessage> trimToolOutputs(List<ChatMessage> messages, int protectFrom) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> result = new ArrayList<>(messages);
        int end = Math.min(Math.max(protectFrom, 0), result.size());

        for (int i = 0; i < end; i++) {
            ChatMessage m = result.get(i);
            if (!ChatMessage.ROLE_TOOL.equals(m.role()) || m.content() == null
                    || m.content().length() <= TOOL_OUTPUT_TRIM_THRESHOLD) {
                continue;
            }
            String head = m.content().substring(0, TOOL_OUTPUT_HEAD).replace('\n', ' ');
            result.set(i, ChatMessage.toolResult(m.toolCallId(),
                    head + "… (较早的工具输出已省略，原长 " + m.content().length() + " 字符)"));
        }
        return result;
    }

    /**
     * 窗口能不能安全发出去——每条 {@code role=tool} 都对得上前面某个 {@code tool_call}。
     *
     * <p>只用于断言和兜底检查。第 0 条 system 不参与，{@link HistoryRepair} 见到
     * 意料之外的角色会就地停住。
     */
    public static boolean isSound(List<ChatMessage> window) {
        if (window == null || window.isEmpty()) {
            return true;
        }
        int from = ChatMessage.ROLE_SYSTEM.equals(window.get(0).role()) ? 1 : 0;
        return HistoryRepair.isIntact(window.subList(from, window.size()));
    }
}
