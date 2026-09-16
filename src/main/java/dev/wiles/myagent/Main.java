package dev.wiles.myagent;

import dev.wiles.myagent.cli.Cli;
import dev.wiles.myagent.cli.CliOptions;
import dev.wiles.myagent.server.AgentServer;

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
            serve(options);
            return;
        }
        new Repl(options).run();
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
