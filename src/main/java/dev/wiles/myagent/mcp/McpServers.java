package dev.wiles.myagent.mcp;

import dev.wiles.myagent.browser.ChromeSession;
import dev.wiles.myagent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 所有已连上的 MCP server：装配一次、用一场、退出时统一关。
 *
 * <h3>启动纪律：并行、限时、单个失败只作废单个</h3>
 * 三条都是被同一个事实逼出来的：<b>MCP server 是外部进程</b>，
 * 起多快、起不起得来都不归我们管（npx 冷启动能到十几秒）。而这段时间
 * 站在整个 agent 的启动路径上——TUI 那头等握手只等 15 秒，刚做完的
 * 启动提速也不能在这儿吐回去。所以：
 * <ul>
 *   <li>多个 server <b>并行</b>拉起，总耗时是最慢那家而不是加起来；</li>
 *   <li>每家限时 {@value #STARTUP_TIMEOUT_MILLIS} 毫秒，到点不候——warn 一声跳过；</li>
 *   <li>没配 mcp.json 的用户（绝大多数）这里是零成本：一个线程都不起。</li>
 * </ul>
 */
public final class McpServers implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpServers.class);

    /** 每家 server 从 spawn 到 tools/list 完成的预算。 */
    static final long STARTUP_TIMEOUT_MILLIS = 10_000;

    private final List<McpClient> clients;
    private final List<Tool> tools;

    private McpServers(List<McpClient> clients, List<Tool> tools) {
        this.clients = clients;
        this.tools = tools;
    }

    public static McpServers start(McpConfig config) {
        if (config.servers().isEmpty()) {
            return new McpServers(List.of(), List.of());
        }

        // 每家一个 future，并行握手。结果按配置顺序收——工具菜单的顺序要稳定
        List<Map.Entry<String, CompletableFuture<Started>>> startups = new ArrayList<>();
        for (Map.Entry<String, McpConfig.ServerSpec> entry : config.servers().entrySet()) {
            startups.add(Map.entry(entry.getKey(),
                    CompletableFuture.supplyAsync(() -> connect(entry.getKey(), entry.getValue()))));
        }

        List<McpClient> clients = new ArrayList<>();
        List<Tool> tools = new ArrayList<>();
        long deadline = System.nanoTime() + STARTUP_TIMEOUT_MILLIS * 1_000_000L;
        for (Map.Entry<String, CompletableFuture<Started>> startup : startups) {
            try {
                // deadline 是全体共享的：并行起的，等最慢那家就等了所有家
                long left = Math.max(1, (deadline - System.nanoTime()) / 1_000_000L);
                Started started = startup.getValue().get(left, TimeUnit.MILLISECONDS);
                clients.add(started.client);
                tools.addAll(started.tools);
                log.info("MCP server {} 接入，{} 个工具", startup.getKey(), started.tools.size());
            } catch (TimeoutException e) {
                log.warn("MCP server {} 在 {} 秒内没就绪，本场不接它——起得慢的 server"
                                + "（npx 首次下载）第二次启动通常就快了",
                        startup.getKey(), STARTUP_TIMEOUT_MILLIS / 1000);
                startup.getValue().thenAccept(s -> s.client.close());   // 迟到的连接别成孤儿
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("MCP server {} 起不来，跳过: {}", startup.getKey(), cause.getMessage());
            }
        }
        return new McpServers(clients, tools);
    }

    private record Started(McpClient client, List<Tool> tools) {
    }

    /**
     * 一家 server 的完整接入：（备浏览器 →）spawn → 握手 → 清点工具。
     * 任何一步炸了整家作废。
     */
    private static Started connect(String name, McpConfig.ServerSpec spec) {
        // 参数里写了 --browserUrl 的，说明它要连一个现成的浏览器而不是自己拉。
        // 那就得有人保证那个浏览器在，否则握手直接失败——而这件事只有我们能做，
        // server 进程自己是连不上就退。备不好也只是少一家 server，不拦别人的路
        prepareBrowser(name, spec);
        McpClient client;
        try {
            client = McpClient.spawn(name, spec);
        } catch (Exception e) {
            throw new McpClient.McpException("拉不起来（" + spec.command() + "）: " + e.getMessage());
        }
        try {
            client.initialize();
            List<McpClient.RemoteTool> offered = client.listTools();
            List<Tool> tools = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (McpClient.RemoteTool remote : offered) {
                if (spec.wants(remote.name())) {
                    tools.add(new McpTool(client, remote));
                } else {
                    dropped.add(remote.name());
                }
            }
            // 筛掉了什么必须说出来：配置里打错一个工具名的症状是「模型就是不用那个工具」，
            // 而菜单上少一个和这个工具压根不存在，在事件流里长得一模一样
            if (spec.picky()) {
                log.info("MCP server {} 提供 {} 个工具，按配置留下 {} 个，筛掉: {}",
                        name, offered.size(), tools.size(),
                        dropped.isEmpty() ? "（一个都没筛掉，检查配置里的名字有没有写错）"
                                : String.join(" ", dropped));
            }
            return new Started(client, tools);
        } catch (RuntimeException e) {
            client.close();                  // 握手失败的进程不能留着
            throw e;
        }
    }

    /**
     * 这家 server 要连现成的浏览器的话，先把浏览器备好。
     *
     * <p>端口从它自己的 {@code --browserUrl} 里读，不让用户再配一遍——
     * 同一个事实写两处迟早对不上。没写这个参数的 server 这里一步都不做。
     */
    private static void prepareBrowser(String name, McpConfig.ServerSpec spec) {
        Optional<URI> target = ChromeSession.debugTargetIn(spec.args());
        if (target.isEmpty()) {
            return;
        }
        log.info("MCP server {} 要连 {} 上的浏览器，先确认它在", name, target.get());
        new ChromeSession().ensure(target.get());
    }

    /** 接入成功的所有工具，给注册表。 */
    public List<Tool> tools() {
        return tools;
    }

    @Override
    public void close() {
        clients.forEach(McpClient::close);
    }
}
