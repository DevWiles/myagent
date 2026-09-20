package dev.wiles.myagent.tool.safety;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级 unified diff，自己写的。
 *
 * <h3>为什么审批框里非得有它</h3>
 * 以前覆盖文件的确认信息是「原 30 行 → 新 45 行」。这句话唯一能支撑的判断是
 * 「文件变长了」——而用户要决定的是「这个改动对不对」。
 * <b>看不见内容的审批不是审批，是仪式</b>：人点头不是因为看懂了，
 * 是因为框弹出来了总得按一个键。信息量不够的确认框，最终会训练出条件反射式的同意。
 *
 * <h3>怎么算的</h3>
 * 先掐掉首尾完全相同的部分，再对中间那段做 LCS 动态规划。
 * 先掐首尾不只是为了快：真实的改动几乎总是集中在几处，
 * 掐完之后一个两千行的文件常常只剩十几行要比，表格就不会爆。
 *
 * <p>没上 Myers。它在超大输入上更省内存，但这里的输入已经被
 * {@link #MAX_CELLS} 挡住了——够不着 Myers 才有意义的那个量级。
 * 而 LCS 表格几行就能读懂，Myers 的 D-path 不能。
 *
 * <h3>三道预算，撞上哪道都要明说</h3>
 * 太大不比、输出太长截断、截断了要讲清还剩多少。悄悄少给比不给更糟——
 * 少给的时候人以为自己看到了全部，于是按了同意。
 */
public final class TextDiff {

    /** 默认最多输出多少行。审批框是终端里的一个框，不是 pager。 */
    public static final int DEFAULT_MAX_LINES = 40;

    /** 改动上下各留几行。 */
    static final int CONTEXT = 3;

    /** LCS 表格的格子上限。掐完首尾还超过这个数，就不比了。 */
    static final int MAX_CELLS = 400_000;

    private TextDiff() {
    }

    public static String unified(String oldText, String newText) {
        return unified(oldText, newText, DEFAULT_MAX_LINES);
    }

    /**
     * @return unified diff 文本；两边一样时返回空串
     */
    public static String unified(String oldText, String newText, int maxLines) {
        String[] before = lines(oldText);
        String[] after = lines(newText);
        if (oldText == null ? newText == null : oldText.equals(newText)) {
            return "";
        }

        int head = commonPrefix(before, after);
        int tail = commonSuffix(before, after, head);
        int midOld = before.length - head - tail;
        int midNew = after.length - head - tail;

        if ((long) midOld * midNew > MAX_CELLS) {
            return "（改动太大，无法逐行比对：原 " + displayCount(before)
                    + " 行 → 新 " + displayCount(after) + " 行）";
        }

        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < head; i++) {
            ops.add(new Op(Kind.EQ, before[i]));
        }
        ops.addAll(diffMiddle(before, after, head, midOld, midNew));
        for (int i = 0; i < tail; i++) {
            ops.add(new Op(Kind.EQ, before[before.length - tail + i]));
        }
        return render(ops, maxLines);
    }

    // ------------------------------------------------------------------ 比

    private enum Kind { EQ, DEL, ADD }

    private record Op(Kind kind, String text) {
    }

    private static List<Op> diffMiddle(String[] before, String[] after,
                                       int head, int midOld, int midNew) {
        List<Op> ops = new ArrayList<>();
        if (midOld == 0 && midNew == 0) {
            return ops;
        }
        // 一边是空的时候没什么可比的，全删或全加
        if (midOld == 0 || midNew == 0) {
            for (int i = 0; i < midOld; i++) {
                ops.add(new Op(Kind.DEL, before[head + i]));
            }
            for (int i = 0; i < midNew; i++) {
                ops.add(new Op(Kind.ADD, after[head + i]));
            }
            return ops;
        }

        // lcs[i][j] = before[head+i..] 和 after[head+j..] 的最长公共子序列长度
        int[][] lcs = new int[midOld + 1][midNew + 1];
        for (int i = midOld - 1; i >= 0; i--) {
            for (int j = midNew - 1; j >= 0; j--) {
                lcs[i][j] = before[head + i].equals(after[head + j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        int i = 0;
        int j = 0;
        while (i < midOld && j < midNew) {
            if (before[head + i].equals(after[head + j])) {
                ops.add(new Op(Kind.EQ, before[head + i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                // 删在加前面：同一处改动读起来是「原来是这样 → 现在是这样」，
                // 反过来排会让人先看见结果再看见原因
                ops.add(new Op(Kind.DEL, before[head + i]));
                i++;
            } else {
                ops.add(new Op(Kind.ADD, after[head + j]));
                j++;
            }
        }
        while (i < midOld) {
            ops.add(new Op(Kind.DEL, before[head + i++]));
        }
        while (j < midNew) {
            ops.add(new Op(Kind.ADD, after[head + j++]));
        }
        return ops;
    }

    // ------------------------------------------------------------------ 印

    private static String render(List<Op> ops, int maxLines) {
        boolean[] keep = new boolean[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).kind() != Kind.EQ) {
                for (int k = Math.max(0, i - CONTEXT);
                     k <= Math.min(ops.size() - 1, i + CONTEXT); k++) {
                    keep[k] = true;
                }
            }
        }

        StringBuilder out = new StringBuilder();
        int printed = 0;
        int skipped = 0;
        int oldLine = 1;
        int newLine = 1;
        boolean inHunk = false;

        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (!keep[i]) {
                inHunk = false;                 // 跳过的地方要断开，下一段改动重新印位置头
                oldLine += op.kind() == Kind.ADD ? 0 : 1;
                newLine += op.kind() == Kind.DEL ? 0 : 1;
                continue;
            }
            if (printed >= maxLines) {
                if (op.kind() != Kind.EQ) {
                    skipped++;
                }
                continue;
            }
            if (!inHunk) {
                // 每一段改动前印一个位置头，否则用户不知道这是文件的哪儿
                out.append("@@ -").append(oldLine).append(" +").append(newLine).append(" @@\n");
                printed++;
                inHunk = true;
            }
            out.append(switch (op.kind()) {
                case EQ -> "  ";
                case DEL -> "- ";
                case ADD -> "+ ";
            }).append(op.text()).append('\n');
            printed++;
            oldLine += op.kind() == Kind.ADD ? 0 : 1;
            newLine += op.kind() == Kind.DEL ? 0 : 1;
        }

        if (skipped > 0) {
            out.append("… 还有 ").append(skipped).append(" 行改动没有显示\n");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 杂

    private static String[] lines(String text) {
        return text == null || text.isEmpty() ? new String[0] : text.split("\n", -1);
    }

    /**
     * 报给人看的行数。
     *
     * <p>比对时用 {@code split("\n", -1)}，末尾那个换行符会切出一个空元素——
     * 这对比对是对的（否则「末尾少个换行」这种改动就看不见了），
     * 但报出去是错的：用户和 {@code wc -l} 都认为 1200 行的文件就是 1200 行。
     */
    private static int displayCount(String[] lines) {
        return lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
    }

    private static int commonPrefix(String[] a, String[] b) {
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i].equals(b[i])) {
            i++;
        }
        return i;
    }

    private static int commonSuffix(String[] a, String[] b, int head) {
        int limit = Math.min(a.length, b.length) - head;
        int i = 0;
        while (i < limit && a[a.length - 1 - i].equals(b[b.length - 1 - i])) {
            i++;
        }
        return i;
    }
}
