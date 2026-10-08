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
            // 用户按了停（Agent.cancel 打断了跑工具的线程）。先杀进程，
            // 再给读流线程一小会儿把管道里剩下的读完——叫停前的输出是现场的一部分
            killTree(process);
            try {
                process.waitFor(1, TimeUnit.SECONDS);
                reader.join(500);
            } catch (InterruptedException again) {
                // 已经在收尾了，再被打断就不等了
            }
            Thread.currentThread().interrupt();
            return new Outcome(false, -1, snapshot(buffer), true);
        }
        if (!finished) {
            killTree(process);
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

    /**
     * 连根带叶杀掉。
     *
     * <p>只杀 {@code sh} 不够：{@code sh -c "mvn test"} 起的 JVM 是它的子进程，
     * sh 一死它就被过继给 init，在后台接着跑、接着改文件——用户以为按停了，其实没有。
     * 而且孙进程还握着输出管道，读流线程等不到 EOF。
     * <b>先</b>收集整棵子孙树再动手：先杀 sh 的话它们已经认不出是谁家的了。
     */
    private static void killTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String snapshot(ByteArrayOutputStream buffer) {
        synchronized (buffer) {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * 命令被用户叫停时回给模型的话。
     *
     * <p>照 Codex（{@code aborted by user after Xs}）和 Gemini CLI（取消前的输出照样带上）：
     * 跑了多久、已经杀掉、<b>可能已部分生效</b>、叫停前的输出。
     * 不能说成超时——模型会去调大时限原样重跑；也不能说「没有执行」——它确实跑了。
     */
    static String stoppedMessage(long elapsedMillis, String partialOutput, int maxChars) {
        String head = "(已取消：命令跑了 " + Math.max(1, Math.round(elapsedMillis / 1000.0))
                + " 秒被用户叫停，进程已强制终止。它可能已经部分生效——先检查现状再决定是否重试)";
        return partialOutput == null || partialOutput.isBlank()
                ? head + "\n(叫停前没有输出)"
                : head + "\n叫停前的输出:\n" + truncate(partialOutput, maxChars);
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
