package dev.wiles.myagent.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行参数解析。
 *
 * 手写而不是引第三方库（picocli 之类）：参数就这么几个，
 * 自己写一个 switch 比引一个几百 KB 的依赖划算。
 */
public final class Cli {

    public static final String VERSION = "0.2.0";

    private Cli() {
    }

    public static CliOptions parse(String[] args) {
        Path workspace = Path.of("");
        String model = null;
        boolean planMode = false;
        boolean unsafe = "1".equals(System.getenv("MYAGENT_UNSAFE_WORKSPACE"));
        boolean color = true;
        boolean spinner = true;
        boolean mouse = false;
        boolean clearScreen = true;
        boolean fullscreen = false;
        boolean help = false;
        boolean version = false;
        boolean serve = false;
        int port = 0;
        List<String> rest = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> help = true;
                case "-v", "--version" -> version = true;
                case "-p", "--plan" -> planMode = true;
                case "--unsafe" -> unsafe = true;
                case "--no-color" -> color = false;
                case "--no-spinner" -> spinner = false;
                case "--mouse" -> mouse = true;
                case "--no-clear" -> clearScreen = false;
                case "--fullscreen", "-f" -> fullscreen = true;
                case "--serve" -> serve = true;
                case "--port" -> {
                    requireValue(args, i, arg);
                    port = parsePort(args[++i]);
                }
                case "-d", "--dir" -> {
                    requireValue(args, i, arg);
                    workspace = Path.of(args[++i]);
                }
                case "-m", "--model" -> {
                    requireValue(args, i, arg);
                    model = args[++i];
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("未知参数: " + arg + "（用 --help 查看用法）");
                    }
                    rest.add(arg);        // 位置参数拼成一次性提示词
                }
            }
        }
        return new CliOptions(workspace, model, planMode, unsafe, color, spinner, mouse,
                clearScreen, fullscreen,
                rest.isEmpty() ? null : String.join(" ", rest), help, version, serve, port);
    }

    private static int parsePort(String value) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--port 要跟一个数字，收到: " + value);
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("--port 超出范围: " + port);
        }
        return port;
    }

    private static void requireValue(String[] args, int i, String arg) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException(arg + " 后面要跟一个值");
        }
    }

    public static String usage() {
        return """
                myagent %s —— 一个从零手写的编码 agent

                用法:
                  myagent [选项]              进入交互模式
                  myagent [选项] "任务描述"    执行一次就退出

                选项:
                  -d, --dir <路径>     指定工作区目录（默认当前目录）
                  -m, --model <名称>   覆盖配置里的模型
                  -p, --plan           只读模式，只给它查看类工具，不能改文件和执行命令
                      --unsafe         跳过工作区安全检查（危险）
                      --no-color       关闭彩色输出
                      --no-spinner     关闭转圈状态行（排查终端渲染问题用）
                  -f, --fullscreen     占用整个终端（退出后恢复原样）。方向键/滚轮滚动内容，
                                       翻输入历史改用 Ctrl-P / Ctrl-N
                      --no-clear       启动时不清屏，保留上文
                      --mouse          开启鼠标点击展开（代价：终端的滚动和拖拽选中
                                       都会被程序接管，基本不可用，不建议开）
                  -h, --help           显示本帮助
                  -v, --version        显示版本

                无头模式（供外部界面驱动，自己不画任何东西）:
                      --serve          只监听 127.0.0.1 的 WebSocket，不进终端交互。
                                       握手信息（端口 + 一次性 token）以一行 JSON 打在 stdout
                      --port <端口>    无头模式监听端口，默认 0（系统分配，推荐）

                配置:
                  ~/.myagent/config.json   {"apiKey": "sk-...", "model": "deepseek-chat"}
                  环境变量 DEEPSEEK_API_KEY 优先级高于配置文件

                示例:
                  myagent                          在当前目录进入交互模式
                  myagent -p "这个项目怎么组织的"    只读模式问一个问题就退出
                  myagent -d ~/code/demo           指定工作区
                """.formatted(VERSION);
    }
}
