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
import java.util.List;

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
            List.of("/help", "/clear", "/tools", "/history", "/verbose", "/config", "/exit", "/quit");

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
    /** 是否展开工具输出。 */
    private boolean verbose = false;
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
        this.status = new StatusLine(System.out, raw && options.color());
        this.reader = new LineReader(System.in, System.out, raw)
                .completions(SLASH_COMMANDS)
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
        }
    }

    private void loop() {
        int consecutiveInterrupts = 0;
        while (true) {
            String line;
            try {
                line = reader.readLine(Ansi.cyan("› "), inputFrame());
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
            appendText(d.text());
            return;
        }
        // 其余事件都要先把攒着的半行吐出来，否则会被后面的内容截断
        flushText();

        if (event instanceof AgentEvent.Thinking t) {
            status.start("思考中…");

        } else if (event instanceof AgentEvent.Thought t) {
            // 文本已经由 TextDelta 打出来了，这里不再重复渲染

        } else if (event instanceof AgentEvent.ToolStarted t) {
            System.out.println("\n" + Ansi.green("⏺ ") + Ansi.bold(t.tool()) + " "
                    + Ansi.dim(oneLine(t.arguments(), 100)));
            status.start("执行 " + t.tool() + "…");

        } else if (event instanceof AgentEvent.ToolFinished t) {
            renderToolOutput(t);

        } else if (event instanceof AgentEvent.Done d) {
            // 文本已经吐完了，这里不用再做什么

        } else if (event instanceof AgentEvent.Compacted c) {
            // 必须让用户知道：摘要意味着早期细节已经丢了，后面它"想不起来"是正常的
            System.out.println("\n" + Ansi.dim("… 上下文已压缩 " + c.tokensBefore() + " → "
                    + c.tokensAfter() + " tokens"
                    + (c.summarized() ? "（早期对话已摘要）" : "（裁剪了旧的工具输出）")));

        } else if (event instanceof AgentEvent.Failed f) {
            System.out.println(Ansi.red("⚠ " + f.reason()));
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
            System.out.println();         // 新一段文字和上面的内容隔开
            textStarted = true;
        }
        pendingLine.append(delta);
        int newline;
        while ((newline = pendingLine.indexOf("\n")) >= 0) {
            String line = pendingLine.substring(0, newline);
            pendingLine.delete(0, newline + 1);
            System.out.println(markdown.renderLine(line));
        }
        System.out.flush();
    }

    /** 把攒着还没换行的最后半行吐出去。 */
    private void flushText() {
        if (pendingLine.length() > 0) {
            System.out.println(markdown.renderLine(pendingLine.toString()));
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
        String mark = event.ok() ? Ansi.dim("  ⎿ ") : Ansi.red("  ⎿ ✗ ");
        String[] lines = event.output().split("\n");
        int limit = verbose ? VERBOSE_OUTPUT_LINES : COLLAPSED_OUTPUT_LINES;

        for (int i = 0; i < Math.min(lines.length, limit); i++) {
            System.out.println((i == 0 ? mark : "    ") + Ansi.dim(oneLine(lines[i], 160)));
        }
        int hidden = lines.length - limit;
        if (hidden > 0) {
            System.out.println(Ansi.dim("    … 另有 " + hidden + " 行"
                    + (verbose ? "" : "（/verbose 展开）")));
        }
    }

    private static String oneLine(String text, int max) {
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
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
                                + Ansi.dim(oneLine(m.content() == null ? "" : m.content(), 80))));
                return true;
            }
            case "/verbose" -> {
                verbose = !verbose;
                System.out.println(Ansi.dim("工具输出" + (verbose ? "已展开" : "已折叠")));
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
                          /verbose  展开或折叠工具输出
                          /config   查看当前配置（密钥已脱敏）
                          /exit     退出（exit / quit / q 也行）

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

    /** 输入框的横线。 */
    private String rule() {
        return Ansi.dim("─".repeat(Math.max(20, width)));
    }

    /** 输入行的上下边线，外加一行上下文用量。 */
    private InputFrame inputFrame() {
        if (agent == null) {
            return new InputFrame(rule(), rule(), null);
        }
        return new InputFrame(rule(), rule(), ContextBar.render(
                agent.estimatedTokens(), agent.contextLimit(),
                agent.history().size(), config.model()));
    }
}
