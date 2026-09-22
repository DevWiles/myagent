package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventLogTest {

    private static final int ROOMY = 1024 * 1024;

    private static ObjectNode event(String type) {
        return Protocol.event(type);
    }

    private static ObjectNode delta(String text) {
        return Protocol.event("text_delta").put("text", text);
    }

    @Test
    @DisplayName("每条广播盖一个递增的 seq，从 1 开始")
    void stampsMonotonically() {
        EventLog log = new EventLog(ROOMY);
        assertTrue(log.stamp(event("a")).contains("\"seq\":1"));
        assertTrue(log.stamp(event("b")).contains("\"seq\":2"));
        assertEquals(2, log.lastSeq());
    }

    @Test
    @DisplayName("重连报上「看到 n 号了」，n 之后的按原序补发")
    void replaysAfter() {
        EventLog log = new EventLog(ROOMY);
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
    @DisplayName("留底按字节封顶——一条几 KB 的工具结果和一条几十字节的 delta 占的不再是同一个「名额」")
    void capsByBytes() {
        // 预算只够两三条小事件
        EventLog log = new EventLog(60);
        long seenBytes = 0;
        for (int i = 0; i < 6; i++) {
            seenBytes += log.stamp(event("e" + i)).length();
        }
        assertTrue(seenBytes > 60, "测试前提：总量得超预算");

        EventLog.Replay replay = log.replay(0);
        assertTrue(replay.gap(), "挤掉了就要如实报缺口");
        assertEquals(1, replay.gapFrom());
        assertFalse(replay.events().isEmpty(), "至少留着最新的");
        // 最新一条必须还在——lastSeq 永远有货
        assertTrue(replay.events().get(replay.events().size() - 1).contains("\"seq\":6"));
    }

    @Test
    @DisplayName("补发时连续 delta 合并成一条，seq 取最后那个——打字机效果不值得重播")
    void mergesConsecutiveDeltas() {
        EventLog log = new EventLog(ROOMY);
        log.stamp(event("busy"));               // seq 1
        log.stamp(delta("你"));                  // seq 2
        log.stamp(delta("好"));                  // seq 3
        log.stamp(delta("啊"));                  // seq 4
        log.stamp(event("done"));               // seq 5

        EventLog.Replay replay = log.replay(0);
        assertEquals(3, replay.events().size(), replay.events().toString());
        String merged = replay.events().get(1);
        assertTrue(merged.contains("你好啊"), merged);
        assertTrue(merged.contains("\"seq\":4"), "合并帧带的是最后那个 seq: " + merged);
    }

    @Test
    @DisplayName("不同并行线（task 不同）的 delta 不能合并——两条线的话会被搅成一段")
    void deltasFromDifferentTasksStaySeparate() {
        EventLog log = new EventLog(ROOMY);
        log.stamp(delta("甲说").put("task", "t1"));
        log.stamp(delta("乙说").put("task", "t2"));

        EventLog.Replay replay = log.replay(0);
        assertEquals(2, replay.events().size(), replay.events().toString());
        assertTrue(replay.events().get(0).contains("t1"));
        assertTrue(replay.events().get(1).contains("t2"));
    }

    @Test
    @DisplayName("delta 中间隔着别的事件就分成两段——顺序不能被合并改写")
    void interleavedEventsSplitTheMerge() {
        EventLog log = new EventLog(ROOMY);
        log.stamp(delta("前半"));
        log.stamp(event("tool_started"));
        log.stamp(delta("后半"));

        EventLog.Replay replay = log.replay(0);
        assertEquals(3, replay.events().size());
        assertTrue(replay.events().get(0).contains("前半"));
        assertTrue(replay.events().get(2).contains("后半"));
    }

    @Test
    @DisplayName("since=0 拿全部留底——手机首连用它把当前会话的近况接过去")
    void sinceZeroReplaysEverythingBuffered() {
        EventLog log = new EventLog(ROOMY);
        log.stamp(event("a"));
        log.stamp(event("b"));
        EventLog.Replay replay = log.replay(0);
        assertFalse(replay.gap());
        assertEquals(2, replay.events().size());
    }

    @Test
    @DisplayName("看齐了就什么都不补，也没有缺口")
    void nothingToReplayWhenCaughtUp() {
        EventLog log = new EventLog(ROOMY);
        log.stamp(event("a"));
        EventLog.Replay replay = log.replay(1);
        assertFalse(replay.gap());
        assertTrue(replay.events().isEmpty());
    }

    @Test
    @DisplayName("since 比 lastSeq 还大（连错后端）：不猜，当成没得补")
    void aheadOfServerMeansNothing() {
        EventLog log = new EventLog(ROOMY);
        log.stamp(event("a"));
        EventLog.Replay replay = log.replay(99);
        assertFalse(replay.gap());
        assertTrue(replay.events().isEmpty());
    }
}
