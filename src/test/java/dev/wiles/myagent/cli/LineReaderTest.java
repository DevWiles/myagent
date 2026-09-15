package dev.wiles.myagent.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 行编辑器的按键处理。
 * raw 模式下终端把字节直接丢过来，所以测试只要喂字节序列就行，不需要真终端。
 */
class LineReaderTest {

    private static final int ENTER = 13;
    private static final int BACKSPACE = 127;
    private static final int TAB = 9;
    private static final int ESC = 27;
    private static final int CTRL_A = 1;
    private static final int CTRL_C = 3;
    private static final int CTRL_D = 4;
    private static final int CTRL_E = 5;
    private static final int CTRL_K = 11;
    private static final int CTRL_U = 21;
    private static final int CTRL_W = 23;

    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() {
        captured = new ByteArrayOutputStream();
    }

    /** 把字符串和单个字节码混在一起拼成输入流。 */
    private static byte[] keys(Object... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object part : parts) {
            if (part instanceof String s) {
                out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
            } else if (part instanceof Character c) {
                out.write(c);
            } else {
                out.write((Integer) part);
            }
        }
        return out.toByteArray();
    }

    private LineReader reader(byte[] input) {
        return new LineReader(new ByteArrayInputStream(input),
                new PrintStream(captured, true, StandardCharsets.UTF_8), true);
    }

    private String read(Object... parts) {
        return reader(keys(parts)).readLine("> ");
    }

    @Test
    @DisplayName("普通输入，回车提交")
    void plainInput() {
        assertEquals("hello", read("hello", ENTER));
    }

    @Test
    @DisplayName("中文输入：多字节要拼完整再解码，否则全是乱码")
    void chineseInput() {
        assertEquals("你好世界", read("你好世界", ENTER));
    }

    @Test
    @DisplayName("中英混输")
    void mixedInput() {
        assertEquals("看看 pom.xml 有什么", read("看看 pom.xml 有什么", ENTER));
    }

    @Test
    @DisplayName("退格删掉前一个字符")
    void backspace() {
        assertEquals("abc", read("abcd", BACKSPACE, ENTER));
    }

    @Test
    @DisplayName("退格也要能删掉整个汉字，不能只删一个字节")
    void backspaceOnChinese() {
        assertEquals("你好", read("你好吗", BACKSPACE, ENTER));
    }

    @Test
    @DisplayName("左箭头移动光标后插入")
    void insertAtCursor() {
        // abd → 左移一位 → 插入 c → abcd
        assertEquals("abcd", read("abd", ESC, '[', 'D', "c", ENTER));
    }

    @Test
    @DisplayName("右箭头移回去")
    void moveRight() {
        assertEquals("abdc", read("abd", ESC, '[', 'D', ESC, '[', 'C', "c", ENTER));
    }

    @Test
    @DisplayName("Ctrl-A 回行首，Ctrl-E 回行尾")
    void homeAndEnd() {
        assertEquals("xabc", read("abc", CTRL_A, "x", ENTER));
        captured.reset();
        assertEquals("abcx", read("abc", CTRL_A, CTRL_E, "x", ENTER));
    }

    @Test
    @DisplayName("Ctrl-U 删到行首，Ctrl-K 删到行尾")
    void killLine() {
        assertEquals("cd", read("abcd", CTRL_A, ESC, '[', 'C', ESC, '[', 'C', CTRL_U, ENTER));
        captured.reset();
        assertEquals("ab", read("abcd", CTRL_A, ESC, '[', 'C', ESC, '[', 'C', CTRL_K, ENTER));
    }

    @Test
    @DisplayName("Ctrl-W 删掉前一个词")
    void deleteWord() {
        assertEquals("mvn clean ", read("mvn clean test", CTRL_W, ENTER));
    }

    @Test
    @DisplayName("Delete 键删掉光标处的字符")
    void deleteKey() {
        assertEquals("abd", read("abcd", CTRL_A, ESC, '[', 'C', ESC, '[', 'C', ESC, '[', '3', '~', ENTER));
    }

    @Test
    @DisplayName("上箭头翻出上一条历史")
    void historyUp() {
        LineReader r = reader(keys("第一条", ENTER, ESC, '[', 'A', ENTER));
        assertEquals("第一条", r.readLine("> "));
        assertEquals("第一条", r.readLine("> "), "上箭头应当翻出刚才那条");
    }

    @Test
    @DisplayName("上下箭头来回翻，能回到正在编辑的那行")
    void historyUpThenDown() {
        LineReader r = reader(keys("旧的", ENTER, "新的", ESC, '[', 'A', ESC, '[', 'B', ENTER));
        assertEquals("旧的", r.readLine("> "));
        assertEquals("新的", r.readLine("> "), "翻下来要还原成翻历史之前正在敲的内容");
    }

    @Test
    @DisplayName("连续重复的输入只记一条历史")
    void deduplicatesHistory() {
        LineReader r = reader(keys("同样的", ENTER, "同样的", ENTER));
        r.readLine("> ");
        r.readLine("> ");
        assertEquals(1, r.history().size());
    }

    @Test
    @DisplayName("空行不进历史")
    void blankNotRemembered() {
        LineReader r = reader(keys(ENTER, "   ", ENTER));
        r.readLine("> ");
        r.readLine("> ");
        assertTrue(r.history().isEmpty());
    }

    @Test
    @DisplayName("Tab 唯一匹配时直接补全")
    void tabCompletesUnique() {
        LineReader r = reader(keys("/to", TAB, ENTER)).completions(List.of("/tools", "/history"));
        assertEquals("/tools", r.readLine("> "));
    }

    @Test
    @DisplayName("Tab 多个匹配时不乱补，把候选列出来")
    void tabListsCandidates() {
        LineReader r = reader(keys("/h", TAB, ENTER))
                .completions(List.of("/help", "/history"));
        assertEquals("/h", r.readLine("> "), "有歧义时不该擅自补全");
        String screen = captured.toString(StandardCharsets.UTF_8);
        assertTrue(screen.contains("/help") && screen.contains("/history"), "要把候选打出来");
    }

    @Test
    @DisplayName("不是斜杠命令就不补全")
    void tabIgnoredForPlainText() {
        LineReader r = reader(keys("help", TAB, ENTER)).completions(List.of("/help"));
        assertEquals("help", r.readLine("> "));
    }

    @Test
    @DisplayName("空行上按 Ctrl-D 表示退出")
    void ctrlDOnEmptyLineExits() {
        assertNull(read(CTRL_D));
    }

    @Test
    @DisplayName("有内容时 Ctrl-D 删除光标处字符，不退出")
    void ctrlDDeletesWhenNotEmpty() {
        assertEquals("bc", read("abc", CTRL_A, CTRL_D, ENTER));
    }

    @Test
    @DisplayName("有内容时 Ctrl-C 放弃当前行，继续读下一行")
    void ctrlCAbandonsLine() {
        assertEquals("重新输入", read("写错了", CTRL_C, "重新输入", ENTER));
    }

    @Test
    @DisplayName("空行上的 Ctrl-C 抛给调用方——我们关了 SIGINT，退出路径得自己给")
    void ctrlCOnEmptyLineThrows() {
        assertThrows(LineReader.Interrupted.class, () -> read(CTRL_C));
    }

    @Test
    @DisplayName("输入流结束返回 null")
    void eofReturnsNull() {
        assertNull(read("没有回车"));
    }

    @Test
    @DisplayName("带边框时，上下线和状态行一次性铺开")
    void drawsInputFrame() {
        LineReader r = reader(keys("你好", ENTER));
        r.readLine("> ", new InputFrame("上边线", "下边线", "状态行"));

        String screen = captured.toString(StandardCharsets.UTF_8);
        assertTrue(screen.contains("上边线"));
        assertTrue(screen.contains("下边线"));
        assertTrue(screen.contains("状态行"));
    }

    @Test
    @DisplayName("铺开之后光标要回到输入行，否则用户会在状态行上打字")
    void movesCursorBackToInputLine() {
        LineReader r = reader(keys(ENTER));
        r.readLine("> ", new InputFrame("上", "下", "状态"));

        // 下方两行装饰 → 上移 2 行回到输入行
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains(Ansi.up(2)),
                "没有把光标移回输入行");
    }

    @Test
    @DisplayName("提交后把框收起来，四行塌成一行——边框是「正在输入」的提示，输完就没意义了")
    void collapsesFrameOnSubmit() {
        Ansi.setEnabled(false);
        try {
            LineReader r = reader(keys("你好", ENTER));
            assertEquals("你好", r.readLine("> ", new InputFrame("上", "下", "状态")));

            String screen = captured.toString(StandardCharsets.UTF_8);
            // 下方两行装饰逐行擦掉，再退回上边线那一行覆盖它
            assertTrue(screen.contains(Ansi.up(3)) || screen.contains("上"),
                    "没有退回到上边线的位置");
            assertTrue(screen.endsWith("> 你好" + System.lineSeparator()),
                    "收起后应当只剩这一行内容");
        } finally {
            Ansi.setEnabled(true);
        }
    }

    @Test
    @DisplayName("输入行就是提示符加内容，不画左右边框")
    void drawsPlainInputLine() {
        Ansi.setEnabled(false);
        try {
            LineReader r = reader(keys("abc", ENTER)).width(30);
            r.readLine("> ", InputFrame.none());

            String screen = captured.toString(StandardCharsets.UTF_8);
            assertTrue(screen.contains("> abc"));
            assertFalse(screen.contains("│"), "不该有左右边框");
        } finally {
            Ansi.setEnabled(true);
        }
    }

    @Test
    @DisplayName("中文输入时光标定位按显示宽度算——汉字占两列")
    void handlesWideCharacters() {
        LineReader r = reader(keys("你好", ESC, '[', 'D', "x", ENTER)).width(40);
        assertEquals("你x好", r.readLine("> ", InputFrame.none()));
    }

    @Test
    @DisplayName("提交后的那一行带底色，一眼能认出哪句是自己说的")
    void submittedLineIsHighlighted() {
        LineReader r = reader(keys("问题", ENTER)).width(40);
        r.readLine("> ", new InputFrame("上", "下", null));

        String screen = captured.toString(StandardCharsets.UTF_8);
        assertTrue(screen.contains("48;5;236"), "提交行没有加底色");
    }

    @Test
    @DisplayName("没有状态行时少收一行")
    void collapsesFrameWithoutStatus() {
        LineReader r = reader(keys("x", ENTER));
        r.readLine("> ", new InputFrame("上", "下", null));

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains(Ansi.up(2)));
    }

    @Test
    @DisplayName("行内有内容时按 Ctrl-C 只重画输入行，不能再铺一个框出来")
    void ctrlCDoesNotRedrawFrame() {
        LineReader r = reader(keys("写错了", CTRL_C, "重来", ENTER));
        assertEquals("重来", r.readLine("> ", new InputFrame("上", "下", "状态")));

        String screen = captured.toString(StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(screen, "上"), "框被铺了不止一次");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    @DisplayName("不带边框时行为和以前一样")
    void worksWithoutFrame() {
        assertEquals("hello", read("hello", ENTER));
    }

    @Test
    @DisplayName("非 raw 环境（管道/重定向）退化成按行读")
    void fallsBackToLineMode() {
        LineReader r = new LineReader(
                new ByteArrayInputStream("第一行\n第二行\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(captured, true, StandardCharsets.UTF_8), false);
        assertEquals("第一行", r.readLine("> "));
        assertEquals("第二行", r.readLine("> "));
    }
}
