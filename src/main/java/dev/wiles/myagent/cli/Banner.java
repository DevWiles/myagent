package dev.wiles.myagent.cli;

import java.nio.file.Path;
import java.util.List;

/**
 * 启动横幅。
 *
 * 只做左对齐，不画右边框——中英混排时右边框极难对齐，
 * 一旦某行多一个汉字整个框就歪了。左侧标签按显示宽度补空格即可。
 */
public final class Banner {

    /** 标签列宽（显示列数，不是字符数）。 */
    private static final int LABEL_WIDTH = 8;

    private Banner() {
    }

    public static String render(String version, String model, Path workspace,
                                List<String> toolNames, boolean planMode, boolean hasApiKey) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("  ").append(Ansi.cyan("◤")).append(' ')
                .append(Ansi.bold("myagent " + version))
                .append(Ansi.dim("   一个从零手写的编码 agent"))
                .append('\n').append('\n');

        if (!hasApiKey) {
            sb.append(row("模型", Ansi.red("未配置 API key")));
        } else {
            sb.append(row("模型", model + (planMode ? "   " + Ansi.yellow("[只读模式]") : "")));
        }
        sb.append(row("工作区", shorten(workspace)));
        sb.append(row("工具", toolNames.isEmpty()
                ? Ansi.dim("无")
                : String.join(Ansi.dim(" · "), toolNames)));
        sb.append('\n');
        sb.append("  ").append(Ansi.dim(
                "↑↓ 历史 · Tab 补全 · Ctrl-C 放弃当前行 · Ctrl-D 退出 · /help 查看命令"));
        sb.append('\n');
        return sb.toString();
    }

    private static String row(String label, String value) {
        return "  " + Ansi.dim(pad(label)) + value + "\n";
    }

    /** 按显示宽度补空格：一个汉字占两列，用 length() 补会歪。 */
    private static String pad(String text) {
        int gap = LABEL_WIDTH - Ansi.displayWidth(text);
        return text + " ".repeat(Math.max(1, gap));
    }

    /** 家目录缩成 ~，路径短一截，也不至于把用户名晒出来。 */
    private static String shorten(Path path) {
        String full = path.toAbsolutePath().normalize().toString();
        String home = System.getProperty("user.home");
        return full.startsWith(home) ? "~" + full.substring(home.length()) : full;
    }
}
