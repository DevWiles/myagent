package dev.wiles.myagent.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 终端模式切换。
 *
 * 默认情况下终端工作在「行缓冲 + 回显」模式：用户敲的字由终端自己显示，
 * 程序要等他按回车才能拿到一整行。这样根本做不了行编辑——
 * 按左箭头时程序毫不知情，屏幕上只会冒出一串控制字符。
 *
 * 要自己处理每一个按键，就得切到 raw 模式：终端不回显、不缓冲，
 * 每个字节立刻交给程序；代价是回显和光标位置全部由程序自己负责。
 *
 * JDK 没有切换终端模式的 API，只能调系统的 stty。
 * 算不上优雅，但这是纯 JDK 环境下唯一的办法。
 */
public final class TerminalMode {

    private static final int DEFAULT_WIDTH = 80;
    private static final int DEFAULT_HEIGHT = 24;
    /** 只防御 stty 返回离谱值，正常情况下不截断——横线就该撑满屏幕。 */
    private static final int MAX_WIDTH = 500;

    private String savedSettings;

    /** 当前进程是不是接在真终端上（管道、重定向时为否）。 */
    public static boolean isInteractive() {
        return System.console() != null;
    }

    /**
     * 切到 raw 模式。
     *
     * @return 是否成功；失败时调用方应退回到按行读取
     */
    public boolean enterRaw() {
        if (!isInteractive()) {
            return false;
        }
        try {
            // 先存下原来的设置，退出时要原样还回去
            savedSettings = exec("stty -g < /dev/tty").trim();
            if (savedSettings.isEmpty()) {
                return false;
            }
            // 关键：用 -icanon -echo -isig，而不是 stty raw。
            //
            // stty raw 会把「输出换行转换」(onlcr) 一并关掉，于是 \n 只让光标下移一行、
            // 不回行首，每行输出都比上一行更靠右，屏幕最后变成阶梯状。
            // 我们真正需要的只有三件事：
            //   -icanon 逐字符读（不等回车）
            //   -echo   终端别替我们回显
            //   -isig   Ctrl-C 交给程序自己处理，不要变成 SIGINT 直接杀进程
            // 输出侧的处理全部保留，printf 的 \n 照常被转成 \r\n。
            exec("stty -icanon -echo -isig min 1 time 0 < /dev/tty");
            // 程序万一崩掉也要还原终端，否则用户的 shell 会变得不回显、没法用
            Runtime.getRuntime().addShutdownHook(new Thread(this::restore));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 终端宽度（列数）。
     *
     * 取不到就退回 80——这是终端宽度的传统默认值，
     * 画框时宁可窄一点也不能超出，超出会换行把整个框撑烂。
     */
    public static int width() {
        if (!isInteractive()) {
            return DEFAULT_WIDTH;
        }
        try {
            String output = exec("stty size < /dev/tty").trim();
            String[] parts = output.split("\\s+");
            if (parts.length == 2) {
                int columns = Integer.parseInt(parts[1]);
                return columns > 20 ? Math.min(columns, MAX_WIDTH) : DEFAULT_WIDTH;
            }
        } catch (Exception ignored) {
            // 拿不到就用默认值
        }
        return DEFAULT_WIDTH;
    }

    /** 终端高度（行数）。取不到就退回 24。 */
    public static int height() {
        if (!isInteractive()) {
            return DEFAULT_HEIGHT;
        }
        try {
            String[] parts = exec("stty size < /dev/tty").trim().split("\\s+");
            if (parts.length == 2) {
                int rows = Integer.parseInt(parts[0]);
                return rows > 5 ? rows : DEFAULT_HEIGHT;
            }
        } catch (Exception ignored) {
            // 拿不到就用默认值
        }
        return DEFAULT_HEIGHT;
    }

    public void restore() {
        if (savedSettings == null) {
            return;
        }
        try {
            exec("stty " + savedSettings + " < /dev/tty");
        } catch (Exception ignored) {
            // 还原失败也没别的办法了
        }
    }

    private static String exec(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }
}
