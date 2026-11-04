package dev.wiles.myagent.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 用户提交一句话时跑的 hook，照 Claude Code 的 {@code UserPromptSubmit}。
 *
 * <h3>为什么要有它</h3>
 * agent 本身不按关键词猜用户意图（见 {@code Agent.run}）。可总有人想要确定性的规则——
 * 「提到发版就先把 CHANGELOG 规矩贴给它」「消息里有密钥就拦下」。这种规则不该写进 agent，
 * 交给用户自己写脚本：hook 拿到原话，可以<b>补一段上下文</b>，也可以<b>直接拦下</b>。
 *
 * <h3>配置</h3>
 * {@code ~/.myagent/settings.json} 和工作区的 {@code .myagent/settings.json}，两处都读、按这个顺序跑。
 * 格式和 Claude Code 一样，那边写好的直接搬过来能用：
 * <pre>
 * {"hooks": {"UserPromptSubmit": [
 *   {"hooks": [{"type": "command", "command": "python ~/.myagent/hooks/guard.py", "timeout": 30}]}
 * ]}}</pre>
 *
 * <h3>约定（和 Claude Code 一致）</h3>
 * 命令用 {@code sh -c} 在工作区根目录跑，stdin 收到一个 JSON：
 * {@code {"hook_event_name","session_id","cwd","prompt"}}。
 * <ul>
 *   <li>退出码 0：stdout 当作补充上下文拼给模型；stdout 是 JSON 时认
 *       {@code {"decision":"block","reason":...}} 和 {@code hookSpecificOutput.additionalContext}</li>
 *   <li>退出码 2：拦下这句话，stderr 作为理由给用户看，模型看不到这句话</li>
 *   <li>别的退出码、超时、起不来：不拦，stderr 作为警告给用户看——hook 自己坏了不该把人卡住</li>
 * </ul>
 */
public final class PromptHooks {

    private static final Logger log = LoggerFactory.getLogger(PromptHooks.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String EVENT = "UserPromptSubmit";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** 一条 hook 命令。 */
    public record Command(String command, int timeoutSeconds, Path source) {
    }

    /**
     * 跑完所有 hook 的结论。
     *
     * @param blocked  被拦下了吗；拦下时 {@code reason} 是给用户看的理由
     * @param context  要拼给模型的补充上下文，每个 hook 一段
     * @param warnings hook 自己出的问题，给用户看，不拦
     */
    public record Outcome(boolean blocked, String reason, List<String> context, List<String> warnings) {
        static final Outcome PASS = new Outcome(false, "", List.of(), List.of());
    }

    private final List<Command> commands;
    private final Path workspace;

    public PromptHooks(List<Command> commands, Path workspace) {
        this.commands = List.copyOf(commands);
        this.workspace = workspace;
    }

    /** 读用户级和工作区级两份配置。读不了、格式不对的跳过并记日志，不让启动失败。 */
    public static PromptHooks load(Path workspace) {
        List<Command> all = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"), ".myagent", "settings.json");
        all.addAll(read(home));
        Path project = workspace.resolve(".myagent").resolve("settings.json");
        if (!project.toAbsolutePath().normalize().equals(home.toAbsolutePath().normalize())) {
            all.addAll(read(project));
        }
        if (!all.isEmpty()) {
            log.info("接入 {} 条 {} hook", all.size(), EVENT);
        }
        return new PromptHooks(all, workspace);
    }

    static List<Command> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return parse(JSON.readTree(file.toFile()), file);
        } catch (IOException e) {
            log.warn("读不了 hook 配置 {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    static List<Command> parse(JsonNode root, Path source) {
        List<Command> out = new ArrayList<>();
        for (JsonNode group : root.path("hooks").path(EVENT)) {
            for (JsonNode hook : group.path("hooks")) {
                String type = hook.path("type").asText("command");
                String command = hook.path("command").asText("").strip();
                if (!"command".equals(type) || command.isEmpty()) {
                    continue;
                }
                int timeout = hook.path("timeout").asInt(DEFAULT_TIMEOUT_SECONDS);
                out.add(new Command(command, timeout > 0 ? timeout : DEFAULT_TIMEOUT_SECONDS, source));
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }

    /** 依次跑。前一个拦下了，后面的就不跑了。 */
    public Outcome run(String prompt, String sessionId) {
        if (commands.isEmpty()) {
            return Outcome.PASS;
        }
        ObjectNode input = JSON.createObjectNode();
        input.put("hook_event_name", EVENT);
        input.put("session_id", sessionId);
        input.put("cwd", String.valueOf(workspace));
        input.put("prompt", prompt);
        byte[] stdin = input.toString().getBytes(StandardCharsets.UTF_8);

        List<String> context = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Command command : commands) {
            Result r = exec(command, stdin);
            if (r.error != null) {
                warnings.add("hook「" + command.command() + "」" + r.error);
                continue;
            }
            if (r.exit == 2) {
                String reason = r.stderr.isBlank() ? "被 hook「" + command.command() + "」拦下了" : r.stderr.strip();
                return new Outcome(true, reason, List.of(), warnings);
            }
            if (r.exit != 0) {
                warnings.add("hook「" + command.command() + "」退出码 " + r.exit
                        + (r.stderr.isBlank() ? "" : ": " + r.stderr.strip()));
                continue;
            }
            String stdout = r.stdout.strip();
            if (stdout.startsWith("{")) {
                try {
                    JsonNode out = JSON.readTree(stdout);
                    if ("block".equals(out.path("decision").asText())) {
                        String reason = out.path("reason").asText("").strip();
                        return new Outcome(true, reason.isEmpty() ? "被 hook「" + command.command() + "」拦下了" : reason,
                                List.of(), warnings);
                    }
                    String extra = out.path("hookSpecificOutput").path("additionalContext").asText("").strip();
                    if (!extra.isEmpty()) {
                        context.add(extra);
                    }
                    continue;
                } catch (IOException e) {
                    // 不是 JSON，就当纯文本
                }
            }
            if (!stdout.isEmpty()) {
                context.add(stdout);
            }
        }
        return new Outcome(false, "", context, warnings);
    }

    private record Result(int exit, String stdout, String stderr, String error) {
    }

    /**
     * 命令先写进一个临时脚本再交给 sh，而不是 {@code sh -c 命令}：Windows 上 Java 拼命令行时
     * 不转义参数里的双引号，{@code echo '{"decision":"block"}'} 这种 hook 一传过去就断了。
     */
    private Result exec(Command command, byte[] stdin) {
        Path script;
        try {
            script = Files.createTempFile("myagent-hook-", ".sh");
            Files.writeString(script, command.command() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result(-1, "", "", "写不了临时脚本: " + e.getMessage());
        }
        try {
            return exec(command, script, stdin);
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException e) {
                script.toFile().deleteOnExit();
            }
        }
    }

    private Result exec(Command command, Path script, byte[] stdin) {
        Process process;
        try {
            process = new ProcessBuilder("sh", script.toAbsolutePath().toString())
                    .directory(workspace.toFile()).start();
        } catch (IOException e) {
            return new Result(-1, "", "", "起不来: " + e.getMessage());
        }
        CompletableFuture<String> out = drain(process.getInputStream());
        CompletableFuture<String> err = drain(process.getErrorStream());
        try (OutputStream in = process.getOutputStream()) {
            in.write(stdin);
        } catch (IOException e) {
            // 命令不读 stdin 就先关了管道，这不算错
        }
        try {
            if (!process.waitFor(command.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, "", "", "超时（" + command.timeoutSeconds() + " 秒），没等它");
            }
            return new Result(process.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, "", "", "被中断");
        } catch (Exception e) {
            return new Result(-1, "", "", "读输出失败: " + e.getMessage());
        }
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                stream.transferTo(buf);
                return buf.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}
