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
        assertTrue(ContextBar.render(0, 64_000, 1, "m").contains("0%"));
        assertTrue(ContextBar.render(32_000, 64_000, 10, "m").contains("50%"));
        assertTrue(ContextBar.render(64_000, 64_000, 99, "m").contains("100%"));
    }

    @Test
    @DisplayName("进度条格子数和百分比对得上")
    void barReflectsUsage() {
        assertTrue(ContextBar.render(0, 100, 1, "m").contains("░░░░░░░░░░"));
        assertTrue(ContextBar.render(100, 100, 1, "m").contains("██████████"));
        assertTrue(ContextBar.render(50, 100, 1, "m").contains("█████░░░░░"));
    }

    @Test
    @DisplayName("超出上限也不会画出格子外")
    void clampsOverflow() {
        String bar = ContextBar.render(200_000, 64_000, 1, "m");
        assertTrue(bar.contains("100%"));
        assertFalse(bar.contains("███████████"), "格子数不能超过宽度");
    }

    @Test
    @DisplayName("上万的数字缩写成 k，一行里塞得下")
    void formatsTokens() {
        assertTrue(ContextBar.render(999, 64_000, 1, "m").contains("999/64.0k"));
        assertTrue(ContextBar.render(12_345, 64_000, 1, "m").contains("12.3k/64.0k"));
    }

    @Test
    @DisplayName("消息条数和模型名都带上")
    void showsContext() {
        String bar = ContextBar.render(1000, 64_000, 17, "deepseek-chat");
        assertTrue(bar.contains("17 条消息"));
        assertTrue(bar.contains("deepseek-chat"));
    }

    @Test
    @DisplayName("上限为 0 时不除零")
    void handlesZeroLimit() {
        assertTrue(ContextBar.render(100, 0, 1, "m").contains("0%"));
    }

    @Test
    @DisplayName("用量高时变色提醒——压缩会让早期细节永久丢失，得让人提前知道")
    void colorsByUsage() {
        Ansi.setEnabled(true);
        String safe = ContextBar.render(10, 100, 1, "m");
        String warn = ContextBar.render(70, 100, 1, "m");
        String danger = ContextBar.render(90, 100, 1, "m");

        assertTrue(safe.contains("32"), "低占用应当是绿色");
        assertTrue(warn.contains("33"), "过半应当转黄");
        assertTrue(danger.contains("31"), "接近上限应当转红");
    }
}
