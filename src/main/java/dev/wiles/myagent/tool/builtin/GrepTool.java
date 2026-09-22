package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.search.PathGlob;
import dev.wiles.myagent.tool.search.SourceFilter;
import dev.wiles.myagent.tool.search.SourceScan;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 按正则搜文件内容——这个 agent 的<b>主力检索手段</b>。
 *
 * <h3>为什么是 grep，而不是向量检索</h3>
 * 做这个功能之前调研过 Claude Code：它<b>做过</b> RAG（embedding + 本地向量库 + 分块），
 * 后来整套拆掉换成了 grep。理由里最硬的一条是成本曲线——
 * {@code 总成本 = 建索引 + 维护×时间 + 每次查询×次数}，而 grep 的前两项<b>都是零</b>：
 * 活的文件系统就是索引，永远不会陈旧。其余几条（代码要的是精确匹配而不是语义邻近、
 * 分块必然切断结构、索引落后于磁盘）也都站得住。详见 docs/devlog.md。
 *
 * <h3>默认只回文件名，是这个工具最重要的设计</h3>
 * {@code output_mode} 默认 {@code files_with_matches}，只给路径和命中条数。
 * 这不是为了省事，是为了<b>逼出一次收窄</b>：模型先看见「命中在这 5 个文件里」，
 * 再决定读哪一个——而不是一次把 200 行匹配全塞进上下文，
 * 其中 190 行它根本不关心。上下文窗口是这个循环里最稀缺的东西，
 * 默认值该站在省的那一边。
 *
 * <h3>三重预算，撞上哪个都会明说</h3>
 * 单文件大小（{@link SourceFilter#MAX_FILE_BYTES}）、结果条数（{@code head_limit}）、
 * 墙钟（{@link #TIME_BUDGET_MS}）。<b>悄悄截断是最要命的</b>：
 * 模型会把「只搜了一半」当成「整个仓库里就这些」，然后基于一个残缺的事实往下推。
 */
public final class GrepTool implements Tool {

    /** 默认最多给多少条结果。文件模式下是文件数，content 模式下是行数。 */
    static final int DEFAULT_HEAD_LIMIT = 100;

    /** {@code head_limit} 再大也不超过这个数——它的出口是上下文窗口。 */
    static final int MAX_HEAD_LIMIT = 500;

    /**
     * 一次搜索最多花多久。
     *
     * <p>纯 Java 实现，在这个项目自己身上（约两万行）跑完是几十毫秒。
     * 给到 10 秒是为了兜住真正的大仓库——超了就带着已有结果返回并说明，
     * 而不是让用户对着一个不动的界面等下去。
     */
    static final long TIME_BUDGET_MS = 10_000;

    /** 回显匹配行时单行的长度上限。压缩过的 js 一行能有几十万字符。 */
    static final int MAX_LINE_LENGTH = 300;

    /** {@code context} 参数的上限。要更多上下文，该用 read_file 读原文。 */
    static final int MAX_CONTEXT = 10;

    /**
     * 单个文件最多记多少条命中。
     *
     * <p>只影响 files/count 两种模式下报出来的数字——搜一个到处都是的词时，
     * 「这个文件里有 10000+ 处」和「有 47231 处」对模型是同一个信息，
     * 而后者要为此留四万个对象。
     */
    static final int MAX_HITS_PER_FILE = 10_000;

    private final Workspace workspace;

    public GrepTool(Workspace workspace) {
        this.workspace = workspace;
    }

    /** 只读，不弹审批，怎么跑都无所谓——允许和别的调用同时执行。 */
    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在工作区里按正则表达式搜索文件内容。"
                + "找某个符号/函数/字符串在哪里定义、在哪里被用到，一律用它，"
                + "不要用 bash 里的 grep、rg、find——这个工具会自动跳过 .git、"
                + "node_modules、构建产物和二进制文件，也会照 .gitignore 办事。"
                + "默认只返回命中的文件名，先据此收窄，再用 read_file 读具体内容；"
                + "确实需要看匹配行时才传 output_mode=\"content\"。"
                + "结果按文件最近修改时间倒序。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "pattern": {
                      "type": "string",
                      "description": "Java 正则表达式，逐行匹配（不跨行）。例如 class\\\\s+Agent"
                    },
                    "literal": {
                      "type": "boolean",
                      "description": "把 pattern 当普通字符串而不是正则，默认 false。搜的内容里带 ( ) [ ] . * ? + | 等符号时传 true，省得自己转义"
                    },
                    "path": {
                      "type": "string",
                      "description": "限定在这个目录或文件里搜，相对工作区根目录，默认搜整个工作区"
                    },
                    "glob": {
                      "type": "string",
                      "description": "只搜文件名匹配的文件，例如 *.java、src/**/*.ts、**/*.{md,txt}。不含斜杠时匹配文件名本身，所以 *.java 能搜到任意深度"
                    },
                    "output_mode": {
                      "type": "string",
                      "enum": ["files_with_matches", "content", "count"],
                      "description": "files_with_matches（默认）只给文件名和命中数；content 给匹配的行；count 只给每个文件的命中数"
                    },
                    "case_insensitive": {
                      "type": "boolean",
                      "description": "忽略大小写，默认 false"
                    },
                    "context": {
                      "type": "integer",
                      "description": "content 模式下每条匹配前后各带几行上下文，默认 0，最多 10"
                    },
                    "head_limit": {
                      "type": "integer",
                      "description": "最多返回多少条，默认 100。files 模式下是文件数，content 模式下是行数"
                    }
                  },
                  "required": ["pattern"]
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String rawPattern = Args.requireString(args, "pattern");
        boolean literal = Args.optBoolean(args, "literal", false);
        boolean ignoreCase = Args.optBoolean(args, "case_insensitive", false);
        String pathArg = Args.optString(args, "path", ".");
        String globArg = Args.optString(args, "glob", null);
        String modeArg = Args.optString(args, "output_mode", Mode.FILES.wire);
        int context = clamp(Args.optInt(args, "context", 0), 0, MAX_CONTEXT);
        int headLimit = clamp(Args.optInt(args, "head_limit", DEFAULT_HEAD_LIMIT),
                1, MAX_HEAD_LIMIT);

        Mode mode = Mode.parse(modeArg);
        if (mode == null) {
            return ToolResult.error("output_mode 只能是 files_with_matches、content 或 count，"
                    + "收到: " + modeArg);
        }

        Pattern pattern;
        try {
            int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            pattern = literal
                    ? Pattern.compile(Pattern.quote(rawPattern), flags)
                    : Pattern.compile(rawPattern, flags);
        } catch (PatternSyntaxException e) {
            // 把「哪儿不对」原样交回去，模型改一下就能重试；这比「搜索失败」有用得多
            return ToolResult.error("正则表达式有语法错误: " + e.getMessage()
                    + "\n如果本来就想搜这串字面量，传 literal=true。");
        }

        Predicate<String> accept;
        try {
            accept = globArg == null ? PathGlob.always() : PathGlob.of(globArg);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        Path start = workspace.resolve(pathArg);       // 越界抛 IllegalArgumentException
        if (!Files.exists(start)) {
            return ToolResult.error("路径不存在: " + pathArg);
        }

        long deadline = System.nanoTime() + TIME_BUDGET_MS * 1_000_000L;
        SourceScan.Result scan = SourceScan.scan(workspace.root(), start, accept, deadline);

        return search(scan, pattern, mode, context, headLimit, deadline,
                describeScope(pathArg, globArg));
    }

    /** 逐个文件搜，边搜边记预算。 */
    private ToolResult search(SourceScan.Result scan, Pattern pattern, Mode mode,
                              int context, int headLimit, long deadline, String scope) {
        Map<String, List<Hit>> byFile = new LinkedHashMap<>();
        int totalHits = 0;
        int shown = 0;
        int filesSearched = 0;
        boolean truncated = false;
        boolean timedOut = false;

        for (SourceScan.Found found : scan.files()) {
            if (System.nanoTime() > deadline) {
                timedOut = true;
                break;
            }
            // 内容模式按行数收口，其余两种按文件数——两者的「一条」不是同一个东西
            if (mode == Mode.CONTENT ? shown >= headLimit : byFile.size() >= headLimit) {
                truncated = true;
                break;
            }
            if (SourceFilter.looksBinary(found.path())) {
                continue;
            }
            filesSearched++;

            List<Hit> hits = matchesIn(found.path(), pattern, mode, context,
                    mode == Mode.CONTENT ? headLimit - shown : Integer.MAX_VALUE);
            if (hits == null || hits.isEmpty()) {
                continue;
            }
            byFile.put(found.relative(), hits);
            totalHits += hits.stream().mapToInt(h -> h.isMatch() ? 1 : 0).sum();
            shown += hits.size();
        }

        if (byFile.isEmpty()) {
            return ToolResult.ok(noMatches(scope, filesSearched, scan, timedOut));
        }
        return ToolResult.ok(render(byFile, mode, totalHits, filesSearched, scope,
                truncated, timedOut, scan));
    }

    /**
     * 搜一个文件。
     *
     * <p>逐行读而不是一次读进内存：2MB 的上限之下差别不大，但流式读让「读到一半
     * 发现是坏编码」这种情况只损失这一个文件。读不了就返回 null，当它不存在。
     */
    private List<Hit> matchesIn(Path file, Pattern pattern, Mode mode, int context, int budget) {
        List<Hit> hits = new ArrayList<>();
        // content 模式要回看前文做上下文，环形缓冲存最近 context 行
        String[] before = new String[Math.max(context, 1)];
        int pendingAfter = 0;
        int lastEmitted = 0;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                Matcher matcher = pattern.matcher(line);
                boolean hit = matcher.find();

                if (hit) {
                    if (mode != Mode.CONTENT) {
                        // 这两种模式只用得上条数。一个文件里命中上万次是有的
                        // （比如搜一个到处都是的关键字），没必要每条都留个对象
                        if (hits.size() < MAX_HITS_PER_FILE) {
                            hits.add(new Hit(number, null, true));
                        }
                        continue;
                    }
                    if (hits.size() >= budget) {
                        return hits;
                    }
                    // 先补上前文；和上一条挨着时不要重复输出
                    for (int back = Math.min(context, number - 1); back >= 1; back--) {
                        int at = number - back;
                        if (at > lastEmitted) {
                            hits.add(new Hit(at, clip(before[at % before.length]), false));
                            lastEmitted = at;
                        }
                    }
                    hits.add(new Hit(number, clip(line), true));
                    lastEmitted = number;
                    pendingAfter = context;
                } else if (mode == Mode.CONTENT && pendingAfter > 0) {
                    hits.add(new Hit(number, clip(line), false));
                    lastEmitted = number;
                    pendingAfter--;
                }
                if (context > 0) {
                    before[number % before.length] = line;
                }
            }
        } catch (CharacterCodingException e) {
            return null;        // 不是 UTF-8，当二进制处理
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return hits;
    }

    private String render(Map<String, List<Hit>> byFile, Mode mode, int totalHits,
                          int filesSearched, String scope, boolean truncated,
                          boolean timedOut, SourceScan.Result scan) {
        StringBuilder out = new StringBuilder();

        switch (mode) {
            case FILES -> byFile.forEach((path, hits) ->
                    out.append(path).append("  (").append(hits.size()).append(" 处)\n"));
            case COUNT -> byFile.forEach((path, hits) ->
                    out.append(hits.size()).append('\t').append(path).append('\n'));
            case CONTENT -> byFile.forEach((path, hits) -> {
                out.append(path).append('\n');
                for (Hit hit : hits) {
                    // 冒号是匹配行、短横是上下文行——grep 用了几十年的记号，
                    // 模型见过无数次，不用解释也认得
                    out.append("  ").append(hit.line()).append(hit.isMatch() ? ": " : "- ")
                            .append(hit.text()).append('\n');
                }
                out.append('\n');
            });
        }

        out.append('\n');
        if (mode == Mode.CONTENT) {
            out.append("共 ").append(totalHits).append(" 处匹配，分布在 ")
                    .append(byFile.size()).append(" 个文件");
        } else {
            out.append("共 ").append(byFile.size()).append(" 个文件命中（")
                    .append(totalHits).append(" 处）");
        }
        out.append("，在 ").append(filesSearched).append(" 个文件里搜过").append(scope)
                .append("。按最近修改倒序。");

        appendIncompleteness(out, truncated, timedOut, scan);
        if (mode == Mode.FILES) {
            out.append("\n需要看匹配的行，用同样的参数加 output_mode=\"content\"。");
        }
        return out.toString();
    }

    private String noMatches(String scope, int filesSearched, SourceScan.Result scan,
                             boolean timedOut) {
        StringBuilder out = new StringBuilder();
        out.append("没有匹配。在 ").append(filesSearched).append(" 个文件里搜过")
                .append(scope).append('。');
        appendIncompleteness(out, false, timedOut, scan);
        if (scan.complete() && !timedOut) {
            // 把「搜完了确实没有」说死，模型才敢据此下结论而不是换个词再搜五遍
            out.append("\n范围内已搜完，确实不存在。换个写法或放宽 path/glob 再试可能有用。");
        }
        return out.toString();
    }

    /** 任何一种「没搜全」都要说出来，且要说清是哪一种。 */
    private void appendIncompleteness(StringBuilder out, boolean truncated, boolean timedOut,
                                      SourceScan.Result scan) {
        if (truncated) {
            out.append("\n注意: 结果已达上限被截断，还有更多命中没列出来。"
                    + "收窄 path/glob，或调大 head_limit。");
        }
        if (timedOut) {
            out.append("\n注意: 搜索超过 ").append(TIME_BUDGET_MS / 1000)
                    .append(" 秒预算，只搜了一部分文件。收窄 path 或 glob 再试。");
        }
        switch (scan.stop()) {
            case TOO_MANY_FILES -> out.append("\n注意: 候选文件超过 ")
                    .append(SourceScan.MAX_CANDIDATES).append(" 个，只看了其中一部分。");
            case TIMEOUT -> out.append("\n注意: 遍历目录就用光了时间预算，搜索范围不完整。");
            case COMPLETE -> {
            }
        }
    }

    private static String describeScope(String pathArg, String globArg) {
        StringBuilder scope = new StringBuilder();
        if (!".".equals(pathArg)) {
            scope.append("（范围 ").append(pathArg);
            scope.append(globArg == null ? "" : "，限 " + globArg).append("）");
        } else if (globArg != null) {
            scope.append("（限 ").append(globArg).append("）");
        }
        return scope.toString();
    }

    private static String clip(String line) {
        if (line == null) {
            return "";
        }
        return line.length() <= MAX_LINE_LENGTH
                ? line
                : line.substring(0, MAX_LINE_LENGTH) + "…(本行过长已截断)";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 一条命中。{@code text} 在非 content 模式下是 null——那时候只数数，不留正文。 */
    private record Hit(int line, String text, boolean isMatch) {
    }

    private enum Mode {
        FILES("files_with_matches"),
        CONTENT("content"),
        COUNT("count");

        final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        static Mode parse(String raw) {
            for (Mode mode : values()) {
                if (mode.wire.equalsIgnoreCase(raw)) {
                    return mode;
                }
            }
            // 模型常常写 "files" 或 "filenames"，认下来比报错有用
            if ("files".equalsIgnoreCase(raw) || "filenames".equalsIgnoreCase(raw)) {
                return FILES;
            }
            return null;
        }
    }
}
