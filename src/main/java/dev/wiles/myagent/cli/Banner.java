package dev.wiles.myagent.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动横幅：大字招牌 + 一块运行信息。
 *
 * <h3>画框最容易错的地方</h3>
 * 右边框要对齐，就得知道每行内容占多少列。这里有两个陷阱叠在一起：
 * <ol>
 *   <li>一个汉字占两列，用 length() 算出来的宽度是错的</li>
 *   <li>上了色的字符串含 ANSI 转义序列，那些字符<b>不占任何显示宽度</b>，
 *       但 length() 会把它们算进去，于是带色的行永远比不带色的短一截</li>
 * </ol>
 * 所以每一行都同时准备「纯文本」和「带色文本」两个版本：
 * 用前者算宽度，用后者输出。
 *
 * <h3>窄终端降级</h3>
 * 大字招牌固定 64 列，终端不够宽时画出来会换行、整幅图错位，
 * 那还不如不画——所以窄终端自动退回不带招牌的紧凑版。
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
        StringBuilder sb = new StringBuilder("\n");
        if (Logo.fitsIn(terminalWidth)) {
            sb.append(drawLogoBox(version, terminalWidth));
        } else {
            sb.append("  ").append(Ansi.cyan("✻ "))
                    .append(Ansi.bold("myagent " + version)).append('\n');
        }
        sb.append('\n');
        sb.append(info(version, model, workspace, toolNames, planMode, hasApiKey, terminalWidth));
        return sb.toString();
    }

    /** 大字招牌，套一个双线框。 */
    private static String drawLogoBox(String version, int terminalWidth) {
        // 按内容宽度走，不跟着终端撑满：输入框的横线该撑满，
        // 招牌撑满就成了一张空荡荡的大框，中间孤零零一个 logo。
        int inner = Math.min(terminalWidth - 4, Logo.WIDTH + 12);

        List<Row> rows = new ArrayList<>();
        rows.add(Row.blank());
        for (String line : Logo.LINES) {
            rows.add(new Row(center(line, inner), center(Ansi.cyan(line), inner, line)));
        }
        rows.add(Row.blank());
        String subtitle = "从零手写的 Java 编码 Agent  ·  v" + version;
        rows.add(new Row(center(subtitle, inner), center(Ansi.dim(subtitle), inner, subtitle)));
        rows.add(Row.blank());

        StringBuilder sb = new StringBuilder();
        sb.append(Ansi.cyan("╔" + "═".repeat(inner + 2) + "╗")).append('\n');
        for (Row row : rows) {
            int pad = inner - Ansi.displayWidth(row.plain());
            sb.append(Ansi.cyan("║ ")).append(row.colored())
                    .append(" ".repeat(Math.max(0, pad)))
                    .append(Ansi.cyan(" ║")).append('\n');
        }
        sb.append(Ansi.cyan("╚" + "═".repeat(inner + 2) + "╝")).append('\n');
        return sb.toString();
    }

    /** 运行信息，不画框——信息块画框反而显得吵。 */
    private static String info(String version, String model, Path workspace,
                               List<String> toolNames, boolean planMode, boolean hasApiKey,
                               int terminalWidth) {
        List<Row> rows = new ArrayList<>();
        if (!hasApiKey) {
            rows.add(labelled("模型", "未配置 API key", Ansi.red("未配置 API key")));
        } else {
            rows.add(labelled("模型", model + (planMode ? "   [只读模式]" : ""),
                    model + (planMode ? "   " + Ansi.yellow("[只读模式]") : "")));
        }
        rows.add(labelled("工作区", shorten(workspace), shorten(workspace)));
        String tools = toolNames.isEmpty() ? "无" : String.join(" · ", toolNames);
        rows.add(labelled("工具", tools, Ansi.dim(tools)));
        rows.add(Row.blank());
        String hint = "/help 查看命令 · Ctrl-O 展开过程 · Ctrl-C 中断 · exit 退出";
        rows.add(new Row("  " + hint, "  " + Ansi.dim(hint)));

        StringBuilder sb = new StringBuilder();
        for (Row row : rows) {
            sb.append(truncate(row, Math.max(MIN_INNER_WIDTH, terminalWidth - 2)).colored())
                    .append('\n');
        }
        return sb.toString();
    }

    private static Row labelled(String label, String plainValue, String coloredValue) {
        String pad = " ".repeat(Math.max(1, LABEL_WIDTH - Ansi.displayWidth(label)));
        return new Row("  " + label + pad + plainValue,
                "  " + Ansi.dim(label + pad) + coloredValue);
    }

    /** 按显示宽度居中。 */
    private static String center(String text, int width) {
        return center(text, width, text);
    }

    /** 带色版本要按纯文本的宽度来算左边距，否则会被转义序列带偏。 */
    private static String center(String colored, int width, String plain) {
        int left = Math.max(0, (width - Ansi.displayWidth(plain)) / 2);
        return " ".repeat(left) + colored;
    }

    /** 超宽的行（长路径、工具列表）要截断，否则终端换行会把排版撑烂。 */
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
        // 截断后颜色没法保留，退回纯文本——保证不错位比保留颜色重要
        return new Row(text, Ansi.dim(text));
    }

    /** 家目录缩成 ~，路径短一截，也不至于把用户名晒出来。 */
    private static String shorten(Path path) {
        String full = path.toAbsolutePath().normalize().toString();
        String home = System.getProperty("user.home");
        return full.startsWith(home) ? "~" + full.substring(home.length()) : full;
    }
}
