package dev.wiles.myagent.cli;

import java.io.PrintStream;
import java.util.List;

/**
 * 全屏模式：占住整个终端，自己画每一行。
 *
 * <h3>和顺序打印模式的区别</h3>
 * 顺序打印模式把内容交给终端，滚动、选中、历史都白捡终端原生的，
 * 代价是打出去的内容就管不着了——没法定位、没法原地改。
 * 全屏模式反过来：进了备用屏幕，屏幕归程序独占，想画哪一行画哪一行，
 * 代价是<b>滚动得自己实现</b>（备用屏幕没有滚动缓冲区）。
 *
 * <h3>滚动怎么拿到的</h3>
 * 靠 Alt Scroll（{@code CSI ? 1007 h}）：备用屏幕激活且<b>鼠标上报关闭</b>时，
 * 终端把滚轮翻译成方向键发给程序。关键就在「鼠标上报关闭」——
 * 鼠标还归终端管，所以拖拽选中仍然是原生的，不用按 Option。
 * 一旦开了鼠标上报去抢点击事件，滚动和选中就一起赔进去了。
 *
 * <h3>屏幕怎么分</h3>
 * <pre>
 *   1 .. H-4   内容区（从缓冲区里按滚动位置取一段）
 *   H-3        上横线
 *   H-2        输入行
 *   H-1        下横线
 *   H          状态栏
 * </pre>
 *
 * 已知限制：内容行超过终端宽度会被截断，而不是软换行。
 */
public final class FullScreenUi {

    /** 底部固定占用的行数：两条横线 + 输入行 + 状态栏。 */
    private static final int CHROME_HEIGHT = 4;

    private final PrintStream out;
    private final ScreenBuffer buffer = new ScreenBuffer();

    private int width;
    private int height;
    private String status = "";
    private boolean active;

    public FullScreenUi(PrintStream out) {
        this.out = out;
        refreshSize();
    }

    public void refreshSize() {
        this.width = TerminalMode.width();
        this.height = Math.max(CHROME_HEIGHT + 2, TerminalMode.height());
    }

    public void enter() {
        active = true;
        out.print(Ansi.enterFullScreen() + Ansi.clearScreen());
        out.flush();
    }

    public void exit() {
        if (!active) {
            return;
        }
        active = false;
        out.print(Ansi.exitFullScreen());
        out.flush();
    }

    public boolean isActive() {
        return active;
    }

    public ScreenBuffer buffer() {
        return buffer;
    }

    /** 内容区有多少行。 */
    public int viewportHeight() {
        return Math.max(1, height - CHROME_HEIGHT);
    }

    public void append(String line) {
        buffer.append(line);
    }

    public void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    public void scroll(int delta) {
        buffer.scroll(delta, viewportHeight());
    }

    /**
     * 把整屏重画一遍。
     *
     * 每一行都先清到行尾再写，不能只写新内容——上一次画的残留会留在右边。
     * 整屏重画看着浪费，但一屏也就几十行，比起算清楚「哪几行变了」要可靠得多。
     */
    public void render(String prompt, String input, int cursorIndex) {
        StringBuilder sb = new StringBuilder();
        int viewport = viewportHeight();
        List<String> visible = buffer.visible(viewport);

        for (int i = 0; i < viewport; i++) {
            sb.append(Ansi.moveTo(i + 1, 1)).append(Ansi.clearToLineEnd());
            if (i < visible.size()) {
                sb.append(fit(visible.get(i)));
            }
        }

        int base = height - CHROME_HEIGHT + 1;
        String rule = Ansi.dim("─".repeat(Math.max(10, width)));
        sb.append(Ansi.moveTo(base, 1)).append(Ansi.clearToLineEnd()).append(rule);
        sb.append(Ansi.moveTo(base + 1, 1)).append(Ansi.clearToLineEnd())
                .append(Ansi.cyan(prompt)).append(input);
        sb.append(Ansi.moveTo(base + 2, 1)).append(Ansi.clearToLineEnd()).append(rule);
        sb.append(Ansi.moveTo(base + 3, 1)).append(Ansi.clearToLineEnd()).append(statusLine());

        // 光标落到输入行里该在的列上（汉字占两列，得按显示宽度算）
        int column = 1 + Ansi.displayWidth(prompt)
                + Ansi.displayWidth(input.substring(0, Math.min(cursorIndex, input.length())));
        sb.append(Ansi.moveTo(base + 1, column));

        out.print(sb);
        out.flush();
    }

    /** 不在跟随最新内容时，状态栏要提示一下，否则用户以为程序卡住了。 */
    private String statusLine() {
        if (buffer.followingTail()) {
            return status;
        }
        return status + Ansi.yellow("   ↑ 正在看历史 " + buffer.scrollPercent(viewportHeight())
                + "%（滚到底或按 End 回到最新）");
    }

    /** 按显示宽度截断，并补一个重置——截断可能把颜色序列切断，不补的话颜色会漏到后面。 */
    private String fit(String line) {
        if (Ansi.displayWidth(stripAnsi(line)) <= width) {
            return line;
        }
        StringBuilder kept = new StringBuilder();
        int used = 0;
        boolean inEscape = false;
        for (char c : line.toCharArray()) {
            if (c == Ansi.ESC) {
                inEscape = true;
            }
            if (inEscape) {
                kept.append(c);
                if (Character.isLetter(c)) {
                    inEscape = false;   // 转义序列以字母收尾
                }
                continue;
            }
            int columns = Ansi.charWidth(c);
            if (used + columns > width - 1) {
                break;
            }
            kept.append(c);
            used += columns;
        }
        return kept + "…" + (Ansi.isEnabled() ? Ansi.ESC + "[0m" : "");
    }

    /** 去掉转义序列，只留真正占宽度的字符。 */
    private static String stripAnsi(String text) {
        return text.replaceAll("\\x1b\\[[0-9;?]*[A-Za-z]", "");
    }
}
