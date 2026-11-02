package dev.wiles.myagent.review;

import dev.wiles.myagent.prompt.PromptRepository;

/**
 * 收尾审查的文字：完成标准（模板）和审查材料（拼出来的）。
 *
 * <h3>为什么末行判定的措辞写死在 final-review.md 里</h3>
 * {@link ReviewLoop#passed} 只认最后一行的「通过 / 重新执行」。
 * 判定规则和要求模型遵守的措辞必须指着同一组词——改词只改一头，
 * 审查就永远通过或永远打回，而且没有任何报错。
 */
public final class ReviewPrompt {

    private static final PromptRepository REPO = PromptRepository.createDefault();

    static {
        // 和 AgentRoles 静态块同一个道理：模板漏打包不会有任何症状，
        // 只会在第一次收尾审查时才蹦出来。宁可启动就炸
        REPO.loadRequired("review/final-review.md");
    }

    private ReviewPrompt() {
    }

    /** 收尾审查的完成标准：跑完整测试、对照原话、看 diff，见 review/final-review.md。 */
    public static String finalReviewAcceptance(int steps) {
        return REPO.loadRequired("review/final-review.md")
                .replace("{{@steps}}", String.valueOf(steps));
    }

    /**
     * 收尾审查的材料：先事实（程序算的 diff），后转述（主 agent 的收尾说明）。
     * 用户原话不在这里——它由 {@code TaskBrief} 排在整段提示词最前面。
     */
    public static String finalReviewBrief(int round, String diff, String mainAnswer) {
        return "收尾审查第 " + round + " 轮：检查另一个 agent 刚完成的改动。\n\n"
                + "【改动 diff——程序比对开工前后的工作区算出来的，不是它的自述】\n"
                + (diff == null || diff.isBlank() ? "（没有检测到改动）" : diff)
                + "\n\n【它的收尾说明——只作参考，可能夸大或漏说】\n"
                + (mainAnswer == null || mainAnswer.isBlank() ? "（没有说明）" : mainAnswer);
    }
}
