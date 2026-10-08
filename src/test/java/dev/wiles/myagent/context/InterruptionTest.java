package dev.wiles.myagent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InterruptionTest {

    private static final long AT = 1_758_100_000_000L;
    private static final long MINUTE = 60_000L;

    private static String noteAfter(long gap) {
        return new Interruption("已取消", "x", 2, AT, 4).resumeNote(AT + gap);
    }

    @Test
    @DisplayName("隔多久用人话说：存档让「隔了三天才接」成了常事，「约 4320 分钟」没人读得懂")
    void gapInHumanUnits() {
        assertTrue(noteAfter(45 * MINUTE).contains("约 45 分钟"), noteAfter(45 * MINUTE));
        assertTrue(noteAfter(3 * 60 * MINUTE).contains("约 3 小时"), noteAfter(3 * 60 * MINUTE));
        assertTrue(noteAfter(3 * 24 * 60 * MINUTE).contains("约 3 天"), noteAfter(3 * 24 * 60 * MINUTE));
    }

    @Test
    @DisplayName("刚断就接不提醒核对")
    void shortGapNoWarning() {
        assertFalse(noteAfter(MINUTE).contains("核对"));
    }
}
