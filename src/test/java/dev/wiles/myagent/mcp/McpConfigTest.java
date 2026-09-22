package dev.wiles.myagent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("完整的一条：command、args、env 都读得出来")
    void parsesFullSpec() throws Exception {
        McpConfig config = McpConfig.parse("""
                {"servers": {"fs": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"],
                  "env": {"TOKEN": "abc"}
                }}}""");

        McpConfig.ServerSpec spec = config.servers().get("fs");
        assertEquals("npx", spec.command());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem", "/data"), spec.args());
        assertEquals("abc", spec.env().get("TOKEN"));
    }

    @Test
    @DisplayName("args 和 env 可省略：最小配置只要一个 command")
    void argsAndEnvAreOptional() throws Exception {
        McpConfig config = McpConfig.parse("""
                {"servers": {"local": {"command": "/usr/local/bin/my-server"}}}""");

        McpConfig.ServerSpec spec = config.servers().get("local");
        assertEquals(List.of(), spec.args());
        assertEquals(0, spec.env().size());
    }

    @Test
    @DisplayName("缺 command 的那条跳过，别的照收——一条配错只作废一条")
    void skipsBrokenEntriesKeepsRest() throws Exception {
        McpConfig config = McpConfig.parse("""
                {"servers": {
                  "broken": {"args": ["x"]},
                  "good": {"command": "ok"}
                }}""");

        assertFalse(config.servers().containsKey("broken"));
        assertTrue(config.servers().containsKey("good"));
    }

    @Test
    @DisplayName("文件不存在就是空清单——这是绝大多数用户的状态，必须零成本零噪音")
    void missingFileMeansEmpty() {
        assertTrue(McpConfig.load(dir.resolve("不存在.json")).servers().isEmpty());
    }

    @Test
    @DisplayName("文件是烂 JSON 也只是空清单加警告，agent 照常起")
    void brokenFileMeansEmpty() throws Exception {
        Path file = dir.resolve("mcp.json");
        Files.writeString(file, "{烂掉的");
        assertTrue(McpConfig.load(file).servers().isEmpty());
    }

    @Test
    @DisplayName("清单顺序 = 书写顺序——工具菜单顺序要稳定")
    void preservesOrder() throws Exception {
        McpConfig config = McpConfig.parse("""
                {"servers": {"z": {"command": "a"}, "a": {"command": "b"}, "m": {"command": "c"}}}""");
        assertEquals(List.of("z", "a", "m"), List.copyOf(config.servers().keySet()));
    }

    @Test
    @DisplayName("不写挑选规则就是全都要——挑工具是优化，不是必填项")
    void withoutRulesEveryToolIsWanted() throws Exception {
        McpConfig config = McpConfig.parse("""
                { "servers": { "chrome": { "command": "npx" } } }
                """);

        McpConfig.ServerSpec spec = config.servers().get("chrome");
        assertFalse(spec.picky());
        assertTrue(spec.wants("take_screenshot"));
        assertTrue(spec.wants("随便什么名字"));
    }

    @Test
    @DisplayName("白名单：只要列出来的那几个")
    void allowListKeepsOnlyWhatIsListed() throws Exception {
        McpConfig config = McpConfig.parse("""
                { "servers": { "chrome": { "command": "npx",
                    "tools": ["new_page", "take_snapshot"] } } }
                """);

        McpConfig.ServerSpec spec = config.servers().get("chrome");
        assertTrue(spec.picky());
        assertTrue(spec.wants("new_page"));
        assertFalse(spec.wants("emulate"));
    }

    @Test
    @DisplayName("黑名单：别的都要，就这几个不要")
    void denyListDropsOnlyWhatIsListed() throws Exception {
        McpConfig config = McpConfig.parse("""
                { "servers": { "chrome": { "command": "npx",
                    "exclude": ["take_screenshot"] } } }
                """);

        McpConfig.ServerSpec spec = config.servers().get("chrome");
        assertTrue(spec.wants("click"));
        assertFalse(spec.wants("take_screenshot"));
    }

    @Test
    @DisplayName("两个都写就先过白名单再剔黑名单——黑名单说了不要的，白名单也留不住")
    void denyWinsOverAllow() throws Exception {
        McpConfig config = McpConfig.parse("""
                { "servers": { "chrome": { "command": "npx",
                    "tools": ["click", "take_screenshot"],
                    "exclude": ["take_screenshot"] } } }
                """);

        McpConfig.ServerSpec spec = config.servers().get("chrome");
        assertTrue(spec.wants("click"));
        assertFalse(spec.wants("take_screenshot"));
    }

    @Test
    @DisplayName("挑选规则写成别的类型，只作废这一条规则，不作废整个 server")
    void brokenRulesOnlyVoidThemselves() throws Exception {
        McpConfig config = McpConfig.parse("""
                { "servers": { "chrome": { "command": "npx", "tools": "click" } } }
                """);

        McpConfig.ServerSpec spec = config.servers().get("chrome");
        assertEquals("npx", spec.command(), "server 本身还得在——外挂配置写错，代价该只是这一条");
        assertFalse(spec.picky(), "读不出清单就当没写，而不是当成一个空白名单把工具全筛光");
    }
}
