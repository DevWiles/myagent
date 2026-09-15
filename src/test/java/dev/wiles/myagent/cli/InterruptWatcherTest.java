package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InterruptWatcherTest {

    private static final int CTRL_C = 3;

    @Test
    @DisplayName("读到 Ctrl-C 就触发回调")
    @Timeout(5)
    void firesOnCtrlC() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        InputStream in = new ByteArrayInputStream(new byte[]{CTRL_C});

        try (InterruptWatcher watcher = new InterruptWatcher(in, fired::countDown)) {
            watcher.start();
            assertTrue(fired.await(3, TimeUnit.SECONDS), "Ctrl-C 没有触发中断");
        }
    }

    @Test
    @DisplayName("其它按键不触发——任务跑着的时候敲别的没有意义")
    @Timeout(5)
    void ignoresOtherKeys() throws Exception {
        AtomicInteger count = new AtomicInteger();
        InputStream in = new ByteArrayInputStream("abc\n".getBytes());

        try (InterruptWatcher watcher = new InterruptWatcher(in, count::incrementAndGet)) {
            watcher.start();
            Thread.sleep(300);
        }
        assertEquals(0, count.get());
    }

    @Test
    @DisplayName("close 之后不再触发，也不会卡住调用方")
    @Timeout(5)
    void stopsCleanly() throws Exception {
        AtomicInteger count = new AtomicInteger();
        // 永远读不到东西的流，用来模拟用户没有按键
        InputStream idle = new InputStream() {
            public int read() {
                return -1;
            }

            public int available() {
                return 0;
            }
        };

        InterruptWatcher watcher = new InterruptWatcher(idle, count::incrementAndGet);
        watcher.start();
        Thread.sleep(150);
        watcher.close();          // 卡在这里就说明后台线程停不下来

        assertEquals(0, count.get());
    }

    @Test
    @DisplayName("连按多次 Ctrl-C 每次都触发")
    @Timeout(5)
    void firesRepeatedly() throws Exception {
        CountDownLatch fired = new CountDownLatch(3);
        InputStream in = new ByteArrayInputStream(new byte[]{CTRL_C, CTRL_C, CTRL_C});

        try (InterruptWatcher watcher = new InterruptWatcher(in, fired::countDown)) {
            watcher.start();
            assertTrue(fired.await(3, TimeUnit.SECONDS));
        }
    }
}
