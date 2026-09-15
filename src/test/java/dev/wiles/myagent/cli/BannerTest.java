package dev.wiles.myagent.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BannerTest {

    @BeforeEach
    void setUp() {
        Ansi.setEnabled(false);   // 关掉颜色才好按显示宽度核对
    }

    @AfterEach
    void tearDown() {
        Ansi.setEnabled(true);
    }

    private List<String> lines(String banner) {
        return banner.lines().filter(l -> !l.isBlank()).toList();
    }

    private void assertAligned(String banner) {
        List<String> lines = lines(banner);
        int expected = Ansi.displayWidth(lines.get(0));
        for (String line : lines) {
            assertEquals(expected, Ansi.displayWidth(line),
                    "这一行宽度对不上，框会歪: [" + line + "]");
        }
        assertTrue(lines.get(0).startsWith("╭"));
        assertTrue(lines.get(lines.size() - 1).startsWith("╰"));
    }

    @Test
    @DisplayName("中英混排时右边框依然对齐")
    void alignsWithChinese() {
        assertAligned(Banner.render("0.2.0", "deepseek-chat", Path.of("/tmp/demo"),
                List.of("read_file", "write_file", "list_dir"), false, true, 80));
    }

    @Test
    @DisplayName("只读模式的标记也不会把框撑歪")
    void alignsInPlanMode() {
        assertAligned(Banner.render("0.2.0", "deepseek-chat", Path.of("/tmp/demo"),
                List.of("read_file"), true, true, 80));
    }

    @Test
    @DisplayName("未配置 key 时同样对齐")
    void alignsWithoutKey() {
        assertAligned(Banner.render("0.2.0", "deepseek-chat", Path.of("/tmp/demo"),
                List.of(), false, false, 80));
    }

    @Test
    @DisplayName("超长内容被截断，不会撑破终端宽度")
    void truncatesLongContent() {
        String banner = Banner.render("0.2.0", "deepseek-chat",
                Path.of("/very/long/path/that/goes/on/and/on/and/on/forever/and/ever/deeper"),
                List.of("tool_with_a_very_long_name_one", "tool_with_a_very_long_name_two",
                        "tool_with_a_very_long_name_three", "tool_with_a_very_long_name_four"),
                false, true, 60);

        assertAligned(banner);
        for (String line : lines(banner)) {
            assertTrue(Ansi.displayWidth(line) <= 60,
                    "超出终端宽度会换行，整个框就烂了: " + Ansi.displayWidth(line));
        }
    }

    @Test
    @DisplayName("窄终端下也有个下限，不会挤成一条缝")
    void hasMinimumWidth() {
        String banner = Banner.render("0.2.0", "m", Path.of("/x"), List.of("a"), false, true, 30);
        assertAligned(banner);
        assertTrue(Ansi.displayWidth(lines(banner).get(0)) >= 40);
    }

    @Test
    @DisplayName("关键信息都在：版本、模型、工作区、工具")
    void containsEssentials() {
        String banner = Banner.render("9.9.9", "some-model", Path.of("/tmp/proj"),
                List.of("read_file"), false, true, 80);

        assertTrue(banner.contains("9.9.9"));
        assertTrue(banner.contains("some-model"));
        assertTrue(banner.contains("proj"));
        assertTrue(banner.contains("read_file"));
        assertTrue(banner.contains("exit"), "得告诉用户怎么退出");
    }

    @Test
    @DisplayName("家目录缩写成 ~，不把用户名晒出来")
    void shortensHome() {
        String home = System.getProperty("user.home");
        String banner = Banner.render("0.2.0", "m", Path.of(home, "code", "demo"),
                List.of("a"), false, true, 80);

        assertTrue(banner.contains("~/code/demo"));
        assertFalse(banner.contains(home));
    }
}
