package dev.wiles.myagent.cli;

/**
 * 输入行周围要画的装饰。
 *
 * @param top    输入行上方的一行（横线）
 * @param bottom 输入行下方的一行（横线）
 * @param below  再下面一行（上下文用量之类的状态），可为 null
 */
public record InputFrame(String top, String bottom, String below) {

    /** 下方一共占几行——提交时光标要跨过它们。 */
    public int linesBelow() {
        int count = bottom == null ? 0 : 1;
        return below == null ? count : count + 1;
    }

    public static InputFrame none() {
        return new InputFrame(null, null, null);
    }

    public boolean isEmpty() {
        return top == null && bottom == null && below == null;
    }
}
