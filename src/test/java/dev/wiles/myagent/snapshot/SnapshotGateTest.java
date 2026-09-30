package dev.wiles.myagent.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotGateTest {

    @TempDir
    Path tmp;

    private static Tool writer(Path ws, String file) {
        return new Tool() {
            public String name() { return "write_file"; }
            public String description() { return ""; }
            public String parametersSchema() { return "{}"; }
            public ToolResult execute(JsonNode args) {
                try {
                    Files.writeString(ws.resolve(file), args.get("c").asText());
                } catch (Exception e) {
                    return ToolResult.error(e.getMessage());
                }
                return ToolResult.ok("写了");
            }
        };
    }

    @Test
    @DisplayName("一轮里第一次写之前拍一张，同一轮再写不拍；undo 回到开工前")
    void snapshotsOncePerTurnAndUndoes() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("f"), "v0");
        ShadowRepo repo = ShadowRepo.open(ws, tmp.resolve("shadow"));
        SnapshotGate gate = new SnapshotGate(repo);
        Tool write = gate.guard(writer(ws, "f"));
        ObjectMapper m = new ObjectMapper();

        gate.arm("第一句");
        write.execute(m.readTree("{\"c\":\"v1\"}"));
        write.execute(m.readTree("{\"c\":\"v2\"}"));
        assertEquals(1, gate.depth(), "同一轮两次写只拍一张");
        assertEquals("第一句", gate.last().label());

        gate.arm("第二句");
        write.execute(m.readTree("{\"c\":\"v3\"}"));
        assertEquals(2, gate.depth());

        assertEquals(List.of("f"), gate.undo());
        assertEquals("v2", Files.readString(ws.resolve("f")), "撤销第二句，回到第二句开工前");
        assertEquals(List.of("f"), gate.undo());
        assertEquals("v0", Files.readString(ws.resolve("f")), "再撤销第一句");
        assertNull(gate.undo(), "没得撤了");
    }

    @Test
    @DisplayName("没 arm 就不拍（不是一轮里的写）；只读工具原样返回；影子仓库不可用时工具照常执行")
    void gateIsTransparentWhenNotArmedOrUnavailable() throws Exception {
        Path ws = tmp.resolve("ws2");
        Files.createDirectories(ws);
        SnapshotGate gate = new SnapshotGate(ShadowRepo.open(ws, tmp.resolve("shadow2")));
        Tool write = gate.guard(writer(ws, "g"));
        write.execute(new ObjectMapper().readTree("{\"c\":\"x\"}"));
        assertEquals(0, gate.depth());

        Tool reader = new Tool() {
            public String name() { return "read_file"; }
            public String description() { return ""; }
            public String parametersSchema() { return "{}"; }
            public ToolResult execute(JsonNode a) { return ToolResult.ok("r"); }
        };
        assertSame(reader, gate.guard(reader));

        SnapshotGate broken = new SnapshotGate(null);
        broken.arm("x");
        assertTrue(broken.guard(writer(ws, "h")).execute(new ObjectMapper().readTree("{\"c\":\"y\"}")).ok());
        assertEquals("y", Files.readString(ws.resolve("h")));
        assertNull(broken.undo());
    }
}
