package dev.wiles.myagent.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 看一眼用户这句话，判断该直接干还是先拆个计划。
 *
 * <h3>先说清楚它做不到什么</h3>
 * 这是<b>字面判断</b>，没有理解。「把那个改一下」六个字背后可能是三天的活，
 * 而「帮我看看 PlanExecutor 里 settle 方法取消分支的判定逻辑」二十多个字只是一次查询。
 * 所以这个类的定位和 {@code CommandGuard} 一样——<b>认出明显的两头，
 * 中间那片交给人</b>，而不是妄想每次都判对。
 *
 * <h3>为什么打分而不是规则优先</h3>
 * 规则优先（「命中 A 就 PLAN」）的毛病是没法表达强弱：「所有」和「然后」各自都不够硬，
 * 凑在一起就很硬了。打分天然能叠加，也能让长度这种<b>只配当加权、不配单独定档</b>的
 * 信号有地方待。
 *
 * <h3>长度的坑</h3>
 * <b>长度绝不能单独定档。</b>「重构整个项目」七个字，事情大得很；
 * 一句四十字的查询仍然只是查询。所以长度只在<b>已经有别的信号</b>时才加减一分。
 * 这是最容易想当然写错的一条。
 */
public final class ModeHeuristic {

    /** 多步连接词：一句话里出现「然后」，多半就不止一步。 */
    private static final List<String> MULTI_STEP = List.of(
            "然后", "接着", "之后", "并且", "同时", "再把", "再改", "再跑", "顺便");

    /** 范围词：范围一铺开，步骤就少不了。 */
    private static final List<String> SCOPE = List.of(
            "所有", "全部", "每个", "每一个", "批量", "整个项目", "整个仓库", "逐个", "挨个", "统统");

    /** 重活：这些词本身就意味着牵一发动全身。 */
    private static final List<String> HEAVY = List.of(
            "重构", "迁移", "升级", "改造", "拆分", "合并", "重写", "接入", "集成");

    /** 疑问句，多半是查询。 */
    private static final List<String> QUESTION = List.of(
            "是什么", "为什么", "怎么", "如何", "哪里", "哪个", "什么时候", "有没有", "是不是",
            "吗", "?", "？");

    /** 只读动词：看、读、查，干完就完了。 */
    private static final List<String> READ_ONLY = List.of(
            "看看", "看一下", "读一下", "查一下", "列出", "解释", "说明", "介绍");

    /** 枚举结构：另起一行写 1. / - / •，是「我要你做好几件事」最直白的信号。 */
    private static final Pattern ENUMERATION =
            Pattern.compile("(?m)^\\s*(?:[0-9]+[.、)]|[-*•])\\s+");

    /** 单个类别最多算几分。一句话里堆了五个「然后」，也不该比两个更有说服力。 */
    private static final int CATEGORY_CAP = 4;

    private static final int LONG_REQUEST = 80;
    private static final int SHORT_REQUEST = 15;

    /** 到这个分数就直接进规划。 */
    static final int PLAN_THRESHOLD = 4;

    /** 到这个分数（含）以下直接干。中间那段问一句。 */
    static final int DIRECT_THRESHOLD = 0;

    /**
     * 判断结果，<b>带上理由</b>。
     *
     * <p>理由不是给日志看的，是给用户看的：自动进了规划却不说为什么，
     * 用户只会觉得「它怎么突然多问一堆」。说一句「看起来有好几步」，
     * 他要么认可，要么当场否决——两种都比莫名其妙强。
     */
    public record Decision(ExecutionMode mode, int score, List<String> reasons) {

        public String explain() {
            return reasons.isEmpty() ? "" : String.join("、", reasons);
        }
    }

    private ModeHeuristic() {
    }

    public static ExecutionMode modeOf(String request) {
        return assess(request).mode();
    }

    public static Decision assess(String request) {
        if (request == null || request.isBlank()) {
            return new Decision(ExecutionMode.DIRECT, 0, List.of());
        }
        String text = request.strip();
        List<String> reasons = new ArrayList<>();
        int score = 0;

        if (ENUMERATION.matcher(text).find()) {
            // 单独一条就够进规划：用户肯另起一行编号，本身就是在说「这是好几件事」。
            // 只列一条又是查询的话，下面的减分会把它拉回来
            score += PLAN_THRESHOLD;
            reasons.add("列了条目");
        }
        score += weigh(text, MULTI_STEP, 2, reasons, "提到好几步");
        score += weigh(text, SCOPE, 2, reasons, "范围铺得比较开");
        score += weigh(text, HEAVY, 2, reasons, "是改动面比较大的活");
        score -= weigh(text, QUESTION, 2, reasons, "像是在问问题");
        score -= weigh(text, READ_ONLY, 2, reasons, "只是看看");

        // 长度只加权，不定档：已经有别的信号时，长短才说明点什么
        if (score > 0 && text.length() > LONG_REQUEST) {
            score += 1;
            reasons.add("说得比较长");
        } else if (score <= 0 && text.length() < SHORT_REQUEST) {
            score -= 1;
        }

        return new Decision(modeFor(score), score, List.copyOf(reasons));
    }

    private static ExecutionMode modeFor(int score) {
        if (score >= PLAN_THRESHOLD) {
            return ExecutionMode.PLAN;
        }
        return score <= DIRECT_THRESHOLD ? ExecutionMode.DIRECT : ExecutionMode.ASK;
    }

    /**
     * 一个类别里命中了几个<b>不同</b>的词，每个算 {@code each} 分，封顶 {@link #CATEGORY_CAP}。
     *
     * <p>按「命中几个不同的词」算而不是「出现几次」：同一个词说三遍，
     * 并不比说一遍更能说明这件事复杂。
     */
    private static int weigh(String text, List<String> keywords, int each,
                             List<String> reasons, String reason) {
        int hits = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                hits++;
            }
        }
        if (hits == 0) {
            return 0;
        }
        reasons.add(reason);
        return Math.min(hits * each, CATEGORY_CAP);
    }
}
