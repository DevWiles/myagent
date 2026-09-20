package dev.wiles.myagent.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextDiffTest {

    private static String lines(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            out.append("line ").append(i).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("一样就是空——没有改动的话根本不该弹确认框")
    void identicalIsEmpty() {
        assertEquals("", TextDiff.unified("a\nb\n", "a\nb\n"));
        assertEquals("", TextDiff.unified("", ""));
    }

    @Test
    @DisplayName("改一行就是一删一加")
    void singleLineChange() {
        String diff = TextDiff.unified("a\nb\nc\n", "a\nB\nc\n");

        assertTrue(diff.contains("- b"), diff);
        assertTrue(diff.contains("+ B"), diff);
        assertTrue(diff.contains("  a"), "上下文没带出来: " + diff);
    }

    @Test
    @DisplayName("删在加前面：读起来是「原来这样 → 现在这样」")
    void deletionComesBeforeAddition() {
        String diff = TextDiff.unified("a\nb\n", "a\nB\n");

        assertTrue(diff.indexOf("- b") < diff.indexOf("+ B"), diff);
    }

    @Test
    @DisplayName("纯新增不该产生删除行")
    void pureInsertion() {
        String diff = TextDiff.unified("a\nc\n", "a\nb\nc\n");

        assertTrue(diff.contains("+ b"), diff);
        assertFalse(diff.contains("- "), "无中生有了删除行: " + diff);
    }

    @Test
    @DisplayName("位置头要给出行号，否则不知道这是文件的哪儿")
    void hunkHeaderCarriesLineNumbers() {
        String diff = TextDiff.unified(lines(0, 100), lines(0, 50) + "CHANGED\n" + lines(51, 100));

        assertTrue(diff.contains("@@ -"), diff);
        assertTrue(diff.contains("+ CHANGED"), diff);
        // 只该印改动附近那一小段，不是整整一百行
        assertTrue(diff.lines().count() < 15, "上下文之外的也印出来了:\n" + diff);
    }

    @Test
    @DisplayName("超过行数预算要截断，并且明说还剩多少")
    void truncationIsAnnounced() {
        String diff = TextDiff.unified(lines(0, 200), "完全不同\n", 8);

        assertTrue(diff.lines().count() <= 10, diff);
        assertTrue(diff.contains("还有"), "悄悄截断了——用户会以为自己看到了全部:\n" + diff);
    }

    @Test
    @DisplayName("大到比不动就直说，不是硬算也不是假装没改动")
    void tooLargeSaysSo() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            a.append("a").append(i).append('\n');
            b.append("b").append(i).append('\n');
        }
        String diff = TextDiff.unified(a.toString(), b.toString());

        assertTrue(diff.contains("无法逐行比对"), diff);
        assertTrue(diff.contains("1200"), "连原来多少行都没说: " + diff);
    }

    @Test
    @DisplayName("首尾相同的大文件照比不误——掐掉公共部分之后表格就小了")
    void trimsCommonPrefixAndSuffix() {
        // 这两个文件各 3000 行，硬算 LCS 是九百万格；掐完首尾只剩一行
        String before = lines(0, 3000);
        String after = lines(0, 1500) + "CHANGED\n" + lines(1501, 3000);

        String diff = TextDiff.unified(before, after);

        assertTrue(diff.contains("+ CHANGED"), diff);
        assertFalse(diff.contains("无法逐行比对"), "掐首尾没起作用");
    }

    @Test
    @DisplayName("空文件变成有内容：全是新增")
    void fromEmpty() {
        String diff = TextDiff.unified("", "a\nb\n");

        assertTrue(diff.contains("+ a"), diff);
        assertTrue(diff.contains("+ b"), diff);
    }

    @Test
    @DisplayName("清空文件：全是删除")
    void toEmpty() {
        String diff = TextDiff.unified("a\nb\n", "");

        assertTrue(diff.contains("- a"), diff);
        assertTrue(diff.contains("- b"), diff);
    }

    @Test
    @DisplayName("两处分开的改动各给一个位置头")
    void separateHunksEachGetAHeader() {
        String before = lines(0, 60);
        String after = "CHANGED\n" + lines(1, 59) + "ALSO\n";

        String diff = TextDiff.unified(before, after);

        assertEquals(2, diff.lines().filter(l -> l.startsWith("@@")).count(), diff);
    }
}
