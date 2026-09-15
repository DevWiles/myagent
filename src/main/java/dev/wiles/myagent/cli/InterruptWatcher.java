package dev.wiles.myagent.cli;

import java.io.InputStream;

/**
 * agent 干活期间盯着 Ctrl-C。
 *
 * <h3>为什么需要一个专门的线程</h3>
 * 主线程这会儿正阻塞在 agent.run() 里等模型响应，根本没机会读键盘。
 * 而我们又用 -isig 关掉了终端的信号处理，Ctrl-C 不会变成 SIGINT，
 * 也就没有任何人来打断它——用户只能眼睁睁等着，或者关掉终端窗口。
 * 所以得另起一个线程专门读输入，读到 0x03 就触发取消。
 *
 * <h3>为什么用轮询而不是直接 read()</h3>
 * System.in.read() 阻塞后没法中断，任务结束时这个线程就停不下来，
 * 还会把用户接下来敲的第一个字符吃掉。available() 轮询虽然土，但收得干净。
 *
 * <h3>只能在 raw 模式下用</h3>
 * 非交互环境（管道、重定向）里 available() 总是大于 0，
 * 这个线程会把后续要喂给 REPL 的输入全部吞掉。
 */
public final class InterruptWatcher implements AutoCloseable {

    private static final int CTRL_C = 3;
    private static final long POLL_INTERVAL_MS = 50;

    private final InputStream in;
    private final Runnable onInterrupt;
    private volatile boolean running;

    public InterruptWatcher(InputStream in, Runnable onInterrupt) {
        this.in = in;
        this.onInterrupt = onInterrupt;
    }

    public void start() {
        running = true;
        Thread thread = new Thread(this::watch, "myagent-interrupt-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void watch() {
        try {
            while (running) {
                if (in.available() > 0) {
                    int b = in.read();
                    if (b == CTRL_C) {
                        onInterrupt.run();
                    }
                    // 其它按键直接丢弃：任务跑着的时候敲别的没有意义
                } else {
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 读不了就安静退出，不能让这个后台线程搅黄主流程
        }
    }

    @Override
    public void close() {
        running = false;
    }
}
