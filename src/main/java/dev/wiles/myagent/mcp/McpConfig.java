package dev.wiles.myagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP server 的清单，读自 ~/.myagent/mcp.json。
 *
 * <pre>
 * {
 *   "servers": {
 *     "fs":     { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"] },
 *     "fetch":  { "command": "uvx", "args": ["mcp-server-fetch"], "env": { "HTTPS_PROXY": "…" } },
 *     "chrome": { "command": "npx", "args": ["-y", "chrome-devtools-mcp@latest"],
 *                 "exclude": ["take_screenshot", "emulate"] }
 *   }
 * }
 * </pre>
 *
 * <p>解析纪律和 {@link dev.wiles.myagent.config.Config} 一脉相承但更狠：
 * <b>任何一条配错都只作废那一条</b>，警告后继续。MCP 是外挂能力——
 * 一个 server 的配置写错了，代价应该是少一个 server，
 * 而不是整个 agent 起不来。文件不存在就是空清单，这也是绝大多数用户的状态。
 */
public record McpConfig(Map<String, ServerSpec> servers) {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    /**
     * 一个 server 怎么拉起来：命令、参数、附加环境变量，外加要不要挑工具。
     *
     * <h3>为什么要能挑</h3>
     * server 上有多少工具不归我们管，而<b>每一个都要常驻在每一次请求的工具菜单里</b>。
     * 实测 chrome-devtools 一家就是 29 个工具、两万多字符——基线窗口从 3.3k 涨到 9.9k，
     * 而 ReAct 每走一步都要把整个窗口重发一遍。这笔钱不管这一轮用不用得上浏览器都得付。
     *
     * <p>所以给两把刀：{@code tools} 是白名单（只要这几个），{@code exclude} 是黑名单
     * （别的都要，就这几个不要）。两个都写就先过白名单再剔黑名单。
     * <b>都不写就是全都要</b>——挑工具是优化，不是必填项。
     *
     * <p>名字用的是 <b>server 那边的原名</b>（{@code take_screenshot}），
     * 不是注册进菜单后的 {@code mcp__chrome__take_screenshot}：用户是照着
     * server 的文档写配置的，让他去推我们的前缀规则等于给一件优化设了道门槛。
     */
    public record ServerSpec(String command, List<String> args, Map<String, String> env,
                             Set<String> allow, Set<String> deny) {

        /** 这个工具要不要。参数是 server 那边的原名。 */
        public boolean wants(String remoteName) {
            if (!allow.isEmpty() && !allow.contains(remoteName)) {
                return false;
            }
            return !deny.contains(remoteName);
        }

        /** 配了挑工具没有。没配的话调用方连「筛掉了几个」的日志都不用打。 */
        public boolean picky() {
            return !allow.isEmpty() || !deny.isEmpty();
        }
    }

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".myagent", "mcp.json");

    public static McpConfig load() {
        return load(CONFIG_FILE);
    }

    static McpConfig load(Path file) {
        if (!Files.isReadable(file)) {
            return new McpConfig(Map.of());
        }
        try {
            return parse(Files.readString(file));
        } catch (Exception e) {
            log.warn("读取 {} 失败，本次不接任何 MCP server: {}", file, e.getMessage());
            return new McpConfig(Map.of());
        }
    }

    static McpConfig parse(String json) throws Exception {
        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode servers = root.path("servers");
        Map<String, ServerSpec> result = new LinkedHashMap<>();
        servers.fieldNames().forEachRemaining(name -> {
            ServerSpec spec = specOf(name, servers.get(name));
            if (spec != null) {
                result.put(name, spec);
            }
        });
        // LinkedHashMap 原样往外递：清单顺序 = 配置文件里的书写顺序，
        // 注册出来的工具菜单顺序才稳定（缓存命中、调试都靠它）
        return new McpConfig(result);
    }

    /** 读一个字符串数组。不是数组、或者里面混了空串，都当没写——见类注释的解析纪律。 */
    private static Set<String> names(JsonNode node, String field) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : node.path(field)) {
            String value = item.asText("").strip();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return Set.copyOf(result);
    }

    private static ServerSpec specOf(String name, JsonNode node) {
        String command = node.path("command").asText("");
        if (command.isBlank()) {
            log.warn("MCP server \"{}\" 没写 command，跳过", name);
            return null;
        }
        List<String> args = new ArrayList<>();
        node.path("args").forEach(a -> args.add(a.asText()));
        Map<String, String> env = new LinkedHashMap<>();
        JsonNode envNode = node.path("env");
        envNode.fieldNames().forEachRemaining(k -> env.put(k, envNode.get(k).asText()));
        return new ServerSpec(command, List.copyOf(args), Map.copyOf(env),
                names(node, "tools"), names(node, "exclude"));
    }
}
