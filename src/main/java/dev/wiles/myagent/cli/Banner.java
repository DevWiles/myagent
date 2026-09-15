package dev.wiles.myagent.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动横幅，画成一个圆角框。
 *
 * <h3>画框最容易错的地方</h3>
 * 右边框要对齐，就得知道每行内容占多少列。而这里有两个陷阱：
 * <ol>
 *   <li>一个汉字占两列，用 length() 算出来的宽度是错的</li>
 *   <li>上了色的字符串里含 ANSI 转义序列，那些字符<b>不占任何显示宽度</b>，
 *       但 length() 会把它们算进去，于是带色的行永远比不带色的短一截</li>
 * </ol>
 * 所以每一行都要同时准备「纯文本」和「带色文本」两个版本：
 * 用前者算宽度，用后者输出。
 */
public final class Banner {

    private static final int LABEL_WIDTH = 8;
    private static final int MIN_INNER_WIDTH = 44;

    /** 一行内容：plain 用来算宽度，colored 用来输出。 */
    private record Row(String plain, String colored) {
        static Row of(String text) {
            return new Row(text, text);
        }

        static Row blank() {
            return new Row("", "");
        }
    }

    private Banner() {
    }

    public static String render(String version, String model, Path workspace,
                                List<String> toolNames, boolean planMode, boolean hasApiKey,
                                int terminalWidth) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("✻ myagent " + version + "   一个从零手写的编码 agent",
                Ansi.cyan("✻ ") + Ansi.bold("myagent " + version)
                        + Ansi.dim("   一个从零手写的编码 agent")));
        rows.add(Row.blank());

        if (!hasApiKey) {
            rows.add(labelled("模型", "未配置 API key", Ansi.red("未配置 API key")));
        } else {
            String suffix = planMode ? "   [只读模式]" : "";
            rows.add(labelled("模型", model + suffix,
                    model + (planMode ? "   " + Ansi.yellow("[只读模式]") : "")));
        }
        rows.add(labelled("工作区", shorten(workspace), shorten(workspace)));

        String tools = toolNames.isEmpty() ? "无" : String.join(" · ", toolNames);
        rows.add(labelled("工具", tools, tools));
        rows.add(Row.blank());
        rows.add(new Row("  /help 查看命令 · Ctrl-C 中断 · exit 退出",
                Ansi.dim("  /help 查看命令 · Ctrl-C 中断 · exit 退出")));

        return draw(rows, terminalWidth);
    }

    private static Row labelled(String label, String plainValue, String coloredValue) {
        String pad = " ".repeat(Math.max(1, LABEL_WIDTH - Ansi.displayWidth(label)));
        return new Row("  " + label + pad + plainValue,
                "  " + Ansi.dim(label + pad) + coloredValue);
    }

    private static String draw(List<Row> rows, int terminalWidth) {
        // 框占 4 列（两条边框各 1 列 + 两侧各 1 空格），内容不能超出剩下的部分
        // 直接撑满终端宽度，这样框的边缘和输入框的横线能对齐；
        // 按内容宽度收缩的话，两者宽度不一致，看着很乱。
        int inner = Math.max(MIN_INNER_WIDTH, terminalWidth - 4);

        StringBuilder sb = new StringBuilder("\n");
        sb.append(Ansi.dim("╭" + "─".repeat(inner + 2) + "╮")).append('\n');
        for (Row row : rows) {
            Row fitted = truncate(row, inner);
            int pad = inner - Ansi.displayWidth(fitted.plain());
            sb.append(Ansi.dim("│ ")).append(fitted.colored())
                    .append(" ".repeat(Math.max(0, pad)))
                    .append(Ansi.dim(" │")).append('\n');
        }
        sb.append(Ansi.dim("╰" + "─".repeat(inner + 2) + "╯")).append('\n');
        return sb.toString();
    }

    /** 超宽的行（工具列表、长路径）要截断，否则终端换行会把整个框撑烂。 */
    private static Row truncate(Row row, int limit) {
        if (Ansi.displayWidth(row.plain()) <= limit) {
            return row;
        }
        StringBuilder kept = new StringBuilder();
        int width = 0;
        for (char c : row.plain().toCharArray()) {
            int w = Ansi.charWidth(c);
            if (width + w > limit - 1) {
                break;
            }
            kept.append(c);
            width += w;
        }
        String text = kept + "…";
        // 截断后颜色没法保留，退回纯文本——保证框不歪比保留颜色重要
        return new Row(text, Ansi.dim(text));
    }

    /** 家目录缩成 ~，路径短一截，也不至于把用户名晒出来。 */
    private static String shorten(Path path) {
        String full = path.toAbsolutePath().normalize().toString();
        String home = System.getProperty("user.home");
        return full.startsWith(home) ? "~" + full.substring(home.length()) : full;
    }
}
