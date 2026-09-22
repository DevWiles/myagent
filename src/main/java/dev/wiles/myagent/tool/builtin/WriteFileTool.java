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
        // 写之前就得算：写完再比，比的是新内容和它自己，永远是空的
        String diff = diffAgainst(file, content);
        if (risk.needsApproval()) {
            // 带上 diff。只说「原 30 行 → 新 45 行」的话，用户能做的判断只有
            // 「文件变长了」，而他要决定的是「这个改动对不对」
            ApprovalVerdict verdict = approval.decide(ApprovalRequest.write(
                    "写入文件", pathArg, risk.reason(), diff));
            if (!verdict.approved()) {
                return ToolResult.error(verdict.deniedMessage("用户拒绝覆盖 " + pathArg + "。"
                        + "请先确认该文件当前内容，或改写到别的路径。"));
            }
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
        // diff 跟着结果一起走，界面才画得出「改了哪几行」。
        //
        // 以前它只出现在**审批请求**里，于是规则放行的那些写入（也就是用惯了之后
        // 的绝大多数写入）从头到尾没有任何地方显示改了什么——屏幕上只有一句
        // 「已写入 45 行」。而 agent 改代码这件事，用户最该看见的恰恰是这个。
        return ToolResult.ok("已写入 " + pathArg + " (" + bytes + " 字节, " + lines + " 行)", diff);
    }

    /**
     * 即将发生的改动。
     *
     * <h3>新建文件也要出 diff</h3>
     * 以前这里对不存在的文件直接返回空串，理由是「没有旧版本可比」。
     * 那在语义上说得通，在界面上却是灾难：新建一个工程要写七八个文件，
     * 屏幕上就只有一句「写入 7 个文件」，<b>一个字的内容都看不到</b>——
     * 而这恰恰是最需要看的时候，因为这些文件是凭空出现的，用户对它们一无所知。
     *
     * <p>所以按 git 的做法办：拿空内容当旧版本比，整份新内容显示成全是新增行。
     *
     * <p>读不动（二进制、编码不对、权限不够）才退回空串——
     * <b>diff 生不成不该挡住写入</b>，那只是少了一份参考，而卡住的是用户正在做的事。
     */
    private static String diffAgainst(Path existing, String content) {
        try {
            // 不存在就按空文件比：新建文件的「改动」就是它的全部内容
            String old = Files.exists(existing)
                    ? Files.readString(existing, StandardCharsets.UTF_8)
                    : "";
            return TextDiff.unified(old, content);
        } catch (IOException | RuntimeException e) {
            return "";                         // 二进制文件、编码不对、权限不够，都归这儿
        }
    }
}
