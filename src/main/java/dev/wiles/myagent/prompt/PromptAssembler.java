package dev.wiles.myagent.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把几段提示词拼成发给模型的那一份 system 消息。
 *
 * <pre>
 *   base.md                恒定：身份 / 语言 / 工作区 / 工作方式 / 回答风格
 * + modes/{mode}.md        这一份是给谁用的
 * + policy/readonly.md     只读时才有
 * + 长期记忆索引            放最后，理由见下
 * </pre>
 *
 * <h3>记忆索引为什么拼在最后</h3>
 * 它的内容每次启动都不一样（记一条就变一次），而前面那几段是恒定的。
 * 把变动的部分放尾部，前缀才能稳定命中提供方的 prompt 缓存——
 * DeepSeek 命中和未命中差 50 倍。反过来放最前面，等于每轮全价重发整份提示词。
 *
 * <h3>两道不变量，装配时就炸</h3>
 * <ol>
 *   <li><b>关键段必须在</b>。少了「语言」那段，模型会改说英文；而这种事没有任何
 *       报错，只在用户看到英文回复时才发现，而那时已经发出去了。</li>
 *   <li><b>不许留下没替换的占位符</b>。{@code {{@workspac}}} 打错一个字母，
 *       模型看到的就是字面的 <code>{{@workspac}}</code>——它会把这串东西当成
 *       内容的一部分去理解，而日志里一切正常。这一条 grep 不出来、测试也未必覆盖，
 *       只能在装配这一刻查。</li>
 * </ol>
 * 两条都是「不查就永远不会暴露」的那类问题，所以宁可启动就失败。
 */
public final class PromptAssembler {

    /**
     * 装配器的占位符：<code>{{@名字}}</code>。
     *
     * <h3>那个 @ 不是装饰</h3>
     * 提示词正文里<b>本来就有</b> <code>{{t1}}</code> 这种东西——那是计划里的
     * 任务引用语法（见 {@code TaskRefs}），是模型要<b>输出</b>的内容，不是要我们替换的。
     * 两种语法撞在一起的后果很具体：规划提示词一搬进资源文件，装配器就会把
     * <code>{{t1}}</code> 当成没填的占位符抛出来，而那段文字完全正确。
     *
     * <p>{@code TaskRefs} 认的 id 是 {@code [A-Za-z0-9_.-]}，不含 {@code @}，
     * 所以加一个 {@code @} 之后两种语法<b>证明性地不相交</b>——
     * 不是「约定不要撞」，是撞不上。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{@([a-zA-Z0-9_]+)}}");

    private final PromptSource repository;

    public PromptAssembler(PromptSource repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public static PromptAssembler createDefault() {
        return new PromptAssembler(PromptRepository.createDefault());
    }

    /**
     * 装配。
     *
     * @throws IllegalStateException 缺了关键段，或者还有没替换掉的占位符
     */
    public String assemble(PromptMode mode, PromptContext context) {
        Objects.requireNonNull(mode, "mode");
        PromptContext ctx = context == null ? PromptContext.empty() : context;

        List<String> sections = new ArrayList<>();
        if (mode.includesBase()) {
            sections.add(repository.loadRequired("base.md"));
        }
        sections.add(repository.loadRequired(mode.resourcePath()));
        if (ctx.readOnly()) {
            sections.add(repository.loadRequired("policy/readonly.md"));
        }

        String assembled = String.join("\n\n", sections);
        assembled = substitute(assembled, ctx.variables());

        // 记忆索引在替换之后才拼：它是运行时算出来的文本，里面可能正好含有
        // 形如 {{...}} 的内容（用户自己写的记忆正文什么都可能有）。
        // 先拼再替换的话，那段内容会被当成占位符去找变量，找不到就抛——
        // 一条无辜的记忆能把整个 agent 卡在启动阶段
        if (ctx.memoryIndex() != null && !ctx.memoryIndex().isBlank()) {
            assembled = assembled + "\n" + ctx.memoryIndex();
        }

        requireMarkers(assembled, mode);
        return assembled;
    }

    /**
     * 把 {@code {{名字}}} 换成值。
     *
     * <p>找不到对应变量时<b>不静默留着</b>——见类注释第二条。
     */
    private static String substitute(String template, Map<String, String> variables) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1);
            String value = variables.get(name);
            if (value == null) {
                missing.add(name);
                value = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("提示词里有没填的占位符: " + missing
                    + "（多半是名字打错了。留着不管的话，模型会把字面的 {{@名字}} "
                    + "当成内容的一部分去理解，而日志里一切正常）");
        }
        return out.toString();
    }

    private static void requireMarkers(String assembled, PromptMode mode) {
        List<String> missing = mode.requiredMarkers().stream()
                .filter(s -> !assembled.contains(s))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(mode + " 装配出来的提示词缺了关键段: " + missing
                    + "（比如少了「语言」那段，模型会改说英文，而这件事不会报错——"
                    + "等用户看到英文回复时，已经发出去了）");
        }
    }
}
