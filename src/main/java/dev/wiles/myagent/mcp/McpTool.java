package dev.wiles.myagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;

/**
 * 把 MCP server 上的一个工具包成本地 {@link Tool}。
 *
 * <p>包完之后 agent 那一侧**完全不知道 MCP 的存在**：菜单里多了几个名字带
 * {@code mcp__} 前缀的工具而已，点单、派发、失败反馈走的全是原来那条路。
 * 这正是 Tool 接口的回报——当初四个方法定得薄，现在外部协议接进来不用动一行主循环。
 *
 * <h3>名字为什么长这样：mcp__server__tool</h3>
 * 两层要防的撞车：MCP 工具撞内置工具（server 完全可能也叫 read_file，
 * 撞上会静默覆盖内置的那个——registry 用后者覆盖前者）；两个 server 互相撞。
 * 前缀把两层都隔开，还让模型和用户一眼看出「这是外挂的、出自哪家」。
 */
public final class McpTool implements Tool {

    /** 兜底 schema：server 没给 inputSchema 时用它——无参工具是合法的。 */
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final McpClient client;
    private final McpClient.RemoteTool remote;
    private final String localName;

    public McpTool(McpClient client, McpClient.RemoteTool remote) {
        this.client = client;
        this.remote = remote;
        this.localName = "mcp__" + sanitize(client.name()) + "__" + sanitize(remote.name());
    }

    /**
     * 模型侧的工具名只认 {@code [a-zA-Z0-9_-]}，MCP 那边却没这个约束。
     * 非法字符换成下划线；64 字符封顶是 OpenAI 系协议的上限，超了整个请求会被拒。
     */
    static String sanitize(String raw) {
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.length() <= 24 ? cleaned : cleaned.substring(0, 24);
    }

    @Override
    public String name() {
        return localName;
    }

    @Override
    public String description() {
        String base = remote.description().isBlank()
                ? "（server 没写说明）" : remote.description();
        // 出处必须带上：审批框、审计日志、转录里都靠名字和这句话判断「这是谁的动作」
        return base + "【来自 MCP server " + client.name() + "】";
    }

    @Override
    public String parametersSchema() {
        JsonNode schema = remote.inputSchema();
        return schema == null || schema.isNull() ? EMPTY_SCHEMA : schema.toString();
    }

    /**
     * 保持默认的 {@code parallelSafe() == false}，不覆盖。
     *
     * <p>并行的前提是「只读、怎么跑都无所谓」，而远端工具做什么我们无从判断——
     * 它叫 query 也可能在写库。拿不准就串行，慢一点的代价和写坏东西不是一个量级。
     */

    @Override
    public ToolResult execute(JsonNode args) {
        McpClient.CallResult result;
        try {
            result = client.callTool(remote.name(), args);
        } catch (McpClient.McpException e) {
            // 协议层失败（超时、server 没了）。翻成人话交给模型，它会换条路
            return ToolResult.error("MCP server " + client.name() + " 没答上来: " + e.getMessage());
        }
        if (result.isError()) {
            // 工具级失败：server 活得好好的，是这次调用本身不成立（参数错、查无此物）。
            // 原文返回，server 的报错就是写给调用方看的
            return ToolResult.error(result.text().isBlank() ? "工具执行失败，server 没说原因"
                    : result.text());
        }
        return ToolResult.ok(result.text());
    }
}
