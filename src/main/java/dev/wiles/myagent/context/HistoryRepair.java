package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把一段可能残缺的对话历史修成<b>能安全发给模型</b>的形状。
 *
 * <h3>残缺是怎么来的</h3>
 * {@code Agent.run()} 里有 {@code rollbackTo}，正常路径下历史永远自洽。
 * 但存档是<b>每轮结束存一次</b>，而进程可能死在任何时刻——
 * {@code kill -9}、断电、终端被关。盘上留下的很可能是
 * 「assistant 说要调三个工具，只回来两个结果」这种半截状态。
 *
 * <h3>为什么这种残缺特别致命</h3>
 * OpenAI 系协议有一条硬约束：每条 {@code role=tool} 必须对应前面某条
 * assistant 消息里的一个 {@code tool_call}，一一对上。对不上就是 400。
 *
 * <p>而它的破坏力在于<b>不可自愈</b>：残缺的消息留在历史里，之后<b>每一次</b>
 * 请求都会带上它，于是每一次都 400。用户看到的现象是「恢复完就再也说不了话了」，
 * 而且重启也没用——存档还在，一恢复又坏。
 *
 * <h3>切在哪里</h3>
 * 只切在<b>不带 tool_calls 的 assistant 消息之后</b>，也就是模型把话说完、
 * 一轮彻底结束的地方。这比「切在 user 消息上」（{@link ContextManager#compact} 用的那条）
 * 更保守，但恢复场景值得保守：压缩是在活跃会话里动刀，切完马上就要用；
 * 恢复是把一段放了不知道多久的历史重新立起来，宁可少留几条，
 * 也不能留下一个每次请求都炸的定时炸弹。
 */
public final class HistoryRepair {

    private static final Logger log = LoggerFactory.getLogger(HistoryRepair.class);

    private HistoryRepair() {
    }

    /**
     * 返回最长的一段自洽前缀。
     *
     * <p>传入的消息<b>不含 system</b>——system 由调用方现场生成，
     * 见 {@code SessionSnapshot} 里关于提示词漂移的那段说明。
     *
     * @return 新的 list，原 list 不动。整段都不可用时返回空 list
     */
    public static List<ChatMessage> repair(List<ChatMessage> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return List.of();
        }

        Set<String> pending = new HashSet<>();   // 已经发出、还没等到结果的 tool_call id
        int safeEnd = 0;                          // 最后一个「一轮彻底结束」的位置（独占）

        for (int i = 0; i < conversation.size(); i++) {
            ChatMessage m = conversation.get(i);
            if (m == null || m.role() == null) {
                break;   // 反序列化出了怪东西，到此为止
            }

            switch (m.role()) {
                case ChatMessage.ROLE_TOOL -> {
                    // 无主的 tool 消息——它前面那条 assistant 没了，或者 id 对不上。
                    // 再往后的内容全都建立在这条之上，一并不要
                    if (m.toolCallId() == null || !pending.remove(m.toolCallId())) {
                        log.debug("第 {} 条是无主的 tool 消息，从这里截断", i);
                        return copy(conversation, safeEnd);
                    }
                }
                case ChatMessage.ROLE_ASSISTANT -> {
                    if (!pending.isEmpty()) {
                        // 上一轮点的工具还没全回来就又说话了，这段不完整
                        log.debug("第 {} 条 assistant 之前还有 {} 个工具结果没回来，截断", i, pending.size());
                        return copy(conversation, safeEnd);
                    }
                    if (m.hasToolCalls()) {
                        for (ToolCall call : m.toolCallsOrEmpty()) {
                            pending.add(call.id());
                        }
                    } else {
                        safeEnd = i + 1;   // 模型把话说完了，这里是干净的切点
                    }
                }
                case ChatMessage.ROLE_USER -> {
                    if (!pending.isEmpty()) {
                        log.debug("第 {} 条 user 之前还有工具结果没回来，截断", i);
                        return copy(conversation, safeEnd);
                    }
                }
                default -> {
                    // 历史里不该再出现 system；出现了说明存档来路不对，就地停住
                    log.debug("第 {} 条角色是 {}，不该出现在对话里，截断", i, m.role());
                    return copy(conversation, safeEnd);
                }
            }
        }

        if (!pending.isEmpty()) {
            // 存档正好停在「工具点了、结果没回来」的当口——最常见的 kill -9 现场
            log.debug("末尾有 {} 个工具结果没回来，回退到上一轮结束处", pending.size());
            return copy(conversation, safeEnd);
        }
        // 全程自洽，但末尾可能停在工具结果上（一轮没说完），同样退到上一轮结束
        return copy(conversation, safeEnd);
    }

    /** 这段历史本来就是自洽的吗——修完还是原样，就说明没动过刀。 */
    public static boolean isIntact(List<ChatMessage> conversation) {
        return repair(conversation).size() == (conversation == null ? 0 : conversation.size());
    }

    private static List<ChatMessage> copy(List<ChatMessage> source, int end) {
        return end <= 0 ? List.of() : new ArrayList<>(source.subList(0, end));
    }
}
