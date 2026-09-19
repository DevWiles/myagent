package dev.wiles.myagent.prompt;

/**
 * 提示词片段从哪来。
 *
 * <p>抽成接口只为一件事：<b>让装配逻辑能被单独断言</b>。
 * 「缺了关键段要抛」这条不变量，测的时候得造一份故意缺段的提示词，
 * 而真实的 {@link PromptRepository} 读的是打进 jar 里的资源——
 * 不留这个口子，那条最值钱的测试就没法写。
 *
 * <p>和 {@code MemoryIndex} 从 {@code MemoryStore} 里拆出来是同一个理由：
 * 提示词的每个字都影响模型行为，所以它得能在不起模型、不碰磁盘的情况下被验证。
 */
@FunctionalInterface
public interface PromptSource {

    /**
     * 读一段提示词。
     *
     * @param path 相对提示词根目录的路径，如 {@code modes/agent.md}
     * @throws IllegalStateException 找不到或读不出来——<b>不返回空串</b>：
     *         静默退化的后果是模型少了一段约束而没有任何迹象
     */
    String loadRequired(String path);
}
