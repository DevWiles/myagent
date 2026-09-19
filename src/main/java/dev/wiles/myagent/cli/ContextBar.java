package dev.wiles.myagent.cli;

/**
 * 输入框下面那行上下文用量指示。
 *
 * 为什么值得占一行：上下文用满会触发压缩，压缩意味着早期对话被摘要掉、细节永久丢失。
 * 用户有权提前看到这件事要发生了，而不是等模型突然"想不起来"十分钟前说过的话才发现。
 */
public final class ContextBar {

    private static final int BAR_WIDTH = 10;
    private static final char FILLED = '█';
    private static final char EMPTY = '░';

    /** 超过这个比例开始变黄，提醒用户快满了。 */
    private static final int WARN_PERCENT = 50;
    /** 超过这个比例变红，压缩随时会发生。 */
    private static final int DANGER_PERCENT = 80;

    private ContextBar() {
    }

    /**
     * @param sentTokens 这段会话<b>累计真正发出去</b>的提示词量。
     *                   它和 {@code usedTokens} 不是一回事，而且通常大得多：
     *                   前者是「这一份窗口有多大」的瞬时快照，后者是把窗口
     *                   重发了几十遍之后的总和。不并排摆出来的话，
     *                   用户会拿进度条那个数当成花销，而真实数字可能是它的十倍。
     */
    public static String render(int usedTokens, int limitTokens, int messageCount, String model,
                                long sentTokens) {
        int percent = limitTokens <= 0 ? 0
                : Math.min(100, (int) Math.round(usedTokens * 100.0 / limitTokens));
        int filled = Math.min(BAR_WIDTH, (int) Math.round(percent / 100.0 * BAR_WIDTH));

        String bar = String.valueOf(FILLED).repeat(filled)
                + String.valueOf(EMPTY).repeat(BAR_WIDTH - filled);

        String sent = sentTokens <= 0 ? ""
                : "  ·  本会话已发 " + formatTokens(sentTokens);

        return "  " + Ansi.dim("上下文 ") + colorize(bar, percent) + " "
                + colorize(percent + "%", percent)
                + Ansi.dim("  ·  " + formatTokens(usedTokens) + sent
                + "  ·  " + messageCount + " 条  ·  " + model);
    }

    private static String colorize(String text, int percent) {
        if (percent >= DANGER_PERCENT) {
            return Ansi.red(text);
        }
        return percent >= WARN_PERCENT ? Ansi.yellow(text) : Ansi.green(text);
    }

    /** 上万的数字写成 12.3k，一行里塞得下也更好读。 */
    public static String formatTokens(long tokens) {
        return tokens < 1000 ? String.valueOf(tokens)
                : String.format("%.1fk", tokens / 1000.0);
    }
}
