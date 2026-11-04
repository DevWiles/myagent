package dev.wiles.myagent.context;

/**
 * 拼在用户那句话前面、<b>给模型看不给人看</b>的一段说明，照 Claude Code 的 {@code <system-reminder>}。
 *
 * <h3>为什么拼进用户消息，而不是改系统提示词</h3>
 * 系统提示词是 history 的第 0 条，改它等于改已经发生过的历史，前缀缓存也跟着全废。
 * 而这几样东西只跟「这一句话」有关：此刻是不是 plan 模式、hook 补了什么上下文、
 * 用户用 {@code /名字} 点了哪份技能。跟着这条消息走，它们就自然落在对话里的正确位置上。
 *
 * <h3>为什么要有统一的包法</h3>
 * 会话恢复时要把对话原样摊回屏幕（{@code Protocol.encodeTranscript}），
 * 这几段不是用户说的话，得能认出来剥掉。各写各的标签，剥的那边就得认一串标签，迟早漏一个。
 */
public final class SystemReminder {

    public static final String OPEN = "<system-reminder>";
    public static final String CLOSE = "</system-reminder>";

    private SystemReminder() {
    }

    public static String wrap(String text) {
        return OPEN + "\n" + text.strip() + "\n" + CLOSE;
    }

    /** 把一段说明拼到用户消息前面。说明是空的就原样返回。 */
    public static String prepend(String reminder, String message) {
        if (reminder == null || reminder.isBlank()) {
            return message;
        }
        return wrap(reminder) + "\n\n" + message;
    }

    /** 剥掉开头所有的说明块，剩下用户真正说的那句。 */
    public static String strip(String content) {
        String rest = content;
        while (rest.startsWith(OPEN)) {
            int end = rest.indexOf(CLOSE);
            if (end < 0) {
                break;
            }
            rest = rest.substring(end + CLOSE.length()).stripLeading();
        }
        return rest;
    }
}
