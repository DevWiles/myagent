package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.CommandGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 【待实现】五个工具里最难的一个，注意管道死锁和超时清理。 */
class BashToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;
    BashTool tool;

    @BeforeEach
    void setUp() {
        // 这个测试类只关心执行机制，安全闸统一放行；安全规则另见 CommandGuardTest
        tool = new BashTool(new Workspace(tmp), new CommandGuard(), ApprovalPolicy.approveAll());
    }

    private ToolResult call(String json) throws Exception {
        return tool.execute(M.readTree(json));
    }

    @Test
    @DisplayName("执行命令并拿到标准输出")
    void capturesStdout() throws Exception {
        ToolResult r = call("{\"command\":\"echo 你好\"}");

        assertTrue(r.ok(), () -> "应当成功，实际: " + r.content());
        assertTrue(r.content().contains("你好"));
    }

    @Test
    @DisplayName("标准错误也要收进来，模型需要看到完整报错")
    void capturesStderr() throws Exception {
        ToolResult r = call("{\"command\":\"echo 出错了 >&2\"}");

        assertTrue(r.content().contains("出错了"));
    }

    @Test
    @DisplayName("退出码非 0 仍算工具执行成功，输出里带上退出码")
    void nonZeroExitIsStillOk() throws Exception {
        ToolResult r = call("{\"command\":\"echo 测试失败 && exit 1\"}");

        assertTrue(r.ok(), "命令跑完了就算工具成功；测试没通过是结果，不是工具故障");
        assertTrue(r.content().contains("测试失败"), "完整输出要给模型，它得据此分析原因");
        assertTrue(r.content().contains("退出码"), "模型靠退出码判断成败");
        assertTrue(r.content().contains("1"));
    }

    @Test
    @DisplayName("工作目录是沙箱根目录")
    void runsInWorkspaceRoot() throws Exception {
        Files.writeString(tmp.resolve("marker.txt"), "x");

        ToolResult r = call("{\"command\":\"ls\"}");

        assertTrue(r.content().contains("marker.txt"));
    }

    @Test
    @DisplayName("超时会被中断并明确告知，不会一直卡着")
    @Timeout(20)
    void timesOut() throws Exception {
        ToolResult r = call("{\"command\":\"sleep 30\",\"timeout_seconds\":1}");

        assertFalse(r.ok());
        assertTrue(r.content().contains("超时"));
    }

    @Test
    @DisplayName("大量输出要截断，否则一条命令就能烧光上下文")
    @Timeout(60)
    void truncatesHugeOutput() throws Exception {
        ToolResult r = call("{\"command\":\"seq 1 200000\"}");

        assertTrue(r.ok(), () -> "应当成功，实际: " + r.content());
        assertTrue(r.content().length() < 100_000, "输出必须截断，实际长度 " + r.content().length());
        assertTrue(r.content().contains("截断"), "要明说截断了，否则模型会以为这就是全部");
    }

    @Test
    @DisplayName("输出量大时不能死锁（先读流再 waitFor）")
    @Timeout(60)
    void doesNotDeadlockOnLargeOutput() throws Exception {
        assertTrue(call("{\"command\":\"seq 1 500000\"}").ok(),
                "卡在这里说明是先 waitFor 后读流，管道写满后双方互等");
    }

    @Test
    @DisplayName("命令不存在：error，不抛异常")
    void commandNotFound() throws Exception {
        ToolResult r = call("{\"command\":\"这个命令肯定不存在xyz123\"}");

        assertNotNull(r.content());
        assertFalse(r.content().isBlank());
    }

    @Test
    @DisplayName("缺少必填参数 command")
    void missingCommand() {
        assertThrows(IllegalArgumentException.class, () -> call("{}"));
    }
}
