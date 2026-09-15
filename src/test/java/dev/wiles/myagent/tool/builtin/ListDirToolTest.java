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

/** 【待实现】断言只卡关键信息，输出的具体排版你自己定。 */
class ListDirToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;
    ListDirTool tool;

    @BeforeEach
    void setUp() {
        tool = new ListDirTool(new Workspace(tmp));
    }

    private ToolResult call(String json) throws Exception {
        return tool.execute(M.readTree(json));
    }

    @Test
    @DisplayName("不传 path 时列出根目录")
    void defaultsToRoot() throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "x");
        Files.createDirectory(tmp.resolve("src"));

        ToolResult r = call("{}");

        assertTrue(r.ok(), () -> "应当成功，实际: " + r.content());
        assertTrue(r.content().contains("pom.xml"));
        assertTrue(r.content().contains("src"));
    }

    @Test
    @DisplayName("目录要能和文件区分开（目录名后加 /）")
    void marksDirectories() throws Exception {
        Files.createDirectory(tmp.resolve("src"));
        Files.writeString(tmp.resolve("README.md"), "x");

        ToolResult r = call("{}");

        assertTrue(r.content().contains("src/"), "目录应当带斜杠，模型才分得清");
    }

    @Test
    @DisplayName("跳过 .git / target / node_modules 这类目录")
    void skipsIgnoredDirs() throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        Files.createDirectory(tmp.resolve("target"));
        Files.createDirectory(tmp.resolve("node_modules"));
        Files.writeString(tmp.resolve("Main.java"), "x");

        ToolResult r = call("{}");

        assertTrue(r.content().contains("Main.java"));
        assertFalse(r.content().contains(".git"), "列出来纯属浪费 token");
        assertFalse(r.content().contains("target"));
        assertFalse(r.content().contains("node_modules"));
    }

    @Test
    @DisplayName("空目录要明确说明，不能返回空串")
    void emptyDir() throws Exception {
        Files.createDirectory(tmp.resolve("empty"));

        ToolResult r = call("{\"path\":\"empty\"}");

        assertTrue(r.ok());
        assertTrue(r.content().contains("空目录"), "空串会让模型以为调用失败了");
    }

    @Test
    @DisplayName("recursive=true 时能看到子目录里的文件")
    void recursive() throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/Main.java"), "x");

        ToolResult flat = call("{\"recursive\":false}");
        assertFalse(flat.content().contains("Main.java"), "非递归不该看到深层文件");

        ToolResult deep = call("{\"recursive\":true}");
        assertTrue(deep.content().contains("Main.java"));
    }

    @Test
    @DisplayName("递归时同样跳过 target 里的内容")
    void recursiveSkipsIgnored() throws Exception {
        Files.createDirectories(tmp.resolve("target/classes"));
        Files.writeString(tmp.resolve("target/classes/Main.class"), "x");
        Files.writeString(tmp.resolve("build.gradle"), "x");

        ToolResult r = call("{\"recursive\":true}");

        assertTrue(r.content().contains("build.gradle"));
        assertFalse(r.content().contains("Main.class"));
    }

    @Test
    @DisplayName("目标不存在：error")
    void missingDir() throws Exception {
        ToolResult r = call("{\"path\":\"nope\"}");
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("目标是文件：error，并提示改用 read_file")
    void targetIsFile() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "x");

        ToolResult r = call("{\"path\":\"a.txt\"}");

        assertFalse(r.ok());
        assertTrue(r.content().contains("read_file"), "要告诉模型该换哪个工具");
    }

    @Test
    @DisplayName("路径越界被挡下")
    void pathEscape() {
        assertThrows(IllegalArgumentException.class, () -> call("{\"path\":\"../..\"}"));
    }
}
