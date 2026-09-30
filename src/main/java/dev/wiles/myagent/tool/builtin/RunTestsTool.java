package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 只能跑测试的命令工具。给 coder 这种<b>不能拿 bash</b> 的角色用：
 * 改完自己跑一遍测试，不用把"写 → 跑 → 改"的循环拆成三道工序。
 *
 * <h3>为什么不是 bash 加白名单</h3>
 * bash 的能力上限是 shell：{@code pytest; rm -rf .} 在 shell 眼里是一条命令。
 * 这里<b>不经过 shell</b>——命令按空白切成 argv 直接 exec，第一段必须是白名单里的
 * 测试运行器，任何一段含 shell 元字符、绝对路径、{@code ..} 都拒绝。
 * 删库的路在结构上就不存在，而不是靠模式匹配拦。
 *
 * <p>它不受审批策略约束：能跑的东西本身就是安全的。也不算"会动世界"的工具——
 * 测试可能写缓存，但不会改任何源文件，所以持有它的角色仍然可以按文件集合并行
 * （见 {@code PlanExecutor.scoped}）。
 */
public final class RunTestsTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_OUTPUT_CHARS = 30_000;

    /** 白名单：命令的开头必须精确匹配其中一条（按 argv 逐段比）。 */
    static final List<List<String>> RUNNERS = List.of(
            List.of("pytest"), List.of("python", "-m", "pytest"), List.of("python3", "-m", "pytest"),
            List.of("python", "-m", "unittest"), List.of("python3", "-m", "unittest"),
            List.of("mvn"), List.of("gradle", "test"), List.of("./gradlew", "test"),
            List.of("npm", "test"), List.of("npm", "run", "test"), List.of("yarn", "test"),
            List.of("pnpm", "test"), List.of("bun", "test"), List.of("npx", "jest"), List.of("npx", "vitest"),
            List.of("go", "test"), List.of("cargo", "test"), List.of("dotnet", "test"));

    /** mvn 只许这两个目标；clean / install / exec 之类都不是"跑测试"。 */
    private static final Set<String> MVN_GOALS = Set.of("test", "verify");

    private static final String META = ";&|<>`$(){}*?~!#\\'\"\n\r";

    private final Workspace workspace;

    public RunTestsTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "run_tests";
    }

    @Override
    public String description() {
        return "在工作区根目录运行一条测试命令，返回完整输出和退出码。"
                + "这不是 shell：只接受测试运行器（pytest / python -m pytest / python -m unittest / "
                + "mvn test / npm test / go test / cargo test 等）开头的命令，"
                + "不能带 ; && | > 等符号，不能用绝对路径或 ..。"
                + "改完代码用它验证；要看文件用 read_file，要列目录用 list_dir。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "测试命令，例如 python -m pytest -q tests/test_keys.py"
                    },
                    "timeout_seconds": {
                      "type": "integer",
                      "description": "超时秒数，默认 120，最大 600"
                    }
                  },
                  "required": ["command"]
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String command = Args.requireString(args, "command");
        int timeout = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS,
                Args.optInt(args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS)));
        List<String> argv;
        try {
            argv = parse(command);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("run_tests 拒绝了这条命令: " + e.getMessage()
                    + "。它只跑测试运行器，不是 shell；请只给测试命令本身，不要重试同样的写法。");
        }
        ProcessRunner.Outcome out;
        try {
            out = ProcessRunner.run(argv, workspace.root(), timeout);
        } catch (IOException e) {
            return ToolResult.error("无法启动命令: " + e.getMessage());
        }
        if (out.interrupted()) {
            return ToolResult.error("命令执行被中断");
        }
        String output = ProcessRunner.truncate(out.output(), MAX_OUTPUT_CHARS);
        if (!out.finished()) {
            return ToolResult.error("命令执行超时（超过 " + timeout + " 秒），已强制终止。"
                    + "已产生的输出:\n" + output);
        }
        StringBuilder sb = new StringBuilder();
        if (!output.isBlank()) {
            sb.append(output);
            if (!output.endsWith("\n")) {
                sb.append('\n');
            }
        }
        sb.append("退出码: ").append(out.exitCode());
        return ToolResult.ok(sb.toString());
    }

    /** 切成 argv 并逐段校验。抛 IAE 时消息是给模型看的。 */
    static List<String> parse(String command) {
        String trimmed = command == null ? "" : command.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("命令是空的");
        }
        List<String> argv = new ArrayList<>(List.of(trimmed.split("\\s+")));
        for (String token : argv) {
            for (char c : token.toCharArray()) {
                if (META.indexOf(c) >= 0) {
                    throw new IllegalArgumentException("参数 \"" + token + "\" 含 shell 符号 '" + c + "'");
                }
            }
            if (token.startsWith("/") || token.equals("..") || token.startsWith("../")
                    || token.contains("/../") || token.endsWith("/..") || token.startsWith("~")) {
                throw new IllegalArgumentException("参数 \"" + token + "\" 指向工作区之外");
            }
        }
        List<String> runner = RUNNERS.stream()
                .filter(prefix -> argv.size() >= prefix.size() && argv.subList(0, prefix.size()).equals(prefix))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("\"" + argv.get(0)
                        + "\" 不是已知的测试运行器（允许: pytest / python -m pytest / python -m unittest / "
                        + "mvn test / gradle test / npm test / go test / cargo test / dotnet test 等）"));
        if (runner.equals(List.of("mvn"))) {
            List<String> goals = argv.stream().skip(1).filter(t -> !t.startsWith("-")).toList();
            if (goals.isEmpty() || !MVN_GOALS.containsAll(goals)) {
                throw new IllegalArgumentException("mvn 只允许 test / verify 目标，收到: " + goals);
            }
        }
        return argv;
    }
}
