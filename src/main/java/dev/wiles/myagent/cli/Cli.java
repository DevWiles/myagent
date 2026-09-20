package dev.wiles.myagent.cli;

import java.nio.file.Path;

/**
 * 命令行参数解析。
 *
 * <p>手写而不是引第三方库（picocli 之类）：参数就这么几个，
 * 自己写一个 switch 比引一个几百 KB 的依赖划算。
 *
 * <p>自带的终端界面删掉之后，这里只剩无头模式的参数。那些只影响终端渲染的开关
 * （颜色、转圈、全屏、鼠标）一并去掉了——见 {@link CliOptions} 的类注释。
 */
public final class Cli {

    public static final String VERSION = "0.3.0";

    private Cli() {
    }

    public static CliOptions parse(String[] args) {
        Path workspace = Path.of("");
        String model = null;
        boolean readOnly = false;
        boolean unsafe = "1".equals(System.getenv("MYAGENT_UNSAFE_WORKSPACE"));
        boolean help = false;
        boolean version = false;
        boolean serve = false;
        int port = 0;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> help = true;
                case "-v", "--version" -> version = true;
                // -p/--plan 是只读模式的老名字。界面还在传它（tui/src/backend.ts），
                // 而且老脚本里也可能有——改掉它得两边同时改，不值当
                case "-r", "--readonly", "-p", "--plan" -> readOnly = true;
                case "--unsafe" -> unsafe = true;
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
                default -> throw new IllegalArgumentException(arg.startsWith("-")
                        ? "未知参数: " + arg + "（用 --help 查看用法）"
                        // 位置参数以前是「一次性提示词」，跑完就退。自带界面删掉之后
                        // 没有任何东西会读它——静默忽略等于让人对着一个不干活的命令发呆
                        : "多余的参数: " + arg + "（这个版本不接受位置参数，"
                                + "要对话请用 myagent-tui 起界面）");
            }
        }
        return new CliOptions(workspace, model, readOnly, unsafe, help, version, serve, port);
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

                这个 jar 是后端，自己不画任何界面。界面用 myagent-tui 启动，
                它会按需 build 这个 jar 再把界面拉起来。

                用法:
                  java -jar myagent.jar --serve [选项]

                选项:
                      --serve          起一个只监听 127.0.0.1 的 WebSocket 服务。
                                       握手信息（端口 + 一次性 token）以一行 JSON 打在 stdout
                      --port <端口>    监听端口，默认 0（系统分配，推荐）
                  -d, --dir <路径>     指定工作区目录（默认当前目录）
                  -m, --model <名称>   覆盖配置里的模型
                  -r, --readonly       只读模式：写文件和执行命令的工具压根不注册
                      --unsafe         跳过工作区安全检查（危险）
                  -h, --help           显示本帮助
                  -v, --version        显示版本

                配置:
                  ~/.myagent/config.json   {"apiKey": "sk-...", "model": "deepseek-flash"}
                  环境变量 DEEPSEEK_API_KEY 优先级高于配置文件

                协议见 docs/protocol.md——照着它可以自己写一个界面。
                """.formatted(VERSION);
    }
}
