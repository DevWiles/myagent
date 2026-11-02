package dev.wiles.myagent.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真起一个子 JVM，看它是不是跟着 stdin 一起走。
 *
 * <p>父进程被硬杀时，系统做的事就是关掉子进程 stdin 的写端——和这里手动 close 是同一件事。
 */
class ParentWatchProcessTest {

    /** 子进程入口：只挂 ParentWatch，然后一直睡。 */
    public static void main(String[] args) throws InterruptedException {
        ParentWatch.start(System.in, () -> System.exit(0));
        Thread.sleep(Long.MAX_VALUE);
    }

    private static Process child() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ParentWatchProcessTest.class.getName())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @Test
    @DisplayName("stdin 一关，子进程自己退出，退出码 0")
    void exitsWhenStdinCloses() throws Exception {
        Process p = child();
        try {
            assertFalse(p.waitFor(1, TimeUnit.SECONDS), "管道还开着，不该退");
            p.getOutputStream().close();
            assertTrue(p.waitFor(10, TimeUnit.SECONDS), "stdin 关了却没退——会留下孤儿后端");
            assertEquals(0, p.exitValue());
        } finally {
            p.destroyForcibly();
        }
    }
}
