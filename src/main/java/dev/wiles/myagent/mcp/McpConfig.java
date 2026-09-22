package dev.wiles.myagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server 的清单，读自 ~/.myagent/mcp.json。
 *
 * <pre>
 * {
 *   "servers": {
 *     "fs":    { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"] },
 *     "fetch": { "command": "uvx", "args": ["mcp-server-fetch"], "env": { "HTTPS_PROXY": "…" } }
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

    /** 一个 server 怎么拉起来：命令、参数、附加环境变量。 */
    public record ServerSpec(String command, List<String> args, Map<String, String> env) {
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
        return new ServerSpec(command, List.copyOf(args), Map.copyOf(env));
    }
}
