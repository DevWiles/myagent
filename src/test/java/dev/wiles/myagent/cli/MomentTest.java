package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class MomentTest {

    private static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static final long NOW = at(2026, 9, 18, 14, 0);

    @Test
    @DisplayName("一分钟内说「刚刚」")
    void justNow() {
        assertEquals("刚刚", Moment.describe(NOW, NOW));
        assertEquals("刚刚", Moment.describe(NOW - 59_000, NOW));
    }

    @Test
    @DisplayName("一小时内报分钟，六小时内报小时")
    void relativeWhileItIsStillTheSameStretchOfWork() {
        assertEquals("1 分钟前", Moment.describe(NOW - 60_000, NOW));
        assertEquals("59 分钟前", Moment.describe(NOW - 59 * 60_000, NOW));
        assertEquals("1 小时前", Moment.describe(NOW - 3600_000, NOW));
        assertEquals("5 小时前", Moment.describe(NOW - 5 * 3600_000, NOW));
    }

    @Test
    @DisplayName("超过六小时改报时刻——「237 小时前」谁也换算不出来是哪天")
    void absoluteOnceItIsFarAway() {
        assertEquals("今天 06:00", Moment.describe(at(2026, 9, 18, 6, 0), NOW));
        assertEquals("昨天 22:30", Moment.describe(at(2026, 9, 17, 22, 30), NOW));
        assertEquals("9-16 09:15", Moment.describe(at(2026, 9, 16, 9, 15), NOW));
        assertEquals("2025-9-16", Moment.describe(at(2025, 9, 16, 9, 15), NOW),
                "跨年了就不报时刻，隔这么久几点几分帮不上任何决定");
    }

    @Test
    @DisplayName("跨天但不满六小时，仍然报「小时前」——那确实还是同一段工作")
    void acrossMidnightStaysRelative() {
        long threeAm = at(2026, 9, 18, 3, 0);
        assertEquals("4 小时前", Moment.describe(threeAm - 3600_000, threeAm + 3 * 3600_000));
    }

    @Test
    @DisplayName("时钟回拨、存档从别的机器拷过来，也不能打出「-3 小时前」")
    void futureTimestampsDoNotProduceGarbage() {
        assertEquals("刚刚", Moment.describe(NOW + 3 * 3600_000, NOW));
    }

    @Test
    @DisplayName("没有时间就说没有，不打 1970 年")
    void missingTimestamp() {
        assertEquals("时间不明", Moment.describe(0, NOW));
        assertEquals("时间不明", Moment.describe(-1, NOW));
    }
}
