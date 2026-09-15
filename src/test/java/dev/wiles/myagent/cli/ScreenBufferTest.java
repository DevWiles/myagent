package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 全屏模式的内容缓冲：备用屏幕没有滚动缓冲区，这些都得自己管。 */
class ScreenBufferTest {

    private static ScreenBuffer withLines(int count) {
        ScreenBuffer buffer = new ScreenBuffer();
        for (int i = 1; i <= count; i++) {
            buffer.append("第" + i + "行");
        }
        return buffer;
    }

    @Test
    @DisplayName("默认盯着最新内容，新行进来画面跟着走")
    void followsTailByDefault() {
        ScreenBuffer buffer = withLines(50);
        assertTrue(buffer.followingTail());
        assertEquals(List.of("第48行", "第49行", "第50行"), buffer.visible(3));

        buffer.append("第51行");
        assertEquals(List.of("第49行", "第50行", "第51行"), buffer.visible(3));
    }

    @Test
    @DisplayName("往上滚就脱离跟随，否则新消息一来正看的东西就被顶走了")
    void scrollingUpLeavesTail() {
        ScreenBuffer buffer = withLines(50);
        buffer.scroll(-10, 3);

        assertFalse(buffer.followingTail());
        assertEquals(List.of("第38行", "第39行", "第40行"), buffer.visible(3));

        buffer.append("第51行");
        assertEquals(List.of("第38行", "第39行", "第40行"), buffer.visible(3),
                "脱离跟随之后视口不该被新内容拖走");
    }

    @Test
    @DisplayName("滚回底部自动重新跟上")
    void scrollingBackToBottomResumesFollowing() {
        ScreenBuffer buffer = withLines(50);
        buffer.scroll(-10, 3);
        buffer.scroll(10, 3);

        assertTrue(buffer.followingTail());
        assertEquals(List.of("第48行", "第49行", "第50行"), buffer.visible(3));
    }

    @Test
    @DisplayName("滚过头不会越界")
    void scrollClampsAtBoundaries() {
        ScreenBuffer buffer = withLines(10);
        buffer.scroll(-999, 3);
        assertEquals(List.of("第1行", "第2行", "第3行"), buffer.visible(3));

        buffer.scroll(999, 3);
        assertEquals(List.of("第8行", "第9行", "第10行"), buffer.visible(3));
        assertTrue(buffer.followingTail());
    }

    @Test
    @DisplayName("内容不足一屏时全部显示")
    void showsEverythingWhenShort() {
        ScreenBuffer buffer = withLines(2);
        assertEquals(List.of("第1行", "第2行"), buffer.visible(10));
    }

    @Test
    @DisplayName("scrollToBottom 直接跳回最新")
    void jumpToBottom() {
        ScreenBuffer buffer = withLines(50);
        buffer.scroll(-20, 3);
        assertFalse(buffer.followingTail());

        buffer.scrollToBottom();
        assertTrue(buffer.followingTail());
        assertEquals(List.of("第48行", "第49行", "第50行"), buffer.visible(3));
    }

    @Test
    @DisplayName("removeLast 删掉末尾若干行——全屏模式下「收起」就靠它")
    void removesTrailingLines() {
        ScreenBuffer buffer = withLines(10);
        buffer.removeLast(3);

        assertEquals(7, buffer.size());
        assertEquals(List.of("第5行", "第6行", "第7行"), buffer.visible(3));
    }

    @Test
    @DisplayName("删得比现有的还多也不出错")
    void removeMoreThanAvailable() {
        ScreenBuffer buffer = withLines(3);
        buffer.removeLast(10);
        assertEquals(0, buffer.size());
        assertTrue(buffer.visible(5).isEmpty());
    }

    @Test
    @DisplayName("超过上限时丢掉最老的，视口位置跟着挪，不然会错位")
    void dropsOldestBeyondLimit() {
        ScreenBuffer buffer = new ScreenBuffer();
        for (int i = 1; i <= 5200; i++) {
            buffer.append("行" + i);
        }
        assertEquals(5000, buffer.size());
        assertEquals(List.of("行5199", "行5200"), buffer.visible(2));
    }

    @Test
    @DisplayName("空缓冲区不炸")
    void handlesEmpty() {
        ScreenBuffer buffer = new ScreenBuffer();
        assertTrue(buffer.visible(5).isEmpty());
        buffer.scroll(-5, 5);
        buffer.removeLast(3);
        assertEquals(0, buffer.size());
    }
}
