package dev.wiles.myagent.agent;

import dev.wiles.myagent.memory.MemoryIndex;

import java.nio.file.Path;

/**
 * agent 的系统提示词。
 *
 * 这段文字的地位和代码是平等的——它决定模型愿不愿意用工具、
 * 会不会在没读文件的情况下瞎猜内容、做完事会不会啰嗦一大段。
 * 调 agent 行为时，改这里往往比改 Java 代码有效。
 */
public final class SystemPrompt {

    private SystemPrompt() {
    }

    public static String forWorkspace(Path workspaceRoot) {
        return forWorkspace(workspaceRoot, MemoryIndex.empty());
    }

    /**
     * 带上长期记忆索引的版本。
     *
     * <p>索引拼在<b>最后</b>，不是最前。理由是它的内容每次启动都不一样
     * （记一条就变一次），而前面那段工作方式的约定是恒定的——
     * 把变动的部分放在尾部，前缀就能稳定命中提供方的 prompt 缓存。
     *
     * <p>索引为空时 {@link MemoryIndex#render()} 返回空串，
     * 拼接的结果和不带记忆的版本<b>一字不差</b>。
     */
    public static String forWorkspace(Path workspaceRoot, MemoryIndex memory) {
        return base(workspaceRoot) + (memory == null ? "" : memory.render());
    }

    private static String base(Path workspaceRoot) {
        return """
                你是 myagent，一个运行在用户终端里的编码助手。你可以调用工具来读写文件、执行命令。

                **始终使用中文回复**，包括调用工具之前的那句说明。

                当前工作区: %s

                工作方式:
                - 动手之前先了解情况。不清楚项目结构就用 list_dir，不清楚文件内容就用 read_file。
                - 绝对不要凭猜测描述文件内容或项目结构，没读过就去读。
                - 修改文件前必须先读取它，避免覆盖掉不该动的内容。
                - 需要多个信息时可以一次发起多个工具调用，不必一个个来回。
                - 改完代码后，如果项目有测试，用 bash 跑一下验证。
                - 工具返回错误时，先看清错误信息再调整做法，不要用同样的参数反复重试。

                回答风格:
                - 简洁。做完事情用一两句话说清改了什么、结果如何，不要复述全部过程。
                - 不确定的地方直接说不确定，不要编造。
                """.formatted(workspaceRoot);
    }
}
