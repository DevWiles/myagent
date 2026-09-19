package dev.wiles.myagent.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextBarTest {

    @BeforeEach
    void setUp() {
        Ansi.setEnabled(false);
    }

    @AfterEach
    void tearDown() {
        Ansi.setEnabled(true);
    }

    @Test
    @DisplayName("百分比按用量算，进度条跟着填")
    void showsPercentage() {
        assertTrue(ContextBar.render(0, 64_000, 1, "m", 0).contains("0%"));
        assertTrue(ContextBar.render(32_000, 64_000, 10, "m", 0).contains("50%"));
        assertTrue(ContextBar.render(64_000, 64_000, 99, "m", 0).contains("100%"));
    }

    @Test
    @DisplayName("进度条格子数和百分比对得上")
    void barReflectsUsage() {
        assertTrue(ContextBar.render(0, 100, 1, "m", 0).contains("░░░░░░░░░░"));
        assertTrue(ContextBar.render(100, 100, 1, "m", 0).contains("██████████"));
        assertTrue(ContextBar.render(50, 100, 1, "m", 0).contains("█████░░░░░"));
    }

    @Test
    @DisplayName("超出上限也不会画出格子外")
    void clampsOverflow() {
        String bar = ContextBar.render(200_000, 64_000, 1, "m", 0);
        assertTrue(bar.contains("100%"));
        assertFalse(bar.contains("███████████"), "格子数不能超过宽度");
    }

    @Test
    @DisplayName("上万的数字缩写成 k，一行里塞得下")
    void formatsTokens() {
        assertTrue(ContextBar.render(999, 64_000, 1, "m", 0).contains("999"));
        assertTrue(ContextBar.render(12_345, 64_000, 1, "m", 0).contains("12.3k"));
        assertEquals("1.0k", ContextBar.formatTokens(1_000));
        assertEquals("156.8k", ContextBar.formatTokens(156_789));
    }

    @Test
    @DisplayName("消息条数和模型名都带上")
    void showsContext() {
        String bar = ContextBar.render(1000, 64_000, 17, "deepseek-chat", 0);
        assertTrue(bar.contains("17 条"));
        assertTrue(bar.contains("deepseek-chat"));
    }

    @Test
    @DisplayName("上限为 0 时不除零")
    void handlesZeroLimit() {
        assertTrue(ContextBar.render(100, 0, 1, "m", 0).contains("0%"));
    }

    @Test
    @DisplayName("用量高时变色提醒——压缩会让早期细节永久丢失，得让人提前知道")
    void colorsByUsage() {
        Ansi.setEnabled(true);
        String safe = ContextBar.render(49, 100, 1, "m", 0);
        String warn = ContextBar.render(50, 100, 1, "m", 0);
        String danger = ContextBar.render(80, 100, 1, "m", 0);

        assertTrue(safe.contains("32"), "不到一半应当是绿色");
        assertTrue(warn.contains("33"), "到一半就该转黄");
        assertTrue(danger.contains("31"), "八成以上转红");
    }

    @Test
    @DisplayName("累计发出量和当前占用并排摆——它俩不是一回事，而且通常差很多")
    void showsCumulativeSent() {
        String bar = ContextBar.render(21_400, 1_000_000, 18, "deepseek-flash", 156_800);

        assertTrue(bar.contains("21.4k"), "当前窗口");
        assertTrue(bar.contains("本会话已发 156.8k"), "累计真正发出去的");
    }

    @Test
    @DisplayName("还没发过东西就不占那一段位置")
    void hidesCumulativeWhenNothingSent() {
        assertFalse(ContextBar.render(1_000, 64_000, 1, "m", 0).contains("本会话已发"));
    }
}
