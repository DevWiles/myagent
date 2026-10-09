package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import dev.wiles.myagent.tool.safety.TextDiff;
import dev.wiles.myagent.tool.safety.CommandRisk;
import dev.wiles.myagent.tool.safety.FileWriteGuard;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import dev.wiles.myagent.tool.Args;

/**
 * 一次性创建多个目录和文件，用来搭项目脚手架。
 *
 * 【设计说明，值得先想清楚】
 * 这个工具在**能力上**不提供任何新东西——有了 write_file（自动建父目录）和 bash（mkdir），
 * 模型已经能搭出任何结构。它的价值只在**省往返**：
 * 建一个 Maven 项目要 5 个文件，用 write_file 就是 5 轮「模型请求 → 我们执行 → 结果回传」，
 * 每轮都要把完整对话历史重发一遍；用它只要 1 轮。
 *
 * 代价是工具清单里出现了功能重叠的选项，模型有时会犹豫该用哪个。
 * 工具集不是越多越好——每多一个，模型选错的概率就涨一点。
 * 所以 description 必须写清楚**什么时候用它、什么时候别用**。
 *
 * 【待你实现】测试见 CreateProjectToolTest。
 */
public final class CreateProjectTool implements Tool {

    private final Workspace workspace;
    private final FileWriteGuard guard;
    private final ApprovalPolicy approval;

    public CreateProjectTool(Workspace workspace) {
        this(workspace, new FileWriteGuard(), ApprovalPolicy.denyAll());
    }

    public CreateProjectTool(Workspace workspace, FileWriteGuard guard, ApprovalPolicy approval) {
        this.workspace = workspace;
        this.guard = guard;
        this.approval = approval;
    }

    @Override
    public String name() {
        return "create_project";
    }

    @Override
    public String description() {
        return "一次性创建多个目录和文件，用于初始化项目骨架。"
                + "只在需要同时创建多个文件时使用；只改一个文件请用 edit_file，只新建一个文件请用 write_file。"
                + "已存在的文件默认不会被覆盖。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "dirs": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "要创建的目录列表，相对于工作区根目录"
                    },
                    "files": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "path": {"type": "string", "description": "文件路径"},
                          "content": {"type": "string", "description": "文件内容"}
                        },
                        "required": ["path", "content"]
                      },
                      "description": "要创建的文件列表，父目录会自动创建"
                    },
                    "overwrite": {
                      "type": "boolean",
                      "description": "已存在的文件是否覆盖，默认 false"
                    }
                  }
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        List<String> dirs = Args.optStringList(args, "dirs");
        JsonNode files = args.get("files");
        boolean overwrite = Args.optBoolean(args, "overwrite", false);

        boolean noFiles = files == null || !files.isArray() || files.isEmpty();
        if (dirs.isEmpty() && noFiles) {
            return ToolResult.error("dirs 和 files 至少要提供一个。");
        }

        List<String> createdDirs = new ArrayList<>();
        List<String> createdFiles = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String dir : dirs) {
            Path target = workspace.resolve(dir);   // 越界直接抛，属于模型的明显错误
            try {
                Files.createDirectories(target);
                createdDirs.add(dir);
            } catch (IOException e) {
                failed.add(dir + " (" + e.getMessage() + ")");
            }
        }

        // 等确认时被叫停了。后面的文件一个都不再碰——更不能再弹框，
        // 用户已经按了停，再问就是逼他一个个点掉，或者干等超时
        List<String> untouched = new ArrayList<>();
        boolean stopped = false;
        if (!noFiles) {
            for (JsonNode file : files) {
                String pathArg = Args.requireString(file, "path");
                if (stopped) {
                    untouched.add(pathArg);
                    continue;
                }
                String content = Args.optString(file, "content", "");
                Path target = workspace.resolve(pathArg);

                if (Files.isDirectory(target)) {
                    failed.add(pathArg + " (已存在同名目录)");
                    continue;
                }
                if (Files.exists(target) && !overwrite) {
                    skipped.add(pathArg);
                    continue;
                }
                CommandRisk risk = guard.assess(pathArg, target, content);
                if (risk.isForbidden()) {
                    failed.add(pathArg + " (" + risk.reason() + ")");
                    continue;
                }
                // 批量创建时逐个弹框会烦死人，所以只对真正要覆盖的那些问，
                // 且把每个文件的变化幅度写进提示里
                if (risk.needsApproval()) {
                    ApprovalVerdict verdict = approval.decide(ApprovalRequest.write(
                            "覆盖文件", pathArg, risk.reason(), diffAgainst(target, content)));
                    if (verdict.cancelled()) {
                        stopped = true;
                        untouched.add(pathArg);
                        continue;
                    }
                    if (!verdict.approved()) {
                        failed.add(pathArg + " (" + verdict.deniedMessage("用户拒绝覆盖") + ")");
                        continue;
                    }
                }
                try {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(target, content);
                    createdFiles.add(pathArg + " ("
                            + content.getBytes(StandardCharsets.UTF_8).length + " 字节)");
                } catch (IOException e) {
                    failed.add(pathArg + " (" + e.getMessage() + ")");
                }
            }
        }

        // 部分失败不回滚：文件系统没有事务，而模型有能力看着现状继续推进。
        // 所以把成功、跳过、失败三份清单一起交出去，让它自己决定下一步。
        StringBuilder sb = new StringBuilder();
        if (!createdDirs.isEmpty()) {
            sb.append("已创建目录: ").append(String.join(", ", createdDirs)).append('\n');
        }
        if (!createdFiles.isEmpty()) {
            sb.append("已创建文件: ").append(String.join(", ", createdFiles)).append('\n');
        }
        if (!skipped.isEmpty()) {
            sb.append("已跳过(文件已存在，如需覆盖请传 overwrite=true): ")
                    .append(String.join(", ", skipped)).append('\n');
        }
        if (!failed.isEmpty()) {
            sb.append("创建失败: ").append(String.join(", ", failed)).append('\n');
        }
        if (!untouched.isEmpty()) {
            // 不列出来的话，模型接着做时会以为它们已经写好了
            sb.append(ApprovalVerdict.interrupted().deniedMessage(""))
                    .append(" 没有处理: ").append(String.join(", ", untouched)).append('\n');
        }
        if (!failed.isEmpty() || !untouched.isEmpty()) {
            return ToolResult.error(sb.toString().trim());
        }
        return ToolResult.ok(sb.toString().trim());
    }

    /** 同 {@code WriteFileTool.diffAgainst}：读不动就不给 diff，但绝不因此挡住审批。 */
    private static String diffAgainst(Path existing, String content) {
        try {
            return TextDiff.unified(Files.readString(existing, StandardCharsets.UTF_8), content);
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }
}
