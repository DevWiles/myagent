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
            exec("stty raw -echo < /dev/tty");
            // 程序万一崩掉也要还原终端，否则用户的 shell 会变得不回显、没法用
            Runtime.getRuntime().addShutdownHook(new Thread(this::restore));
            return true;
        } catch (Exception e) {
            return false;
        }
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
