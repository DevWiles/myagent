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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;
    GrepTool tool;

    @BeforeEach
    void setUp() {
        tool = new GrepTool(new Workspace(root));
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
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

    // ---------- 基本行为 ----------

    @Test
    @DisplayName("默认只回文件名，不回正文——这是这个工具最重要的默认值")
    void defaultsToFileNamesOnly() throws IOException {
        write("src/Agent.java", "class Agent {\n  void loop() {}\n}\n");

        String out = run("""
                {"pattern": "class Agent"}""");

        assertTrue(out.contains("src/Agent.java"), out);
        // 正文不该出现。默认回正文的话，一次宽泛的搜索就能把上下文吃掉一大半
        assertFalse(out.contains("void loop"), "默认模式不该带出正文:\n" + out);
        assertTrue(out.contains("output_mode"), "要告诉模型怎么拿到正文");
    }

    @Test
    @DisplayName("content 模式给出行号和正文")
    void contentModeShowsLines() throws IOException {
        write("src/Agent.java", "package x;\nclass Agent {\n}\n");

        String out = run("""
                {"pattern": "class Agent", "output_mode": "content"}""");

        assertTrue(out.contains("2: class Agent"), out);
    }

    @Test
    @DisplayName("count 模式只给数字")
    void countMode() throws IOException {
        write("a.txt", "foo\nbar\nfoo\n");

        String out = run("""
                {"pattern": "foo", "output_mode": "count"}""");

        assertTrue(out.contains("2\ta.txt"), out);
    }

    @Test
    @DisplayName("没有匹配时是成功不是失败，而且要把「已搜完」说死")
    void noMatchesIsSuccess() throws IOException {
        write("a.txt", "hello\n");

        ToolResult result = tool.execute(args("""
                {"pattern": "nonexistent"}"""));

        // 「搜过了，没有」是一条有用的事实，不是错误。返回 error 会让模型
        // 以为是工具坏了，于是换个参数反复重试同一件不存在的事
        assertTrue(result.ok());
        assertTrue(result.content().contains("没有匹配"), result.content());
        assertTrue(result.content().contains("确实不存在"),
                "要说死，模型才敢据此下结论:\n" + result.content());
    }

    // ---------- 正则与字面量 ----------

    @Test
    @DisplayName("正则语法错误要把原因交回去，并提示 literal")
    void badRegexExplainsItself() throws IOException {
        write("a.txt", "x\n");

        ToolResult result = tool.execute(args("""
                {"pattern": "foo("}"""));

        assertFalse(result.ok());
        assertTrue(result.content().contains("literal"),
                "要指一条出路，不能只说语法错:\n" + result.content());
    }

    @Test
    @DisplayName("literal=true 时元字符当字面量——这挡住的是一类静默的错答案")
    void literalModeEscapesMetacharacters() throws IOException {
        write("a.txt", "call foo(bar)\n");
        write("b.txt", "foobar\n");

        String literal = run("""
                {"pattern": "foo(bar)", "literal": true}""");
        assertTrue(literal.contains("a.txt"), literal);
        assertFalse(literal.contains("b.txt"), "字面量模式不该匹配 foobar:\n" + literal);

        // 不加 literal 时 foo(bar) 是个合法正则，匹配的是 foobar——
        // 不报错、结果却是错的，这种失败最难发现
        String asRegex = run("""
                {"pattern": "foo(bar)"}""");
        assertTrue(asRegex.contains("b.txt"), asRegex);
    }

    @Test
    @DisplayName("忽略大小写")
    void caseInsensitive() throws IOException {
        write("a.txt", "Hello World\n");

        assertTrue(run("""
                {"pattern": "hello world", "case_insensitive": true}""").contains("a.txt"));
        assertTrue(run("""
                {"pattern": "hello world"}""").contains("没有匹配"));
    }

    // ---------- 范围限定 ----------

    @Test
    @DisplayName("glob 限定文件类型")
    void globNarrowsByFileType() throws IOException {
        write("src/Main.java", "target\n");
        write("src/main.ts", "target\n");

        String out = run("""
                {"pattern": "target", "glob": "*.java"}""");

        assertTrue(out.contains("Main.java"), out);
        assertFalse(out.contains("main.ts"), out);
    }

    @Test
    @DisplayName("path 限定目录")
    void pathNarrowsToDirectory() throws IOException {
        write("keep/a.txt", "needle\n");
        write("skip/b.txt", "needle\n");

        String out = run("""
                {"pattern": "needle", "path": "keep"}""");

        assertTrue(out.contains("keep/a.txt"), out);
        assertFalse(out.contains("skip/b.txt"), out);
    }

    @Test
    @DisplayName("path 可以直接指一个文件")
    void pathCanBeASingleFile() throws IOException {
        write("a.txt", "needle\n");
        write("b.txt", "needle\n");

        String out = run("""
                {"pattern": "needle", "path": "a.txt"}""");

        assertTrue(out.contains("a.txt"), out);
        assertFalse(out.contains("b.txt"), out);
    }

    @Test
    @DisplayName("路径越界要拒绝——搜索也是一条能读到文件内容的路")
    void rejectsPathOutsideWorkspace() {
        // grep 和 read_file 一样能把内容送进模型上下文，所以同样得过工作区那道闸。
        // 漏掉这里的话，沙箱就等于只挡住了正门
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(args("""
                        {"pattern": "x", "path": "../../etc"}""")));
    }

    @Test
    @DisplayName("路径不存在要说清楚，而不是报「没有匹配」")
    void missingPathIsAnError() {
        ToolResult result = tool.execute(args("""
                {"pattern": "x", "path": "nope"}"""));

        // 「路径不对」和「搜遍了没有」是两件完全不同的事，
        // 混为一谈会让模型带着错误的路径继续往下推
        assertFalse(result.ok());
        assertTrue(result.content().contains("nope"), result.content());
    }

    // ---------- 跳过什么 ----------

    @Test
    @DisplayName("产物目录不搜，哪怕里面确实有匹配")
    void skipsBuildDirectories() throws IOException {
        write("src/Main.java", "needle\n");
        write("target/classes/Main.java", "needle\n");
        write("node_modules/pkg/index.js", "needle\n");

        String out = run("""
                {"pattern": "needle"}""");

        assertTrue(out.contains("src/Main.java"), out);
        assertFalse(out.contains("target"), out);
        assertFalse(out.contains("node_modules"), out);
    }

    @Test
    @DisplayName(".gitignore 里写的也不搜")
    void respectsGitignore() throws IOException {
        write(".gitignore", "*.log\ngenerated/\n");
        write("app.log", "needle\n");
        write("generated/code.java", "needle\n");
        write("src/Real.java", "needle\n");

        String out = run("""
                {"pattern": "needle"}""");

        assertTrue(out.contains("src/Real.java"), out);
        assertFalse(out.contains("app.log"), out);
        assertFalse(out.contains("generated"), out);
    }

    @Test
    @DisplayName("子目录的 .gitignore 能用 ! 把父目录挡掉的东西放回来")
    void nestedGitignoreOverridesParent() throws IOException {
        write(".gitignore", "*.log\n");
        write("keep/.gitignore", "!*.log\n");
        write("keep/wanted.log", "needle\n");
        write("other/unwanted.log", "needle\n");

        String out = run("""
                {"pattern": "needle"}""");

        assertTrue(out.contains("keep/wanted.log"),
                "深一层的 .gitignore 该能推翻浅一层的:\n" + out);
        assertFalse(out.contains("other/unwanted.log"), out);
    }

    @Test
    @DisplayName("在子目录里搜，上层的 .gitignore 仍然管用")
    void parentGitignoreAppliesWhenStartingDeeper() throws IOException {
        write(".gitignore", "*.log\n");
        write("sub/app.log", "needle\n");
        write("sub/Real.java", "needle\n");

        String out = run("""
                {"pattern": "needle", "path": "sub"}""");

        // 从 sub 开走时如果不先把 root 那层规则铺好，产物文件就又冒出来了
        assertTrue(out.contains("sub/Real.java"), out);
        assertFalse(out.contains("app.log"), out);
    }

    @Test
    @DisplayName("二进制文件不搜——搜出来也是乱码，只会把上下文喂脏")
    void skipsBinaryFiles() throws IOException {
        Files.write(root.resolve("data.bin"),
                new byte[]{'n', 'e', 'e', 'd', 'l', 'e', 0, 1, 2, 3});
        write("a.txt", "needle\n");

        String out = run("""
                {"pattern": "needle"}""");

        assertTrue(out.contains("a.txt"), out);
        assertFalse(out.contains("data.bin"), out);
    }

    // ---------- 预算与截断 ----------

    @Test
    @DisplayName("按最近修改倒序——截断时留下的才是有意义的那一批")
    void sortsByRecentlyModified() throws IOException {
        write("old.txt", "needle\n");
        write("new.txt", "needle\n");
        Files.setLastModifiedTime(root.resolve("old.txt"),
                java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(root.resolve("new.txt"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

        String out = run("""
                {"pattern": "needle"}""");

        assertTrue(out.indexOf("new.txt") < out.indexOf("old.txt"),
                "刚改过的该排前面:\n" + out);
    }

    @Test
    @DisplayName("撞上条数上限要明说，不能让模型把半截结果当成全部")
    void truncationIsAnnounced() throws IOException {
        for (int i = 0; i < 10; i++) {
            write("f" + i + ".txt", "needle\n");
        }

        String out = run("""
                {"pattern": "needle", "head_limit": 3}""");

        assertTrue(out.contains("截断"), "截断必须说出来:\n" + out);
    }

    @Test
    @DisplayName("超长的行要截断，压缩过的 js 一行能有几十万字符")
    void longLinesAreClipped() throws IOException {
        write("min.js", "x".repeat(50_000) + "needle\n");

        String out = run("""
                {"pattern": "needle", "output_mode": "content"}""");

        assertTrue(out.contains("已截断"), out);
        assertTrue(out.length() < 5_000, "输出不该被一行撑爆，实际 " + out.length());
    }

    @Test
    @DisplayName("过大的文件不读")
    void skipsHugeFiles() throws IOException {
        write("big.txt", "needle\n" + "x".repeat(3 * 1024 * 1024));
        write("small.txt", "needle\n");

        String out = run("""
                {"pattern": "needle"}""");

        assertTrue(out.contains("small.txt"), out);
        assertFalse(out.contains("big.txt"), out);
    }

    // ---------- 上下文行 ----------

    @Test
    @DisplayName("context 带出前后几行，用 - 和 : 区分上下文和匹配")
    void contextLines() throws IOException {
        write("a.txt", "one\ntwo\nneedle\nfour\nfive\n");

        String out = run("""
                {"pattern": "needle", "output_mode": "content", "context": 1}""");

        assertTrue(out.contains("2- two"), out);
        assertTrue(out.contains("3: needle"), out);
        assertTrue(out.contains("4- four"), out);
        assertFalse(out.contains("one"), "context=1 不该带出第 1 行:\n" + out);
    }

    @Test
    @DisplayName("两条挨得近的匹配，中间的行不能输出两遍")
    void overlappingContextDoesNotDuplicate() throws IOException {
        write("a.txt", "needle\nmiddle\nneedle\n");

        String out = run("""
                {"pattern": "needle", "output_mode": "content", "context": 2}""");

        int first = out.indexOf("middle");
        assertTrue(first >= 0, out);
        assertEquals(first, out.lastIndexOf("middle"), "中间那行被输出了两遍:\n" + out);
    }

    // ---------- 参数容错 ----------

    @Test
    @DisplayName("output_mode 写错要报错并列出可选值")
    void badOutputModeIsRejected() throws IOException {
        write("a.txt", "x\n");

        ToolResult result = tool.execute(args("""
                {"pattern": "x", "output_mode": "everything"}"""));

        assertFalse(result.ok());
        assertTrue(result.content().contains("files_with_matches"), result.content());
    }

    @Test
    @DisplayName("模型爱写的 files / filenames 也认下来")
    void toleratesCommonModeAliases() throws IOException {
        write("a.txt", "needle\n");

        // 报错也能让它改，但白费一轮。这两个别名的意图毫无歧义，没有理由不认
        assertTrue(tool.execute(args("""
                {"pattern": "needle", "output_mode": "files"}""")).ok());
        assertTrue(tool.execute(args("""
                {"pattern": "needle", "output_mode": "filenames"}""")).ok());
    }

    @Test
    @DisplayName("glob 写错了要说清是 glob 的问题")
    void badGlobIsReported() throws IOException {
        write("a.txt", "x\n");

        ToolResult result = tool.execute(args("""
                {"pattern": "x", "glob": "*.{unclosed"}"""));

        assertFalse(result.ok());
        assertTrue(result.content().contains("*.{unclosed"), result.content());
    }

    @Test
    @DisplayName("缺 pattern 抛参数异常，由注册表转成给模型的提示")
    void patternIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute(args("{}")));
    }
}
