package dev.wiles.myagent.tool.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批的第一道关：先算，算不出来才问人。
 *
 * <pre>
 *   工具 → PolicyGate ─┬─ 规则命中 allow → 直接放行，不打扰任何人
 *                      ├─ 规则命中 deny  → 直接拒绝，也不打扰
 *                      └─ 算不出来        → 交给下一层（界面 / 终端 / denyAll）
 * </pre>
 *
 * <h3>为什么是装饰器，而不是塞进 RemoteApproval</h3>
 * 和 {@code AuditLog.audited} 一个道理：审批的入口以后还会增加，
 * 把规则判定埋在某一个实现里，总有一天会出现一条绕过它的路。
 * 包在外面，任何经过这条策略的请求都躲不掉。
 *
 * <p>顺带也让两件事各归各位：<b>规则匹配是算出来的</b>（这个类），
 * <b>没算出来的才是人的判断</b>（下一层）。两者混在一起时，
 * 「以后都同意」只能做成一个全局开关，因为根本没有规则这个概念。
 *
 * <h3>每次都看一眼文件时间</h3>
 * 撤销授权的办法是手改 {@code ~/.myagent/policy.json}。要是得重启才生效，
 * 用户改完发现照样不问，会以为自己改错了地方——然后去按更多的「以后都同意」。
 * 审批本来就不频繁，多一次 stat 不值一提。
 */
public final class PolicyGate implements ApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(PolicyGate.class);

    private final ApprovalPolicy ask;
    private final PolicyStore store;

    private volatile ApprovalRules rules;
    private volatile long loadedAt;

    public PolicyGate(ApprovalPolicy ask, PolicyStore store) {
        this.ask = ask;
        this.store = store;
        this.rules = store.load();
        this.loadedAt = store.lastModified();
    }

    /** 当前生效的规则。给界面和测试看。 */
    public ApprovalRules rules() {
        return rules;
    }

    @Override
    public ApprovalVerdict decide(ApprovalRequest request) {
        reloadIfChanged();

        ApprovalRules current = rules;
        ApprovalRules.Outcome outcome = current.check(request);
        switch (outcome.decision()) {
            case DENY -> {
                // 拒绝的理由要说清是哪条规则挡的，否则模型和用户都会
                // 反复撞同一堵墙，还找不到墙在哪儿
                return ApprovalVerdict.no("被 " + store.file().getFileName()
                        + " 里的规则挡下: " + outcome.rule().display());
            }
            case ALLOW -> {
                log.debug("规则 {} 放行 {}", outcome.rule().display(), request.subject());
                return ApprovalVerdict.yes();
            }
            case ASK -> {
                ApprovalVerdict verdict = ask.decide(request);
                if (verdict.approved() && !verdict.remember().isEmpty()) {
                    remember(request.kind(), verdict.remember());
                }
                return verdict;
            }
        }
        return ApprovalVerdict.no();            // 枚举已经穷尽，这行只是让编译器安心
    }

    /** 把用户挑定的那几条规则存下来。存不进去只记日志——下次再问一遍而已。 */
    private synchronized void remember(ApprovalKind kind, List<String> patterns) {
        List<ApprovalRule> added = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                added.add(new ApprovalRule(kind, pattern, PolicyStore.today()));
            } catch (IllegalArgumentException e) {
                log.warn("界面回来的规则不合法（{}），跳过: {}", e.getMessage(), pattern);
            }
        }
        if (added.isEmpty()) {
            return;
        }
        ApprovalRules merged = rules.plus(added);
        if (store.save(merged)) {
            loadedAt = store.lastModified();
        }
        // 存盘失败也要让内存里这一份生效：用户已经表过态了，
        // 这一次会话里不该再问他同一件事
        rules = merged;
    }

    private synchronized void reloadIfChanged() {
        long stamp = store.lastModified();
        if (stamp != loadedAt) {
            rules = store.load();
            loadedAt = stamp;
            log.debug("{} 变了，重新装载规则", store.file());
        }
    }
}
