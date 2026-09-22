package dev.wiles.myagent;

import dev.wiles.myagent.cli.Cli;
import dev.wiles.myagent.cli.CliOptions;
import dev.wiles.myagent.relay.RelayServer;
import dev.wiles.myagent.server.AgentServer;


/**
 * 程序入口。
 *
 * <p><b>这个 jar 只有无头模式。</b>自带的终端界面（{@code Repl} 和 {@code cli/} 里
 * 那一套渲染）已经删掉了——一个项目维护两套界面，代价不只是多写一遍：
 * 同一个键位在两边行为不一致，比任何一种行为本身都更糟（那条教训记在 docs/devlog.md）。
 * 界面现在只有一个，用 {@code myagent-tui} 启动。
 */
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
        if (options.relayServe()) {
            relayServe(options);     // 公网那台机器上的角色：只对接两条连接，不起 agent
            return;
        }
        if (!options.serve()) {
            // 不默认起服务：手滑敲一下 java -jar 不该在当前目录上悄悄开一个能读写文件、
            // 能跑命令的服务。要什么就说什么
            System.err.println("这个 jar 只有无头模式，不带界面。");
            System.err.println("  起界面:  ./myagent-tui");
            System.err.println("  起后端:  java -jar myagent.jar --serve");
            System.err.println("用 --help 看全部参数。");
            System.exit(2);
            return;
        }
        serve(options);          // 日志走 stderr，由外部界面自己收
    }

    /**
     * 中继服务端。**同一个 jar 的第二个角色**，部署在公网机器上。
     *
     * <p>这条路径上不读 API key、不注册任何工具、不起 agent——
     * 它只把两条出站连接对上，连内容都看不懂（协议 1.3）。
     */
    private static void relayServe(CliOptions options) {
        RelayServer relay = new RelayServer(options.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                relay.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        relay.start();
        try {
            relay.awaitStop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 无头模式。前台挂住，收到 SIGINT/SIGTERM 时把服务停干净。 */
    private static void serve(CliOptions options) {
        AgentServer server = new AgentServer(options, options.port());

        // 不该在家目录/根目录上跑 agent
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
