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

    // ── 给界面看的 diff ────────────────────────────────────────

    @Test
    @DisplayName("覆盖已有文件时带上 diff——这是界面唯一能显示「改了什么」的来源")
    void overwriteCarriesDiffForTheUi() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "第一行\n第二行\n");

        ToolResult r = call("{\"path\":\"a.txt\",\"content\":\"第一行\\n改过的第二行\\n\"}");

        assertTrue(r.ok());
        assertFalse(r.diff().isEmpty(), "覆盖写入必须带 diff");
        // TextDiff 的格式是「符号 + 空格 + 正文」，上下文行前面是两个空格
        assertTrue(r.diff().contains("- 第二行"), r.diff());
        assertTrue(r.diff().contains("+ 改过的第二行"), r.diff());
        assertTrue(r.diff().contains("@@"), "要有 hunk 抬头，界面靠它分段: " + r.diff());
    }

    @Test
    @DisplayName("diff 不许混进 content——那是要发给模型的，它刚写完，再喂一遍是白花钱")
    void diffStaysOutOfWhatTheModelSees() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "旧内容\n");

        ToolResult r = call("{\"path\":\"a.txt\",\"content\":\"新内容\\n\"}");

        assertFalse(r.content().contains("旧内容"), "content 里不该有 diff: " + r.content());
        assertFalse(r.content().contains("@@"), "content 里不该有 diff: " + r.content());
        assertTrue(r.content().startsWith("已写入 a.txt"), r.content());
    }

    @Test
    @DisplayName("新建文件的 diff 是整份内容的新增——新建一个工程时，这才是最该看的")
    void brandNewFileShowsEverythingAsAdded() throws Exception {
        ToolResult r = call("{\"path\":\"新的.txt\",\"content\":\"第一行\\n第二行\\n\"}");

        assertTrue(r.ok());
        // 空串的话，「写入 7 个文件」旁边一个字都看不到，而这些文件是凭空出现的
        assertTrue(r.diff().contains("+ 第一行"), r.diff());
        assertTrue(r.diff().contains("+ 第二行"), r.diff());
        assertFalse(r.diff().contains("- "), "新建文件不该有删除行: " + r.diff());
    }

    @Test
    @DisplayName("内容没变时 diff 是空的——没改动却画一个空 diff 框，只会让人以为改了")
    void unchangedContentProducesNoDiff() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "一模一样\n");

        ToolResult r = call("{\"path\":\"a.txt\",\"content\":\"一模一样\\n\"}");

        assertTrue(r.ok());
        assertEquals("", r.diff());
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
