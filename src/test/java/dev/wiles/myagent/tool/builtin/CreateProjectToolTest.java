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


class CreateProjectToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;
    CreateProjectTool tool;

    @BeforeEach
    void setUp() {
        // 这个测试类只关心写入逻辑，审批统一放行；审批行为另见 FileWriteApprovalTest
        tool = new CreateProjectTool(new Workspace(tmp), new FileWriteGuard(), ApprovalPolicy.approveAll());
    }

    private ToolResult call(String json) throws Exception {
        return tool.execute(M.readTree(json));
    }

    @Test
    @DisplayName("一次创建多个目录和文件")
    void createsDirsAndFiles() throws Exception {
        ToolResult r = call("""
                {
                  "dirs": ["src/main/java", "src/test/java"],
                  "files": [
                    {"path": "pom.xml", "content": "<project/>"},
                    {"path": "src/main/java/Main.java", "content": "class Main {}"}
                  ]
                }
                """);

        assertTrue(r.ok(), () -> "应当成功，实际: " + r.content());
        assertTrue(Files.isDirectory(tmp.resolve("src/main/java")));
        assertTrue(Files.isDirectory(tmp.resolve("src/test/java")));
        assertEquals("<project/>", Files.readString(tmp.resolve("pom.xml")));
        assertEquals("class Main {}", Files.readString(tmp.resolve("src/main/java/Main.java")));
    }

    @Test
    @DisplayName("文件的父目录会自动创建，不必先列在 dirs 里")
    void createsParentDirsImplicitly() throws Exception {
        call("{\"files\":[{\"path\":\"a/b/c/deep.txt\",\"content\":\"x\"}]}");

        assertTrue(Files.exists(tmp.resolve("a/b/c/deep.txt")));
    }

    @Test
    @DisplayName("默认不覆盖已存在的文件，而是跳过并汇报")
    void skipsExistingByDefault() throws Exception {
        Files.writeString(tmp.resolve("README.md"), "原有内容");

        ToolResult r = call("{\"files\":[{\"path\":\"README.md\",\"content\":\"新内容\"}]}");

        assertEquals("原有内容", Files.readString(tmp.resolve("README.md")), "默认不该覆盖");
        assertTrue(r.content().contains("跳过"), "跳过了什么必须告诉模型");
    }

    @Test
    @DisplayName("overwrite=true 时才覆盖")
    void overwritesWhenAsked() throws Exception {
        Files.writeString(tmp.resolve("README.md"), "原有内容");

        call("{\"overwrite\":true,\"files\":[{\"path\":\"README.md\",\"content\":\"新内容\"}]}");

        assertEquals("新内容", Files.readString(tmp.resolve("README.md")));
    }

    @Test
    @DisplayName("部分失败：成功的要保留，并把成功/失败清单一起汇报")
    void reportsPartialFailure() throws Exception {
        Files.createDirectory(tmp.resolve("blocked"));   // 同名目录挡住文件创建

        ToolResult r = call("""
                {
                  "files": [
                    {"path": "ok.txt", "content": "成功的"},
                    {"path": "blocked", "content": "会失败"}
                  ]
                }
                """);

        assertEquals("成功的", Files.readString(tmp.resolve("ok.txt")), "成功的部分不该回滚");
        assertTrue(r.content().contains("ok.txt"), "要说明哪些成功了");
        assertTrue(r.content().contains("blocked"), "要说明哪些失败了");
    }

    @Test
    @DisplayName("dirs 和 files 都为空：error，提示至少给一样")
    void nothingToDo() throws Exception {
        assertFalse(call("{}").ok());
    }

    @Test
    @DisplayName("路径越界被挡下")
    void pathEscape() {
        assertThrows(IllegalArgumentException.class,
                () -> call("{\"dirs\":[\"../evil\"]}"));
    }
}
