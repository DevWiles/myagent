package dev.wiles.myagent.tool;

import dev.wiles.myagent.tool.builtin.BashTool;
import dev.wiles.myagent.tool.builtin.CreateProjectTool;
import dev.wiles.myagent.tool.builtin.ListDirTool;
import dev.wiles.myagent.tool.builtin.ReadFileTool;
import dev.wiles.myagent.tool.builtin.WriteFileTool;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.CommandGuard;
import dev.wiles.myagent.tool.safety.FileWriteGuard;

/** 内置工具的装配点。以后加新工具，在这里注册一行即可。 */
public final class BuiltinTools {

    private BuiltinTools() {
    }

    /** 不传审批策略时，危险命令一律拒绝。 */
    public static ToolRegistry createDefault(Workspace workspace) {
        return createDefault(workspace, ApprovalPolicy.denyAll());
    }

    public static ToolRegistry createDefault(Workspace workspace, ApprovalPolicy approval) {
        return new ToolRegistry()
                .register(new ReadFileTool(workspace))
                .register(new WriteFileTool(workspace, new FileWriteGuard(), approval))
                .register(new ListDirTool(workspace))
                .register(new BashTool(workspace, new CommandGuard(), approval))
                .register(new CreateProjectTool(workspace, new FileWriteGuard(), approval));
    }
}
