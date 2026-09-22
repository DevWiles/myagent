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
}
