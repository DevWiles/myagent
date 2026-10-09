package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import dev.wiles.myagent.tool.safety.CommandRisk;
import dev.wiles.myagent.tool.safety.FileWriteGuard;
import dev.wiles.myagent.tool.safety.TextDiff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 改已有文件的一处：把 {@code old_string} 换成 {@code new_string}，文件其余部分一个字不动。
 * 照 Claude Code 的 Edit / Codex 的 apply_patch。
 *
 * <h3>为什么要有它</h3>
 * 原来改文件只有 {@code write_file} 一条路：改一行也得把整个文件重写一遍。文件一大，
 * 模型重写时就会漏——cookiecutter 上几次掉到 118/202，都是收尾前一次 write_file
 * 把 generate.py / prompt.py 整个写成了只剩一两个函数的残缺版，测试文件连导入都过不了。
 * 只送改动的那一段，没提到的部分就不可能被弄丢。
 *
 * <h3>为什么要求 old_string 唯一</h3>
 * 出现好几处时换哪一处是猜的，猜错了比报错糟得多：文件看起来改了，改的却不是那一处。
 * 所以不唯一就拒绝，让模型多带几行上下文；确实要全换就显式传 {@code replace_all}。
 *
 * <p>安全约束和 {@link WriteFileTool} 一致：写不出工作区、不碰 .git，改已有文件要过审批，
 * 审批请求和结果都带 diff。
 */
public final class EditFileTool implements Tool {

    private final Workspace workspace;
    private final FileWriteGuard guard;
    private final ApprovalPolicy approval;

    /** 不指定审批策略时，改已有文件一律拒绝。 */
    public EditFileTool(Workspace workspace) {
        this(workspace, new FileWriteGuard(), ApprovalPolicy.denyAll());
    }

    public EditFileTool(Workspace workspace, FileWriteGuard guard, ApprovalPolicy approval) {
        this.workspace = workspace;
        this.guard = guard;
        this.approval = approval;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "改已有文件里的一处：把 old_string 原样替换成 new_string，文件其余部分不动。"
                + "改已有文件优先用它，而不是用 write_file 整个重写——重写大文件容易漏掉没提到的部分。"
                + "old_string 必须和文件内容逐字一致（包括缩进和换行），并且在文件里只出现一次；"
                + "不唯一时多带几行上下文，确实要全部替换就传 replace_all=true。"
                + "先用 read_file 读过再改。新建文件用 write_file。";
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
                    "old_string": {
                      "type": "string",
                      "description": "要被替换的原文，必须和文件内容逐字一致"
                    },
                    "new_string": {
                      "type": "string",
                      "description": "替换成的新内容，可以为空（等于删掉 old_string）"
                    },
                    "replace_all": {
                      "type": "boolean",
                      "description": "true 时替换所有出现的地方；默认只换唯一的那一处"
                    }
                  },
                  "required": ["path", "old_string", "new_string"]
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pathArg = Args.requireString(args, "path");
        String oldString = Args.optString(args, "old_string", "");
        String newString = Args.optString(args, "new_string", "");
        boolean replaceAll = args.path("replace_all").asBoolean(false);

        if (oldString.isEmpty()) {
            return ToolResult.error("old_string 不能为空。新建文件或整体重写用 write_file。");
        }
        if (oldString.equals(newString)) {
            return ToolResult.error("old_string 和 new_string 一模一样，没有要改的。");
        }

        Path file = workspace.resolve(pathArg);
        if (Files.isDirectory(file)) {
            return ToolResult.error(pathArg + " 是一个目录，不是文件。");
        }
        if (!Files.exists(file)) {
            return ToolResult.error(pathArg + " 不存在。新建文件用 write_file。");
        }

        String original;
        try {
            original = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.error("读不了 " + pathArg + "（可能是二进制文件）: " + e.getMessage());
        }

        int count = occurrences(original, oldString);
        if (count == 0) {
            return ToolResult.error("在 " + pathArg + " 里没找到 old_string。它必须和文件内容逐字一致"
                    + "（缩进、空格、换行都算）；先用 read_file 看一下当前内容再改。");
        }
        if (count > 1 && !replaceAll) {
            return ToolResult.error("old_string 在 " + pathArg + " 里出现了 " + count + " 次，不知道该换哪一处。"
                    + "多带几行前后文让它唯一；确实要全部替换就传 replace_all=true。");
        }

        // replace 是按字面替换，不认正则；只有一处时它就是那一处
        String updated = replaceAll ? original.replace(oldString, newString)
                : replaceFirst(original, oldString, newString);

        CommandRisk risk = guard.assess(pathArg, file, updated);
        if (risk.isForbidden()) {
            return ToolResult.error(risk.reason());
        }
        String diff = diff(original, updated);
        if (risk.needsApproval()) {
            ApprovalVerdict verdict = approval.decide(ApprovalRequest.write(
                    "修改文件", pathArg, "将修改已有文件 " + pathArg + "（替换 " + count + " 处）", diff));
            if (!verdict.approved()) {
                return ToolResult.error(verdict.deniedMessage("用户拒绝修改 " + pathArg + "。"));
            }
        }

        try {
            Files.writeString(file, updated);
        } catch (IOException e) {
            return ToolResult.error("写入失败: " + e.getMessage());
        }
        return ToolResult.ok("已修改 " + pathArg + "（替换 " + count + " 处）", diff);
    }

    static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static String replaceFirst(String text, String target, String replacement) {
        int at = text.indexOf(target);
        return text.substring(0, at) + replacement + text.substring(at + target.length());
    }

    private static String diff(String before, String after) {
        try {
            return TextDiff.unified(before, after);
        } catch (RuntimeException e) {
            return "";              // diff 生不成不该挡住修改，那只是少了一份参考
        }
    }
}
