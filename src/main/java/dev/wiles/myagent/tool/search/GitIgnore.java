package dev.wiles.myagent.tool.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 一个 {@code .gitignore} 文件的规则。
 *
 * <h3>为什么要自己解析，而不是只用一张硬黑名单</h3>
 * 硬黑名单（{@code target/}、{@code node_modules/} 那些）挡得住通用的产物目录，
 * 挡不住<b>这个项目自己</b>的规矩：{@code *.log}、{@code dist/}、{@code .env}、
 * 生成的 {@code *_pb2.py}。这些东西数量常常压倒源码，搜出来一屏全是它们，
 * 真正的命中被挤到看不见的地方——而模型只会读它看见的那几条。
 *
 * <p>反过来也不能只用 .gitignore：仓库里<b>没有</b> .gitignore 的时候（或者
 * {@code .git} 目录本身，它从来不写进 .gitignore）就一点保护都没有了。
 * 所以两道并存，硬黑名单在 {@link SourceFilter}，永远生效。
 *
 * <h3>支持哪些写法</h3>
 * <ul>
 *   <li>空行、{@code #} 开头的注释——跳过</li>
 *   <li>{@code *} {@code ?} {@code **} 通配，{@code [abc]} 字符组</li>
 *   <li>结尾的 {@code /}——只匹配目录（{@code build/} 不会误伤名叫 build 的文件）</li>
 *   <li>开头的 {@code /} 或中间带 {@code /}——锚定在本文件所在目录，
 *       不再匹配任意层级（{@code /target} 只挡根下那个）</li>
 *   <li>{@code !} 取反。<b>后面的规则覆盖前面的</b>，git 就是这个语义</li>
 *   <li>{@code \} 转义，用来写出字面的 {@code #}、{@code !}、空格</li>
 * </ul>
 *
 * <h3>不支持哪些（故意的）</h3>
 * <ul>
 *   <li>{@code .git/info/exclude} 和全局的 {@code core.excludesFile}——
 *       它们在仓库之外，而这里的规则是「能在工作区里读到的才算数」</li>
 *   <li>被 git <b>跟踪</b>的文件即使命中 .gitignore 也仍然可见（git 的真实行为）。
 *       要复现这条得读 index，那是解析一个二进制格式，代价远超收益</li>
 * </ul>
 * 两条都会让我们比 git <b>多忽略</b>一点点东西。方向是对的——搜索少给几个
 * 边缘文件，比多给一屏噪音强；而且真要找，{@code read_file} 按路径直取从不受此限制。
 *
 * <h3>不抛异常</h3>
 * 读不出来、某条规则写得太离奇编译不成正则，就当那条不存在。
 * 为了一行忽略规则让整次搜索失败，本末倒置。
 */
public final class GitIgnore {

    private static final Logger log = LoggerFactory.getLogger(GitIgnore.class);

    /** 什么都不忽略。没有 .gitignore 的目录用它，省掉调用方到处判空。 */
    public static final GitIgnore EMPTY = new GitIgnore(List.of());

    /** 单个文件的规则条数上限。生成出来的巨型 .gitignore 见过，不设上限会拖垮遍历。 */
    static final int MAX_RULES = 1000;

    private final List<Rule> rules;

    private GitIgnore(List<Rule> rules) {
        this.rules = rules;
    }

    /** 读 {@code dir/.gitignore}。没有、读不动、或者是个目录，都返回 {@link #EMPTY}。 */
    public static GitIgnore of(Path dir) {
        Path file = dir.resolve(".gitignore");
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.debug("读不出 {}: {}", file, e.toString());
            return EMPTY;
        }
    }

    /** 从若干行规则里解析。公开给测试用——不必为了测一条规则先造一个文件。 */
    public static GitIgnore parse(List<String> lines) {
        List<Rule> parsed = new ArrayList<>();
        for (String line : lines) {
            if (parsed.size() >= MAX_RULES) {
                log.debug(".gitignore 规则超过 {} 条，后面的忽略", MAX_RULES);
                break;
            }
            Rule rule = Rule.of(line);
            if (rule != null) {
                parsed.add(rule);
            }
        }
        return parsed.isEmpty() ? EMPTY : new GitIgnore(List.copyOf(parsed));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * 这条路径要不要忽略。
     *
     * <p><b>最后一条命中的规则说了算</b>，不是第一条——这是 git 的语义，
     * 也是 {@code !} 取反唯一能起作用的前提：
     * <pre>
     *   *.log        ← 先全挡掉
     *   !important.log  ← 再把这个放回来
     * </pre>
     * 反过来（第一条命中就返回）的话，取反规则永远轮不上，
     * 而症状是「我明明写了 ! 却还是搜不到」。
     *
     * @param relativePath 相对于本 .gitignore 所在目录的路径，分隔符统一用 {@code /}
     * @param directory    这个路径是不是目录。{@code build/} 这类规则只对目录生效
     */
    public boolean ignores(String relativePath, boolean directory) {
        return Boolean.TRUE.equals(match(relativePath, directory));
    }

    /**
     * 同 {@link #ignores}，但区分得开「命中了一条放行规则」和「一条都没命中」。
     *
     * <p>嵌套 .gitignore 要靠这个区别：深一层的文件想推翻浅一层的规则，
     * 前提是能说出「我这儿有话说」。只回 boolean 的话，深层的「没意见」
     * 和「明确放行」长得一模一样，于是父目录的规则<b>永远推翻不了</b>——
     * 症状是子目录里写的 {@code !} 完全不起作用。
     *
     * @return {@code TRUE} 忽略、{@code FALSE} 明确放行、{@code null} 没有规则命中
     */
    public Boolean match(String relativePath, boolean directory) {
        Boolean verdict = null;
        for (Rule rule : rules) {
            if (rule.matches(relativePath, directory)) {
                verdict = !rule.negated;
            }
        }
        return verdict;
    }

    /** 一条规则。{@code pattern} 已经编译成正则，匹配的是整条相对路径。 */
    private record Rule(Pattern pattern, boolean negated, boolean dirOnly) {

        boolean matches(String path, boolean directory) {
            if (dirOnly && !directory) {
                return false;
            }
            return pattern.matcher(path).matches();
        }

        /** @return 解析好的规则；空行、注释、以及编译不了的，返回 null */
        static Rule of(String raw) {
            // 行尾空白不算数，除非用 \ 转义过。行首空白 git 是算的，所以不 strip 左边
            String line = stripTrailingSpaces(raw);
            if (line.isEmpty() || line.startsWith("#")) {
                return null;
            }

            boolean negated = line.startsWith("!");
            if (negated) {
                line = line.substring(1);
            } else if (line.startsWith("\\")) {
                // \# 和 \! 用来写出字面量，去掉转义符本身
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                return null;
            }

            boolean dirOnly = line.endsWith("/");
            if (dirOnly) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                return null;
            }

            // 带斜杠的锚定在本目录；不带的匹配任意层级上的同名条目。
            // 注意判断要在去掉尾斜杠**之后**做，否则 "build/" 会被当成带斜杠的锚定规则，
            // 于是只挡根下的 build，挡不住 src/build——而写下这行的人想挡的是全部
            boolean anchored = line.contains("/");
            if (line.startsWith("/")) {
                line = line.substring(1);
            }

            try {
                String regex = (anchored ? "" : "(?:.*/)?") + toRegex(line);
                return new Rule(Pattern.compile(regex), negated, dirOnly);
            } catch (PatternSyntaxException e) {
                log.debug("忽略规则 \"{}\" 编译不成正则: {}", raw, e.getMessage());
                return null;
            }
        }

        private static String stripTrailingSpaces(String line) {
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') {
                // 反斜杠转义过的空格要留着
                if (end >= 2 && line.charAt(end - 2) == '\\') {
                    break;
                }
                end--;
            }
            return line.substring(0, end);
        }

        /**
         * glob → 正则。
         *
         * <p>关键是 {@code *} 和 {@code **} 的区别：<b>单星不跨目录</b>
         * （{@code [^/]*}），双星跨（{@code .*}）。把两者混为一谈的话，
         * {@code src/*.java} 会连 {@code src/a/b/C.java} 一起挡掉。
         */
        private static String toRegex(String glob) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < glob.length()) {
                char c = glob.charAt(i);
                switch (c) {
                    case '*' -> {
                        boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                        if (doubleStar) {
                            // "a/**/b" 里的 ** 要能匹配零层目录，所以把跟在后面的
                            // 斜杠一起吃掉，换成「零个或多个目录段」
                            if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                                out.append("(?:.*/)?");
                                i += 3;
                            } else {
                                out.append(".*");
                                i += 2;
                            }
                        } else {
                            out.append("[^/]*");
                            i++;
                        }
                    }
                    case '?' -> {
                        out.append("[^/]");
                        i++;
                    }
                    case '[' -> {
                        int close = glob.indexOf(']', i + 1);
                        if (close < 0) {
                            out.append("\\[");     // 没闭合就是个字面的方括号
                            i++;
                        } else {
                            String body = glob.substring(i + 1, close);
                            // glob 用 ! 表示取反，正则用 ^
                            out.append('[').append(body.startsWith("!") ? "^" + body.substring(1) : body).append(']');
                            i = close + 1;
                        }
                    }
                    case '\\' -> {
                        if (i + 1 < glob.length()) {
                            out.append(Pattern.quote(String.valueOf(glob.charAt(i + 1))));
                            i += 2;
                        } else {
                            i++;
                        }
                    }
                    default -> {
                        out.append(Pattern.quote(String.valueOf(c)));
                        i++;
                    }
                }
            }
            return out.toString();
        }
    }
}
