package dev.wiles.myagent.agent;

import dev.wiles.myagent.memory.MemoryIndex;
import dev.wiles.myagent.prompt.PromptAssembler;
import dev.wiles.myagent.prompt.PromptContext;
import dev.wiles.myagent.prompt.PromptMode;

import java.nio.file.Path;

/**
 * agent 的系统提示词。
 *
 * <h3>文字不在这个文件里了</h3>
 * 提示词正文搬去了 {@code src/main/resources/prompts/}，这里只剩下「怎么装配」。
 * 理由见 {@link dev.wiles.myagent.prompt.PromptRepository}：提示词的每个字都影响
 * 模型行为，所以它会被反复调，而调一次要重新编译重启的话，人就不调了。
 *
 * <p>这个类留着是因为它是个<b>好用的门面</b>——调用方要的是
 * 「给我这个工作区的 system 提示词」，不该每处都去拼 mode 和 context。
 */
public final class SystemPrompt {

    private static final PromptAssembler ASSEMBLER = PromptAssembler.createDefault();

    private SystemPrompt() {
    }

    /**
     * 不带记忆功能的版本——<b>一个字都不提记忆</b>。
     *
     * <p>走这条路的是计划任务（{@code TaskPrompt}），而角色（{@code AgentRole}）的白名单里
     * 没有 {@code recall}：工具不在菜单上，还要交代「你没有记忆」纯属噪音，
     * 甚至会勾着模型去找一个它根本调不到的工具。
     *
     * <p>注意这和「接了记忆但一条没记过」是两回事，后者必须出声，
     * 理由见 {@code MemoryIndex.EMPTY_NOTICE}。
     */
    public static String forWorkspace(Path workspaceRoot) {
        return forWorkspace(workspaceRoot, null);
    }

    /**
     * 带上长期记忆索引的版本。
     *
     * <p>索引拼在<b>最后</b>，不是最前。理由是它的内容每次启动都不一样
     * （记一条就变一次），而前面那几段是恒定的——把变动的部分放尾部，
     * 前缀就能稳定命中提供方的 prompt 缓存。
     *
     * <p>索引为空时 {@link MemoryIndex#render()} 会明说「没有可用的记忆」，
     * 所以这个版本和不带记忆的版本<b>永远不一样</b>——那一句是故意留的，
     * 少了它模型会去猜记忆的名字。
     */
    public static String forWorkspace(Path workspaceRoot, MemoryIndex memory) {
        return forWorkspace(workspaceRoot, memory, false);
    }

    /**
     * 再带上「当前是只读模式」这件事。
     *
     * <p><b>只读要明说，不能让模型自己从「菜单上没有写工具」推断出来。</b>
     * 推断得出的结论是「这些工具坏了」或者「我该换个法子写文件」，
     * 而不是「用户现在就是不想让我改东西」——前者会让它去试 bash 里的重定向，
     * 后者才会让它老老实实把方案说清楚。这条是从
     * {@code wiles-code-cli} 的 {@code noToolsSection()} 学来的：
     * 能力被裁剪时要交代一句，别留给模型猜。
     */
    public static String forWorkspace(Path workspaceRoot, MemoryIndex memory, boolean readOnly) {
        return forWorkspace(workspaceRoot, memory, readOnly, false);
    }

    /** 再带上「主动委派」：{@code MYAGENT_SUBAGENTS=proactive} 时，能并行就派子 agent。 */
    public static String forWorkspace(Path workspaceRoot, MemoryIndex memory, boolean readOnly,
                                      boolean proactiveSubagents) {
        return ASSEMBLER.assemble(PromptMode.AGENT, PromptContext.builder()
                .variable("workspace", String.valueOf(workspaceRoot))
                .memoryIndex(memory == null ? null : memory.render())
                .readOnly(readOnly)
                .proactiveSubagents(proactiveSubagents)
                .build());
    }
}
