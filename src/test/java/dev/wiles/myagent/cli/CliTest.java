package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {

    @Test
    @DisplayName("没有参数时是交互模式，默认当前目录")
    void defaults() {
        CliOptions o = Cli.parse(new String[]{});
        assertNull(o.initialPrompt());
        assertFalse(o.planMode());
        assertTrue(o.color());
        assertEquals(Path.of(""), o.workspace());
    }

    @Test
    @DisplayName("--no-spinner 关掉转圈状态行")
    void noSpinner() {
        assertTrue(Cli.parse(new String[]{}).spinner());
        assertFalse(Cli.parse(new String[]{"--no-spinner"}).spinner());
    }

    @Test
    @DisplayName("鼠标默认关闭——开了它终端就滚不动了")
    void mouseOffByDefault() {
        assertFalse(Cli.parse(new String[]{}).mouse());
        assertTrue(Cli.parse(new String[]{"--mouse"}).mouse());
    }

    @Test
    @DisplayName("默认启动时清屏让招牌置顶，--no-clear 保留上文")
    void clearScreenByDefault() {
        assertTrue(Cli.parse(new String[]{}).clearScreen());
        assertFalse(Cli.parse(new String[]{"--no-clear"}).clearScreen());
    }

    @Test
    @DisplayName("--fullscreen 占用整个终端，默认关闭")
    void fullscreenFlag() {
        assertFalse(Cli.parse(new String[]{}).fullscreen());
        assertTrue(Cli.parse(new String[]{"--fullscreen"}).fullscreen());
        assertTrue(Cli.parse(new String[]{"-f"}).fullscreen());
    }

    @Test
    @DisplayName("长短参数都认")
    void shortAndLongFlags() {
        assertTrue(Cli.parse(new String[]{"-h"}).help());
        assertTrue(Cli.parse(new String[]{"--help"}).help());
        assertTrue(Cli.parse(new String[]{"-p"}).planMode());
        assertTrue(Cli.parse(new String[]{"--plan"}).planMode());
        assertFalse(Cli.parse(new String[]{"--no-color"}).color());
    }

    @Test
    @DisplayName("带值的参数")
    void valuedOptions() {
        CliOptions o = Cli.parse(new String[]{"-d", "/tmp/x", "-m", "deepseek-reasoner"});
        assertEquals(Path.of("/tmp/x"), o.workspace());
        assertEquals("deepseek-reasoner", o.model());
    }

    @Test
    @DisplayName("位置参数拼成一次性提示词")
    void positionalBecomesPrompt() {
        CliOptions o = Cli.parse(new String[]{"看看", "pom.xml", "里有什么"});
        assertEquals("看看 pom.xml 里有什么", o.initialPrompt());
    }

    @Test
    @DisplayName("选项和提示词可以混着写")
    void mixedOptionsAndPrompt() {
        CliOptions o = Cli.parse(new String[]{"-p", "这个项目怎么组织的"});
        assertTrue(o.planMode());
        assertEquals("这个项目怎么组织的", o.initialPrompt());
    }

    @Test
    @DisplayName("未知参数直接报错，而不是被当成提示词")
    void unknownFlagFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Cli.parse(new String[]{"--nonexistent"}));
        assertTrue(e.getMessage().contains("--nonexistent"));
    }

    @Test
    @DisplayName("带值参数缺值时报错")
    void missingValueFails() {
        assertThrows(IllegalArgumentException.class, () -> Cli.parse(new String[]{"--dir"}));
    }

    @Test
    @DisplayName("帮助文本要把所有选项都列出来")
    void usageListsEverything() {
        String usage = Cli.usage();
        for (String flag : new String[]{"--dir", "--model", "--plan", "--unsafe", "--no-color", "--help"}) {
            assertTrue(usage.contains(flag), "帮助里漏了 " + flag);
        }
    }

    @Test
    @DisplayName("默认不进无头模式，--serve 才开")
    void serveOffByDefault() {
        assertFalse(Cli.parse(new String[]{}).serve());
        assertTrue(Cli.parse(new String[]{"--serve"}).serve());
    }

    @Test
    @DisplayName("无头模式默认端口 0——让系统分配，同时开两个界面不该打架")
    void portDefaultsToEphemeral() {
        assertEquals(0, Cli.parse(new String[]{"--serve"}).port());
        assertEquals(8123, Cli.parse(new String[]{"--serve", "--port", "8123"}).port());
    }

    @Test
    @DisplayName("端口不是数字或超范围，要当场报错而不是悄悄用默认值")
    void rejectsBadPort() {
        assertThrows(IllegalArgumentException.class,
                () -> Cli.parse(new String[]{"--port", "abc"}));
        assertThrows(IllegalArgumentException.class,
                () -> Cli.parse(new String[]{"--port", "70000"}));
        assertThrows(IllegalArgumentException.class,
                () -> Cli.parse(new String[]{"--port"}));
    }
}
