package dev.wiles.myagent.cli;

import dev.wiles.myagent.context.SessionSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * 把这个工作区的会话历史画成一张能挑的列表。
 *
 * <p>纯函数、不打印，和 {@link PlanView} 同一个理由：终端渲染最容易在窄屏和长标题上
 * 出问题，那两种情况用断言比肉眼盯可靠。
 *
 * <h3>为什么每行要有轮数和时间</h3>
 * 挑错会话的代价是<b>拿着过时的上下文继续往下做</b>——模型会拿三天前的结论
 * 去改今天的代码，而且语气一样笃定。首句告诉用户「这段在聊什么」，
 * 轮数告诉他「聊得深不深」，时间告诉他「还新鲜吗」。三样都是为了少挑错。
 *
 * <h3>为什么只列前 {@value #MAX_ROWS} 段</h3>
 * 再多就要翻页，翻页要接方向键、要处理光标——那是另一套东西。
 * 但被砍掉的部分<b>必须说出来</b>：列表只显示 10 段却不提一句，
 * 用户会以为更早的会话根本不存在。
 */
public final class SessionList {

    /** 一次最多让用户在几段里挑。 */
    public static final int MAX_ROWS = 10;

    /** 标题至少留这么宽，窄屏上也得剩下点能认的东西。 */
    private static final int MIN_TITLE = 12;

    private SessionList() {
    }

    /**
     * 会话列表。
     *
     * @param currentId 此刻正待在哪一段里（可以是 null）。标出来，
     *                  否则用户会挑中自己现在就在的那一段，然后发现「什么都没变」
     */
    public static List<String> render(List<SessionSummary> sessions, String currentId,
                                      int width, long now) {
        List<String> lines = new ArrayList<>();
        if (sessions == null || sessions.isEmpty()) {
            return lines;
        }
        int shown = Math.min(sessions.size(), MAX_ROWS);

        // 元信息（轮数 + 时间）宽度不定，先量一遍，剩下的宽度才是标题的
        List<String> metas = new ArrayList<>();
        int metaWidth = 0;
        for (int i = 0; i < shown; i++) {
            SessionSummary s = sessions.get(i);
            String meta = s.turns() + " 轮 · " + Moment.describe(s.savedAt(), now);
            metas.add(meta);
            metaWidth = Math.max(metaWidth, Ansi.displayWidth(meta));
        }
        int titleWidth = Math.max(MIN_TITLE, width - metaWidth - 12);

        lines.add("");
        lines.add(Ansi.bold("会话历史") + Ansi.dim("  共 " + sessions.size() + " 段"));
        for (int i = 0; i < shown; i++) {
            SessionSummary s = sessions.get(i);
            String title = pad(clip(s.title().isBlank() ? "(没有内容)" : s.title(), titleWidth),
                    titleWidth);
            String row = "  " + Ansi.cyan(String.format("%2d", i + 1)) + "  " + title
                    + "  " + Ansi.dim(metas.get(i));
            if (s.id() != null && s.id().equals(currentId)) {
                row += Ansi.dim("  ← 当前");
            }
            lines.add(row);
        }
        if (sessions.size() > shown) {
            lines.add(Ansi.dim("     …还有 " + (sessions.size() - shown)
                    + " 段更早的，这里挑不到"));
        }
        lines.add("");
        lines.add(Ansi.dim("  输入编号接着聊，直接回车取消"));
        return lines;
    }

    /**
     * 把用户敲的东西翻译成 0 起的下标。认不出来、或者不在 {@code 1..count} 里就返回空。
     *
     * <p>越界的编号当认不出来处理，不做「就近取一个」：用户敲 7 而列表只有 3 段时，
     * 悄悄给他第 3 段，等于替他做了一个他没做的选择。
     */
    public static OptionalInt pick(String answer, int count) {
        if (answer == null || count <= 0) {
            return OptionalInt.empty();
        }
        String text = answer.strip();
        if (text.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int n = Integer.parseInt(text);
            return n >= 1 && n <= count ? OptionalInt.of(n - 1) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** 用户是不是直接回车（取消），而不是敲错了。两者要给不同的反馈。 */
    public static boolean isCancel(String answer) {
        return answer == null || answer.strip().isEmpty();
    }

    /**
     * 按<b>列宽</b>截断，不是按字符数。
     *
     * <p>标题多半是中文，一个字占两列。按字符数算的话这一列会宽出去一截，
     * 后面的轮数和时间就对不齐了——而列表对不齐的时候，人是真的会挑错行。
     */
    private static String clip(String text, int max) {
        String oneLine = text.strip().replace('\n', ' ');
        if (Ansi.displayWidth(oneLine) <= max) {
            return oneLine;
        }
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (char c : oneLine.toCharArray()) {
            int columns = Ansi.charWidth(c);
            if (used + columns > max - 1) {
                break;
            }
            kept.append(c);
            used += columns;
        }
        return kept + "…";
    }

    /** 右边补空格到指定列宽。 */
    private static String pad(String text, int width) {
        return text + " ".repeat(Math.max(0, width - Ansi.displayWidth(text)));
    }
}
