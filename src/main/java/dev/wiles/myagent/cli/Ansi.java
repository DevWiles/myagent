package dev.wiles.myagent.cli;

/**
 * ANSI 转义序列。终端的颜色和光标控制没有 API，
 * 全靠往输出流里写这些特定的字符序列（都以 ESC 加 '[' 开头）。
 *
 * 输出不是终端时（管道、重定向到文件）必须全部关掉，
 * 否则文件里会塞满一堆控制序列乱码。
 */
public final class Ansi {

    /** ESC 字符，ASCII 27。写成 (char) 27 而不是转义写法，源码里看得清楚些。 */
    public static final char ESC = (char) 27;
    /** CSI：控制序列的引导符，所有颜色和光标指令都以它开头。 */
    private static final String CSI = ESC + "[";

    private static boolean enabled = true;

    private Ansi() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static String wrap(String code, String text) {
        return enabled ? CSI + code + "m" + text + CSI + "0m" : text;
    }

    public static String dim(String text) {
        return wrap("2", text);
    }

    public static String bold(String text) {
        return wrap("1", text);
    }

    public static String red(String text) {
        return wrap("31", text);
    }

    public static String green(String text) {
        return wrap("32", text);
    }

    public static String yellow(String text) {
        return wrap("33", text);
    }

    public static String cyan(String text) {
        return wrap("36", text);
    }

    // ---- 光标与清除，行编辑要用 ----

    /** 回到行首并清掉整行。 */
    public static String clearLine() {
        return enabled ? "\r" + CSI + "2K" : "\r";
    }

    /** 光标左移 n 列。 */
    public static String left(int n) {
        return n <= 0 || !enabled ? "" : CSI + n + "D";
    }

    /**
     * 字符串在终端里占几列。
     *
     * 不能用 length()：一个汉字占两列，而光标定位是按列算的。
     * 算错的直接后果就是——中英文混输时光标飘到别处去。
     */
    public static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += charWidth(text.charAt(i));
        }
        return width;
    }

    public static int charWidth(char c) {
        if (c < 0x1100) {
            return 1;
        }
        // 汉字、假名、谚文、全角标点等都是双列宽
        return (c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6) ? 2 : 1;
    }
}
