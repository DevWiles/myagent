package dev.wiles.myagent.tool.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.builtin.CreateProjectTool;
import dev.wiles.myagent.tool.builtin.WriteFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 写文件的审批行为。核心诉求：该拦的拦住，不该问的别问。 */
class FileWriteApprovalTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;

    private final List<String> asked = new ArrayList<>();

    private ApprovalPolicy recording(boolean answer) {
        return request -> {
            asked.add(request.subject() + " | " + request.reason());
            return answer ? ApprovalVerdict.yes() : ApprovalVerdict.no();
        };
    }

    private WriteFileTool writeTool(ApprovalPolicy policy) {
        return new WriteFileTool(new Workspace(tmp), new FileWriteGuard(), policy);
    }

    private ToolResult write(WriteFileTool tool, String path, String content) throws Exception {
        return tool.execute(M.readTree("{\"path\":" + M.writeValueAsString(path)
                + ",\"content\":" + M.writeValueAsString(content) + "}"));
    }

    @Test
    @DisplayName("新建文件不打扰用户——问烦了他就会一路按 y")
    void newFileNeedsNoApproval() throws Exception {
        ToolResult r = write(writeTool(recording(false)), "src/New.java", "class New {}");

        assertTrue(r.ok(), () -> "新建文件不该被拦: " + r.content());
        assertTrue(asked.isEmpty(), "新建文件竟然问了用户: " + asked);
        assertTrue(Files.exists(tmp.resolve("src/New.java")));
    }

    @Test
    @DisplayName("覆盖已有文件必须先问")
    void overwriteAsksFirst() throws Exception {
        Files.writeString(tmp.resolve("Main.java"), "原有代码\n第二行\n");

        write(writeTool(recording(true)), "Main.java", "新代码\n");

        assertEquals(1, asked.size());
        assertTrue(asked.get(0).contains("Main.java"));
    }

    @Test
    @DisplayName("用户拒绝时原文件分毫不动")
    void refusalKeepsOriginal() throws Exception {
        Files.writeString(tmp.resolve("Main.java"), "原有代码");

        ToolResult r = write(writeTool(recording(false)), "Main.java", "新代码");

        assertFalse(r.ok());
        assertEquals("原有代码", Files.readString(tmp.resolve("Main.java")), "被拒还是写进去了");
        assertTrue(r.content().contains("拒绝"));
    }

    @Test
    @DisplayName("等确认时被叫停：不写、也不说成用户拒绝")
    void cancelledWhileAskingIsNotRefusal() throws Exception {
        Files.writeString(tmp.resolve("Main.java"), "原有代码");
        ApprovalPolicy cancelled = request -> ApprovalVerdict.interrupted();

        ToolResult r = write(writeTool(cancelled), "Main.java", "新代码");

        assertFalse(r.ok());
        assertEquals("原有代码", Files.readString(tmp.resolve("Main.java")), "没批就写进去了");
        assertFalse(r.content().contains("拒绝"), "用户没拒绝，只是叫停: " + r.content());
        assertTrue(r.content().contains("已取消"), r.content());
    }

    @Test
    @DisplayName("批量覆盖时被叫停：后面的文件不再弹框，并如实交代哪些没处理")
    void createProjectStopsAskingAfterCancel() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "原 a");
        Files.writeString(tmp.resolve("b.txt"), "原 b");
        Files.writeString(tmp.resolve("c.txt"), "原 c");
        ApprovalPolicy cancelled = request -> {
            asked.add(request.subject());
            return ApprovalVerdict.interrupted();
        };
        CreateProjectTool tool = new CreateProjectTool(new Workspace(tmp), new FileWriteGuard(), cancelled);

        ToolResult r = tool.execute(M.readTree("""
                {"overwrite": true, "files": [
                  {"path": "a.txt", "content": "新 a"},
                  {"path": "b.txt", "content": "新 b"},
                  {"path": "c.txt", "content": "新 c"}
                ]}
                """));

        assertEquals(List.of("a.txt"), asked,
                "用户已经按了停，后面每个文件再弹一次框，他就得一个个点掉（或者干等超时）");
        assertEquals("原 b", Files.readString(tmp.resolve("b.txt")), "叫停之后还在写");
        assertEquals("原 c", Files.readString(tmp.resolve("c.txt")), "叫停之后还在写");
        assertFalse(r.ok());
        assertFalse(r.content().contains("拒绝"), r.content());
        assertTrue(r.content().contains("b.txt") && r.content().contains("c.txt"),
                "没处理的文件不交代，模型接着做时会以为它们写好了: " + r.content());
    }

    @Test
    @DisplayName("确认信息要说清楚会损失什么：原多少行、变成多少行")
    void approvalMessageShowsScale() throws Exception {
        Files.writeString(tmp.resolve("Big.java"), "a\nb\nc\nd\ne\n");

        write(writeTool(recording(false)), "Big.java", "x\n");

        String message = asked.get(0);
        assertTrue(message.contains("原 6 行"), "要告诉用户原来多大: " + message);
        assertTrue(message.contains("新 2 行"), "也要告诉他要变成多大: " + message);
    }

    @Test
    @DisplayName("禁止改写 .git 内部——那是用户最后的后悔药")
    void refusesToTouchGitInternals() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        Files.writeString(tmp.resolve(".git/HEAD"), "ref: refs/heads/main");

        ToolResult r = write(writeTool(ApprovalPolicy.approveAll()), ".git/HEAD", "毁掉它");

        assertFalse(r.ok(), "即使审批全放行也不能改 .git");
        assertEquals("ref: refs/heads/main", Files.readString(tmp.resolve(".git/HEAD")));
        assertTrue(r.content().contains("版本库"));
    }

    @Test
    @DisplayName("默认构造一律拒绝覆盖：非交互环境没人能点头")
    void defaultDeniesOverwrite() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "原有");

        ToolResult r = write(new WriteFileTool(new Workspace(tmp)), "a.txt", "新的");

        assertFalse(r.ok());
        assertEquals("原有", Files.readString(tmp.resolve("a.txt")));
    }

    @Test
    @DisplayName("create_project：只对真要覆盖的那些文件发问，新建的不问")
    void createProjectAsksOnlyForOverwrites() throws Exception {
        Files.writeString(tmp.resolve("README.md"), "原有说明");
        CreateProjectTool tool = new CreateProjectTool(
                new Workspace(tmp), new FileWriteGuard(), recording(true));

        tool.execute(M.readTree("""
                {
                  "overwrite": true,
                  "files": [
                    {"path": "A.java", "content": "class A {}"},
                    {"path": "README.md", "content": "新说明"},
                    {"path": "B.java", "content": "class B {}"}
                  ]
                }
                """));

        assertEquals(1, asked.size(), "三个文件里只有 README.md 是覆盖，只该问这一次: " + asked);
        assertTrue(asked.get(0).contains("README.md"));
        assertEquals("新说明", Files.readString(tmp.resolve("README.md")));
        assertTrue(Files.exists(tmp.resolve("A.java")));
    }

    @Test
    @DisplayName("create_project：用户拒某个文件时，其余文件照常创建")
    void createProjectContinuesAfterRefusal() throws Exception {
        Files.writeString(tmp.resolve("README.md"), "原有说明");
        CreateProjectTool tool = new CreateProjectTool(
                new Workspace(tmp), new FileWriteGuard(), recording(false));

        ToolResult r = tool.execute(M.readTree("""
                {
                  "overwrite": true,
                  "files": [
                    {"path": "A.java", "content": "class A {}"},
                    {"path": "README.md", "content": "新说明"}
                  ]
                }
                """));

        assertEquals("原有说明", Files.readString(tmp.resolve("README.md")), "被拒的不能写");
        assertTrue(Files.exists(tmp.resolve("A.java")), "没被拒的要照常创建");
        assertTrue(r.content().contains("A.java"));
        assertTrue(r.content().contains("拒绝"));
    }
}
