package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventLogTest {

    private static ObjectNode event(String type) {
        return Protocol.event(type);
    }

    @Test
    @DisplayName("每条广播盖一个递增的 seq，从 1 开始")
    void stampsMonotonically() {
        EventLog log = new EventLog(10);
        assertTrue(log.stamp(event("a")).contains("\"seq\":1"));
        assertTrue(log.stamp(event("b")).contains("\"seq\":2"));
        assertEquals(2, log.lastSeq());
    }

    @Test
    @DisplayName("重连报上「看到 n 号了」，n 之后的按原序补发")
    void replaysAfter() {
        EventLog log = new EventLog(10);
        for (int i = 0; i < 5; i++) {
            log.stamp(event("e" + i));
        }
        EventLog.Replay replay = log.replay(2);
        assertFalse(replay.gap());
        assertEquals(3, replay.events().size());
        assertTrue(replay.events().get(0).contains("\"seq\":3"));
        assertTrue(replay.events().get(2).contains("\"seq\":5"));
    }

    @Test
    @DisplayName("since=0 拿全部留底——手机首连用它把当前会话的近况接过去")
    void sinceZeroReplaysEverythingBuffered() {
        EventLog log = new EventLog(10);
        log.stamp(event("a"));
        log.stamp(event("b"));
        EventLog.Replay replay = log.replay(0);
        assertFalse(replay.gap());
        assertEquals(2, replay.events().size());
    }

    @Test
    @DisplayName("缓冲挤掉的那段要如实报缺口，不许静默跳过")
    void reportsGapWhenBufferOverflowed() {
        EventLog log = new EventLog(3);
        for (int i = 0; i < 6; i++) {
            log.stamp(event("e" + i));      // seq 1..6，缓冲只剩 4、5、6
        }
        EventLog.Replay replay = log.replay(1);
        assertTrue(replay.gap());
        assertEquals(2, replay.gapFrom());
        assertEquals(3, replay.gapTo());
        assertEquals(3, replay.events().size());
    }

    @Test
    @DisplayName("全部都被挤掉时缺口一路报到 lastSeq，补发为空")
    void gapCoversEverythingWhenNothingLeft() {
        EventLog log = new EventLog(2);
        for (int i = 0; i < 5; i++) {
            log.stamp(event("e" + i));      // 缓冲剩 4、5
        }
        // 客户端看到 1 号，但 2、3 已经没了——4、5 还补得上
        EventLog.Replay replay = log.replay(1);
        assertTrue(replay.gap());
        assertEquals(2, replay.gapFrom());
        assertEquals(3, replay.gapTo());
        assertEquals(2, replay.events().size());
    }

    @Test
    @DisplayName("看齐了就什么都不补，也没有缺口")
    void nothingToReplayWhenCaughtUp() {
        EventLog log = new EventLog(10);
        log.stamp(event("a"));
        EventLog.Replay replay = log.replay(1);
        assertFalse(replay.gap());
        assertTrue(replay.events().isEmpty());
    }

    @Test
    @DisplayName("since 比 lastSeq 还大（连错后端）：不猜，当成没得补")
    void aheadOfServerMeansNothing() {
        EventLog log = new EventLog(10);
        log.stamp(event("a"));
        EventLog.Replay replay = log.replay(99);
        assertFalse(replay.gap());
        assertTrue(replay.events().isEmpty());
    }
}
