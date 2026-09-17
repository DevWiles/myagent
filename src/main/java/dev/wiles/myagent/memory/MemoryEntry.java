package dev.wiles.myagent.memory;

import java.time.LocalDate;

/**
 * 一条长期记忆。一条一个文件，人可以直接拿编辑器改。
 *
 * <h3>为什么 name 和 description 分开，而 description 又和 content 分开</h3>
 * 三个字段对应<b>召回的三个阶段</b>，这是 LongMemEval 那套索引/检索/阅读的拆法：
 * <ul>
 *   <li>{@code name} —— 句柄。模型拿它调 {@code recall}，必须唯一、稳定、好打。</li>
 *   <li>{@code description} —— <b>常驻系统提示词</b>的那一行。每轮对话都要发，
 *       所以它的长度直接乘以对话轮数变成 token 账单。要能让模型判断
 *       「这条跟当前的事有没有关系」，但不必说全。</li>
 *   <li>{@code content} —— 正文。只有模型主动 {@code recall} 才发，
 *       所以可以写长、写细节、写当初是怎么发现的。</li>
 * </ul>
 *
 * <p>这个划分是整个召回策略的地基：常驻的那部分要小，昂贵的细节按需取。
 * 把 description 写成正文的副本，等于退化成全量注入。
 *
 * @param name        句柄，小写连字符风格，同一 scope 内唯一
 * @param description 一句话摘要，常驻系统提示词
 * @param content     正文，按需读取
 * @param scope       管多宽的范围
 * @param source      怎么来的，也就是有多可信
 * @param created     写下的日期
 */
public record MemoryEntry(
        String name,
        String description,
        String content,
        MemoryScope scope,
        MemorySource source,
        LocalDate created) {

    /** description 落进提示词时的长度上限。超了截断——它是要乘以轮数的。 */
    public static final int MAX_DESCRIPTION = 200;

    public MemoryEntry {
        name = normalizeName(name);
        description = trimTo(description, MAX_DESCRIPTION);
        content = content == null ? "" : content.strip();
        scope = scope == null ? MemoryScope.PROJECT : scope;
        source = source == null ? MemorySource.OBSERVATION : source;
        created = created == null ? LocalDate.now() : created;
    }

    /**
     * 名字要能当文件名用，也要能被模型稳定地复述出来。
     *
     * <p>模型给的名字可能带空格、中文、大写、路径分隔符。不归一化的话，
     * 两条实质同名的记忆会变成两个文件，而 {@code recall} 按原样查又<b>两个都查不到</b>——
     * 写进去的时候是「Mvn Offline」，模型下次打的是「mvn-offline」。
     */
    public static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("记忆的 name 不能为空");
        }
        String slug = raw.strip().toLowerCase()
                .replaceAll("[\\s_]+", "-")           // 空白和下划线统一成连字符
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5-]", "")   // 中文留着，其余符号删掉
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("记忆的 name 归一化之后是空的: " + raw);
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    /** 索引里的一行。这是每轮对话都要发出去的那部分，改它等于改 token 账单。 */
    public String indexLine() {
        return "- " + name + " — " + description;
    }

    private static String trimTo(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("记忆的 description 不能为空——索引里全靠它判断相关性");
        }
        String one = raw.strip().replaceAll("\\s+", " ");   // 索引是一行一条，不能带换行
        return one.length() > max ? one.substring(0, max - 1) + "…" : one;
    }
}
