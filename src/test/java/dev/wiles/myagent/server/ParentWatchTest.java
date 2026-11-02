package dev.wiles.myagent.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「界面死了后端跟着退」只靠一件事：stdin 读到 EOF。真进程硬杀的那一遍见
 * {@code ParentWatchProcessTest}，这里只钉住读的规则。
 */
class ParentWatchTest {

    @Test
    @DisplayName("管道没关就一直等，写进来的东西照单丢掉；关了才通知")
    void firesOnlyOnEof() throws Exception {
        PipedOutputStream parent = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(parent);
        CountDownLatch gone = new CountDownLatch(1);

        ParentWatch.start(stdin, gone::countDown);
        parent.write("界面不该写，写了也别当成退出".getBytes());
        parent.flush();
        assertFalse(gone.await(300, TimeUnit.MILLISECONDS), "有数据不是 EOF，不能退");

        parent.close();
        assertTrue(gone.await(2, TimeUnit.SECONDS), "管道关了就该通知");
    }

    @Test
    @DisplayName("读出错也按界面没了处理——卡在一个坏掉的 stdin 上比退出更糟")
    void readErrorCountsAsGone() throws Exception {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("句柄失效");
            }
        };
        CountDownLatch gone = new CountDownLatch(1);

        ParentWatch.start(broken, gone::countDown);

        assertTrue(gone.await(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("守护线程：不能因为它挂着让 JVM 退不掉")
    void isDaemon() throws Exception {
        PipedInputStream stdin = new PipedInputStream(new PipedOutputStream());
        Thread t = ParentWatch.start(stdin, () -> {
        });
        assertTrue(t.isDaemon());
    }
}
