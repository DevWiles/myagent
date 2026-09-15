package dev.wiles.myagent;

import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.cli.Ansi;
import dev.wiles.myagent.cli.Banner;
import dev.wiles.myagent.cli.Cli;
import dev.wiles.myagent.cli.CliOptions;
import dev.wiles.myagent.cli.LineReader;
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

    /** 工具输出在终端里最多显示几行；完整内容照样发给模型。 */
    private static final int OUTPUT_PREVIEW_LINES = 6;

    private static final List<String> SLASH_COMMANDS =
            List.of("/help", "/clear", "/tools", "/history", "/config", "/exit", "/quit");

    private final CliOptions options;
    private final Config config;
    private final Workspace workspace;
    private final ToolRegistry tools;
    private final TerminalMode terminal = new TerminalMode();
    private final LineReader reader;
    private final Agent agent;

    /** 当前是否有一行流式文本还没收尾。 */
    private boolean streaming = false;

    public Repl(CliOptions options) {
        this.options = options;
        Ansi.setEnabled(options.color() && TerminalMode.isInteractive());

        Config loaded = Config.load();
        this.config = options.model() == null ? loaded
                : new Config(loaded.apiKey(), loaded.baseUrl(), options.model());

        this.workspace = new Workspace(options.workspace());
        // raw 模式拿不到就退化成按行读，管道和重定向下都是这条路径
        boolean raw = terminal.enterRaw();
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
        agent.run(prompt);          // 内容已经通过流式回调打印过了
        System.out.println();
    }

    private void loop() {
        while (true) {
            String line = reader.readLine(Ansi.cyan("› "));
            if (line == null) {                          // Ctrl-D
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if ("/exit".equals(line) || "/quit".equals(line)) {
                break;
            }
            if (handleSlashCommand(line)) {
                continue;
            }
            if (agent == null) {
                System.out.println(noKeyMessage());
                continue;
            }
            agent.run(line);        // 内容已经通过流式回调打印过了
            System.out.println();
        }
        System.out.println(Ansi.dim("bye."));
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
        if (event instanceof AgentEvent.TextDelta d) {
            if (!streaming) {
                System.out.println();      // 新一段文本和上面的工具输出隔开
            }
            // 边收边打，不换行——同一段的后续增量紧接着往后续
            System.out.print(d.text());
            System.out.flush();
            streaming = true;

        } else if (event instanceof AgentEvent.Thought t) {
            // 文本已经由 TextDelta 实时打出来了，这里不再重复渲染

        } else if (event instanceof AgentEvent.ToolStarted t) {
            endStreamingLine();
            System.out.println("\n" + Ansi.green("⏺ ") + Ansi.bold(t.tool()) + " "
                    + Ansi.dim(oneLine(t.arguments(), 100)));

        } else if (event instanceof AgentEvent.ToolFinished t) {
            endStreamingLine();
            String mark = t.ok() ? Ansi.dim("  ⎿ ") : Ansi.red("  ⎿ ✗ ");
            String[] lines = t.output().split("\n");
            for (int i = 0; i < Math.min(lines.length, OUTPUT_PREVIEW_LINES); i++) {
                System.out.println((i == 0 ? mark : "    ") + Ansi.dim(oneLine(lines[i], 160)));
            }
            if (lines.length > OUTPUT_PREVIEW_LINES) {
                System.out.println(Ansi.dim("    … 另有 " + (lines.length - OUTPUT_PREVIEW_LINES) + " 行"));
            }

        } else if (event instanceof AgentEvent.Done d) {
            endStreamingLine();

        } else if (event instanceof AgentEvent.Compacted c) {
            endStreamingLine();
            // 必须让用户知道：摘要意味着早期细节已经丢了，后面它"想不起来"是正常的
            System.out.println("\n" + Ansi.dim("… 上下文已压缩 " + c.tokensBefore() + " → "
                    + c.tokensAfter() + " tokens"
                    + (c.summarized() ? "（早期对话已摘要）" : "（裁剪了旧的工具输出）")));

        } else if (event instanceof AgentEvent.Failed f) {
            endStreamingLine();
            System.out.println(Ansi.red("⚠ " + f.reason()));
        }
        // Thinking 不渲染，太吵
    }

    /** 流式输出没有换行符收尾，后面要打别的东西之前得先补一个。 */
    private void endStreamingLine() {
        if (streaming) {
            System.out.println();
            streaming = false;
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
            case "/config" -> {
                System.out.println(config);
                return true;
            }
            case "/help" -> {
                System.out.println("""
                          /clear    清空对话历史
                          /tools    查看已注册的工具（即发给模型的菜单）
                          /history  查看当前上下文攒了哪些消息
                          /config   查看当前配置（密钥已脱敏）
                          /exit     退出

                        快捷键: ↑↓ 历史   Tab 补全   Ctrl-A/E 行首尾   Ctrl-U/K 删到行首/尾
                                Ctrl-W 删词   Ctrl-C 放弃当前行   Ctrl-D 退出""");
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
                options.planMode(), agent != null));
    }
}
