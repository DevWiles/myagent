package dev.wiles.myagent.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏模式下的内容缓冲区。
 *
 * <h3>为什么需要它</h3>
 * 顺序打印模式里，内容打出去就归终端管了——终端负责保存、滚动、选中。
 * 进了备用屏幕这些全没了：备用屏幕<b>没有滚动缓冲区</b>，
 * 屏幕装不下的内容就是不存在。所以程序得自己留一份，
 * 再根据滚动位置决定这一屏画哪一段。
 *
 * <h3>跟随末尾</h3>
 * 默认盯着最新内容（像 tail -f）。用户往上滚就脱离跟随，
 * 滚回底部又自动跟上——不然新消息来了画面会被顶走，正看着的东西就没了。
 */
public final class ScreenBuffer {

    /** 最多留多少行。太长会白占内存，而且人也不会往上翻那么远。 */
    private static final int MAX_LINES = 5000;

    private final List<String> lines = new ArrayList<>();
    /** 视口顶部对应第几行。 */
    private int top;
    /** 是否跟随最新内容。 */
    private boolean followTail = true;

    public void append(String line) {
        lines.add(line == null ? "" : line);
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
            if (top > 0) {
                top--;
            }
        }
    }

    /**
     * 删掉末尾若干行。
     *
     * 全屏模式下「收起工具详情」就是这么做的——直接把那几行从缓冲区里拿掉，
     * 比顺序打印模式里靠光标上移加清行干净得多，也不怕内容已经滚出屏幕。
     */
    public void removeLast(int count) {
        for (int i = 0; i < count && !lines.isEmpty(); i++) {
            lines.remove(lines.size() - 1);
        }
        top = Math.min(top, Math.max(0, lines.size() - 1));
    }

    public void clear() {
        lines.clear();
        top = 0;
        followTail = true;
    }

    public int size() {
        return lines.size();
    }

    public boolean followingTail() {
        return followTail;
    }

    /**
     * 取出这一屏要画的内容。
     *
     * @param viewportHeight 内容区有多少行
     */
    public List<String> visible(int viewportHeight) {
        if (viewportHeight <= 0) {
            return List.of();
        }
        if (followTail) {
            top = Math.max(0, lines.size() - viewportHeight);
        }
        int from = Math.max(0, Math.min(top, Math.max(0, lines.size() - 1)));
        int to = Math.min(lines.size(), from + viewportHeight);
        return new ArrayList<>(lines.subList(from, to));
    }

    /**
     * 滚动。
     *
     * @param delta          正数往下（看更新的），负数往上（看更早的）
     * @param viewportHeight 内容区有多少行，用来算边界
     */
    public void scroll(int delta, int viewportHeight) {
        int maxTop = Math.max(0, lines.size() - viewportHeight);
        if (followTail) {
            top = maxTop;               // 从跟随状态脱离时，先把 top 对齐到当前位置
        }
        top = Math.max(0, Math.min(maxTop, top + delta));
        // 滚回底部就重新跟上，不然新消息来了画面不动，用户以为卡住了
        followTail = top >= maxTop;
    }

    /** 直接跳到最新。 */
    public void scrollToBottom() {
        followTail = true;
    }

    /** 当前滚动位置的百分比，用于在状态栏提示「你正在看历史」。 */
    public int scrollPercent(int viewportHeight) {
        int maxTop = Math.max(0, lines.size() - viewportHeight);
        return maxTop == 0 ? 100 : Math.min(100, top * 100 / maxTop);
    }
}
