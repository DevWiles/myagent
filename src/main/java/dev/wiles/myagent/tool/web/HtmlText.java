package dev.wiles.myagent.tool.web;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML → 给模型看的正文。纯函数，不碰网络。
 *
 * <p>为什么自己写而不是引 jsoup：本项目的约定是依赖只留 Jackson/OkHttp/slf4j，
 * 每个轮子都自己造一遍才知道它为什么长那样。而「转正文」这个轮子出乎意料地小——
 * 模型要的不是还原排版，是<b>把字读出来</b>，所以整件事就三步：
 * 剥掉不是内容的部分（script/style/注释）、把块级标签换成换行、解转义。
 * 丢掉的东西（表格对齐、嵌套列表、代码块缩进）模型多半也能从上下文猜回来。
 *
 * <p>已知的粗糙处：{@code <pre>} 里的换行会被空白折叠压掉。真遇到再修。
 */
final class HtmlText {

    /** 整块剥掉的元素：内容对「读字」毫无贡献，还特别大。 */
    private static final Pattern DROP_BLOCKS = Pattern.compile(
            "<(script|style|noscript|svg|head|template)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENTS = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** 换成换行的标签：它们在页面上本来就意味着「另起一行」。 */
    private static final Pattern BLOCK_TAGS = Pattern.compile(
            "</?(p|div|br|tr|h[1-6]|section|article|header|footer|ul|ol|table|blockquote|pre|hr)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);

    /** 列表项换成「- 」，不然整个列表会糊成一行看不出条目边界。 */
    private static final Pattern LIST_ITEM = Pattern.compile("<li\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** 表格单元格之间至少留个空格，不然相邻两格的字会粘在一起。 */
    private static final Pattern CELL_TAGS = Pattern.compile("</?(td|th)\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** 剩下的一律当行内标签抹掉（b、span、a……），两侧的字本来就该连着。 */
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>(.*?)</title\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** 网页里最常见的一批命名实体。长尾的走数字实体分支，剩下的原样留着也能看懂。 */
    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("mdash", "—"), Map.entry("ndash", "–"), Map.entry("hellip", "…"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"),
            Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"),
            Map.entry("middot", "·"), Map.entry("copy", "©"), Map.entry("times", "×"));

    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

    private HtmlText() {
    }

    /** 页面标题，没有就是空串。 */
    static String title(String html) {
        Matcher m = TITLE.matcher(html);
        return m.find() ? flatten(m.group(1)) : "";
    }

    /** 整页 HTML → 正文。 */
    static String text(String html) {
        String s = COMMENTS.matcher(html).replaceAll("");
        s = DROP_BLOCKS.matcher(s).replaceAll("");
        s = LIST_ITEM.matcher(s).replaceAll("\n- ");
        s = BLOCK_TAGS.matcher(s).replaceAll("\n");
        s = CELL_TAGS.matcher(s).replaceAll(" ");
        s = ANY_TAG.matcher(s).replaceAll("");
        // 解转义放在剥标签**之后**：这时 &lt;script&gt; 变出来的只是几个字符，
        // 不是标签——反过来先解再剥，就等于让页面内容有机会伪造出「要被剥掉的结构」
        s = decodeEntities(s);
        return collapse(s);
    }

    /** HTML 片段 → 一行字。给搜索结果的标题、摘要用。 */
    static String flatten(String fragment) {
        String s = ANY_TAG.matcher(fragment).replaceAll("");
        s = decodeEntities(s);
        return s.replaceAll("\\s+", " ").trim();
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            String name = m.group(1);
            String replacement;
            if (name.startsWith("#")) {
                replacement = decodeNumeric(name);
            } else {
                replacement = ENTITIES.get(name);
            }
            // 认不出的实体原样保留：&foobar; 大概率本来就是正文
            m.appendReplacement(out, Matcher.quoteReplacement(
                    replacement != null ? replacement : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String decodeNumeric(String name) {
        try {
            int code = name.startsWith("#x") || name.startsWith("#X")
                    ? Integer.parseInt(name.substring(2), 16)
                    : Integer.parseInt(name.substring(1));
            return Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 收空白：行内多个空格并一个，行首行尾修掉，三个以上连续换行并成两个。
     * 剥完标签的页面全是这种「排版留下的尸体」，不收的话一半 token 花在空白上。
     */
    private static String collapse(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int blankRun = 0;
        for (String line : s.split("\n", -1)) {
            String trimmed = line.replaceAll("[ \\t\\x0B\\f\\r\\u00A0]+", " ").trim();
            if (trimmed.isEmpty()) {
                blankRun++;
                continue;
            }
            if (out.length() > 0) {
                out.append(blankRun > 0 ? "\n\n" : "\n");
            }
            blankRun = 0;
            out.append(trimmed);
        }
        return out.toString();
    }
}
