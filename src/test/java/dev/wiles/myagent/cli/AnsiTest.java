package dev.wiles.myagent.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnsiTest {

    @AfterEach
    void tearDown() {
        Ansi.setEnabled(true);
    }

    @Test
    @DisplayName("汉字占两列——光标定位错了中英混输就会飘")
    void cjkIsDoubleWidth() {
        assertEquals(2, Ansi.displayWidth("你"));
        assertEquals(8, Ansi.displayWidth("你好世界"));
        assertEquals(4, Ansi.displayWidth("abcd"));
        assertEquals(7, Ansi.displayWidth("ab你好c"));
    }

    @Test
    @DisplayName("全角标点也是两列")
    void fullWidthPunctuation() {
        assertEquals(2, Ansi.displayWidth("，"));
        assertEquals(2, Ansi.displayWidth("："));
    }

    @Test
    @DisplayName("关掉颜色后输出里不能残留任何转义序列")
    void disabledProducesPlainText() {
        Ansi.setEnabled(false);
        assertEquals("文本", Ansi.red("文本"));
        assertEquals("文本", Ansi.dim("文本"));
        assertEquals("", Ansi.left(5));
        assertFalse(Ansi.clearLine().indexOf(Ansi.ESC) >= 0,
                "重定向到文件时留下转义序列会变成乱码");
    }

    @Test
    @DisplayName("开启颜色时确实带上了转义序列")
    void enabledWrapsText() {
        Ansi.setEnabled(true);
        String red = Ansi.red("x");
        assertTrue(red.indexOf(Ansi.ESC) >= 0);
        assertTrue(red.contains("31"));
        assertTrue(red.endsWith("0m"));
    }
}
