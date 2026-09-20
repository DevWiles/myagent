package dev.wiles.myagent.tool.safety;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从「这一次的动作」推导出「以后都同意什么」的几种写法，从紧到松。
 *
 * <h3>为什么规则只能从这里长出来，不给用户手写的入口</h3>
 * 手写规则最容易写出的就是 {@code bash: *}——它合法、省事、并且悄悄关掉整个系统。
 * 而从一次真实发生过的动作推导出来的规则，天然有个下界：
 * <b>它至少覆盖了一件用户刚刚亲眼看过并同意的事</b>。
 *
 * <p>这也是这个类属于第 ① 类决策的原因：给定一条命令，「它的最小合理规则是什么」
 * 有唯一答案、可以算，所以算出来给用户看，而不是让用户在空白框里编。
 * 人只做那个算不出来的决定——松紧到哪一档。
 *
 * <h3>命令是逐段推导的</h3>
 * {@code mvn test && git status} 会推出<b>两条</b>规则。少推一条的话，
 * 用户明明按了「以后都同意」，下次还是被问——而重复被问正是这套系统唯一的失败模式。
 * 分开记也是安全的：{@code mvn test && rm -rf ~} 照样要问，
 * 因为 {@code rm -rf ~} 那一段没有任何规则罩着（见 {@link ApprovalRules#check}）。
 */
public final class RuleSuggester {

    /** 最多给几档。选项多到要翻页，用户就不看了，直接按默认。 */
    static final int MAX_LEVELS = 6;

    /**
     * 一档松紧。
     *
     * @param label    显示给用户的一行。多段命令时是几条规则拼起来
     * @param patterns 真要存下去的模式，一段命令一条
     */
    public record Level(String label, List<String> patterns) {
        public Level {
            patterns = List.copyOf(patterns);
        }
    }

    /**
     * @param levels    从紧到松。空表示这次不能记成规则，只能批这一次
     * @param preferred 默认选中第几档
     */
    public record Suggestions(List<Level> levels, int preferred) {
        public Suggestions {
            levels = List.copyOf(levels);
            preferred = levels.isEmpty() ? 0 : Math.max(0, Math.min(levels.size() - 1, preferred));
        }

        public static Suggestions none() {
            return new Suggestions(List.of(), 0);
        }

        public boolean isEmpty() {
            return levels.isEmpty();
        }
    }

    private RuleSuggester() {
    }

    public static Suggestions forRequest(ApprovalRequest request) {
        if (request == null || request.subject().isBlank()) {
            return Suggestions.none();
        }
        return switch (request.kind()) {
            case BASH -> forCommand(request.subject());
            case WRITE -> forPath(request.subject());
        };
    }

    // ---------------------------------------------------------------- 命令

    private static Suggestions forCommand(String command) {
        List<List<String>> segments = CommandGuard.splitSegments(command).stream()
                .map(String::strip)
                .filter(segment -> !segment.isEmpty())
                .map(RuleSuggester::tokens)
                .filter(tokens -> !tokens.isEmpty())
                .toList();
        if (segments.isEmpty()) {
            return Suggestions.none();
        }

        // 「到子命令为止」这一档只在每段都真有子命令时才给。curl https://x.com 的
        // 第二个 token 是个 URL，按子命令处理会推出 `curl https://x.com *`——
        // 比精确匹配松，又没松到有用，白占一个选项位，还可能被当成默认选走
        boolean hasSubcommand = segments.stream().allMatch(RuleSuggester::subcommandUsable);

        // 会毁东西的命令只给「一字不差」这一档。结尾那个 * 的意思是
        // 「后面还可以跟参数」，对 mvn test -X 是放宽，对 rm -rf target src
        // 就是<b>又授权了一个删除目标</b>——而用户点头时看到的只有 target。
        // 实测过：默认推荐的 `rm -rf target *` 会静默放行 `rm -rf target src`
        boolean destructive = segments.stream().anyMatch(RuleSuggester::destroysItsArguments);

        List<Level> levels = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // 三档：一字不差 / 到子命令为止 / 只认命令名
        for (int tightness = 0; tightness < 3; tightness++) {
            if (tightness > 0 && destructive) {
                break;
            }
            if (tightness == 1 && !hasSubcommand) {
                continue;
            }
            List<String> patterns = new ArrayList<>();
            for (List<String> tokens : segments) {
                patterns.add(commandPattern(tokens, tightness));
            }
            String label = String.join("  ＋  ", patterns);
            if (seen.add(label)) {
                levels.add(new Level(label, patterns));
            }
        }
        // 默认选「到子命令为止」。没有这一档时退回最紧的那一档，
        // 而不是顺延到「只认命令名」——默认值宁可紧过头
        int preferred = hasSubcommand && !destructive ? Math.min(1, levels.size() - 1) : 0;
        return new Suggestions(levels, preferred);
    }

    /**
     * @param tightness 0 一字不差，1 到子命令为止，2 只认命令名
     */
    private static String commandPattern(List<String> tokens, int tightness) {
        if (tightness == 0) {
            return String.join(" ", tokens);
        }
        int name = indexOfWord(tokens, 0);
        if (name < 0) {
            return String.join(" ", tokens);      // 整段都是选项或赋值，没有命令名可认
        }
        int keep = tightness == 2 ? name : indexOfWord(tokens, name + 1);
        if (keep < 0) {
            keep = name;                          // 没有子命令，退回命令名那一档
        }
        List<String> head = new ArrayList<>(tokens.subList(0, keep + 1));
        head.add("*");
        return String.join(" ", head);
    }

    private static boolean destroysItsArguments(List<String> tokens) {
        int name = indexOfWord(tokens, 0);
        return name >= 0 && CommandGuard.destroysItsArguments(tokens.get(name));
    }

    /** 这一段有没有一个像样的子命令可用来划档。 */
    private static boolean subcommandUsable(List<String> tokens) {
        int name = indexOfWord(tokens, 0);
        if (name < 0) {
            return false;
        }
        int sub = indexOfWord(tokens, name + 1);
        return sub >= 0 && !pathLike(tokens.get(sub));
    }

    /** 看着像路径或 URL 的 token，不是子命令。 */
    private static boolean pathLike(String token) {
        return token.contains("/") || token.contains(":");
    }

    /** 从 from 开始，第一个既不是选项也不是 {@code K=V} 前缀赋值的 token。 */
    private static int indexOfWord(List<String> tokens, int from) {
        for (int i = Math.max(0, from); i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("-")) {
                continue;
            }
            if (token.contains("=") && !token.contains("/")) {
                continue;                         // FOO=1 前缀赋值；带 / 的更像路径
            }
            return i;
        }
        return -1;
    }

    private static List<String> tokens(String segment) {
        return List.of(segment.strip().split("\\s+"));
    }

    // ---------------------------------------------------------------- 路径

    private static Suggestions forPath(String rawPath) {
        String path = rawPath.replace('\\', '/').strip();
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isBlank()) {
            return Suggestions.none();
        }

        List<String> patterns = new ArrayList<>();
        patterns.add(path);                                   // 一字不差

        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            String dir = path.substring(0, slash);
            patterns.add(dir + "/*");                         // 同一层
            // 往上逐级放宽。**只取靠外的几级**：真要放宽的人想的是 src/**，
            // 不是 src/main/java/dev/wiles/myagent/tool/**——那一档和「同一层」
            // 差不多紧，占着选项位却没人会选
            List<String> ancestors = new ArrayList<>();
            for (String at = dir; at != null && !at.isBlank(); at = parentOf(at)) {
                ancestors.add(at + "/**");
            }
            int room = MAX_LEVELS - patterns.size();
            int from = Math.max(0, ancestors.size() - room);
            patterns.addAll(ancestors.subList(from, ancestors.size()));
        }

        List<Level> levels = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String pattern : patterns) {
            // 兜底：任何一档都不能是「放行一切」，哪怕推导出来是
            if (new ApprovalRule(ApprovalKind.WRITE, pattern, "").matchesEverything()) {
                continue;
            }
            if (seen.add(pattern)) {
                levels.add(new Level(pattern, List.of(pattern)));
            }
        }
        return new Suggestions(levels, Math.min(1, Math.max(0, levels.size() - 1)));
    }

    private static String parentOf(String dir) {
        int slash = dir.lastIndexOf('/');
        return slash <= 0 ? null : dir.substring(0, slash);
    }
}
