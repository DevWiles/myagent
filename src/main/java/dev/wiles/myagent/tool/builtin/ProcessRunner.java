package dev.wiles.myagent.tool.builtin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 跑一个子进程、带超时、收全部输出。{@link BashTool} 和 {@link RunTestsTool} 共用。
 *
 * <p>两条纪律都是踩过坑的：输出必须用独立线程读（先 waitFor 再读会在管道写满时死锁，
 * 主线程同步读到 EOF 又会让超时形同虚设）；超时要 {@code destroyForcibly}，
 * 不杀掉的话进程会留在后台。
 */
final class ProcessRunner {

    /** @param finished 在时限内结束了；没结束的话 exitCode 无意义、output 是已收到的部分 */
    record Outcome(boolean finished, int exitCode, String output, boolean interrupted) {
    }

    private ProcessRunner() {
    }

    static Outcome run(List<String> argv, Path dir, int timeoutSeconds) throws IOException {
        Process process = new ProcessBuilder(argv)
                .directory(dir.toFile())
                .redirectErrorStream(true)      // stderr 并入 stdout，模型要看完整报错
                .start();
        try {
            process.getOutputStream().close();  // 等输入的命令立刻读到 EOF，而不是永远挂着
        } catch (IOException ignored) {
            // 关不上也不影响后面
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    synchronized (buffer) {
                        buffer.write(chunk, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时读流会抛异常，已读到的部分仍然有用
            }
        });
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Outcome(false, -1, snapshot(buffer), true);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
                reader.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Outcome(false, -1, snapshot(buffer), false);
        }
        try {
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Outcome(true, process.exitValue(), snapshot(buffer), false);
    }

    private static String snapshot(ByteArrayOutputStream buffer) {
        synchronized (buffer) {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /** 截断时保留头尾各一半：编译错误、测试失败的关键信息通常都在末尾。 */
    static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int half = maxChars / 2;
        int omitted = text.length() - half * 2;
        return text.substring(0, half)
                + "\n\n... (中间 " + omitted + " 个字符已截断) ...\n\n"
                + text.substring(text.length() - half);
    }
}
