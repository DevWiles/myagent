package dev.wiles.myagent.cli;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 把一串工具调用概括成一句话，比如「读取 3 个文件 · 执行 1 条命令」。
 *
 * 用途是等这一组工具跑完之后，把屏幕上那十几行过程收起来，只留这一行。
 * 过程进行时该显示还是要显示——用户需要知道它在干什么；
 * 但跑完之后那些细节就没用了，留着只会把结论挤出屏幕。
 */
public final class ToolSummary {

    /** 工具名 → 「动词, 量词」。没登记的工具退回通用说法。 */
    private static final Map<String, String[]> WORDING = Map.of(
            "read_file", new String[]{"读取", "个文件"},
            "write_file", new String[]{"写入", "个文件"},
            "list_dir", new String[]{"列出", "个目录"},
            "bash", new String[]{"执行", "条命令"},
            "create_project", new String[]{"创建", "批文件"});

    private ToolSummary() {
    }

    /**
     * @param counts 工具名 → 调用次数，按首次调用顺序排列
     * @return 概括文字；没有任何调用时返回空串
     */
    public static String describe(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(" · ");
        counts.forEach((tool, count) -> {
            String[] wording = WORDING.get(tool);
            joiner.add(wording == null
                    ? "调用 " + count + " 次 " + tool
                    : wording[0] + " " + count + " " + wording[1]);
        });
        return joiner.toString();
    }

    /** 顺序要稳定：谁先被调用就先出现在概括里。 */
    public static Map<String, Integer> newCounter() {
        return new LinkedHashMap<>();
    }
}
