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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EditFileToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;
    EditFileTool tool;

    @BeforeEach
    void setUp() {
        tool = new EditFileTool(new Workspace(tmp), new FileWriteGuard(), ApprovalPolicy.approveAll());
    }

    private ToolResult call(Map<String, ?> args) throws Exception {
        return tool.execute(M.valueToTree(args));
    }

    @Test
    @DisplayName("只换唯一的那一处，文件其余部分一个字不动；结果带 diff，content 里不带")
    void replacesOnlyTheTargetedSpan() throws Exception {
        String body = "def a():\n    return 1\n\n\ndef b():\n    return 2\n";
        Files.writeString(tmp.resolve("m.py"), body);

        ToolResult r = call(Map.of("path", "m.py", "old_string", "    return 2", "new_string", "    return 3"));

        assertTrue(r.ok(), r.content());
        assertEquals(body.replace("return 2", "return 3"), Files.readString(tmp.resolve("m.py")));
        assertTrue(r.diff().contains("+     return 3"), r.diff());
        assertFalse(r.content().contains("return 3"), "diff 给界面看，不重复喂给模型");
    }

    @Test
    @DisplayName("没找到就拒绝，文件不动——不能静默地什么都没改还报成功")
    void refusesWhenNotFound() throws Exception {
        Files.writeString(tmp.resolve("m.py"), "x = 1\n");

        ToolResult r = call(Map.of("path", "m.py", "old_string", "x = 2", "new_string", "x = 3"));

        assertFalse(r.ok());
        assertTrue(r.content().contains("没找到"));
        assertEquals("x = 1\n", Files.readString(tmp.resolve("m.py")));
    }

    @Test
    @DisplayName("出现多次时不猜：拒绝并要求多带上下文；replace_all 才全换")
    void ambiguousNeedsReplaceAll() throws Exception {
        Files.writeString(tmp.resolve("m.py"), "foo()\nfoo()\n");

        ToolResult refused = call(Map.of("path", "m.py", "old_string", "foo()", "new_string", "bar()"));
        assertFalse(refused.ok());
        assertTrue(refused.content().contains("2 次"));
        assertEquals("foo()\nfoo()\n", Files.readString(tmp.resolve("m.py")));

        ToolResult all = call(Map.of("path", "m.py", "old_string", "foo()", "new_string", "bar()", "replace_all", true));
        assertTrue(all.ok());
        assertTrue(all.content().contains("2 处"));
        assertEquals("bar()\nbar()\n", Files.readString(tmp.resolve("m.py")));
    }

    @Test
    @DisplayName("按字面替换，不认正则；new_string 为空就是删掉")
    void literalAndDeletion() throws Exception {
        Files.writeString(tmp.resolve("m.py"), "a = re.sub(r'$1', x)\nkeep\n");

        assertTrue(call(Map.of("path", "m.py", "old_string", "r'$1'", "new_string", "r'\\\\d+'")).ok());
        assertEquals("a = re.sub(r'\\\\d+', x)\nkeep\n", Files.readString(tmp.resolve("m.py")));

        assertTrue(call(Map.of("path", "m.py", "old_string", "keep\n", "new_string", "")).ok());
        assertEquals("a = re.sub(r'\\\\d+', x)\n", Files.readString(tmp.resolve("m.py")));
    }

    @Test
    @DisplayName("新建文件、空 old_string、无变化、.git 里的文件都拒绝")
    void guardRails() throws Exception {
        assertTrue(call(Map.of("path", "new.py", "old_string", "a", "new_string", "b")).content().contains("write_file"));
        Files.writeString(tmp.resolve("m.py"), "a\n");
        assertFalse(call(Map.of("path", "m.py", "old_string", "", "new_string", "b")).ok());
        assertFalse(call(Map.of("path", "m.py", "old_string", "a", "new_string", "a")).ok());
        Files.createDirectories(tmp.resolve(".git"));
        Files.writeString(tmp.resolve(".git/config"), "x\n");
        ToolResult git = call(Map.of("path", ".git/config", "old_string", "x", "new_string", "y"));
        assertFalse(git.ok());
        assertEquals("x\n", Files.readString(tmp.resolve(".git/config")));
    }

    @Test
    @DisplayName("没有审批策略时改已有文件被拒绝，和 write_file 覆盖已有文件一个口径")
    void needsApproval() throws Exception {
        Files.writeString(tmp.resolve("m.py"), "a\n");
        EditFileTool strict = new EditFileTool(new Workspace(tmp));

        ToolResult r = strict.execute(M.valueToTree(Map.of("path", "m.py", "old_string", "a", "new_string", "b")));

        assertFalse(r.ok());
        assertEquals("a\n", Files.readString(tmp.resolve("m.py")));
    }
}
