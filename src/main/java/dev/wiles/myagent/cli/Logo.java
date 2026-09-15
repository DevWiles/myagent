package dev.wiles.myagent.cli;

/**
 * ASCII art 大字招牌。
 *
 * 用的是 ANSI Shadow 风格：█ 做笔画，╗╝╔╚═║ 做立体阴影。
 * 这些字符都属于 Unicode 的 Block Elements 和 Box Drawing 区段，
 * <b>每个占一列宽</b>——所以整幅图的每一行字符数必须完全相同，
 * 差一个字符整幅图就会错位。测试会逐行核对这件事。
 */
public final class Logo {

    /** 大字图案，6 行，每行 64 列。 */
    public static final String[] LINES = {
            "███╗   ███╗██╗   ██╗ █████╗  ██████╗ ███████╗███╗   ██╗████████╗",
            "████╗ ████║╚██╗ ██╔╝██╔══██╗██╔════╝ ██╔════╝████╗  ██║╚══██╔══╝",
            "██╔████╔██║ ╚████╔╝ ███████║██║  ███╗█████╗  ██╔██╗ ██║   ██║   ",
            "██║╚██╔╝██║  ╚██╔╝  ██╔══██║██║   ██║██╔══╝  ██║╚██╗██║   ██║   ",
            "██║ ╚═╝ ██║   ██║   ██║  ██║╚██████╔╝███████╗██║ ╚████║   ██║   ",
            "╚═╝     ╚═╝   ╚═╝   ╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝   ╚═╝   ",
    };

    /** 图案宽度（列）。 */
    public static final int WIDTH = 64;

    /** 窄于这个宽度就别画大字了，画出来会换行，比不画还难看。 */
    public static final int MIN_TERMINAL_WIDTH = WIDTH + 8;

    private Logo() {
    }

    public static boolean fitsIn(int terminalWidth) {
        return terminalWidth >= MIN_TERMINAL_WIDTH;
    }
}
