package dev.wiles.myagent.plan;

import java.util.List;

/**
 * 整件事的交底：用户原话 + 规划器从原话抽的需求清单。交给计划里的<b>每一个</b>执行者。
 *
 * <h3>为什么每一步都要看到原话</h3>
 * 子任务原来只拿到「任务描述 + 完成标准 + 上游产出」，三样全是规划器的转述。
 * 转述会漏，漏掉的东西下游没有任何途径找回来：bench 里 date_parse 的原话写着「两位年」，
 * 规划把规格交给一步「照着实现和测试整理」，可见测试不测两位年，于是 coder 不知道、
 * verifier 不验，计划全绿，隐藏测试挂。单 agent 看得到原话，同一题是过的。
 *
 * <p>流水线（{@code team}）的 worker 早就看得到原话（方案席的标题里带着），
 * 验收席也是对着原话验的——这里把同一件事补给 {@code /plan}。
 *
 * <p>它<b>不</b>放宽「只做这一步」的纪律：原话是用来理解这一步的边界条件的，
 * 不是让每一步都去把整件事做一遍。这层意思写在 {@link #render()} 的措辞里。
 *
 * @param request      用户原话；没有（老存档、手捏的计划）是 null
 * @param requirements 需求清单，可能为空
 */
public record TaskBrief(String request, List<String> requirements) {

    public static final TaskBrief NONE = new TaskBrief(null, List.of());

    public TaskBrief {
        request = request == null || request.isBlank() ? null : request.strip();
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    public static TaskBrief of(Plan plan) {
        return plan == null ? NONE : new TaskBrief(plan.request(), plan.requirements());
    }

    public boolean isEmpty() {
        return request == null && requirements.isEmpty();
    }

    /**
     * 排进提示词的那一段。空的时候是空串——不接交底的调用方行为和以前一字不差。
     */
    public String render() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (request != null) {
            sb.append("【整件事的目标——用户原话】\n").append(request).append("\n\n");
        }
        if (!requirements.isEmpty()) {
            sb.append("【需求清单——从原话里抽出来的，每条都是要达成的】\n");
            for (String r : requirements) {
                sb.append("- ").append(r).append('\n');
            }
            sb.append('\n');
        }
        sb.append("上面是整件事的交底，用来弄清你这一步的边界条件和细节要求；")
                .append("别的步骤的活不要做。但凡这一步涉及到的要求，")
                .append("以原话和需求清单为准，不能因为任务描述没提、或者现有测试没测就打折扣。\n\n");
        return sb.toString();
    }
}
