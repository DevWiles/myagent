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
    @DisplayName("跑着时被叫停：进程当场杀掉，交代跑了多久、可能已部分生效，并带上已有的输出")
    @Timeout(20)
    void interruptKillsAndReportsHonestly() throws Exception {
        ToolResult[] result = new ToolResult[1];
        Thread worker = new Thread(() -> {
            try {
                result[0] = call("{\"command\":\"echo 已经编译了一半; sleep 3; touch late.txt; sleep 30\"}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        worker.start();
        Thread.sleep(800);                       // 等 echo 落地
        long t0 = System.nanoTime();
        worker.interrupt();                      // Agent.cancel() 就是这么叫停工具的
        worker.join(5000);

        assertFalse(worker.isAlive(), "叫停了还在跑——用户按 Esc 得干等");
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, "得当场停，不是等命令自己跑完");
        String out = result[0].content();
        assertFalse(result[0].ok());
        assertTrue(out.contains("已经编译了一半"), "叫停前的输出是现场的一部分，扔了模型就不知道跑到哪了: " + out);
        assertTrue(out.contains("叫停") && out.contains("部分生效"), out);
        assertFalse(out.contains("超时"), "这不是超时，说成超时模型会去调大时限重跑: " + out);
        Thread.sleep(3500);
        assertFalse(Files.exists(tmp.resolve("late.txt")), "子进程没杀掉，叫停之后还在后台改文件");
    }

    @Test
    @DisplayName("杀要杀整棵进程树：sh 起的子进程（mvn 起的 JVM）不能变成孤儿在后台接着跑")
    @Timeout(20)
    void interruptKillsWholeTree() throws Exception {
        Thread worker = new Thread(() -> {
            try {
                call("{\"command\":\"sleep 31 & echo $! > child.pid; wait\"}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        worker.start();
        Path pidFile = tmp.resolve("child.pid");
        for (int i = 0; i < 50 && !(Files.exists(pidFile) && Files.size(pidFile) > 0); i++) {
            Thread.sleep(100);
        }
        long child = Long.parseLong(Files.readString(pidFile).strip());
        assertTrue(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false), "子进程该在跑");

        worker.interrupt();
        worker.join(5000);
        Thread.sleep(500);

        boolean alive = ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false);
        ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);   // 红的时候别留垃圾
        assertFalse(alive, "只杀了 sh，它起的子进程还活着");
    }

    @Test
    @DisplayName("超时也要杀整棵树")
    @Timeout(20)
    void timeoutKillsWholeTree() throws Exception {
        call("{\"command\":\"sleep 32 & echo $! > child.pid; wait\",\"timeout_seconds\":1}");
        Thread.sleep(500);

        long child = Long.parseLong(Files.readString(tmp.resolve("child.pid")).strip());
        boolean alive = ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false);
        ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
        assertFalse(alive, "超时只杀了 sh，子进程还在后台跑");
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
