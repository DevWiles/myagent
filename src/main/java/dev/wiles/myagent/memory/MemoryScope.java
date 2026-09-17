package dev.wiles.myagent.memory;

/**
 * 一条记忆管多宽的范围。
 *
 * <p>分这两档是因为 coding agent 要记的事天然分成两类，混在一起会互相污染：
 * 「这个项目的测试要加 {@code -o}」换个仓库就是错的；
 * 「用户要求始终用中文回复」在哪个仓库都成立。
 * 只留一档的话，要么项目细节泄漏到别的项目（误导），
 * 要么通用偏好在每个项目里都要重学一遍（白费）。
 */
public enum MemoryScope {

    /** 绑定某个工作区，只在那个目录下可见。默认选它——记错范围的代价是拿 A 项目的结论去改 B 项目。 */
    PROJECT("project"),

    /** 跨项目可见。用户的习惯、偏好、身份这类事实才配得上。 */
    GLOBAL("global");

    private final String wire;

    MemoryScope(String wire) {
        this.wire = wire;
    }

    /** 落在 frontmatter 里的写法，小写，人可直接手改。 */
    public String wire() {
        return wire;
    }

    /** 认不出来就当 PROJECT——范围小的那个更安全。 */
    public static MemoryScope from(String raw) {
        if (raw != null && GLOBAL.wire.equalsIgnoreCase(raw.trim())) {
            return GLOBAL;
        }
        return PROJECT;
    }
}
