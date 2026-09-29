package dev.wiles.myagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.config.Config;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.context.TokenLedger;
import dev.wiles.myagent.context.TokenMeter;
import dev.wiles.myagent.llm.DeepSeekClient;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.memory.MemoryIndex;
import dev.wiles.myagent.memory.MemoryStore;
import dev.wiles.myagent.plan.PlanContext;
import dev.wiles.myagent.plan.Planner;
import dev.wiles.myagent.team.Pipeline;
import dev.wiles.myagent.team.TeamResult;
import dev.wiles.myagent.team.TeamStatus;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无头单次运行：读一段提示词，跑完就退。给评测框架和沙盒用的入口。
 *
 * <h3>为什么不走 --serve + WebSocket</h3>
 * 在别人的容器里（AsynCodeBench 这类评测）只有一条 {@code execute_command}，
 * 端口映射不由我们定。一个进程从头跑到尾、结果在 stdout、退出码说成败，
 * 是最不挑环境的接口——Claude Code 的 {@code -p} 就是这个形状。
 *
 * <h3>危险动作一律自动批准</h3>
 * 没有人在看，问了也没人答。所以这个入口只能在沙盒里用，帮助里写明了。
 * 不接 MCP、不读技能：评测要的是可复现，外挂越少越好。
 */
public final class Headless {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Headless() {
    }

    /** @return 进程退出码：0 正常收口，1 没做完（撞上限 / 验收没过 / 模型失败），2 参数或配置错 */
    public static int run(CliOptions options) {
        String prompt;
        try {
            prompt = "-".equals(options.once())
                    ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(Path.of(options.once()));
        } catch (IOException e) {
            System.err.println("读不了提示词: " + e.getMessage());
            return 2;
        }
        if (prompt.isBlank()) {
            System.err.println("提示词是空的");
            return 2;
        }

        Config loaded = Config.load();
        Config config = options.model() == null ? loaded
                : new Config(loaded.apiKey(), loaded.baseUrl(), options.model(),
                        loaded.contextTokens(), loaded.thinking());
        if (!config.hasApiKey()) {
            System.err.println("没有 API key：配 ~/.myagent/config.json 或环境变量 DEEPSEEK_API_KEY");
            return 2;
        }

        Workspace workspace = new Workspace(options.workspace());
        MemoryStore memory = MemoryStore.inUserHome();
        ToolRegistry tools = BuiltinTools.createDefault(workspace, ApprovalPolicy.approveAll(), memory);
        TokenMeter meter = new TokenMeter();
        java.util.function.Supplier<LlmClient> clients = () -> meter.meter(new DeepSeekClient(config));
        int maxSteps = options.maxSteps() > 0 ? options.maxSteps() : Agent.DEFAULT_MAX_STEPS;

        long started = System.currentTimeMillis();
        ObjectNode summary = JSON.createObjectNode();
        summary.put("mode", options.onceMode());
        summary.put("model", config.model());
        summary.put("maxSteps", maxSteps);
        int exit;
        String text;
        try {
            if ("team".equals(options.onceMode())) {
                Planner planner = new Planner(clients.get())
                        .context(PlanContext.of(workspace.root(), tools));
                AtomicInteger steps = new AtomicInteger();
                Pipeline pipeline = new Pipeline(clients, tools, workspace.root(), planner);
                pipeline.listener(new dev.wiles.myagent.team.TeamListener() {
                    @Override
                    public void onAgentEvent(dev.wiles.myagent.plan.Task stage, AgentEvent event) {
                        if (event instanceof AgentEvent.Thinking) {
                            steps.incrementAndGet();
                        }
                    }
                });
                TeamResult result = pipeline.run(prompt);
                text = result.verdict() == null ? "" : result.verdict();
                summary.put("status", result.status().name());
                summary.put("rounds", result.rounds());
                summary.put("steps", steps.get());
                summary.put("seats", result.stages().size());
                exit = result.status() == TeamStatus.COMPLETED ? 0 : 1;
            } else {
                MemoryIndex index = memory.index(workspace.root(),
                        MemoryIndex.budgetFor(config.contextTokens()));
                Agent agent = new Agent(clients.get(), tools,
                        SystemPrompt.forWorkspace(workspace.root(), index, false),
                        maxSteps, new ContextManager(config.contextTokens()))
                        .withWritingTools(BuiltinTools.WRITING_TOOLS);
                AtomicInteger steps = new AtomicInteger();
                AtomicReference<String> failed = new AtomicReference<>();
                agent.onEvent(event -> {
                    if (event instanceof AgentEvent.Thinking) {
                        steps.incrementAndGet();
                    } else if (event instanceof AgentEvent.Failed f) {
                        failed.set(f.reason());
                    }
                });
                text = agent.run(prompt);
                summary.put("status", failed.get() == null ? "COMPLETED" : "FAILED");
                if (failed.get() != null) {
                    summary.put("failed", failed.get());
                }
                summary.put("steps", steps.get());
                exit = failed.get() == null ? 0 : 1;
            }
        } catch (RuntimeException e) {
            text = "";
            summary.put("status", "ERROR");
            summary.put("failed", e.getClass().getSimpleName() + ": " + e.getMessage());
            exit = 1;
        }
        TokenLedger ledger = meter.snapshot();
        summary.put("promptTokens", ledger.promptTokens());
        summary.put("completionTokens", ledger.completionTokens());
        summary.put("requests", ledger.requests());
        summary.put("durationMs", System.currentTimeMillis() - started);

        System.out.println(text);
        System.out.flush();
        String line = summary.toString();
        if (options.onceOut() != null) {
            try {
                Files.writeString(Path.of(options.onceOut()), line + "\n");
            } catch (IOException e) {
                System.err.println("写不了摘要 " + options.onceOut() + ": " + e.getMessage());
            }
        } else {
            System.err.println(line);
        }
        return exit;
    }
}
