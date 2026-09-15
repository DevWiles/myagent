package dev.wiles.myagent.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型输出的 Markdown 渲染成终端里能看的样子。
 *
 * <h3>为什么要做</h3>
 * 模型习惯用 Markdown 组织回答，直接打出来满屏都是 {@code **技术栈**} 和反引号，
 * 该醒目的地方反而被符号淹没了。
 *
 * <h3>为什么按行处理</h3>
 * 行内标记（{@code **粗体**}）要等整行到齐才能判断闭合，
 * 所以流式输出时得攒够一行再渲染。代价是文字从「逐字冒出来」变成「逐行冒出来」，
 * 但这其实更好看——逐字显示时用户会眼睁睁看着 {@code *} 一个个蹦出来再消失。
 *
 * <h3>代码块要记状态</h3>
 * 围栏代码块跨多行，所以这个类是有状态的：每轮对话开始前记得 {@link #reset()}。
 * 块内不做行内渲染——代码里的星号是代码，不是强调。
 */
public final class MarkdownRenderer {

    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+[.)])\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])\\1{2,}\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*```(.*)$");

    /** 保护行内代码用的占位符。用 ASCII 1，正文里不可能出现。 */
    private static final char MARKER = (char) 1;

    private boolean inCodeBlock;

    /** 每轮对话开始前调用，免得上一轮没闭合的代码块影响这一轮。 */
    public void reset() {
        inCodeBlock = false;
    }

    public boolean inCodeBlock() {
        return inCodeBlock;
    }

    public String renderLine(String line) {
        Matcher fence = FENCE.matcher(line);
        if (fence.matches()) {
            inCodeBlock = !inCodeBlock;
            String language = fence.group(1).trim();
            return inCodeBlock && !language.isEmpty()
                    ? Ansi.dim("─── " + language + " ───")
                    : Ansi.dim("───────────");
        }
        if (inCodeBlock) {
            return Ansi.green(line);        // 块内原样输出，代码里的星号是代码不是强调
        }

        Matcher heading = HEADING.matcher(line);
        if (heading.matches()) {
            int level = heading.group(1).length();
            String text = inline(heading.group(2));
            return level <= 2 ? Ansi.bold(Ansi.cyan(text)) : Ansi.bold(text);
        }

        if (RULE.matcher(line).matches()) {
            return Ansi.dim("─".repeat(40));
        }

        Matcher quote = QUOTE.matcher(line);
        if (quote.matches()) {
            return Ansi.dim("│ " + quote.group(1));
        }

        Matcher bullet = BULLET.matcher(line);
        if (bullet.matches()) {
            String indent = bullet.group(1);
            String marker = indent.isEmpty() ? "• " : "◦ ";
            return indent + Ansi.cyan(marker) + inline(bullet.group(2));
        }

        Matcher numbered = NUMBERED.matcher(line);
        if (numbered.matches()) {
            return numbered.group(1) + Ansi.cyan(numbered.group(2) + " ")
                    + inline(numbered.group(3));
        }

        return inline(line);
    }

    /**
     * 渲染行内标记。
     *
     * 先把代码片段抠出来占位，等其它规则都跑完再放回去——
     * 否则代码里的两个星号会被当成加粗标记处理掉。
     */
    private String inline(String text) {
        List<String> codes = new ArrayList<>();
        StringBuilder guarded = new StringBuilder();
        Matcher code = INLINE_CODE.matcher(text);
        while (code.find()) {
            code.appendReplacement(guarded,
                    Matcher.quoteReplacement(MARKER + String.valueOf(codes.size()) + MARKER));
            codes.add(code.group(1));
        }
        code.appendTail(guarded);

        String result = BOLD.matcher(guarded.toString())
                .replaceAll(m -> Matcher.quoteReplacement(Ansi.bold(m.group(1))));

        for (int i = 0; i < codes.size(); i++) {
            result = result.replace(MARKER + String.valueOf(i) + MARKER,
                    Ansi.cyan(codes.get(i)));
        }
        return result;
    }
}
