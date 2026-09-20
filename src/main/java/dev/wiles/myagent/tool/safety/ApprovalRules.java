package dev.wiles.myagent.tool.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 一整套授权规则，以及「这次要不要问人」的判定。
 *
 * <h3>这个类属于第 ① 类决策</h3>
 * 「这个动作危险吗」和「它匹不匹配已授权的规则」是两个完全不同性质的问题。
 * 后者<b>有唯一正确答案、可由代码推导</b>，所以它该被算出来、被测试钉死，而不是问人。
 * 只有算不出来的那些才升到人那里去。
 *
 * <p>以前没有这一层，于是「以后都同意」只能做成一个全局开关——
 * 因为压根没有「规则」这个概念可匹配。一个开关一旦按下，后面所有危险动作
 * 全部静默放行：为了批一条 {@code mvn test} 而顺手授权了 {@code curl} 和 {@code rm}。
 * 那不是「少问一点」，是「不问了」。
 *
 * <h3>命令必须逐段判，这是整个类里最要紧的一行</h3>
 * {@code mvn test && rm -rf ~} 如果整条去比前缀，规则 {@code mvn test *} 会直接放行它。
 * 所以 BASH 的判定是：<b>拆成段，每一段都得有 allow 规则罩着，才算放行；
 * 任何一段撞上 deny，整条拒绝。</b>
 *
 * <p>而且拆段用的是 {@link CommandGuard#splitSegments} <b>本尊</b>，不是另写一个。
 * 理由写在那个方法的注释里：两边拆得不一样，差值就是后门。
 */
public final class ApprovalRules {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRules.class);

    /** 装得下多少条。手写文件塞爆了也不至于让每次审批都遍历上万条。 */
    static final int MAX_RULES = 500;

    public enum Decision {
        /** 有规则罩着，不用问 */ ALLOW,
        /** 撞上黑名单，直接拒，也不用问 */ DENY,
        /** 算不出来，去问人 */ ASK
    }

    /**
     * @param decision 判定
     * @param rule     促成这个判定的规则。ASK 时为 null
     */
    public record Outcome(Decision decision, ApprovalRule rule) {
        static final Outcome ASK = new Outcome(Decision.ASK, null);
    }

    private final List<ApprovalRule> allow;
    private final List<ApprovalRule> deny;

    private ApprovalRules(List<ApprovalRule> allow, List<ApprovalRule> deny) {
        this.allow = List.copyOf(allow);
        this.deny = List.copyOf(deny);
    }

    public static ApprovalRules empty() {
        return new ApprovalRules(List.of(), List.of());
    }

    /**
     * 装载。<b>会丢掉「什么都放行」的 allow 规则</b>——理由见
     * {@link ApprovalRule#matchesEverything()}。丢的时候记一条 warn，
     * 不然用户会困惑于「我明明写了怎么不生效」。
     */
    public static ApprovalRules of(List<ApprovalRule> allow, List<ApprovalRule> deny) {
        List<ApprovalRule> kept = new ArrayList<>();
        for (ApprovalRule rule : allow == null ? List.<ApprovalRule>of() : allow) {
            if (rule == null) {
                continue;
            }
            if (rule.matchesEverything()) {
                log.warn("忽略一条放行一切的规则 {}——它会悄悄关掉整个审批系统", rule.display());
                continue;
            }
            if (kept.size() >= MAX_RULES) {
                log.warn("放行规则超过 {} 条，后面的忽略", MAX_RULES);
                break;
            }
            kept.add(rule);
        }
        List<ApprovalRule> denies = deny == null ? List.<ApprovalRule>of()
                : deny.stream().filter(java.util.Objects::nonNull).limit(MAX_RULES).toList();
        return new ApprovalRules(kept, denies);
    }

    public List<ApprovalRule> allow() {
        return allow;
    }

    public List<ApprovalRule> deny() {
        return deny;
    }

    public boolean isEmpty() {
        return allow.isEmpty() && deny.isEmpty();
    }

    /** 加几条放行规则，返回新的一份。已经被现有规则罩住的不重复加。 */
    public ApprovalRules plus(List<ApprovalRule> more) {
        List<ApprovalRule> merged = new ArrayList<>(allow);
        for (ApprovalRule rule : more) {
            if (rule == null || rule.matchesEverything()) {
                continue;
            }
            boolean duplicate = merged.stream()
                    .anyMatch(existing -> existing.kind() == rule.kind()
                            && existing.match().equals(rule.match()));
            if (!duplicate) {
                merged.add(rule);
            }
        }
        return of(merged, deny);
    }

    /** 要不要问人。 */
    public Outcome check(ApprovalRequest request) {
        return switch (request.kind()) {
            case BASH -> checkCommand(request.subject());
            case WRITE -> checkOne(ApprovalKind.WRITE, request.subject());
        };
    }

    private Outcome checkCommand(String command) {
        List<String> segments = CommandGuard.splitSegments(command).stream()
                .map(String::strip)
                .filter(segment -> !segment.isEmpty())
                .toList();
        if (segments.isEmpty()) {
            return Outcome.ASK;
        }
        // deny 先看，而且只要一段撞上就整条拒——黑名单要悲观
        for (String segment : segments) {
            ApprovalRule hit = firstCovering(deny, ApprovalKind.BASH, segment);
            if (hit != null) {
                return new Outcome(Decision.DENY, hit);
            }
        }
        // allow 要每一段都罩得住才算——白名单要乐观但不能有缺口
        ApprovalRule last = null;
        for (String segment : segments) {
            ApprovalRule hit = firstCovering(allow, ApprovalKind.BASH, segment);
            if (hit == null) {
                return Outcome.ASK;
            }
            last = hit;
        }
        return new Outcome(Decision.ALLOW, last);
    }

    private Outcome checkOne(ApprovalKind kind, String subject) {
        ApprovalRule denied = firstCovering(deny, kind, subject);
        if (denied != null) {
            return new Outcome(Decision.DENY, denied);
        }
        ApprovalRule allowed = firstCovering(allow, kind, subject);
        return allowed == null ? Outcome.ASK : new Outcome(Decision.ALLOW, allowed);
    }

    private static ApprovalRule firstCovering(List<ApprovalRule> rules,
                                              ApprovalKind kind, String subject) {
        for (ApprovalRule rule : rules) {
            if (rule.covers(kind, subject)) {
                return rule;
            }
        }
        return null;
    }
}
