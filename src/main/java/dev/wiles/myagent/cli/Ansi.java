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

    /** 淡色背景，用来标出用户刚提交的那一行。 */
    public static String highlight(String text) {
        return enabled ? CSI + "48;5;236m" + text + CSI + "0m" : text;
    }

    // ---- 光标与清除，行编辑要用 ----

    /**
     * 回到行首并清掉整行。
     *
     * 用 CHA（ESC[1G「光标移到第 1 列」）而不是回车符 \r。
     * 两者效果一样，但 \r 是个古老的控制字符，终端的各种启发式
     * （比如 macOS Terminal.app 的「自动标记提示符行」）天然会盯着它；
     * 每 100ms 发一次 \r 重画，在它看来就像在反复重画命令提示符，
     * 于是给那一行画上标记。CHA 的语义明确得多，不会被误读。
     */
    public static String clearLine() {
        return enabled ? CSI + "1G" + CSI + "2K" : "\r";
    }

    /**
     * 开启鼠标上报：终端会把点击位置以转义序列的形式写进 stdin。
     *
     * 1000 是「只报按下和松开」，1006 是 SGR 扩展坐标格式
     * （老式格式把坐标塞进单字节，列号超过 223 就废了，宽屏下必然踩到）。
     *
     * 代价很大，默认不要开：开启之后<b>滚轮事件也会被发给程序</b>，
     * 终端不再自己滚动——而我们的内容在终端的滚动缓冲区里，程序根本没法代劳，
     * 结果就是屏幕滚不动。拖拽选中同样会被接管。
     *
     * 这也说明能正常滚动的终端程序（比如 Claude Code）用的不是这套机制。
     */
    public static String mouseOn() {
        return enabled ? CSI + "?1000h" + CSI + "?1006h" : "";
    }

    public static String mouseOff() {
        return enabled ? CSI + "?1006l" + CSI + "?1000l" : "";
    }

    /**
     * 清空可见屏幕并把光标移到左上角。
     *
     * 只清当前这一屏，不动滚动缓冲区——往上翻仍然能看到启动之前的终端内容。
     * （真要连历史一起清是 ESC[3J，但那等于替用户做主删东西，不合适。）
     */
    public static String clearScreen() {
        return enabled ? CSI + "H" + CSI + "2J" : "";
    }

    /** 查询光标位置（DSR）。终端会回一个 ESC[行;列R。 */
    public static String queryCursor() {
        return enabled ? CSI + "6n" : "";
    }

    /** 把光标移到本行第 1 列（CHA），不清除任何内容。 */
    public static String toLineStart() {
        return enabled ? CSI + "1G" : "\r";
    }

    /**
     * 清除从光标位置到屏幕末尾的全部内容（ED，ESC[0J）。
     *
     * 注意它是从<b>当前光标位置</b>开始清的，不是从行首。
     * 所以用之前几乎总要先 {@link #toLineStart()} 回到第 1 列，
     * 否则光标左边的内容会原样留在屏幕上。
     *
     * 要擦掉光标下方连续的好几行时，用它一次搞定，
     * 比「逐行下移 + 清行 + 再逐行上移」那种做法干净得多，
     * 发出去的控制序列也少一个数量级。
     */
    public static String clearToEnd() {
        return enabled ? CSI + "0J" : "";
    }

    /** 光标上移 n 行。 */
    public static String up(int n) {
        return n <= 0 || !enabled ? "" : CSI + n + "A";
    }

    /** 光标下移 n 行。 */
    public static String down(int n) {
        return n <= 0 || !enabled ? "" : CSI + n + "B";
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
