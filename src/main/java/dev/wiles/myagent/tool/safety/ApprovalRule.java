package dev.wiles.myagent.tool.safety;

import dev.wiles.myagent.tool.search.PathGlob;

import java.util.List;
import java.util.function.Predicate;

/**
 * 一条授权规则：「这一类动作以后不用再问我」。
 *
 * <h3>两种 kind，两套匹配语义</h3>
 * <b>{@link ApprovalKind#BASH}</b> 的模式是一串 token，按<b>前缀</b>比：
 * <pre>
 *   mvn -o test *   匹配  mvn -o test、mvn -o test -Dtest=Foo
 *                   不匹配 mvn -o deploy、mvn -o testing
 *   git status      匹配  git status（<b>一字不差</b>，没有结尾的 * 就是精确匹配）
 * </pre>
 * 结尾的独立 {@code *} 是唯一的通配符，意思是「后面还可以跟任意参数」。
 * <b>token 内部不做通配</b>——{@code mvn te*} 这种不支持，
 * 因为它很难一眼看出会放行什么，而一条看不懂的授权规则比没有规则更危险。
 *
 * <p><b>{@link ApprovalKind#WRITE}</b> 的模式是路径 glob，直接交给 {@link PathGlob}：
 * <pre>
 *   src/**            src 下任意深度
 *   src/main/java/*   只有这一层
 * </pre>
 * 复用 {@code PathGlob} 不是图省事——这个仓库里 glob 只能有<b>一套</b>语义。
 * 检索时 {@code src/**} 匹配到的文件集合，和授权时它放行的文件集合，
 * 必须是同一个集合；两套实现迟早会在某个边角上分叉，而分叉处就是意外授权。
 *
 * <h3>为什么 BASH 的匹配对象是「一段」而不是整条命令</h3>
 * 见 {@link ApprovalRules#allows}。这个类只管比一个单位，
 * 拆段是上一层的事——但那一层必须拆，否则这里的前缀匹配就是个后门。
 *
 * @param kind  动作类型
 * @param match 模式
 * @param added 加进来的日期（{@code 2026-09-20}）。只为了让用户翻文件时知道这是什么时候批的
 */
public record ApprovalRule(ApprovalKind kind, String match, String added) {

    /** 一条规则最长多少字符。防止手写文件里塞进一段离谱的东西。 */
    static final int MAX_MATCH_LENGTH = 400;

    public ApprovalRule {
        if (kind == null) {
            throw new IllegalArgumentException("规则缺少 kind");
        }
        match = match == null ? "" : match.strip().replaceAll("\\s+", " ");
        if (match.isEmpty()) {
            throw new IllegalArgumentException("规则的 match 不能为空");
        }
        if (match.length() > MAX_MATCH_LENGTH) {
            throw new IllegalArgumentException("规则太长（上限 " + MAX_MATCH_LENGTH + " 字符）: " + match);
        }
        added = added == null ? "" : added.strip();
    }

    public static ApprovalRule of(ApprovalKind kind, String match, String added) {
        return new ApprovalRule(kind, match, added);
    }

    /**
     * 这条规则是不是「什么都放行」。
     *
     * <p>{@code bash: *} 或 {@code write: **} 在语法上完全合法，效果却是
     * <b>把整个审批系统关掉</b>，而且是悄悄关掉——用户下次看到确认框不再弹出，
     * 会以为是自己批过了。所以这种规则在装载时会被丢掉（见 {@link ApprovalRules#of}）。
     *
     * <p>注意只对 allow 方向成立。deny 里写 {@code *} 是「一律不放行」，
     * 方向是安全的，随它去。
     */
    public boolean matchesEverything() {
        return switch (kind) {
            case BASH -> "*".equals(match);
            case WRITE -> "*".equals(match) || "**".equals(match) || "**/*".equals(match);
            case BROWSE -> "*".equals(match) || "*.*".equals(match);
        };
    }

    /**
     * 这条规则管不管得着这个 subject。
     *
     * @param subject BASH 时是<b>一段</b>命令（不是整条！），WRITE 时是工作区相对路径
     */
    public boolean covers(ApprovalKind subjectKind, String subject) {
        if (subjectKind != kind || subject == null || subject.isBlank()) {
            return false;
        }
        return switch (kind) {
            case BASH -> coversSegment(subject);
            case WRITE -> pathMatcher().test(subject.replace('\\', '/'));
            case BROWSE -> coversHost(subject);
        };
    }

    /**
     * 主机名匹配：要么一模一样，要么 {@code *.example.com} 这种前缀通配。
     *
     * <p><b>{@code *.example.com} 连 {@code example.com} 本身一起罩住。</b>
     * 用户按下「以后都同意 *.example.com」时想的是「这个站我信得过」，
     * 而不是「除了主域名之外的那些子域我信得过」——按字面理解会让他
     * 在同一个站上被问第二次，然后他会去按更松的那一档。
     *
     * <p>大小写不敏感（主机名本来就是），但<b>不做任何后缀推断</b>：
     * {@code *.com} 就是字面上的「com 下面全部」，我们不去猜公共后缀，
     * 猜错的方向永远是放得太宽。
     */
    private boolean coversHost(String host) {
        String want = match.toLowerCase(java.util.Locale.ROOT);
        String got = host.strip().toLowerCase(java.util.Locale.ROOT);
        if (want.equals(got)) {
            return true;
        }
        if (!want.startsWith("*.")) {
            return false;
        }
        String domain = want.substring(2);
        return !domain.isEmpty() && (got.equals(domain) || got.endsWith("." + domain));
    }

    private boolean coversSegment(String segment) {
        List<String> want = tokens(match);
        List<String> got = tokens(segment);
        boolean openEnded = !want.isEmpty() && "*".equals(want.get(want.size() - 1));
        if (openEnded) {
            want = want.subList(0, want.size() - 1);
            if (want.isEmpty()) {
                return false;                  // 光一个 *，交给 matchesEverything 处理
            }
            if (got.size() < want.size()) {
                return false;
            }
        } else if (got.size() != want.size()) {
            return false;
        }
        for (int i = 0; i < want.size(); i++) {
            if (!want.get(i).equals(got.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 路径模式编译失败时一律不匹配。
     *
     * <p>策略文件是手可改的，写坏一条 glob 很正常。让它抛异常的话，
     * 一条坏规则会在<b>每次审批时</b>炸一次；返回「不匹配」则只是这条规则失效，
     * 用户照常被问一句——坏掉的授权应当退化成「多问一次」，不是退化成崩溃。
     */
    private Predicate<String> pathMatcher() {
        try {
            return PathGlob.of(match);
        } catch (IllegalArgumentException e) {
            return path -> false;
        }
    }

    private static List<String> tokens(String text) {
        return List.of(text.strip().split("\\s+"));
    }

    /** 落在界面上的一行，如 {@code bash  mvn -o test *}。 */
    public String display() {
        return kind.id() + "  " + match;
    }
}
