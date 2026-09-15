package dev.wiles.myagent.cli;

/**
 * 过程信息显示到什么程度。
 *
 * agent 干一件事可能要调七八次工具，每次都完整铺开的话，
 * 屏幕上几十行全是过程，真正的结论反而被挤到看不见。
 * 所以给三档，让用户按需要选。
 */
public enum OutputDetail {

    /** 只留一行工具名，连参数和输出都不显示——只关心结论时用。 */
    QUIET,
    /** 工具名带参数，输出露一行。默认。 */
    NORMAL,
    /** 输出展开到 20 行，排查问题时用。 */
    VERBOSE;

    public OutputDetail toggle(OutputDetail target) {
        return this == target ? NORMAL : target;
    }

    public String label() {
        return switch (this) {
            case QUIET -> "精简";
            case NORMAL -> "正常";
            case VERBOSE -> "详细";
        };
    }
}
