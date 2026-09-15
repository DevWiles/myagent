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
 * 状态行和正文抢同一块屏幕，谁也不能压着压。约定是：
 * <b>主线程要打印任何东西之前，先 stop() 把状态行擦掉</b>。
 *
 * 这里有个必须处理的竞态：光把标志位设成 false 是不够的，
 * 动画线程可能正卡在「检查完 running」和「执行 print」之间——
 * 于是主线程刚打印出来的正文，会被随后醒来的动画线程一个 clearLine 抹掉。
 * 每 100ms 一次，屏幕上就什么都留不下。
 * 所以 stop() 必须 <b>中断并等待动画线程真正退出</b> 之后才能返回。
 */
public final class StatusLine {

    /** 盲文点阵做的转圈动画，比 |/-\ 顺滑。 */
    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final long FRAME_INTERVAL_MS = 100;

    private final PrintStream out;
    private final boolean animated;

    private volatile String text = "";
    private volatile boolean running;
    private volatile Thread thread;
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
        Thread worker = new Thread(this::animate, "myagent-status");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    /** 换一句状态文字，计时继续。 */
    public void update(String text) {
        this.text = text;
    }

    /**
     * 停掉动画并把这一行擦干净，光标留在行首，后面可以直接打正文。
     *
     * 必须等动画线程真的退出再返回：否则它可能在主线程打印正文之后
     * 才醒过来，一个 clearLine 就把正文抹掉了。
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        Thread worker = thread;
        if (worker != null) {
            worker.interrupt();            // 把它从 sleep 里叫醒，不用干等 100ms
            try {
                worker.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        out.print(Ansi.clearLine());
        out.flush();
    }

    public boolean isRunning() {
        return running;
    }

    private void animate() {
        int frame = 0;
        // 不再发「隐藏光标」。它本来只是想让转圈时光标别跟着跳，
        // 但 macOS Terminal.app 在光标不可见时会画自己的光标位置指示器
        // （行首行尾一对方括号），反而多出一堆看不懂的字符。
        // 光标跟着 spinner 走其实没什么不好，大多数命令行工具也是这样。
        while (running) {
            // 先画再睡。反过来的话，100ms 内就结束的任务会一帧都画不出来，
            // 用户按完回车对着空屏幕，只能猜程序是不是死了。
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
