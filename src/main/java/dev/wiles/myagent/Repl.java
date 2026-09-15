package dev.wiles.myagent;

import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.config.Config;
import dev.wiles.myagent.llm.DeepSeekClient;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.WorkspaceGuard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 交互式命令行外壳。
 *
 * 它现在只做三件事：读输入、把输入交给 Agent、把 agent 发出的事件画到屏幕上。
 * 真正的逻辑全在 Agent 里。
 */
public final class Repl {

    private static final String PROMPT = "\n> ";
    /** 工具输出在终端里最多显示几行，完整内容仍然会完整发给模型。 */
    private static final int OUTPUT_PREVIEW_LINES = 6;

    /** 和 TerminalApproval 共用一个 reader：各建各的会各自缓冲 System.in，输入会丢。 */
    private final BufferedReader in =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private final Config config;
    private final Workspace workspace;
    private final ToolRegistry tools;
    private final Agent agent;

    public Repl() {
        this.config = Config.load();
        this.workspace = Workspace.ofCurrentDir();
        this.tools = BuiltinTools.createDefault(workspace, new TerminalApproval(in));

        if (config.hasApiKey()) {
            LlmClient client = new DeepSeekClient(config);
            this.agent = new Agent(client, tools, SystemPrompt.forWorkspace(workspace.root()))
                    .onEvent(this::render);
        } else {
            this.agent = null;
        }
    }

    public void run() {
        String rejection = WorkspaceGuard.rejectionReason(workspace.root());
        if (rejection != null) {
            // 留一个逃生舱，但必须显式设环境变量——这样绝不会是手滑进来的
            if ("1".equals(System.getenv("MYAGENT_UNSAFE_WORKSPACE"))) {
                System.out.println("⚠ 警告: " + rejection);
                System.out.println("⚠ 你已通过 MYAGENT_UNSAFE_WORKSPACE=1 强制在此目录运行，请格外小心。\n");
            } else {
                System.out.println("拒绝在此目录启动: " + workspace.root());
                System.out.println(rejection);
                System.out.println("请 cd 到某个具体的项目目录再运行。");
                System.out.println("（确实需要在这里运行的话：MYAGENT_UNSAFE_WORKSPACE=1 java -jar ...）");
                return;
            }
        }
        printBanner();
        try {
            while (true) {
                System.out.print(PROMPT);
                System.out.flush();

                String line = in.readLine();
                if (line == null) {          // Ctrl-D
                    System.out.println();
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
                    System.out.println("未配置 API key，无法对话。请设置环境变量 DEEPSEEK_API_KEY，"
                            + "或在 ~/.myagent/config.json 里写 {\"apiKey\": \"sk-...\"}");
                    continue;
                }
                System.out.println("\n" + agent.run(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("bye.");
    }

    /**
     * 把 agent 的事件画到终端。
     *
     * 这一步不只是好看：一次 run 可能跑十几轮、花上一分钟，
     * 全程不出声的话，用户没法判断它是在干活还是卡死了。
     */
    private void render(AgentEvent event) {
        if (event instanceof AgentEvent.Thought t) {
            System.out.println("\n" + t.text());

        } else if (event instanceof AgentEvent.ToolStarted t) {
            System.out.println("\n⏺ " + t.tool() + " " + oneLine(t.arguments(), 100));

        } else if (event instanceof AgentEvent.ToolFinished t) {
            String mark = t.ok() ? "  ⎿ " : "  ⎿ ✗ ";
            String[] lines = t.output().split("\n");
            for (int i = 0; i < Math.min(lines.length, OUTPUT_PREVIEW_LINES); i++) {
                System.out.println((i == 0 ? mark : "    ") + oneLine(lines[i], 160));
            }
            if (lines.length > OUTPUT_PREVIEW_LINES) {
                System.out.println("    … 另有 " + (lines.length - OUTPUT_PREVIEW_LINES) + " 行");
            }

        } else if (event instanceof AgentEvent.Compacted c) {
            // 必须让用户知道：摘要意味着早期细节已经丢了，后面它"想不起来"是正常的
            System.out.println("\n… 上下文已压缩 " + c.tokensBefore() + " → " + c.tokensAfter()
                    + " tokens" + (c.summarized() ? "（早期对话已摘要）" : "（裁剪了旧的工具输出）"));

        } else if (event instanceof AgentEvent.Failed f) {
            System.out.println("\n⚠ " + f.reason());
        }
        // Thinking 和 Done 不单独渲染：前者太吵，后者由 run() 的返回值负责打印
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
                System.out.println("对话历史已清空。");
                return true;
            }
            case "/tools" -> {
                for (Tool tool : tools.all()) {
                    System.out.println("  " + tool.name() + "  —  " + tool.description());
                }
                return true;
            }
            case "/history" -> {
                if (agent == null) {
                    System.out.println("(未启用)");
                    return true;
                }
                System.out.println("当前上下文共 " + agent.history().size() + " 条消息，约 "
                        + agent.estimatedTokens() + " tokens（含每轮重发的工具菜单）:");
                agent.history().forEach(m -> System.out.println(
                        "  [" + m.role() + "] "
                                + (m.hasToolCalls() ? "调用 " + m.toolCallsOrEmpty().size() + " 个工具 " : "")
                                + oneLine(m.content() == null ? "" : m.content(), 80)));
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
                        /history  查看当前上下文里攒了哪些消息
                        /config   查看当前配置（密钥已脱敏）
                        /exit     退出""");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void printBanner() {
        System.out.println("myagent 0.1.0  (Java " + Runtime.version().feature() + ")");
        if (agent == null) {
            System.out.println("⚠ 未配置 API key，只能使用 /help 等本地命令。");
        } else {
            System.out.println("模型: " + config.model() + "   /help 查看命令，/exit 退出。");
        }
        System.out.println("工作区: " + workspace.root() + "   已注册 " + tools.all().size() + " 个工具");
    }
}
