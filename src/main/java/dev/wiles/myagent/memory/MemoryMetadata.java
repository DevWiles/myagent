package dev.wiles.myagent.memory;

/**
 * 记忆条目上那些<b>不值得各自占一个字段</b>的溯源信息，统一走 frontmatter 的自由键。
 *
 * <h3>为什么用 map 而不是继续加 record 组件</h3>
 * {@code name / description / content} 三个字段各自对应召回的一个阶段，
 * {@code scope / source / created} 各自影响可见性、可信度、时效——它们都在被代码读。
 * 而下面这几个键是<b>溯源和生命周期</b>用的：谁写的、取代了谁、哪一轮写的。
 * 它们会随功能增加（将来还要加衰减期、命中次数），每加一个就改一次 record
 * 意味着每次都要动所有构造点和所有测试。
 *
 * <p>更要紧的是：一条记忆<b>是一个用户可以直接拿编辑器改的 Markdown 文件</b>。
 * 用户在 frontmatter 里写一个我们不认识的键，正确的行为是原样留着，
 * 而不是解析时丢掉——下次保存就把人家的东西抹了。map 天然做到这点。
 *
 * <p>常量集中在这里而不是散成字符串字面量：拼错一个键不会报错，
 * 只会让 supersede 静默失效，而那是最难查的一类。
 */
public final class MemoryMetadata {

    /**
     * 这条记忆讲的是<b>哪件事</b>——取代机制的依据。
     *
     * <p>「这个项目用什么包管理器」是一个主题，先记成 npm 后改成 bun，
     * 两条同主题，新的把旧的顶掉。没有这个键的条目<b>一律不参与取代</b>，
     * 所以存量数据行为不变。
     */
    public static final String TOPIC_KEY = "topic_key";

    /**
     * 被哪条记忆取代了。非空即表示<b>已失效</b>：不再注入提示词、不再被 recall 命中，
     * 但<b>不删除</b>——用户要能看见系统曾经记过什么。
     */
    public static final String SUPERSEDED_BY = "superseded_by";

    /**
     * 哪一次压缩顺手捡出来的，形如 {@code seg[8,22)}。
     *
     * <p>用户问「你怎么知道这件事的」时，这是唯一能指回原文的线索。
     * 手动 {@code remember} 的条目没有这个键——那时候用户自己就在现场。
     */
    public static final String TURN_ID = "turn_id";

    private MemoryMetadata() {
    }
}
