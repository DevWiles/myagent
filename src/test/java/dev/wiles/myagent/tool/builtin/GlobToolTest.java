package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class GlobToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;
    GlobTool tool;

    @BeforeEach
    void setUp() {
        tool = new GlobTool(new Workspace(root));
    }

    private void write(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x\n");
    }

    private JsonNode args(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private String run(String json) {
        ToolResult result = tool.execute(args(json));
        assertTrue(result.ok(), "本该成功却失败了: " + result.content());
        return result.content();
    }

    @Test
    @DisplayName("*.java 要能找到任意深度的 java 文件")
    void bareSuffixSearchesAnyDepth() throws IOException {
        write("src/main/java/dev/wiles/Main.java");
        write("Other.java");
        write("src/main.ts");

        String out = run("""
                {"pattern": "*.java"}""");

        assertTrue(out.contains("src/main/java/dev/wiles/Main.java"), out);
        assertTrue(out.contains("Other.java"), out);
        assertFalse(out.contains("main.ts"), out);
    }

    @Test
    @DisplayName("带路径的模式按相对路径匹配")
    void patternWithPathAnchors() throws IOException {
        write("src/a.ts");
        write("other/b.ts");

        String out = run("""
                {"pattern": "src/*.ts"}""");

        assertTrue(out.contains("src/a.ts"), out);
        assertFalse(out.contains("other/b.ts"), out);
    }

    @Test
    @DisplayName("结果带上修改时间——模型据此分得出活跃代码和老模块")
    void showsAge() throws IOException {
        write("a.java");

        String out = run("""
                {"pattern": "*.java"}""");

        assertTrue(out.contains("今天"), "刚建的文件该显示「今天」:\n" + out);
    }

    @Test
    @DisplayName("按最近修改倒序")
    void sortsByRecentlyModified() throws IOException {
        write("old.java");
        write("new.java");
        Files.setLastModifiedTime(root.resolve("old.java"), FileTime.fromMillis(1_000_000));

        String out = run("""
                {"pattern": "*.java"}""");

        assertTrue(out.indexOf("new.java") < out.indexOf("old.java"), out);
    }

    @Test
    @DisplayName("产物目录和 .gitignore 都要照办——和 grep 必须是同一套规矩")
    void skipsSameThingsAsGrep() throws IOException {
        // 两边取舍不一致的话，会出现「glob 列得出来、grep 搜不到」，
        // 而模型对这种矛盾的反应是反复重试，它没别的办法判断谁错了
        write("src/Real.java");
        write("target/classes/Built.java");
        write("node_modules/pkg/dep.java");
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        write("ignored/Hidden.java");

        String out = run("""
                {"pattern": "*.java"}""");

        assertTrue(out.contains("src/Real.java"), out);
        assertFalse(out.contains("target"), out);
        assertFalse(out.contains("node_modules"), out);
        assertFalse(out.contains("ignored"), out);
    }

    @Test
    @DisplayName("没找到时把「范围内确实没有」说死，并指一条出路")
    void emptyResultIsExplicit() {
        ToolResult result = tool.execute(args("""
                {"pattern": "*.rs"}"""));

        // 空结果最容易被误读成「模式写错了」，于是换着花样再试五遍
        assertTrue(result.ok());
        assertTrue(result.content().contains("确实没有"), result.content());
        assertTrue(result.content().contains("grep"), "要指出按内容找该用什么");
    }

    @Test
    @DisplayName("超过上限时要说清列了多少、一共多少")
    void truncationSaysHowMuchIsMissing() throws IOException {
        for (int i = 0; i < 10; i++) {
            write("f" + i + ".java");
        }

        String out = run("""
                {"pattern": "*.java", "limit": 3}""");

        assertTrue(out.contains("共 10 个文件"), "得说一共多少:\n" + out);
        assertTrue(out.contains("前 3 个"), "得说列了多少:\n" + out);
    }

    @Test
    @DisplayName("path 限定起点")
    void pathNarrowsScope() throws IOException {
        write("keep/a.java");
        write("skip/b.java");

        String out = run("""
                {"pattern": "*.java", "path": "keep"}""");

        assertTrue(out.contains("keep/a.java"), out);
        assertFalse(out.contains("skip/b.java"), out);
    }

    @Test
    @DisplayName("path 指到文件上要报错，并说清该用哪个工具")
    void pathMustBeADirectory() throws IOException {
        write("a.java");

        ToolResult result = tool.execute(args("""
                {"pattern": "*.java", "path": "a.java"}"""));

        assertFalse(result.ok());
        assertTrue(result.content().contains("read_file"), result.content());
    }

    @Test
    @DisplayName("路径越界要拒绝")
    void rejectsPathOutsideWorkspace() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(args("""
                        {"pattern": "*", "path": "../.."}""")));
    }

    @Test
    @DisplayName("模式写错要报错并给例子")
    void badPatternIsReported() {
        ToolResult result = tool.execute(args("""
                {"pattern": "*.{unclosed"}"""));

        assertFalse(result.ok());
        assertTrue(result.content().contains("例"), result.content());
    }

    @Test
    @DisplayName("缺 pattern 抛参数异常")
    void patternIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute(args("{}")));
    }

    @Test
    @DisplayName("时间说法只到量级——给模型看的，精确到分钟是浪费 token")
    void ageIsCoarse() {
        long day = 86_400_000L;
        long now = System.currentTimeMillis();

        assertEquals("今天", GlobTool.age(now));
        assertEquals("昨天", GlobTool.age(now - day - 1000));
        assertEquals("5 天前", GlobTool.age(now - 5 * day - 1000));
        assertEquals("2 个月前", GlobTool.age(now - 70 * day));
        assertEquals("2 年前", GlobTool.age(now - 800 * day));
        // 时钟回拨、从别的机器拷过来的文件，都能让修改时间落在未来
        assertEquals("时间异常", GlobTool.age(now + 10 * day));
    }
}
