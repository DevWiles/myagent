package dev.wiles.myagent.memory;

/**
 * 这条记忆是<b>怎么来的</b>——也就是它有多可信。
 *
 * <h3>为什么要记来源</h3>
 * 两条记忆冲突时（「用 npm」vs「用 bun」），得有个依据决定信哪条。
 * 而来源就是最好用的那个依据：<b>用户亲口纠正过的，比 agent 自己观察到的可信得多</b>，
 * 也过期得慢得多——观察到的事实（某个文件在哪）随着代码改动天天在变，
 * 用户的偏好可能一年都不会变。
 *
 * <p>这个分级借自
 * <a href="https://github.com/SaravananJaichandar/coding-agent-memory-benchmark">
 * coding-agent-memory-benchmark</a>，它按证据类型设不同的衰减期
 * （user_correction 730 天、test 180 天、session 14 天），
 * 在 SWE-bench Verified 的 49 组配对任务上拿到 +10.2 分。
 *
 * <h3>本轮只记录，不使用</h3>
 * 衰减和冲突消解都没做——那两件事需要先有真实语料才知道该怎么定参数，
 * 拍脑袋定一个半衰期，比不做更糟。但字段现在就得留：
 * 等攒够语料再回头补，那时候<b>历史记忆里没有这个字段</b>，
 * 只能靠猜或者全部作废。
 */
public enum MemorySource {

    /** 用户明确纠正过 agent 的做法。最可信。 */
    USER_CORRECTION("user_correction"),

    /** 用户主动陈述的事实或偏好。 */
    USER_STATED("user_stated"),

    /** agent 自己跑出来、读出来的结论。最容易过期。 */
    OBSERVATION("observation");

    private final String wire;

    MemorySource(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 认不出来就当 OBSERVATION——可信度最低的那个，宁可低估。 */
    public static MemorySource from(String raw) {
        if (raw != null) {
            String t = raw.trim();
            for (MemorySource s : values()) {
                if (s.wire.equalsIgnoreCase(t)) {
                    return s;
                }
            }
        }
        return OBSERVATION;
    }
}
