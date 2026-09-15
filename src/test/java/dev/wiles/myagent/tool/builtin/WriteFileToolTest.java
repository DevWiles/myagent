package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.FileWriteGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;


class WriteFileToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;
    WriteFileTool tool;

    @BeforeEach
    void setUp() {
        // 这个测试类只关心写入逻辑，审批统一放行；审批行为另见 FileWriteApprovalTest
        tool = new WriteFileTool(new Workspace(tmp), new FileWriteGuard(), ApprovalPolicy.approveAll());
    }

    private ToolResult call(String json) throws Exception {
        return tool.execute(M.readTree(json));
    }

    @Test
    @DisplayName("写入新文件")
    void writesNewFile() throws Exception {
        ToolResult r = call("{\"path\":\"a.txt\",\"content\":\"你好\\n世界\"}");

        assertTrue(r.ok(), () -> "应当成功，实际: " + r.content());
        assertEquals("你好\n世界", Files.readString(tmp.resolve("a.txt")));
    }

    @Test
    @DisplayName("整文件覆盖，不是追加")
    void overwritesEntirely() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "旧内容旧内容旧内容");

        call("{\"path\":\"a.txt\",\"content\":\"新\"}");

        assertEquals("新", Files.readString(tmp.resolve("a.txt")));
    }

    @Test
    @DisplayName("父目录不存在时自动创建")
    void createsParentDirs() throws Exception {
        ToolResult r = call("{\"path\":\"src/main/java/Main.java\",\"content\":\"class Main {}\"}");

        assertTrue(r.ok(), () -> "应当成功，实际: " + r.content());
        assertTrue(Files.exists(tmp.resolve("src/main/java/Main.java")));
    }

    @Test
    @DisplayName("写空内容是合法的，不能当成缺参数")
    void writesEmptyContent() throws Exception {
        ToolResult r = call("{\"path\":\"empty.txt\",\"content\":\"\"}");

        assertTrue(r.ok(), () -> "写空文件是正当需求，实际: " + r.content());
        assertTrue(Files.exists(tmp.resolve("empty.txt")));
        assertEquals("", Files.readString(tmp.resolve("empty.txt")));
    }

    @Test
    @DisplayName("目标是已存在的目录：error，不抛异常")
    void targetIsDirectory() throws Exception {
        Files.createDirectory(tmp.resolve("sub"));

        ToolResult r = call("{\"path\":\"sub\",\"content\":\"x\"}");

        assertFalse(r.ok());
    }

    @Test
    @DisplayName("缺少必填参数 path")
    void missingPath() {
        assertThrows(IllegalArgumentException.class, () -> call("{\"content\":\"x\"}"));
    }

    @Test
    @DisplayName("路径越界被挡下")
    void pathEscape() {
        assertThrows(IllegalArgumentException.class,
                () -> call("{\"path\":\"../evil.sh\",\"content\":\"rm -rf /\"}"));
    }
}
