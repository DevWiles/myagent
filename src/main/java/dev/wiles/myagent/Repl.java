package dev.wiles.myagent;

import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.cli.Ansi;
import dev.wiles.myagent.cli.Banner;
import dev.wiles.myagent.cli.Cli;
import dev.wiles.myagent.cli.CliOptions;
import dev.wiles.myagent.cli.ContextBar;
import dev.wiles.myagent.cli.InputFrame;
import dev.wiles.myagent.cli.InterruptWatcher;
import dev.wiles.myagent.cli.LineReader;
import dev.wiles.myagent.cli.MarkdownRenderer;
import dev.wiles.myagent.cli.OutputDetail;
import dev.wiles.myagent.cli.ToolSummary;
import dev.wiles.myagent.cli.StatusLine;
import dev.wiles.myagent.cli.TerminalMode;
import dev.wiles.myagent.config.Config;
import dev.wiles.myagent.llm.DeepSeekClient;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.WorkspaceGuard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 交互式命令行外壳：读输入、交给 Agent、把事件画到屏幕上。
 * 真正的逻辑都在 Agent 里，这里只管人机交互。
 */
public final class Repl {

    /** 工具输出默认只露一行——过程细节平时没人看，真要看再展开。 */
    private static final int COLLAPSED_OUTPUT_LINES = 1;
    /** /verbose 打开后显示这么多行。完整内容无论如何都会原样发给模型。 */
    private static final int VERBOSE_OUTPUT_LINES = 20;

    private static final List<String> SLASH_COMMANDS =
            List.of("/help", "/clear", "/tools", "/history", "/expand", "/quiet",
                    "/verbose", "/config", "/exit", "/quit");

    /** 退出词。带不带斜杠都认——没人愿意在想退出的时候还猜该敲哪个。 */
    private static final List<String> EXIT_WORDS =
            List.of("/exit", "/quit", "/q", "exit", "quit", "q", ":q", ":wq");

    private final CliOptions options;
    private final Config config;
    private final Workspace workspace;
    private final ToolRegistry tools;
    private final TerminalMode terminal = new TerminalMode();
    private final LineReader reader;
    private final Agent agent;

    /** 攒够一行再渲染：Markdown 的行内标记要整行到齐才能判断闭合。 */
    private final StringBuilder pendingLine = new StringBuilder();
    private final MarkdownRenderer markdown = new MarkdownRenderer();
    /** 当前这段文字是否已经开了头（用来决定要不要先空一行）。 */
    private boolean textStarted = false;
    /** 过程信息显示到什么程度。 */
    private OutputDetail detail = OutputDetail.NORMAL;
    /**
     * 精简模式下攒着的文字。
     *
     * 流式输出时没法预先知道哪段是「过程说明」哪段是「最终答案」——
     * 所以先攒着：后面要是来了工具调用，说明刚才那段只是过程说明，丢掉；
     * 一直攒到这一轮结束还没有工具调用，那就是最终答案，打出来。
     */
    private final StringBuilder quietBuffer = new StringBuilder();

    /**
     * 当前这组工具调用在屏幕上占了几行，以及都调了些什么。
     *
     * 跑的时候细节要显示——用户得知道它在干嘛；跑完之后这些细节就没用了，
     * 留着只会把结论挤出屏幕。所以等模型重新开口（或这一轮结束）时，
     * 把这几行擦掉，换成一句「读取 3 个文件 · 执行 1 条命令」。
     */
    private int toolGroupLines;
    private final Map<String, Integer> toolGroupCounts = ToolSummary.newCounter();
    private boolean toolGroupHasFailure;

    /**
     * 最近一轮每次工具调用的完整记录。
     *
     * 过程收起来之后细节就从屏幕上消失了，但人经常会想回头看一眼
     * ——「刚才那条命令到底输出了什么」。终端没法把已经打印的内容再展开，
     * 所以留一份在内存里，按 Ctrl-O 或 /expand 重新打一遍。
     */
    private record ToolRecord(String tool, String arguments, String output, boolean ok) {
    }

    private final List<ToolRecord> lastRunTools = new ArrayList<>();

    /**
     * 摘要行之后又打印了哪些内容。
     *
     * 「原地展开」靠的就是它：把光标退回摘要行的位置、清掉，
     * 先打详情、再把这些内容原样重放一遍，看起来就是那一行展开了。
     * 没有它只能把详情追加打印在最下面，那不叫展开，叫又打了一遍。
     */
    private final List<String> replayBuffer = new ArrayList<>();
    private boolean recording;
    private String pendingToolName;
    private String pendingToolArgs;

    /** 本轮统计，跑完在输入框上方汇报一行。 */
    private long runStartedAt;
    private int runSteps;
    private int runToolCalls;
    /** 是否处于 raw 模式：只有这时才能后台监听 Ctrl-C。 */
    private final boolean rawMode;
    /** 终端宽度，用来画框和分隔线。 */
    private final int width;
    /** 「思考中…」那一行。 */
    private final StatusLine status;

    public Repl(CliOptions options) {
        this.options = options;
        Ansi.setEnabled(options.color() && TerminalMode.isInteractive());

        Config loaded = Config.load();
        this.config = options.model() == null ? loaded
                : new Config(loaded.apiKey(), loaded.baseUrl(), options.model());

        this.workspace = new Workspace(options.workspace());
        // raw 模式拿不到就退化成按行读，管道和重定向下都是这条路径
        boolean raw = terminal.enterRaw();
        this.rawMode = raw;
        this.width = TerminalMode.width();
        this.status = new StatusLine(System.out, raw && options.color() && options.spinner());
        this.reader = new LineReader(System.in, System.out, raw)
                .width(TerminalMode.width())
                .completions(SLASH_COMMANDS)
                .onExpand(this::expandLastRun)
                .historyFile(Path.of(System.getProperty("user.home"), ".myagent", "history"));

        this.tools = options.planMode()
                ? BuiltinTools.createReadOnly(workspace)
                : BuiltinTools.createDefault(workspace, new TerminalApproval(reader));

        this.agent = config.hasApiKey()
                ? new Agent(newClient(), tools, SystemPrompt.forWorkspace(workspace.root()))
                        .onEvent(this::render)
                : null;
    }

    private LlmClient newClient() {
        return new DeepSeekClient(config);
    }

    public void run() {
        try {
            if (!checkWorkspace()) {
                return;
            }
            if (options.initialPrompt() != null) {
                runOnce(options.initialPrompt());       // 一次性模式，跑完就走
                return;
            }
            printBanner();
            loop();
        } finally {
            terminal.restore();                          // 无论如何都要把终端还回去
        }
    }

    private boolean checkWorkspace() {
        String rejection = WorkspaceGuard.rejectionReason(workspace.root());
        if (rejection == null) {
            return true;
        }
        if (options.unsafe()) {
            System.out.println(Ansi.yellow("⚠ 警告: " + rejection));
            System.out.println(Ansi.yellow("⚠ 你已强制在此目录运行，请格外小心。") + "\n");
            return true;
        }
        System.out.println(Ansi.red("拒绝在此目录启动: ") + workspace.root());
        System.out.println(rejection);
        System.out.println("请 cd 到某个具体的项目目录再运行。");
        System.out.println(Ansi.dim("（确实需要的话加 --unsafe）"));
        return false;
    }

    private void runOnce(String prompt) {
        if (agent == null) {
            System.out.println(noKeyMessage());
            return;
        }
        runInterruptible(prompt);   // 内容已经通过流式回调打印过了
        System.out.println();
    }

    /**
     * 跑一轮任务，期间允许 Ctrl-C 中断。
     *
     * 主线程这会儿阻塞在 agent.run() 里，自己读不了键盘，
     * 所以要另起一个线程盯着 Ctrl-C。非 raw 模式下不能这么做——
     * 那个线程会把管道里后续的输入全吃掉。
     */
    private void runInterruptible(String prompt) {
        markdown.reset();          // 上一轮没闭合的代码块不能影响这一轮
        runStartedAt = System.nanoTime();
        runSteps = 0;
        runToolCalls = 0;
        quietBuffer.setLength(0);
        lastRunTools.clear();
        pendingToolName = null;
        replayBuffer.clear();
        recording = false;
        // 立刻起转。等 Agent 发出第一个 Thinking 事件才转的话，
        // 中间那段（建连接、压缩上下文）屏幕上是死的，用户会以为程序挂了。
        status.start("思考中…");
        if (!rawMode) {
            agent.run(prompt);
            status.stop();
            flushText();
            return;
        }
        try (InterruptWatcher watcher = new InterruptWatcher(System.in, agent::cancel)) {
            watcher.start();
            agent.run(prompt);
        } finally {
            status.stop();          // 无论如何都不能把转圈留在屏幕上
            flushText();
            collapseToolGroup();
            flushQuietBuffer();     // 中途被打断时也不能把答案吞掉
            printRunSummary();
        }
    }

    /**
     * 跑完在输入框上方留一行小结。
     *
     * 过程细节被折叠之后，用户看不出这一轮到底忙了些什么——
     * 这行摘要既是交代，也是耗时的反馈。
     */
    private void printRunSummary() {
        long seconds = (System.nanoTime() - runStartedAt) / 1_000_000_000L;
        if (seconds < 1 && runToolCalls == 0) {
            return;                 // 一问一答的小事不必汇报
        }
        StringBuilder sb = new StringBuilder("✻ 用时 ").append(seconds).append("s");
        if (runSteps > 1) {
            sb.append(" · ").append(runSteps).append(" 轮");
        }
        if (runToolCalls > 0) {
            sb.append(" · ").append(runToolCalls).append(" 次工具调用");
        }
        emit("");
        emit(Ansi.dim(sb.toString()));
    }


    private void loop() {
        int consecutiveInterrupts = 0;
        while (true) {
            String line;
            try {
                line = reader.readLine("› ", inputFrame());
            } catch (LineReader.Interrupted e) {
                // 空行上按 Ctrl-C。我们关掉了 SIGINT，必须自己提供这条退路，
                // 但也不能一按就退——手滑的代价太大，所以要求连按两次。
                if (++consecutiveInterrupts >= 2) {
                    break;
                }
                System.out.println(Ansi.dim("再按一次 Ctrl-C 退出，或输入 /exit"));
                continue;
            }
            consecutiveInterrupts = 0;

            if (line == null) {                          // Ctrl-D
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (isExitCommand(line)) {
                break;
            }
            if (handleSlashCommand(line)) {
                continue;
            }
            if (agent == null) {
                System.out.println(noKeyMessage());
                continue;
            }
            runInterruptible(line);
            System.out.println();
        }
        System.out.println(Ansi.dim("bye."));
    }

    private static boolean isExitCommand(String line) {
        return EXIT_WORDS.contains(line.toLowerCase());
    }

    private String noKeyMessage() {
        return Ansi.red("未配置 API key。") + "请设置环境变量 DEEPSEEK_API_KEY，"
                + "或在 ~/.myagent/config.json 里写 {\"apiKey\": \"sk-...\"}";
    }

    /**
     * 把 agent 的事件画到终端。
     *
     * 不只是好看：一次 run 可能跑十几轮、花上一分钟，
     * 全程不出声的话，用户没法判断它是在干活还是卡死了。
     */
    private void render(AgentEvent event) {
        // 状态行和正文抢同一块屏幕，所以打印任何东西之前先把它擦掉
        status.stop();

        if (event instanceof AgentEvent.TextDelta d) {
            collapseToolGroup();        // 模型又开口了，先把上一组工具收起来
            if (detail == OutputDetail.QUIET) {
                quietBuffer.append(d.text());       // 先攒着，还不知道是不是最终答案
            } else {
                appendText(d.text());
            }
            return;
        }
        // 其余事件都要先把攒着的半行吐出来，否则会被后面的内容截断
        flushText();

        if (event instanceof AgentEvent.Thinking t) {
            runSteps = t.step();
            status.start("思考中…");

        } else if (event instanceof AgentEvent.Thought t) {
            // 文本已经由 TextDelta 打出来了，这里不再重复渲染

        } else if (event instanceof AgentEvent.ToolStarted t) {
            runToolCalls++;
            pendingToolName = t.tool();
            pendingToolArgs = t.arguments();
            if (detail == OutputDetail.QUIET) {
                // 既然来了工具调用，说明刚才攒的那段只是过程说明，不是答案
                quietBuffer.setLength(0);
            } else {
                if (toolGroupLines == 0) {
                    toolPrintln("");    // 一组工具开始前空一行
                }
                // 减去 "⏺ " 和工具名的占位，保证整行不会折行
                int room = width - 4 - Ansi.displayWidth(t.tool());
                toolPrintln(Ansi.green("⏺ ") + Ansi.bold(t.tool()) + " "
                        + Ansi.dim(oneLine(t.arguments(), Math.max(20, room))));
                toolGroupCounts.merge(t.tool(), 1, Integer::sum);
            }
            status.start("执行 " + t.tool() + "…");

        } else if (event instanceof AgentEvent.ToolFinished t) {
            if (pendingToolName != null) {
                lastRunTools.add(new ToolRecord(pendingToolName, pendingToolArgs,
                        t.output(), t.ok()));
                pendingToolName = null;
            }
            renderToolOutput(t);

        } else if (event instanceof AgentEvent.Done d) {
            collapseToolGroup();
            flushQuietBuffer();

        } else if (event instanceof AgentEvent.Compacted c) {
            collapseToolGroup();
            // 必须让用户知道：摘要意味着早期细节已经丢了，后面它"想不起来"是正常的
            System.out.println("\n" + Ansi.dim("… 上下文已压缩 " + c.tokensBefore() + " → "
                    + c.tokensAfter() + " tokens"
                    + (c.summarized() ? "（早期对话已摘要）" : "（裁剪了旧的工具输出）")));

        } else if (event instanceof AgentEvent.Failed f) {
            collapseToolGroup();
            flushQuietBuffer();
            emit(Ansi.red("⚠ " + f.reason()));
        }
    }

    /**
     * 收到新的文本增量。
     *
     * 攒够一整行才渲染输出：Markdown 的行内标记要等整行到齐才能判断闭合，
     * 逐字打的话用户会眼看着星号一个个冒出来再消失。
     */
    private void appendText(String delta) {
        if (!textStarted) {
            emit("");                     // 新一段文字和上面的内容隔开
            textStarted = true;
        }
        pendingLine.append(delta);
        int newline;
        while ((newline = pendingLine.indexOf("\n")) >= 0) {
            String line = pendingLine.substring(0, newline);
            pendingLine.delete(0, newline + 1);
            emit(markdown.renderLine(line));
        }
        System.out.flush();
    }

    /**
     * 把最近一轮的工具调用详情重新打一遍。
     *
     * 终端做不到「点一下原地展开」——内容一旦打进滚动缓冲区就改不了了，
     * 除非整个界面改成全屏重绘。这里的做法是把详情追加打印在下面，
     * 信息一样拿得到。
     */
    private void expandLastRun() {
        if (lastRunTools.isEmpty()) {
            System.out.println(Ansi.dim("最近一轮没有调用工具。"));
            return;
        }
        // 要退回去多少行：摘要行自己 1 行，加上它之后记下来的那些
        int rewind = replayBuffer.size() + 1;
        boolean inPlace = rawMode && recording && rewind < TerminalMode.height() - 3;

        if (!inPlace) {
            // 退不回去就只能追加打印：内容已经滚出屏幕，或者压根不在交互终端里。
            // 硬退的话会把屏幕上别的东西啃掉。
            printToolDetails();
            return;
        }
        // 回到摘要行的位置，清掉它和它下面的一切，
        // 先打详情、再把原来那些内容一行不差地重放——看起来就是这一行展开了
        System.out.print(Ansi.up(rewind) + Ansi.toLineStart() + Ansi.clearToEnd());
        printToolDetails();
        replayBuffer.forEach(System.out::println);
        recording = false;          // 已经是展开状态了，不用再记账
    }

    /** 把最近一轮每次工具调用的完整参数和输出打出来。 */
    private void printToolDetails() {
        for (ToolRecord record : lastRunTools) {
            System.out.println(Ansi.green("⏺ ") + Ansi.bold(record.tool()) + " "
                    + Ansi.dim(oneLine(record.arguments(), Math.max(20, width - 8))));
            String mark = record.ok() ? Ansi.dim("  ⎿ ") : Ansi.red("  ⎿ ✗ ");
            String[] lines = record.output().split("\n");
            for (int i = 0; i < Math.min(lines.length, VERBOSE_OUTPUT_LINES); i++) {
                System.out.println((i == 0 ? mark : "    ")
                        + Ansi.dim(oneLine(lines[i], Math.max(20, width - 6))));
            }
            if (lines.length > VERBOSE_OUTPUT_LINES) {
                System.out.println(Ansi.dim("    … 另有 "
                        + (lines.length - VERBOSE_OUTPUT_LINES) + " 行"));
            }
        }
    }

    /** 精简模式攒下来的最终答案，到这一轮结束才打出来。 */
    private void flushQuietBuffer() {
        if (quietBuffer.length() == 0) {
            return;
        }
        emit("");
        for (String line : quietBuffer.toString().split("\n", -1)) {
            emit(markdown.renderLine(line));
        }
        quietBuffer.setLength(0);
    }

    /** 把攒着还没换行的最后半行吐出去。 */
    private void flushText() {
        if (pendingLine.length() > 0) {
            emit(markdown.renderLine(pendingLine.toString()));
            pendingLine.setLength(0);
        }
        textStarted = false;
    }

    /**
     * 工具输出默认只露第一行。
     *
     * 过程细节平时没人看，一屏全是文件内容反而把结论挤没了。
     * 注意这只影响**显示**——完整内容照样原样发给模型，它需要看全。
     */
    private void renderToolOutput(AgentEvent.ToolFinished event) {
        if (detail == OutputDetail.QUIET) {
            if (event.ok()) {
                return;             // 成功的过程一概不显示
            }
            // 失败是必须让人看见的，否则用户完全不知道出了什么事
            emit(Ansi.red("⏺ " + event.tool() + " ✗ ")
                    + Ansi.dim(oneLine(event.output(), Math.max(20, width - 8))));
            return;
        }
        if (!event.ok()) {
            toolGroupHasFailure = true;   // 出过错的这一组不收起来，得让人看见
        }
        String mark = event.ok() ? Ansi.dim("  ⎿ ") : Ansi.red("  ⎿ ✗ ");
        String[] lines = event.output().split("\n");
        int limit = detail == OutputDetail.VERBOSE ? VERBOSE_OUTPUT_LINES : COLLAPSED_OUTPUT_LINES;

        for (int i = 0; i < Math.min(lines.length, limit); i++) {
            toolPrintln((i == 0 ? mark : "    ")
                    + Ansi.dim(oneLine(lines[i], Math.max(20, width - 6))));
        }
        int hidden = lines.length - limit;
        if (hidden > 0) {
            toolPrintln(Ansi.dim("    … 另有 " + hidden + " 行"
                    + (detail == OutputDetail.VERBOSE ? "" : "（/verbose 展开）")));
        }
    }

    /** 打印一行；摘要行之后的内容要记下来，展开时原样重放。 */
    private void emit(String line) {
        System.out.println(line);
        if (recording) {
            replayBuffer.add(line);
        }
    }

    /** 打印属于当前工具组的一行，记着行数好在收起时擦掉。 */
    private void toolPrintln(String text) {
        System.out.println(text);
        toolGroupLines++;
    }

    /**
     * 把这一组工具调用的细节从屏幕上擦掉，换成一行概括。
     *
     * 三种情况不收：非交互终端（擦除靠 ANSI 光标控制，管道里只会留下乱码）、
     * 组里有失败（错误必须留在屏幕上）、以及 /verbose（用户明确要看细节，
     * 而且那时候行数可能超出一屏，擦除也不可靠）。
     */
    private void collapseToolGroup() {
        if (toolGroupLines == 0) {
            return;
        }
        if (!rawMode || toolGroupHasFailure || detail == OutputDetail.VERBOSE) {
            resetToolGroup();
            return;
        }
        StringBuilder erase = new StringBuilder();
        for (int i = 0; i < toolGroupLines; i++) {
            erase.append(Ansi.up(1)).append(Ansi.clearLine());
        }
        System.out.print(erase);
        System.out.println(Ansi.green("⏺ ") + Ansi.dim(ToolSummary.describe(toolGroupCounts)));
        // 从这里开始记账：展开时要把摘要行之后的内容原样重放
        replayBuffer.clear();
        recording = true;
        resetToolGroup();
    }

    private void resetToolGroup() {
        toolGroupLines = 0;
        toolGroupHasFailure = false;
        toolGroupCounts.clear();
    }

    /**
     * 压成一行并截断到指定列数。
     *
     * 必须按<b>显示列数</b>截而不是字符数：一个汉字占两列，按字符数截出来的行
     * 仍可能超出终端宽度、被终端自动折行。而折行会让一行变成两行，
     * 收起工具组时按行数擦除就会多擦一行，把上面的正文吃掉。
     */
    private static String oneLine(String text, int maxColumns) {
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (Ansi.displayWidth(flat) <= maxColumns) {
            return flat;
        }
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (char c : flat.toCharArray()) {
            int columns = Ansi.charWidth(c);
            if (used + columns > maxColumns - 1) {
                break;
            }
            kept.append(c);
            used += columns;
        }
        return kept + "…";
    }

    private boolean handleSlashCommand(String line) {
        switch (line) {
            case "/clear" -> {
                if (agent != null) {
                    agent.reset();
                }
                System.out.println(Ansi.dim("对话历史已清空。"));
                return true;
            }
            case "/tools" -> {
                for (Tool tool : tools.all()) {
                    System.out.println("  " + Ansi.bold(tool.name()) + "  "
                            + Ansi.dim(tool.description()));
                }
                return true;
            }
            case "/history" -> {
                if (agent == null) {
                    System.out.println(Ansi.dim("(未启用)"));
                    return true;
                }
                System.out.println(Ansi.dim("当前上下文共 " + agent.history().size() + " 条消息，约 "
                        + agent.estimatedTokens() + " tokens（含每轮重发的工具菜单）:"));
                agent.history().forEach(m -> System.out.println(
                        "  " + Ansi.cyan("[" + m.role() + "]") + " "
                                + (m.hasToolCalls() ? "调用 " + m.toolCallsOrEmpty().size() + " 个工具 " : "")
                                + Ansi.dim(oneLine(m.content() == null ? "" : m.content(),
                                        Math.max(20, width - 20)))));
                return true;
            }
            case "/expand" -> {
                expandLastRun();
                return true;
            }
            case "/verbose" -> {
                detail = detail.toggle(OutputDetail.VERBOSE);
                System.out.println(Ansi.dim("过程信息：" + detail.label()));
                return true;
            }
            case "/quiet" -> {
                detail = detail.toggle(OutputDetail.QUIET);
                System.out.println(Ansi.dim("过程信息：" + detail.label()));
                return true;
            }
            case "/config" -> {
                System.out.println(config);
                return true;
            }
            case "/help" -> {
                System.out.println("""
                          /clear    清空对话历史
                          /tools    查看已注册的工具（即发给模型的菜单）
                          /history  查看当前上下文攒了哪些消息
                          /expand   把最近一轮的工具调用详情重新打出来（等同 Ctrl-O）
                          /quiet    整个推理过程收起，只看最终答案
                          /verbose  展开工具输出（再按一次回到正常）
                          /config   查看当前配置（密钥已脱敏）
                          /exit     退出（exit / quit / q 也行）

                        展开: Ctrl-O 把最近一轮的过程详情重新打出来
                        退出: Ctrl-D，或空行连按两次 Ctrl-C，或输入 exit
                        中断: 任务跑到一半按 Ctrl-C 可以中止它，会话继续
                        编辑: ↑↓ 历史   Tab 补全   Ctrl-A/E 行首尾
                              Ctrl-U/K 删到行首/尾   Ctrl-W 删词""");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void printBanner() {
        System.out.print(Banner.render(Cli.VERSION, config.model(), workspace.root(),
                tools.all().stream().map(Tool::name).toList(),
                options.planMode(), agent != null, width));
    }

    /** 输入框的上下横线，撑满整个终端宽度。 */
    private String rule() {
        return Ansi.dim("─".repeat(Math.max(20, width)));
    }

    /** 输入行的上下横线，外加一行上下文用量。 */
    private InputFrame inputFrame() {
        if (agent == null) {
            return new InputFrame(rule(), rule(), null);
        }
        return new InputFrame(rule(), rule(), ContextBar.render(
                agent.estimatedTokens(), agent.contextLimit(),
                agent.history().size(), config.model()));
    }
}
