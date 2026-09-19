package dev.wiles.myagent.tool;

import dev.wiles.myagent.memory.MemoryStore;
import dev.wiles.myagent.tool.builtin.BashTool;
import dev.wiles.myagent.tool.builtin.CreateProjectTool;
import dev.wiles.myagent.tool.builtin.ListDirTool;
import dev.wiles.myagent.tool.builtin.ReadFileTool;
import dev.wiles.myagent.tool.builtin.RecallTool;
import dev.wiles.myagent.tool.builtin.RememberTool;
import dev.wiles.myagent.tool.builtin.WriteFileTool;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.CommandGuard;
import dev.wiles.myagent.tool.safety.FileWriteGuard;

/** 内置工具的装配点。以后加新工具，在这里注册一行即可。 */
public final class BuiltinTools {

    /**
     * 哪些工具算「改了文件」。
     *
     * <p>上下文压缩要靠它算出「这次会话动过哪些文件」那一条钉住块，
     * 而那是压缩里<b>唯一不经过模型</b>的保证——摘要提示词里那四条是请模型自觉，
     * 这一条是扫出来的。所以名字声明在注册它们的地方，
     * 将来加了新的写工具，在这儿补一个名字就行，压缩那边不用改。
     */
    public static final java.util.Set<String> WRITING_TOOLS =
            java.util.Set.of("write_file", "create_project");

    private BuiltinTools() {
    }

    /** 不传审批策略时，危险命令一律拒绝。 */
    public static ToolRegistry createDefault(Workspace workspace) {
        return createDefault(workspace, ApprovalPolicy.denyAll());
    }

    /**
     * 只读工具集：连写文件和执行命令的工具都不注册。
     *
     * 这比「注册了但拦住」可靠得多——模型看不到菜单上没有的工具，
     * 就根本不会尝试调用，也就不存在被绕过的可能。
     */
    public static ToolRegistry createReadOnly(Workspace workspace) {
        return createReadOnly(workspace, null);
    }

    /**
     * 只读工具集 + 记忆的<b>读</b>那一半。
     *
     * <p>给 {@code recall} 不给 {@code remember}：读记忆不改用户的任何东西，
     * 而只读模式承诺的就是不改任何东西——{@code ~/.myagent/} 也算在内。
     * 反过来说，不给 recall 的话，系统提示词里那份索引就成了一堆
     * <b>永远读不到正文的悬空引用</b>，比不给还糟。
     */
    public static ToolRegistry createReadOnly(Workspace workspace, MemoryStore memory) {
        ToolRegistry registry = new ToolRegistry()
                .register(new ReadFileTool(workspace))
                .register(new ListDirTool(workspace));
        if (memory != null) {
            registry.register(new RecallTool(memory, workspace.root()));
        }
        return registry;
    }

    public static ToolRegistry createDefault(Workspace workspace, ApprovalPolicy approval) {
        return createDefault(workspace, approval, null);
    }

    /**
     * 完整工具集 + 记忆的读写两半。
     *
     * <p>{@code memory} 传 null 表示这个 agent 没有记忆——单测和一次性调用走这条，
     * 行为和加记忆之前<b>一字不差</b>。记忆是加进来的能力，不是所有人都得付的税。
     */
    public static ToolRegistry createDefault(Workspace workspace, ApprovalPolicy approval,
                                             MemoryStore memory) {
        ToolRegistry registry = new ToolRegistry()
                .register(new ReadFileTool(workspace))
                .register(new WriteFileTool(workspace, new FileWriteGuard(), approval))
                .register(new ListDirTool(workspace))
                .register(new BashTool(workspace, new CommandGuard(), approval))
                .register(new CreateProjectTool(workspace, new FileWriteGuard(), approval));
        if (memory != null) {
            registry.register(new RememberTool(memory, workspace.root()))
                    .register(new RecallTool(memory, workspace.root()));
        }
        return registry;
    }
}
