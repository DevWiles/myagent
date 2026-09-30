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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * run_tests 存在的理由是「coder 能跑测试，但删不了库」。所以这里一半在测它能跑，
 * 一半在测它拒绝什么——而且拒绝要发生在结构上（不经 shell、白名单开头），不是靠猜命令危不危险。
 */
class RunTestsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;
    RunTestsTool tool;

    @BeforeEach
    void setUp() {
        tool = new RunTestsTool(new Workspace(tmp));
    }

    private ToolResult call(String command) throws Exception {
        return tool.execute(M.readTree("{\"command\":" + M.writeValueAsString(command) + "}"));
    }

    @Test
    @DisplayName("能跑一个真实的 unittest，输出和退出码都回来；测试失败仍算工具成功")
    void runsUnittest() throws Exception {
        Files.writeString(tmp.resolve("test_x.py"), """
                import unittest
                class T(unittest.TestCase):
                    def test_ok(self): self.assertEqual(1, 1)
                    def test_bad(self): self.assertEqual(1, 2)
                """);

        ToolResult r = call("python3 -m unittest -v test_x");

        assertTrue(r.ok(), r.content());
        assertTrue(r.content().contains("test_ok") && r.content().contains("退出码: 1"), r.content());
    }

    @Test
    @DisplayName("不是测试运行器开头的一律拒：rm、python -c、make、sh")
    void rejectsNonRunners() {
        for (String cmd : List.of("rm -rf .", "python -c print(1)", "make test", "sh run.sh", "git clean -fdx")) {
            assertThrows(IllegalArgumentException.class, () -> RunTestsTool.parse(cmd), cmd);
        }
    }

    @Test
    @DisplayName("带 shell 符号的拒：分号、管道、重定向、反引号、$()——pytest; rm -rf 在 shell 眼里是一条命令")
    void rejectsShellMetacharacters() {
        for (String cmd : List.of("pytest; rm -rf .", "pytest | tee out", "pytest > out.txt",
                "pytest `rm x`", "pytest $(rm x)", "pytest && rm x", "pytest tests/*.py")) {
            assertThrows(IllegalArgumentException.class, () -> RunTestsTool.parse(cmd), cmd);
        }
    }

    @Test
    @DisplayName("指向工作区外的路径拒：绝对路径、..、~")
    void rejectsPathsOutsideWorkspace() {
        for (String cmd : List.of("pytest /etc", "pytest ../other", "pytest tests/../../x", "pytest ~/x")) {
            assertThrows(IllegalArgumentException.class, () -> RunTestsTool.parse(cmd), cmd);
        }
    }

    @Test
    @DisplayName("mvn 只许 test / verify 目标；clean install 不是跑测试")
    void mavenGoalsAreRestricted() {
        assertEquals(List.of("mvn", "-q", "-Dtest=FooTest", "test"), RunTestsTool.parse("mvn -q -Dtest=FooTest test"));
        assertThrows(IllegalArgumentException.class, () -> RunTestsTool.parse("mvn clean install"));
        assertThrows(IllegalArgumentException.class, () -> RunTestsTool.parse("mvn"));
    }

    @Test
    @DisplayName("常见运行器都放行，并且原样切成 argv")
    void acceptsCommonRunners() {
        assertEquals(List.of("python", "-m", "pytest", "-q", "tests/test_keys.py"),
                RunTestsTool.parse("python -m pytest -q tests/test_keys.py"));
        assertEquals(List.of("npm", "test"), RunTestsTool.parse("npm test"));
        assertEquals(List.of("go", "test", "./..."), RunTestsTool.parse("go test ./..."));
        assertEquals(List.of("cargo", "test", "--", "--nocapture"), RunTestsTool.parse("cargo test -- --nocapture"));
    }

    @Test
    @DisplayName("被拒的命令回一条给模型看的错误，而不是抛异常")
    void rejectionIsAToolError() throws Exception {
        ToolResult r = call("rm -rf .");

        assertFalse(r.ok());
        assertTrue(r.content().contains("不是 shell"), r.content());
        assertTrue(Files.exists(tmp), "工作区还在");
    }
}
