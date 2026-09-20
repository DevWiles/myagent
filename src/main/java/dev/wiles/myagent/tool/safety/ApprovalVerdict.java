package dev.wiles.myagent.tool.safety;

import java.util.List;
import java.util.Objects;

/**
 * 审批的答复。
 *
 * <h3>为什么不再是一个 boolean</h3>
 * 两件事塞不进 boolean，而这两件事恰好是这套系统最值钱的部分：
 *
 * <p><b>一、拒绝要能带话。</b>用户按下 n 的时候，心里想的通常不是「不许动」，
 * 而是「别这么干，换个法子」。原来这句话没有出口，模型收到的只有一句
 * 「用户拒绝执行这条命令」，于是它要么原样再试一次，要么放弃。
 * HITL 一半的价值在拦住，另一半在纠偏，丢掉 {@link #note} 就等于丢掉后一半。
 *
 * <p><b>二、同意要能记住。</b>{@link #remember} 是用户在确认框里挑定的规则。
 * 注意它是<b>一串</b>而不是一条：{@code mvn test && git status} 会被拆成两段分别判，
 * 记的时候也就得记两条——只记一条的话，另一段下次还是要问，
 * 用户会觉得「我明明批过了」。
 *
 * @param approved 放行与否
 * @param note     拒绝时用户补的话，或规则拒绝时的说明。没有就是空串
 * @param remember 要记下来的规则模式。空表示只批这一次
 */
public record ApprovalVerdict(boolean approved, String note, List<String> remember) {

    public ApprovalVerdict {
        note = Objects.requireNonNullElse(note, "");
        remember = remember == null ? List.of() : List.copyOf(remember);
    }

    public static ApprovalVerdict yes() {
        return new ApprovalVerdict(true, "", List.of());
    }

    /** 同意，并把这几条规则记下来。 */
    public static ApprovalVerdict yes(List<String> remember) {
        return new ApprovalVerdict(true, "", remember);
    }

    public static ApprovalVerdict no() {
        return new ApprovalVerdict(false, "", List.of());
    }

    public static ApprovalVerdict no(String note) {
        return new ApprovalVerdict(false, note, List.of());
    }

    public boolean hasNote() {
        return !note.isBlank();
    }

    /**
     * 拼一句给模型看的拒绝说明。
     *
     * <p>{@link #note} <b>原样接在后面，一个字都不改写</b>。它要么是用户亲口补的纠偏
     * （「别用 curl，改用 mvn」），要么是规则挡下时的出处说明——两者都是模型下一步
     * 唯一的依据。转述、概括、润色都会把它变成一句正确的废话。
     *
     * <p>加前缀（「用户补充：」之类）是产出这句话的那一层的事，不是这里。
     * 这个方法不知道 note 是谁说的，也就没资格替它定语气。
     *
     * @param base 工具自己那句「因为什么做不了」
     */
    public String deniedMessage(String base) {
        return hasNote() ? base + " " + note : base;
    }
}
