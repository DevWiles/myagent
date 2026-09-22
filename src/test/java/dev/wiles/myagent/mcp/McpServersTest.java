package dev.wiles.myagent.mcp;

import dev.wiles.myagent.browser.ChromeSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServersTest {

    @Test
    @DisplayName("备浏览器的预算要明显小于这家 server 的启动预算——它住在里面，不另开一份")
    void browserBudgetFitsInsideTheServerBudget() {
        assertTrue(ChromeSession.LAUNCH_TIMEOUT_MILLIS < McpServers.STARTUP_TIMEOUT_MILLIS,
                "浏览器那份预算一旦顶到甚至超过 server 的启动预算，"
                        + "症状是每次冷启动都「MCP server chrome 在 10 秒内没就绪」，"
                        + "而日志里两个超时长得一模一样，根本看不出是谁吃掉了时间");
        assertTrue(ChromeSession.LAUNCH_TIMEOUT_MILLIS * 2 <= McpServers.STARTUP_TIMEOUT_MILLIS,
                "留一半给 npx 冷启动和握手——实测 Chrome 冷启动 1.4 秒、npx 起 2 秒出头，"
                        + "两边都得放得下");
    }

    @Test
    @DisplayName("没配 server 就是零成本：一个线程都不起，浏览器更不会去碰")
    void emptyConfigStartsNothing() {
        try (McpServers servers = McpServers.start(new McpConfig(Map.of()))) {
            assertTrue(servers.tools().isEmpty());
        }
    }

    @Test
    @DisplayName("起不来的 server 只作废它自己，不拦别人的路")
    void oneBrokenServerDoesNotBlockTheRest() {
        McpConfig config = new McpConfig(Map.of("坏的", new McpConfig.ServerSpec(
                "这个命令根本不存在-" + McpServersTest.class.getSimpleName(),
                List.of(), Map.of(), Set.of(), Set.of())));

        try (McpServers servers = McpServers.start(config)) {
            assertTrue(servers.tools().isEmpty(), "拉不起来就是没有工具，而不是抛异常");
        }
    }
}
