package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 范例工具的测试，其余四个工具的测试都照这个结构写。 */
class ReadFileToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;
    ReadFileTool tool;

    @BeforeEach
    void setUp() {
        tool = new ReadFileTool(new Workspace(tmp));
    }

    private ToolResult call(String json) throws Exception {
        return tool.execute(M.readTree(json));
    }

    @Test
    @DisplayName("读到内容，且每行带行号")
    void readsWithLineNumbers() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "第一行\n第二行\n第三行\n");

        ToolResult r = call("{\"path\":\"a.txt\"}");

        assertTrue(r.ok());
        assertTrue(r.content().contains("1: 第一行"));
        assertTrue(r.content().contains("3: 第三行"));
    }

    @Test
    @DisplayName("offset / limit 控制读取范围，并提示还有多少没读")
    void respectsOffsetAndLimit() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            sb.append("line").append(i).append('\n');
        }
        Files.writeString(tmp.resolve("big.txt"), sb.toString());

        ToolResult r = call("{\"path\":\"big.txt\",\"offset\":10,\"limit\":5}");

        assertTrue(r.ok());
        assertTrue(r.content().contains("10: line10"));
        assertTrue(r.content().contains("14: line14"));
        assertFalse(r.content().contains("15: line15"));
        assertTrue(r.content().contains("offset=15"), "要告诉模型怎么接着读");
    }

    @Test
    @DisplayName("文件不存在：error，不抛异常")
    void missingFile() throws Exception {
        ToolResult r = call("{\"path\":\"nope.txt\"}");
        assertFalse(r.ok());
        assertTrue(r.content().contains("不存在"));
    }

    @Test
    @DisplayName("传了目录：error，并提示改用 list_dir")
    void pathIsDirectory() throws Exception {
        Files.createDirectory(tmp.resolve("sub"));
        ToolResult r = call("{\"path\":\"sub\"}");
        assertFalse(r.ok());
        assertTrue(r.content().contains("list_dir"), "要告诉模型该换哪个工具");
    }

    @Test
    @DisplayName("空文件明确说明，不返回空串")
    void emptyFile() throws Exception {
        Files.writeString(tmp.resolve("empty.txt"), "");
        ToolResult r = call("{\"path\":\"empty.txt\"}");
        assertTrue(r.ok());
        assertFalse(r.content().isBlank(), "空内容会让模型分不清是空文件还是调用失败");
    }

    @Test
    @DisplayName("缺少必填参数 path：error")
    void missingPathArg() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> call("{}"));
    }

    @Test
    @DisplayName("路径越界：抛 IllegalArgumentException，由 registry 兜成 error")
    void pathEscape() {
        assertThrows(IllegalArgumentException.class, () -> call("{\"path\":\"../../etc/passwd\"}"));
    }
}
