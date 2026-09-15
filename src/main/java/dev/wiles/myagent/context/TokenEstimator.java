package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;

import java.util.List;

/**
 * token 数量估算。
 *
 * 为什么是「估算」而不是精确计算：真正的分词要用模型自己的 tokenizer，
 * DeepSeek 没有开放 Java 版，而为了数个数去引一个几十 MB 的分词库不划算。
 * 官方文档给的经验值是：1 个中文字符 ≈ 0.6 token，1 个英文字符 ≈ 0.3 token。
 *
 * 估算偏差在 10%~20% 是正常的，所以触发压缩的阈值要留足余量（默认 75%），
 * 别卡在 99% 才动手——那时候估错一点就直接撞上限了。
 */
public final class TokenEstimator {

    private static final double CJK_RATE = 0.6;
    private static final double OTHER_RATE = 0.3;
    /** 每条消息的固定开销：角色标记、分隔符等协议结构。 */
    private static final int MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return (int) Math.ceil(cjk * CJK_RATE + other * OTHER_RATE);
    }

    public static int estimate(ChatMessage message) {
        int total = MESSAGE_OVERHEAD + estimate(message.content());
        if (message.toolCallId() != null) {
            total += estimate(message.toolCallId());
        }
        for (ToolCall call : message.toolCallsOrEmpty()) {
            // 工具调用本身也要占位置：名字 + 那串参数 JSON
            total += MESSAGE_OVERHEAD + estimate(call.name()) + estimate(call.argumentsJson());
        }
        return total;
    }

    public static int estimate(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage m : messages) {
            total += estimate(m);
        }
        return total;
    }

    /**
     * 工具菜单同样按 token 计费，而且**每一轮都要重发一遍**。
     * 算上下文预算时漏掉它，是很容易犯的错。
     */
    public static int estimateTools(List<ToolSpec> tools) {
        if (tools == null) {
            return 0;
        }
        int total = 0;
        for (ToolSpec spec : tools) {
            total += MESSAGE_OVERHEAD
                    + estimate(spec.function().name())
                    + estimate(spec.function().description())
                    + estimate(String.valueOf(spec.function().parameters()));
        }
        return total;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x3000 && c <= 0x303F)  // 中文标点
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角字符
    }
}
