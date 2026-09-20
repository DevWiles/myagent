package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.search.PathGlob;
import dev.wiles.myagent.tool.search.SourceScan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * 按文件名模式找文件。
 *
 * <h3>它和另外两个「看文件」的工具怎么分工</h3>
 * <pre>
 *   知道名字长什么样  → glob      （**&#47;*Test.java 在哪儿）
 *   知道内容长什么样  → grep      （哪个文件里写了 AgentRole）
 *   知道具体在哪一层  → list_dir  （src/main 下面有什么）
 * </pre>
 * 三个的边界写进各自的 description 里，因为模型只能靠那几句话选工具。
 * 选错的代价不是报错，是绕一大圈——比如用 {@code list_dir} 递归列出整个仓库
 * 再自己从几百行里找 {@code *Test.java}，把一屏 token 花在一件 glob 一步就完的事上。
 *
 * <h3>为什么按最近修改倒序，还要显示时间</h3>
 * 排序的理由见 {@link SourceScan}——人正在改的就是人正在问的。
 * 而<b>把时间一并显示出来</b>是另一件事：模型据此能分辨「这是活跃代码」
 * 还是「三年没动的老模块」，这个判断它自己做不出来，文件名里没有这个信息。
 */
public final class GlobTool implements Tool {

    /** 默认最多列多少个。和 grep 的默认上限一致，理由也一样：出口是上下文窗口。 */
    static final int DEFAULT_LIMIT = 100;

    /** 上限的上限。 */
    static final int MAX_LIMIT = 500;

    /** 遍历的时间预算。只走目录不读内容，比 grep 快得多，给 5 秒足够。 */
    static final long TIME_BUDGET_MS = 5_000;

    private final Workspace workspace;

    public GlobTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "按文件名模式查找文件，例如 **/*.java、*Test.java、src/**/*.{ts,tsx}。"
                + "知道文件名大概长什么样时用它，不要用 bash 里的 find、ls。"
                + "不含斜杠的模式匹配文件名本身，所以 *.java 能找到任意深度的 java 文件。"
                + "结果按最近修改时间倒序，并带上修改时间——据此可以判断哪些是活跃代码。"
                + "会自动跳过 .git、node_modules、构建产物，也照 .gitignore 办事。"
                + "要按文件内容找用 grep，要看某个目录下有什么用 list_dir。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "pattern": {
                      "type": "string",
                      "description": "文件名模式。支持 * ? ** 和 {a,b} 大括号展开。例如 *.java、**/*Test.java、src/**/*.{ts,tsx}"
                    },
                    "path": {
                      "type": "string",
                      "description": "在这个目录下找，相对工作区根目录，默认整个工作区"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "最多返回多少个文件，默认 100"
                    }
                  },
                  "required": ["pattern"]
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pattern = Args.requireString(args, "pattern");
        String pathArg = Args.optString(args, "path", ".");
        int limit = Math.max(1, Math.min(MAX_LIMIT, Args.optInt(args, "limit", DEFAULT_LIMIT)));

        Predicate<String> accept;
        try {
            accept = PathGlob.of(pattern);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        Path start = workspace.resolve(pathArg);       // 越界抛 IllegalArgumentException
        if (!Files.exists(start)) {
            return ToolResult.error("路径不存在: " + pathArg);
        }
        if (!Files.isDirectory(start)) {
            return ToolResult.error(pathArg + " 是一个文件，不是目录。"
                    + "要读它用 read_file；要在它里面搜内容用 grep。");
        }

        long deadline = System.nanoTime() + TIME_BUDGET_MS * 1_000_000L;
        SourceScan.Result scan = SourceScan.scan(workspace.root(), start, accept, deadline);
        List<SourceScan.Found> files = scan.files();

        if (files.isEmpty()) {
            return ToolResult.ok(nothing(pattern, pathArg, scan));
        }

        boolean truncated = files.size() > limit;
        List<SourceScan.Found> shown = truncated ? files.subList(0, limit) : files;

        StringBuilder out = new StringBuilder();
        for (SourceScan.Found found : shown) {
            out.append(found.relative())
                    .append("  (")
                    .append(age(found.modified()))
                    .append(")\n");
        }

        out.append("\n共 ").append(files.size()).append(" 个文件匹配 ").append(pattern);
        if (truncated) {
            // 说清「列了多少 / 一共多少」，模型才知道要不要收窄；
            // 只说「已截断」它没法判断差多少
            out.append("，已列出最近修改的前 ").append(limit).append(" 个。");
        } else {
            out.append("，按最近修改倒序。");
        }
        appendIncompleteness(out, scan);
        return ToolResult.ok(out.toString());
    }

    private String nothing(String pattern, String pathArg, SourceScan.Result scan) {
        StringBuilder out = new StringBuilder();
        out.append("没有文件匹配 ").append(pattern);
        if (!".".equals(pathArg)) {
            out.append("（范围 ").append(pathArg).append("）");
        }
        out.append('。');
        appendIncompleteness(out, scan);
        if (scan.complete()) {
            // 空结果最容易被模型误读成「模式写错了」，于是换着花样再试五遍。
            // 把「范围内确实没有」说死，同时指出唯一真正常见的写错法
            out.append("\n范围内已找完，确实没有这样的文件。")
                    .append("注意模式是匹配文件名或相对路径，不是正则——")
                    .append("按文件内容找请用 grep。");
        }
        return out.toString();
    }

    private void appendIncompleteness(StringBuilder out, SourceScan.Result scan) {
        switch (scan.stop()) {
            case TOO_MANY_FILES -> out.append("\n注意: 候选文件超过 ")
                    .append(SourceScan.MAX_CANDIDATES).append(" 个，没有走完整棵目录树。");
            case TIMEOUT -> out.append("\n注意: 遍历超过 ").append(TIME_BUDGET_MS / 1000)
                    .append(" 秒预算，结果不完整。收窄 path 再试。");
            case COMPLETE -> {
            }
        }
    }

    /**
     * 修改时间 → 人话。
     *
     * <p>和界面那边的 {@code formatRelativeTime} 是同一个思路但<b>更粗</b>：
     * 这行字是给模型看的，它要的是「活跃还是陈旧」这一个量级判断，
     * 精确到分钟纯属浪费 token。
     */
    static String age(long modified) {
        long days = (System.currentTimeMillis() - modified) / 86_400_000L;
        if (days < 0) {
            return "时间异常";     // 时钟回拨、从别的机器拷过来的文件
        }
        if (days == 0) {
            return "今天";
        }
        if (days == 1) {
            return "昨天";
        }
        if (days < 30) {
            return days + " 天前";
        }
        if (days < 365) {
            return (days / 30) + " 个月前";
        }
        return (days / 365) + " 年前";
    }
}
