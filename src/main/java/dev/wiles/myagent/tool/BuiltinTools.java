package dev.wiles.myagent.tool;

import dev.wiles.myagent.tool.builtin.BashTool;
import dev.wiles.myagent.tool.builtin.CreateProjectTool;
import dev.wiles.myagent.tool.builtin.ListDirTool;
import dev.wiles.myagent.tool.builtin.ReadFileTool;
import dev.wiles.myagent.tool.builtin.WriteFileTool;

/** 内置工具的装配点。以后加新工具，在这里注册一行即可。 */
public final class BuiltinTools {

    private BuiltinTools() {
    }

    public static ToolRegistry createDefault(Workspace workspace) {
        return new ToolRegistry()
                .register(new ReadFileTool(workspace))
                .register(new WriteFileTool(workspace))
                .register(new ListDirTool(workspace))
                .register(new BashTool(workspace))
                .register(new CreateProjectTool(workspace));
    }
}
