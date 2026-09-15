package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogoTest {

    @Test
    @DisplayName("每一行都必须是同样的列数——差一个字符整幅图就错位")
    void allLinesHaveSameWidth() {
        for (int i = 0; i < Logo.LINES.length; i++) {
            assertEquals(Logo.WIDTH, Ansi.displayWidth(Logo.LINES[i]),
                    "第 " + (i + 1) + " 行宽度不对: [" + Logo.LINES[i] + "]");
        }
    }

    @Test
    @DisplayName("画图用的字符都是单列宽的")
    void usesSingleWidthCharacters() {
        for (String line : Logo.LINES) {
            for (char c : line.toCharArray()) {
                assertEquals(1, Ansi.charWidth(c),
                        "字符 " + c + " (U+" + Integer.toHexString(c) + ") 不是单列宽，会把图撑歪");
            }
        }
    }

    @Test
    @DisplayName("窄终端下不画大字——画出来会换行，比不画还难看")
    void doesNotFitInNarrowTerminal() {
        assertFalse(Logo.fitsIn(60));
        assertFalse(Logo.fitsIn(Logo.WIDTH));
        assertTrue(Logo.fitsIn(80));
        assertTrue(Logo.fitsIn(120));
    }
}
