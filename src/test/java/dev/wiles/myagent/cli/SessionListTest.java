package dev.wiles.myagent.cli;

import dev.wiles.myagent.context.SessionSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionListTest {

    private static final long NOW = 1789999999000L;

    @BeforeEach
    void setUp() {
        Ansi.setEnabled(false);
    }

    @AfterEach
    void tearDown() {
        Ansi.setEnabled(true);
    }

    private static SessionSummary session(String id, String title, int turns, long savedAt) {
        return new SessionSummary(id, "deepseek-chat", savedAt - 60_000, savedAt, turns, title);
    }

    private static List<SessionSummary> three() {
        return List.of(
                session("20260918-090000-cccc", "加上多会话功能", 12, NOW - 600_000),
                session("20260917-090000-bbbb", "这个项目怎么跑测试", 6, NOW - 30 * 3600_000L),
                session("20260916-090000-aaaa", "改 SessionStore", 1, NOW - 80 * 3600_000L));
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("每行都有编号、首句、轮数、时间——挑错会话的代价是拿着过时上下文往下做")
    void everyRowCarriesEnoughToChooseBy() {
        String out = joined(SessionList.render(three(), null, 80, NOW));

        assertTrue(out.contains(" 1  加上多会话功能"), out);
        assertTrue(out.contains("12 轮"), out);
        assertTrue(out.contains("10 分钟前"), out);
        assertTrue(out.contains(" 3  改 SessionStore"), out);
        assertTrue(out.contains("共 3 段"), out);
    }

    @Test
    @DisplayName("当前待着的那一段标出来，免得挑中自己再发现「什么都没变」")
    void marksTheCurrentSession() {
        String out = joined(SessionList.render(three(), "20260917-090000-bbbb", 80, NOW));

        List<String> marked = new ArrayList<>();
        for (String line : out.lines().toList()) {
            if (line.contains("← 当前")) {
                marked.add(line);
            }
        }
        assertEquals(1, marked.size(), "只该标一行");
        assertTrue(marked.get(0).contains("这个项目怎么跑测试"));
    }

    @Test
    @DisplayName("超过 10 段时说清楚剩下的挑不到——不提一句，用户会以为更早的不存在")
    void saysWhatItCannotShow() {
        List<SessionSummary> many = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            many.add(session("2026091" + (i % 10) + "-090000-000" + i,
                    "第 " + i + " 段", i, NOW - i * 3600_000L));
        }
        String out = joined(SessionList.render(many, null, 80, NOW));

        assertTrue(out.contains("共 14 段"), out);
        assertTrue(out.contains("还有 4 段更早的"), out);
        assertTrue(out.contains("第 9 段"), "前 10 段要都在");
        assertFalse(out.contains("第 10 段"), "第 11 行不该画出来");
    }

    @Test
    @DisplayName("窄屏上标题被截掉，但每行仍然不超宽")
    void survivesNarrowTerminals() {
        List<SessionSummary> wide = List.of(
                session("20260918-090000-cccc",
                        "这是一个特别长的首句，长到任何终端都放不下它，用来验证截断", 12, NOW));

        for (int width : new int[]{40, 60, 100}) {
            for (String line : SessionList.render(wide, null, width, NOW)) {
                assertTrue(Ansi.displayWidth(line) <= width,
                        "宽度 " + width + " 时这行是 " + Ansi.displayWidth(line) + " 列: " + line);
            }
        }
    }

    @Test
    @DisplayName("轮数和时间这一列要对齐——列表对不齐的时候人是真的会挑错行")
    void metaColumnLinesUp() {
        List<String> lines = SessionList.render(three(), null, 80, NOW);
        List<Integer> columns = new ArrayList<>();
        for (String line : lines) {
            int at = line.indexOf(" 轮 · ");
            if (at >= 0) {
                columns.add(Ansi.displayWidth(line.substring(0, at).replaceAll("\\d+$", "")));
            }
        }
        assertEquals(3, columns.size());
        assertEquals(1, columns.stream().distinct().count(), "三行的轮数列起点应该一样: " + columns);
    }

    @Test
    @DisplayName("没有会话就一个字都不画，交给调用方去说")
    void emptyRendersNothing() {
        assertTrue(SessionList.render(List.of(), null, 80, NOW).isEmpty());
        assertTrue(SessionList.render(null, null, 80, NOW).isEmpty());
    }

    @Test
    @DisplayName("首句是空的（比如只发了张空消息）也得有东西占位")
    void blankTitleStillHasSomethingToClickOn() {
        String out = joined(SessionList.render(
                List.of(session("20260918-090000-cccc", "   ", 1, NOW)), null, 80, NOW));
        assertTrue(out.contains("(没有内容)"), out);
    }

    @Test
    @DisplayName("编号认得出来，越界和乱敲都当认不出——不替用户「就近取一个」")
    void picksByNumber() {
        assertEquals(0, SessionList.pick("1", 3).orElseThrow());
        assertEquals(2, SessionList.pick(" 3 ", 3).orElseThrow());
        assertTrue(SessionList.pick("4", 3).isEmpty(), "越界不许就近取");
        assertTrue(SessionList.pick("0", 3).isEmpty());
        assertTrue(SessionList.pick("-1", 3).isEmpty());
        assertTrue(SessionList.pick("二", 3).isEmpty());
        assertTrue(SessionList.pick("1.5", 3).isEmpty());
        assertTrue(SessionList.pick("", 3).isEmpty());
        assertTrue(SessionList.pick(null, 3).isEmpty());
        assertTrue(SessionList.pick("1", 0).isEmpty(), "没东西可挑的时候 1 也不是合法编号");
    }

    @Test
    @DisplayName("直接回车是取消，和敲错要给不同的反馈")
    void cancelIsNotTheSameAsATypo() {
        assertTrue(SessionList.isCancel(""));
        assertTrue(SessionList.isCancel("  "));
        assertTrue(SessionList.isCancel(null), "管道输入读到结尾也是取消，不能当 0 号");
        assertFalse(SessionList.isCancel("2"));
        assertFalse(SessionList.isCancel("x"));
    }
}
