package dev.wiles.myagent.tool.search;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;

/**
 * 文件名模式 → 路径判定。{@code glob} 工具和 {@code grep} 的 {@code glob} 参数共用。
 *
 * <h3>一条和标准 glob 不一样的规矩，是故意的</h3>
 * <b>模式里不含 {@code /} 时，匹配的是文件名，不是相对路径。</b>
 * 也就是说 {@code *.java} 能找到 {@code src/main/java/dev/wiles/Main.java}，
 * 而按标准 glob 语义它<b>只能</b>匹配根目录下的 java 文件，得写成 {@code **}{@code /*.java}。
 *
 * <p>为什么要偏离标准：因为模型几乎总是写 {@code *.java}，心里想的是「任何地方的 java 文件」。
 * 按标准语义办，它得到的是<b>空结果</b>——而空结果和「真的没有」长得一模一样，
 * 模型分辨不出自己是模式写错了还是文件不存在，于是要么放弃，要么换个写法再猜一次。
 * 一个「技术上正确」的行为，如果它最常见的触发方式是静默地给错答案，那它就是错的。
 *
 * <p>带了 {@code /} 就说明用户/模型在表达路径结构（{@code src/**}{@code /*.ts}、
 * {@code docs/*.md}），这时按标准语义锚定到相对路径，符合预期。
 *
 * <h3>第二条偏离：{@code **}{@code /} 表示「零层或多层目录」</h3>
 * 标准 glob 里它是<b>至少一层</b>，于是 {@code **}{@code /src/test/**}{@code /*.java}
 * 匹配不到 {@code src/test/java/X.java}——因为 {@code src} 前面没有目录可给它匹配。
 * 同理 {@code src/**}{@code /*.ts} 匹配不到 {@code src/main.ts}。
 *
 * <p>这两个模式都是模型爱写的，而它们的失败方式是<b>静默少给结果</b>：
 * 少了几个文件，没有任何提示，模型据此以为那些文件不存在。
 * 真实跑一轮就撞上了——问「最近改动最多的测试文件」，模型写了
 * {@code **}{@code /src/test/**}{@code /*.java}，只捞回一个深层目录下的文件，
 * 顶层 {@code src/test/} 下那五十多个全漏了。
 *
 * <p>做法是把每个 {@code **}{@code /} 展开成「有」和「没有」两种，全部试一遍，
 * 命中任意一种就算匹配。顺带让这里和 {@link GitIgnore} 的 {@code **} 语义对齐了——
 * 同一个记号在同一个包里表示两种意思，是迟早要出事的那种不一致。
 *
 * <h3>为什么用 JDK 自带的 PathMatcher</h3>
 * {@code glob:} 语法 JDK 里就有，而且 {@code **}、{@code ?}、{@code [a-z]}、
 * {@code {ts,tsx}} 大括号展开全都支持。这块没有自己写一遍的价值——
 * 它不是这个项目想搞清楚的东西，且自己写必然在大括号嵌套之类的地方出错。
 */
public final class PathGlob {

    /** 一个模式里最多展开几个 {@code **}{@code /}。组合数是 2 的幂，得封顶。 */
    static final int MAX_DOUBLE_STARS = 4;

    private static final String DOUBLE_STAR_SLASH = "**/";

    /** 连着的多个 {@code **}{@code /}，合并成一个。 */
    private static final java.util.regex.Pattern REPEATED_DOUBLE_STAR =
            java.util.regex.Pattern.compile("(?:\\*\\*/){2,}");

    private PathGlob() {
    }

    /** 什么都放行。{@code grep} 不传 glob 时用它。 */
    public static Predicate<String> always() {
        return path -> true;
    }

    /**
     * 编译一个模式。
     *
     * @param pattern 形如 {@code *.java}、{@code src/**}{@code /*.ts}、{@code **}{@code /*.{c,h}}
     * @throws IllegalArgumentException 模式语法不对。调用方转成给模型的错误，让它改了重来
     */
    public static Predicate<String> of(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return always();
        }
        String glob = pattern.strip();
        boolean nameOnly = !glob.contains("/");
        List<PathMatcher> matchers = new ArrayList<>();
        try {
            for (String variant : expandDoubleStars(glob)) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + variant));
            }
        } catch (PatternSyntaxException | UnsupportedOperationException e) {
            throw new IllegalArgumentException(
                    "文件名模式 \"" + pattern + "\" 语法不对: " + e.getMessage()
                            + "（例：*.java、src/**/*.ts、**/*.{md,txt}）");
        }
        return relative -> {
            Path candidate = Path.of(nameOnly ? fileName(relative) : relative);
            for (PathMatcher matcher : matchers) {
                if (matcher.matches(candidate)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * 每个 {@code **}{@code /} 都展开成「留着」和「去掉」两种，返回所有组合。
     *
     * <p>{@code src/**}{@code /*.ts} → {@code [src/**}{@code /*.ts, src/*.ts]}，
     * 于是深层和平层都能匹配上。
     *
     * <p>最多处理 {@link #MAX_DOUBLE_STARS} 个——组合数是 2 的幂，
     * 不封顶的话一个写得离奇的模式就能生成几千个 matcher。超出的原样留着，
     * 那种模式本来也没人手写。
     */
    static List<String> expandDoubleStars(String pattern) {
        // 连着写的 **/**/ 和单个 **/ 是一个意思（都是「任意多层目录」），
        // 先合并掉。不合并的话它们会各自展开成一个二选一，组合数白白翻倍，
        // 而且撞上 MAX_DOUBLE_STARS 之后剩下的原样留着，就变成了
        // 「至少 N 层目录」——一个谁也没要求过、又不会报错的约束
        String glob = REPEATED_DOUBLE_STAR.matcher(pattern).replaceAll(DOUBLE_STAR_SLASH);

        // 记下每个 **/ 的位置。用位置而不是字符串替换——String.replace 换的是
        // **所有**匹配，于是 **/src/**/*.java 会被一次砍掉两个，
        // 生成一个谁也没要求过的模式
        List<Integer> positions = new ArrayList<>();
        for (int at = glob.indexOf(DOUBLE_STAR_SLASH);
             at >= 0 && positions.size() < MAX_DOUBLE_STARS;
             at = glob.indexOf(DOUBLE_STAR_SLASH, at + DOUBLE_STAR_SLASH.length())) {
            positions.add(at);
        }
        if (positions.isEmpty()) {
            return List.of(glob);
        }

        List<String> variants = new ArrayList<>();
        for (int mask = 0; mask < (1 << positions.size()); mask++) {
            StringBuilder variant = new StringBuilder();
            int cursor = 0;
            for (int i = 0; i < positions.size(); i++) {
                variant.append(glob, cursor, positions.get(i));
                if ((mask & (1 << i)) == 0) {          // 这一位为 0 表示留着
                    variant.append(DOUBLE_STAR_SLASH);
                }
                cursor = positions.get(i) + DOUBLE_STAR_SLASH.length();
            }
            variant.append(glob, cursor, glob.length());

            String text = variant.toString();
            // 模式 "**/" 整个被删光会得到空串，那不是任何人要的东西
            if (!text.isEmpty() && !variants.contains(text)) {
                variants.add(text);
            }
        }
        return variants;
    }

    private static String fileName(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }
}
