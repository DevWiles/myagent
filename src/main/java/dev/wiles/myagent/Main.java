package dev.wiles.myagent;

import dev.wiles.myagent.cli.Cli;
import dev.wiles.myagent.cli.CliOptions;
import dev.wiles.myagent.server.AgentServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 程序入口：解析参数，然后交给 Repl（自带界面）或 AgentServer（外部界面）。 */
public final class Main {

    public static void main(String[] args) {
        CliOptions options;
        try {
            options = Cli.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(Cli.usage());
            return;
        }
        if (options.version()) {
            System.out.println("myagent " + Cli.VERSION);
            return;
        }
        if (options.serve()) {
            serve(options);      // 无头模式的日志继续走 stderr，由外部界面自己收
            return;
        }
        redirectLogsToFile();
        new Repl(options).run();
    }

    /**
     * 交互模式下把 slf4j 的输出从 stderr 挪到文件。
     *
     * <p>不挪的话，任何一条 info 日志都会直接打进终端界面——全屏模式下更是直接把画面搅乱。
     * 必须在<b>第一次拿 Logger 之前</b>设置，slf4j-simple 是在首次初始化时读这个属性的，
     * 之后再改没用。所以这件事只能在 main 里、Repl 构造之前做。
     */
    private static void redirectLogsToFile() {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".myagent", "logs");
            Files.createDirectories(dir);
            System.setProperty("org.slf4j.simpleLogger.logFile",
                    dir.resolve("myagent.log").toString());
        } catch (IOException | RuntimeException e) {
            // 建不了目录就还走 stderr。日志去向不值得为它挡住启动
            System.err.println("警告: 日志文件建不了，继续输出到终端: " + e.getMessage());
        }
    }

    /** 无头模式。前台挂住，收到 SIGINT/SIGTERM 时把服务停干净。 */
    private static void serve(CliOptions options) {
        AgentServer server = new AgentServer(options, options.port());

        // 安全检查和交互式模式同一套标准：不该在家目录/根目录上跑 agent
        String rejection = server.workspaceRejection();
        if (rejection != null) {
            System.err.println("拒绝在此目录启动: " + options.workspace().toAbsolutePath());
            System.err.println(rejection);
            System.err.println("请指定一个具体的项目目录（-d），确实需要的话加 --unsafe");
            System.exit(3);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        server.start();
        try {
            server.awaitStop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
