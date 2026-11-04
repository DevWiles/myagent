package dev.wiles.myagent.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** UserPromptSubmit hook：退出码和 stdout 的约定照 Claude Code。命令走 sh -c。 */
class PromptHooksTest {

    @TempDir
    Path dir;

    private PromptHooks hooks(String... commands) {
        return new PromptHooks(java.util.Arrays.stream(commands)
                .map(c -> new PromptHooks.Command(c, 10, dir)).toList(), dir);
    }

    @Test
    @DisplayName("配置格式和 Claude Code 一样：hooks.UserPromptSubmit[].hooks[]；非 command 类型、空命令跳过")
    void parsesClaudeCodeShape() throws Exception {
        var root = new ObjectMapper().readTree("""
                {"hooks": {
                  "UserPromptSubmit": [
                    {"hooks": [{"type": "command", "command": "echo a", "timeout": 5},
                               {"type": "prompt", "prompt": "不支持"},
                               {"type": "command", "command": "  "}]},
                    {"hooks": [{"command": "echo b"}]}
                  ],
                  "PreToolUse": [{"hooks": [{"type": "command", "command": "echo 不是这个事件"}]}]
                }}""");

        List<PromptHooks.Command> commands = PromptHooks.parse(root, dir);

        assertEquals(List.of("echo a", "echo b"), commands.stream().map(PromptHooks.Command::command).toList());
        assertEquals(5, commands.get(0).timeoutSeconds());
        assertEquals(60, commands.get(1).timeoutSeconds(), "没写超时用默认值");
    }

    @Test
    @DisplayName("读不了的配置文件跳过，不让启动失败")
    void brokenConfigIsSkipped() throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{坏的");
        assertEquals(List.of(), PromptHooks.read(file));
        assertEquals(List.of(), PromptHooks.read(dir.resolve("没有这个.json")));
    }

    @Test
    @DisplayName("退出码 0：stdout 当补充上下文；stdin 收到的是带原话的 JSON（原样吐回 JSON 就按结构化输出解析，不算上下文）")
    void stdoutBecomesContext() {
        assertEquals(List.of(), hooks("cat").run("x", "s1").context());

        PromptHooks.Outcome out = hooks("printf '收到: '; cat").run("提到发版了", "s1");

        assertFalse(out.blocked());
        assertEquals(1, out.context().size(), out.toString());
        assertTrue(out.context().get(0).contains("\"prompt\":\"提到发版了\""), out.context().toString());
        assertTrue(out.context().get(0).contains("\"hook_event_name\":\"UserPromptSubmit\""));
    }

    @Test
    @DisplayName("退出码 2：拦下，stderr 是给用户看的理由；后面的 hook 不再跑")
    void exitTwoBlocks() {
        PromptHooks.Outcome out = hooks("echo 消息里有密钥 >&2; exit 2", "echo 不该跑到这").run("key=sk-xxx", "s1");

        assertTrue(out.blocked());
        assertEquals("消息里有密钥", out.reason());
        assertEquals(List.of(), out.context());
    }

    @Test
    @DisplayName("stdout 是 JSON：decision=block 拦下；additionalContext 当上下文")
    void jsonOutput() {
        PromptHooks.Outcome blocked = hooks("echo '{\"decision\":\"block\",\"reason\":\"先写 CHANGELOG\"}'")
                .run("发版", "s1");
        assertTrue(blocked.blocked());
        assertEquals("先写 CHANGELOG", blocked.reason());

        PromptHooks.Outcome extra = hooks(
                "echo '{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\",\"additionalContext\":\"发版前跑 make check\"}}'")
                .run("发版", "s1");
        assertFalse(extra.blocked());
        assertEquals(List.of("发版前跑 make check"), extra.context());
    }

    @Test
    @DisplayName("hook 自己坏了（别的退出码、超时）：不拦，记成警告给用户看")
    void brokenHookWarnsButPasses() {
        PromptHooks slow = new PromptHooks(List.of(new PromptHooks.Command("sleep 5", 1, dir)), dir);
        PromptHooks.Outcome out = slow.run("x", "s1");
        assertFalse(out.blocked());
        assertTrue(out.warnings().get(0).contains("超时"), out.warnings().toString());

        PromptHooks.Outcome failed = hooks("echo 坏了 >&2; exit 1", "echo 后面的照跑").run("x", "s1");
        assertFalse(failed.blocked());
        assertTrue(failed.warnings().get(0).contains("退出码 1") && failed.warnings().get(0).contains("坏了"));
        assertEquals(List.of("后面的照跑"), failed.context());
    }

    @Test
    @DisplayName("没配 hook：什么都不跑")
    void emptyPasses() {
        PromptHooks none = new PromptHooks(List.of(), dir);
        assertTrue(none.isEmpty());
        assertFalse(none.run("x", "s1").blocked());
    }
}
