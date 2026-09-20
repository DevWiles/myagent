package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {

    @Test
    @DisplayName("没有参数时什么都不开——默认起服务太危险，得显式说 --serve")
    void defaults() {
        CliOptions o = Cli.parse(new String[]{});
        assertFalse(o.serve());
        assertFalse(o.readOnly());
        assertEquals(Path.of(""), o.workspace());
    }

    @Test
    @DisplayName("长短参数都认")
    void shortAndLongFlags() {
        assertTrue(Cli.parse(new String[]{"-h"}).help());
        assertTrue(Cli.parse(new String[]{"--help"}).help());
        assertTrue(Cli.parse(new String[]{"-v"}).version());
        assertTrue(Cli.parse(new String[]{"-r"}).readOnly());
        assertTrue(Cli.parse(new String[]{"--readonly"}).readOnly());
        assertTrue(Cli.parse(new String[]{"--unsafe"}).unsafe());
    }

    @Test
    @DisplayName("-p/--plan 是只读模式的老名字，还得认——界面仍然在传它")
    void planIsStillAnAliasForReadOnly() {
        // tui/src/backend.ts 起后端时传的就是 --plan。改名要两边同时改，
        // 而两边不同步的症状是「只读模式静默失效」——那是安全边界，不能赌
        assertTrue(Cli.parse(new String[]{"-p"}).readOnly());
        assertTrue(Cli.parse(new String[]{"--plan"}).readOnly());
    }

    @Test
    @DisplayName("带值的参数")
    void valuedOptions() {
        CliOptions o = Cli.parse(new String[]{"-d", "/tmp/x", "-m", "deepseek-reasoner"});
        assertEquals(Path.of("/tmp/x"), o.workspace());
        assertEquals("deepseek-reasoner", o.model());
    }

    @Test
    @DisplayName("只影响终端渲染的老参数已经删了，敲了要当场报错")
    void terminalOnlyFlagsAreGone() {
        // 自带界面删掉之后，--no-color / --fullscreen 这些一个都不再有效果。
        // **静默接受比报错糟得多**：用户以为自己关掉了颜色，其实什么也没发生
        for (String gone : new String[]{"--no-color", "--no-spinner", "--mouse",
                "--no-clear", "--fullscreen", "-f"}) {
            assertThrows(IllegalArgumentException.class, () -> Cli.parse(new String[]{gone}),
                    gone + " 已经没用了，不该被静默吃掉");
        }
    }

    @Test
    @DisplayName("位置参数（以前的一次性提示词）现在要报错，并指路 myagent-tui")
    void positionalArgumentsAreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Cli.parse(new String[]{"看看", "pom.xml", "里有什么"}));

        assertTrue(e.getMessage().contains("myagent-tui"), e.getMessage());
    }

    @Test
    @DisplayName("未知参数直接报错，而不是被当成别的什么")
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
    @DisplayName("帮助文本要把还在的选项都列出来，删掉的一个都不许留")
    void usageListsExactlyWhatExists() {
        String usage = Cli.usage();

        for (String flag : new String[]{"--serve", "--port", "--dir", "--model",
                "--readonly", "--unsafe", "--help"}) {
            assertTrue(usage.contains(flag), "帮助里漏了 " + flag);
        }
        for (String gone : new String[]{"--no-color", "--fullscreen", "--mouse"}) {
            assertFalse(usage.contains(gone), "帮助里还留着已经删掉的 " + gone);
        }
        assertTrue(usage.contains("myagent-tui"), "得告诉人界面在哪儿启动");
    }

    @Test
    @DisplayName("--serve 才进无头模式")
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
