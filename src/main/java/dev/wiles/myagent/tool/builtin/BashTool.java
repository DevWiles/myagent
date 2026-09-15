package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.CommandGuard;
import dev.wiles.myagent.tool.safety.CommandRisk;

/**
 * 执行 shell 命令。**五个工具里威力最大、也最危险的一个。**
 *
 * 危险在于它绕过了 Workspace 沙箱：cat ../../../etc/passwd 照样能跑。
 * 一旦开放这个工具，前面辛苦做的路径校验就只剩「防手滑」的作用了。
 *
 * 所以这里叠了四层防护，层层都不完美，合起来才够用：
 *   1. 限定工作目录、超时、输出截断 —— 防手滑和防失控
 *   2. CommandGuard 静态评估 —— 挡住明显危险（但挡不住蓄意绕过）
 *   3. ApprovalPolicy 人工确认 —— 真正的防线，重要动作总有人过目
 *   4. 被拦下来时返回 error 而不是抛异常 —— 模型看得懂，会换个做法
 */
public final class BashTool implements Tool {

    /** 默认超时。模型很容易生成一个卡住的命令（比如误开交互式程序），不设超时 agent 就永远挂在那。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    /** 输出截断上限，理由同 ReadFileTool：工具输出会变成 token。 */
    private static final int MAX_OUTPUT_CHARS = 30_000;

    private final Workspace workspace;
    private final CommandGuard guard;
    private final ApprovalPolicy approval;

    /** 不指定审批策略时一律拒绝：非交互环境没人能点头，默认就该是最安全的那档。 */
    public BashTool(Workspace workspace) {
        this(workspace, new CommandGuard(), ApprovalPolicy.denyAll());
    }

    public BashTool(Workspace workspace, CommandGuard guard, ApprovalPolicy approval) {
        this.workspace = workspace;
        this.guard = guard;
        this.approval = approval;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在工作区根目录下执行一条 shell 命令，返回标准输出和标准错误的合并结果。"
                + "适合运行构建、测试、git 等命令。"
                + "禁止用它查看目录或读写文件——列目录必须用 list_dir，读文件必须用 read_file，"
                + "写文件必须用 write_file。"
                + "不要执行需要交互输入的命令。"
                + "删除文件、访问网络、安装依赖等操作会先征求用户同意，可能被拒绝。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "要执行的 shell 命令，例如 mvn -q test"
                    },
                    "timeout_seconds": {
                      "type": "integer",
                      "description": "超时秒数，默认 30，最大 300"
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

        // 先过安全闸：被拦下来要把理由讲清楚，模型才能换个做法而不是原样重试
        CommandRisk risk = guard.assess(command);
        if (risk.isForbidden()) {
            return ToolResult.error("这条命令被安全策略拒绝执行。原因: " + risk.reason()
                    + "。请改用其它方式完成目标，不要重试同样的命令。");
        }
        if (risk.needsApproval() && !approval.approve("执行命令", command, risk.reason())) {
            return ToolResult.error("用户拒绝执行这条命令（" + risk.reason() + "）。"
                    + "请换一种做法，或者先向用户说明为什么需要它。");
        }

        Process process;
        try {
            process = new ProcessBuilder("sh", "-c", command)
                    .directory(workspace.root().toFile())   // 限定工作目录
                    .redirectErrorStream(true)              // stderr 并入 stdout，模型要看完整报错
                    .start();
        } catch (IOException e) {
            return ToolResult.error("无法启动命令: " + e.getMessage());
        }

        // 关掉子进程的标准输入，等待输入的命令会立刻读到 EOF 而不是永远挂着
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 关不上也不影响后面
        }

        // 必须用独立线程读输出：
        //  - 若先 waitFor 再读，管道缓冲区（几十 KB）写满后子进程阻塞在写、我们阻塞在等，死锁
        //  - 若在主线程同步读到 EOF，又会让 waitFor 的超时形同虚设（比如 sleep 30 不产出任何输出）
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    synchronized (buffer) {
                        buffer.write(chunk, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时读流会抛异常，已读到的部分仍然有用
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();       // 恢复中断标志，别把信号吞掉
            return ToolResult.error("命令执行被中断");
        }

        if (!finished) {
            process.destroyForcibly();                // 不杀掉的话进程会留在后台
            try {
                process.waitFor(2, TimeUnit.SECONDS);
                reader.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.error("命令执行超时（超过 " + timeout + " 秒），已强制终止。"
                    + "已产生的输出:\n" + truncate(snapshot(buffer)));
        }

        try {
            reader.join(5000);   // 进程结束后给读线程一点时间收尾
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String output = truncate(snapshot(buffer));
        int exitCode = process.exitValue();

        // 退出码非 0 依然算工具执行成功：命令跑完了，只是结果失败（比如测试没过）。
        // 这和「工具本身没能执行」是两回事，模型需要看到完整输出去分析原因。
        StringBuilder sb = new StringBuilder();
        if (!output.isBlank()) {
            sb.append(output);
            if (!output.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("(没有输出)\n");
        }
        sb.append("退出码: ").append(exitCode);
        return ToolResult.ok(sb.toString());
    }

    private static String snapshot(ByteArrayOutputStream buffer) {
        synchronized (buffer) {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * 截断时保留头尾各一半，而不是只留开头——
     * 编译错误、测试失败的关键信息通常都在末尾。
     */
    private static String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        int half = MAX_OUTPUT_CHARS / 2;
        int omitted = text.length() - half * 2;
        return text.substring(0, half)
                + "\n\n... (中间 " + omitted + " 个字符已截断) ...\n\n"
                + text.substring(text.length() - half);
    }
}
