package dev.wiles.myagent.cli;

import java.io.PrintStream;

/**
 * 原地刷新的工作状态行，例如「⠹ 思考中… 3s」。
 *
 * <h3>它解决什么</h3>
 * 模型响应要等好几秒，工具执行也要时间。这期间屏幕上一个字都不动的话，
 * 用户分不清它是在干活还是卡死了——这是 CLI 工具最常见的体验问题。
 *
 * <h3>为什么要单开一个线程</h3>
 * 主线程正阻塞在网络请求上，没机会更新画面。所以动画由后台线程驱动，
 * 每 100ms 用 \r 回到行首重画一次。
 *
 * <h3>与正文输出的协调</h3>
 * 状态行和正文抢同一块屏幕，谁也不能压着谁。约定很简单：
 * <b>主线程要打印任何东西之前，先 stop() 把状态行擦掉</b>。
 * 这件事由 Repl 统一负责，所以这个类自己不用加锁。
 */
public final class StatusLine {

    /** 盲文点阵做的转圈动画，比 |/-\ 顺滑。 */
    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final long FRAME_INTERVAL_MS = 100;

    private final PrintStream out;
    private final boolean animated;

    private volatile String text = "";
    private volatile boolean running;
    private long startedAt;

    public StatusLine(PrintStream out, boolean animated) {
        this.out = out;
        this.animated = animated;
    }

    /** 开始显示某个状态。重复调用等同于改文字，计时不重来。 */
    public void start(String text) {
        this.text = text;
        if (!animated) {
            return;                       // 非交互环境不画动画，免得日志里全是刷屏字符
        }
        if (running) {
            return;                       // 已经在转了，换个文字就行
        }
        running = true;
        startedAt = System.nanoTime();
        Thread thread = new Thread(this::animate, "myagent-status");
        thread.setDaemon(true);
        thread.start();
    }

    /** 换一句状态文字，计时继续。 */
    public void update(String text) {
        this.text = text;
    }

    /** 停掉动画并把这一行擦干净，光标留在行首，后面可以直接打正文。 */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        out.print(Ansi.clearLine() + Ansi.showCursor());
        out.flush();
    }

    public boolean isRunning() {
        return running;
    }

    private void animate() {
        int frame = 0;
        out.print(Ansi.hideCursor());
        while (running) {
            long seconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
            out.print(Ansi.clearLine()
                    + Ansi.cyan(FRAMES[frame % FRAMES.length]) + " "
                    + Ansi.dim(text)
                    + (seconds > 0 ? Ansi.dim("  " + seconds + "s") : ""));
            out.flush();
            frame++;
            try {
                Thread.sleep(FRAME_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
