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

    private static List<String> boxLines(String banner) {
        return banner.lines()
                .filter(l -> l.startsWith("╔") || l.startsWith("║") || l.startsWith("╚"))
                .toList();
    }

    private static void assertBoxAligned(String banner) {
        List<String> lines = boxLines(banner);
        assertFalse(lines.isEmpty(), "没画出框来");
        int expected = Ansi.displayWidth(lines.get(0));
        for (String line : lines) {
            assertEquals(expected, Ansi.displayWidth(line),
                    "这一行宽度对不上，框会歪: [" + line + "]");
        }
        assertTrue(lines.get(0).startsWith("╔"));
        assertTrue(lines.get(lines.size() - 1).startsWith("╚"));
    }

    private static String render(int width, boolean readOnly, boolean hasKey, List<String> tools) {
        return Banner.render("0.2.0", "deepseek-chat", Path.of("/tmp/demo"),
                tools, readOnly, hasKey, width);
    }

    @Test
    @DisplayName("大字招牌出现在框里，且框的每一行等宽")
    void drawsLogoBox() {
        String banner = render(100, false, true, List.of("read_file", "write_file"));

        assertTrue(banner.contains(Logo.LINES[0]), "招牌第一行没画出来");
        assertTrue(banner.contains(Logo.LINES[5]), "招牌最后一行没画出来");
        assertBoxAligned(banner);
    }

    @Test
    @DisplayName("中文副标题不会把框撑歪")
    void alignsWithChineseSubtitle() {
        String banner = render(100, false, true, List.of("read_file"));
        assertTrue(banner.contains("从零手写的 Java 编码 Agent"));
        assertBoxAligned(banner);
    }

    @Test
    @DisplayName("80 列终端刚好放得下")
    void fitsIn80Columns() {
        String banner = render(80, false, true, List.of("read_file", "bash"));

        assertBoxAligned(banner);
        for (String line : banner.lines().toList()) {
            assertTrue(Ansi.displayWidth(line) <= 80,
                    "超出终端宽度会换行: " + Ansi.displayWidth(line) + " [" + line + "]");
        }
    }

    @Test
    @DisplayName("终端太窄就不画大字，退回一行标题")
    void fallsBackWhenNarrow() {
        String banner = render(60, false, true, List.of("read_file"));

        assertFalse(banner.contains(Logo.LINES[0]), "窄终端下画大字会换行错位");
        assertTrue(banner.contains("myagent 0.2.0"));
        for (String line : banner.lines().toList()) {
            assertTrue(Ansi.displayWidth(line) <= 60, "窄终端下也不能超宽: [" + line + "]");
        }
    }

    @Test
    @DisplayName("关键信息都在：版本、模型、工作区、工具、怎么退出")
    void containsEssentials() {
        String banner = Banner.render("9.9.9", "some-model", Path.of("/tmp/proj"),
                List.of("read_file"), false, true, 100);

        assertTrue(banner.contains("9.9.9"));
        assertTrue(banner.contains("some-model"));
        assertTrue(banner.contains("proj"));
        assertTrue(banner.contains("read_file"));
        assertTrue(banner.contains("exit"), "得告诉用户怎么退出");
    }

    @Test
    @DisplayName("只读模式和未配置 key 都有标记")
    void showsModes() {
        assertTrue(render(100, true, true, List.of("read_file")).contains("只读模式"));
        assertTrue(render(100, false, false, List.of()).contains("未配置 API key"));
    }

    @Test
    @DisplayName("超长的工具列表被截断，不会撑破终端宽度")
    void truncatesLongToolList() {
        String banner = render(80, false, true, List.of(
                "tool_with_a_very_long_name_one", "tool_with_a_very_long_name_two",
                "tool_with_a_very_long_name_three", "tool_with_a_very_long_name_four"));

        for (String line : banner.lines().toList()) {
            assertTrue(Ansi.displayWidth(line) <= 80, "超宽了: [" + line + "]");
        }
    }

    @Test
    @DisplayName("家目录缩写成 ~，不把用户名晒出来")
    void shortensHome() {
        String home = System.getProperty("user.home");
        String banner = Banner.render("0.2.0", "m", Path.of(home, "code", "demo"),
                List.of("a"), false, true, 100);

        assertTrue(banner.contains("~/code/demo"));
        assertFalse(banner.contains(home));
    }
}
