package dev.wiles.myagent.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务描述里的<b>数据流引用</b>：{@code {{t1}}} 表示「这里要填上 t1 的产出」。
 *
 * <p>为什么光有 {@code dependsOn} 不够——它表达的是<b>控制流</b>（t2 得等 t1 跑完），
 * 但 t1 读到的东西<b>怎么传给 t2</b>，图里说不清楚。t2 有三个上游时，
 * 一句「根据上游的结果改文件」到底指谁，执行 t2 的子模型只能猜。
 *
 * <p>有了它，计划才真的是一张<b>数据流图</b>而不只是带先后顺序的清单：
 * <pre>
 *   {"id":"t3","description":"把 {{t1}} 里查到的版本号写进 README","dependsOn":["t1"]}
 * </pre>
 * 执行 t3 之前，{@code {{t1}}} 会被换成 t1 真正的产出（见 {@link #resolve}）。
 *
 * <p><b>引用和依赖必须对得上</b>：引用了 {@code {{t1}}} 却没把 t1 写进 dependsOn，
 * 那么 t3 可能在 t1 之前就跑了，占位符换进来的是一片空白。这种不一致在
 * {@link PlanParser} 里当场拦下并回灌给模型，而不是等跑到那儿才发现。
 */
public final class TaskRefs {

    /**
     * 允许 id 里有字母数字和 {@code . _ -}——子任务的 id 形如 {@code t1.1}，点号必须收。
     * 花括号内侧容忍空格，模型写 <code>{{ t1 }}</code> 是常事，为这个打回重来不划算。
     */
    private static final Pattern REF = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    private TaskRefs() {
    }

    /** 文本里引用到的任务 id，去重且保持出现顺序（报错时按原文顺序说话，人好对照）。 */
    public static List<String> referencedIn(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = REF.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return new ArrayList<>(found);
    }

    /**
     * 把占位符换成上游真正的产出。
     *
     * <p>换成带界定符的一段而不是裸文本：上游产出往往有好几行，
     * 直接怼进句子中间会和任务描述糊成一团，模型分不清哪句是指令、哪句是材料。
     *
     * <p>找不到对应上游、或上游没产出时<b>不留空</b>——留空等于凭空删掉一句指令，
     * 模型会以为本来就该是这样。明说「没有产出」，它至少知道该去追问或报告缺料。
     */
    public static String resolve(String text, List<Task> upstream) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(render(m.group(1), upstream)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String render(String id, List<Task> upstream) {
        for (Task task : upstream == null ? List.<Task>of() : upstream) {
            if (task.id().equals(id)) {
                String result = task.result();
                return result == null || result.isBlank()
                        ? "【" + id + " 没有给出产出】"
                        : "【" + id + " 的产出: " + result.strip() + "】";
            }
        }
        return "【" + id + " 的产出不可用】";
    }
}
