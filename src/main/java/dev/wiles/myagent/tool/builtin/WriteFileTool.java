package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.CommandRisk;
import dev.wiles.myagent.tool.safety.FileWriteGuard;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import dev.wiles.myagent.tool.Args;

/**
 * 写文件（整文件覆盖）。
 *
 * 两道防护：Workspace 保证写不出工作区，FileWriteGuard 保证
 * 覆盖已有文件前先问过人（新建文件则直接放行，免得把用户问烦）。
 */
public final class WriteFileTool implements Tool {

    private final Workspace workspace;
    private final FileWriteGuard guard;
    private final ApprovalPolicy approval;

    /** 不指定审批策略时，覆盖已有文件一律拒绝。 */
    public WriteFileTool(Workspace workspace) {
        this(workspace, new FileWriteGuard(), ApprovalPolicy.denyAll());
    }

    public WriteFileTool(Workspace workspace, FileWriteGuard guard, ApprovalPolicy approval) {
        this.workspace = workspace;
        this.guard = guard;
        this.approval = approval;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "把内容写入工作区内的文件，整个文件会被覆盖。"
                + "父目录不存在时会自动创建。修改已有文件前应先用 read_file 读取，避免误覆盖。"
                + "覆盖已存在的文件会先征求用户同意，可能被拒绝。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "相对于工作区根目录的文件路径"
                    },
                    "content": {
                      "type": "string",
                      "description": "要写入的完整文件内容"
                    }
                  },
                  "required": ["path", "content"]
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pathArg = Args.requireString(args, "path");
        // 注意用 optString 而不是 requireString：写一个空文件是正当需求，
        // 而 requireString 会把空串当成「没传参数」。
        String content = Args.optString(args, "content", "");

        Path file = workspace.resolve(pathArg);

        if (Files.isDirectory(file)) {
            return ToolResult.error(pathArg + " 是一个已存在的目录，无法写入文件。");
        }

        CommandRisk risk = guard.assess(pathArg, file, content);
        if (risk.isForbidden()) {
            return ToolResult.error(risk.reason());
        }
        if (risk.needsApproval() && !approval.approve("写入文件", pathArg, risk.reason())) {
            return ToolResult.error("用户拒绝覆盖 " + pathArg + "。"
                    + "请先确认该文件当前内容，或改写到别的路径。");
        }

        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);   // 已存在时不会报错
            }
            Files.writeString(file, content);      // 默认 UTF-8，且会截断已有内容
        } catch (IOException e) {
            return ToolResult.error("写入失败: " + e.getMessage());
        }

        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
        return ToolResult.ok("已写入 " + pathArg + " (" + bytes + " 字节, " + lines + " 行)");
    }
}
