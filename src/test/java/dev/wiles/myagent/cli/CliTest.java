package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    @DisplayName("--once 一族：提示词文件、方式、摘要文件、步数；方式只认 react/team")
    void onceFlags() {
        CliOptions o = Cli.parse(new String[]{"--once", "p.txt", "--once-mode", "team",
                "--once-out", "s.json", "--max-steps", "40", "-d", "/w"});
        assertEquals("p.txt", o.once());
        assertEquals("team", o.onceMode());
        assertEquals("s.json", o.onceOut());
        assertEquals(40, o.maxSteps());
        assertNull(Cli.parse(new String[]{"--serve"}).once(), "不带 --once 就不是单次模式");
        assertEquals("react", Cli.parse(new String[]{"--once", "-"}).onceMode());
        assertThrows(IllegalArgumentException.class,
                () -> Cli.parse(new String[]{"--once", "p", "--once-mode", "swarm"}));
        assertThrows(IllegalArgumentException.class,
                () -> Cli.parse(new String[]{"--once", "p", "--max-steps", "0"}));
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

        for (String flag : new String[]{"--serve", "--port", "--lan", "--relay", "--dir", "--model",
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
    @DisplayName("--lan 默认关——「只听 127.0.0.1」是安全边界，跨过去必须显式说")
    void lanOffByDefault() {
        assertFalse(Cli.parse(new String[]{"--serve"}).lan());
        assertTrue(Cli.parse(new String[]{"--serve", "--lan"}).lan());
    }

    @Test
    @DisplayName("中继开关：默认全关，--relay 要带地址")
    void relayFlags() {
        CliOptions off = Cli.parse(new String[]{"--serve"});
        assertNull(off.relay());
        assertFalse(off.relayReset());
        assertFalse(off.relayServe());

        CliOptions on = Cli.parse(new String[]{"--serve", "--relay", "wss://x/ws", "--relay-reset"});
        assertEquals("wss://x/ws", on.relay());
        assertTrue(on.relayReset());

        // 地址漏了要当场报错——静默当成没开中继的话，用户会对着一个永远连不上的手机发呆
        assertThrows(IllegalArgumentException.class, () -> Cli.parse(new String[]{"--relay"}));
    }

    @Test
    @DisplayName("--relay-serve 是另一个角色，和 --serve 各走各的")
    void relayServeIsItsOwnRole() {
        CliOptions o = Cli.parse(new String[]{"--relay-serve", "--port", "8790"});
        assertTrue(o.relayServe());
        assertFalse(o.serve());
        assertEquals(8790, o.port());
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

    @Test
    @DisplayName("三处版本号必须一致——漂开了没人会发现，直到用户报一个对不上的版本")
    void everyPlaceThatSpellsTheVersionAgrees() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher project = Pattern.compile("<artifactId>myagent</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);
        assertTrue(project.find(), "pom.xml 里找不到本项目的 version");

        assertEquals(project.group(1), Cli.VERSION,
                "pom.xml 和 Cli.VERSION 对不上。后者是 --version 和协议 ready 帧里报的那个，"
                        + "而前者决定 jar 叫什么名字——两个都是用户看得见的");

        String tuiPackage = Files.readString(Path.of("tui/package.json"));
        Matcher tui = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(tuiPackage);
        assertTrue(tui.find());
        assertEquals(Cli.VERSION, tui.group(1),
                "界面和后端是一起发的一个东西，版本号分家只会让「你用的是哪个版本」问不清楚");
    }

    @Test
    @DisplayName("--once-mode 认 react+review：单写者做完后独立审查一次")
    void acceptsReactPlusReview() {
        CliOptions o = Cli.parse(new String[]{"--once", "p.txt", "--once-mode", "react+review"});
        assertEquals("react+review", o.onceMode());
    }
}
