package dev.wiles.myagent.memory;

import java.util.List;

/**
 * 记忆索引——<b>常驻系统提示词</b>的那一段文字。
 *
 * <h3>它在召回链路里的位置</h3>
 * <pre>
 *   索引（这个类，每轮都发） ──模型觉得某条相关──▶ recall 工具 ──▶ 正文（只在需要时发）
 * </pre>
 * 这就是所谓「索引常驻 + 工具读正文」。换个说法：
 * <b>每轮都付的是摘要的钱，细节的钱只在用得上时才付</b>。
 *
 * <h3>为什么单独成类，而不是在 MemoryStore 里拼字符串</h3>
 * 这段文字是提示词，提示词的每个字都影响模型行为，所以它得能被单独断言——
 * 测「索引里确实有这一行」不该需要先造一个存储、再发一次模型调用。
 * 和 {@code PlanContext} 分担的是同一种职责：纯数据进来，排好版的文字出去。
 */
public record MemoryIndex(List<MemoryEntry> entries) {

    public MemoryIndex {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    /** 什么都没记过。第一次用这个 agent 的人就是这个状态。 */
    public static MemoryIndex empty() {
        return new MemoryIndex(List.of());
    }

    public static MemoryIndex of(List<MemoryEntry> entries) {
        return new MemoryIndex(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /**
     * 排版成塞进系统提示词的那段文字。空索引返回空串——
     * <b>没记过东西时，一个字都不该多说</b>：写一句「你还没有任何记忆」
     * 纯属浪费 token，还可能勾着模型去为了记而记。
     */
    public String render() {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n## 你记住的事\n\n");
        for (MemoryEntry e : entries) {
            sb.append(e.indexLine()).append('\n');
        }
        // 这句不能省。只给摘要行的话，模型会拿那一行当完整事实直接用——
        // 摘要是为了让它判断「相不相关」，不是为了让它照着做。
        sb.append("""

                上面每行只是一句摘要，不是完整内容。判断某条和当前的事有关时，
                先用 recall 工具读出正文再下结论，不要照着摘要行动。
                """);
        return sb.toString();
    }
}
