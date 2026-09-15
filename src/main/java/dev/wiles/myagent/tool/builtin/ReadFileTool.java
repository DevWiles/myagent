package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 读文件。所有工具实现的范例，照着它写其余四个。
 *
 * 三个值得注意的设计：
 *
 * 1. **带行号输出**。模型看到 "42: foo" 才能准确说出「改第 42 行」，
 *    后面做 edit 工具时定位全靠它。
 * 2. **必须截断**。工具的返回值会原封不动变成 token 塞进上下文，
 *    一个 5 万行的日志文件能瞬间烧光预算，还会把有用信息挤出窗口。
 * 3. **失败返回 error 而不是抛异常**，且信息要写得让模型知道下一步怎么办。
 */
public final class ReadFileTool implements Tool {

    /** 单次最多返回的行数，超出部分截断并告知模型。 */
    private static final int MAX_LINES = 2000;
    /** 单行过长也要截断，防止压缩过的 js/json 一行几十万字符。 */
    private static final int MAX_LINE_LENGTH = 2000;

    private final Workspace workspace;

    public ReadFileTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取工作区内一个文本文件的内容，返回结果带行号。"
                + "修改文件前应当先读取确认内容。不能用于读取二进制文件。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "相对于工作区根目录的文件路径，例如 src/main/java/Main.java"
                    },
                    "offset": {
                      "type": "integer",
                      "description": "从第几行开始读，从 1 开始计数，默认 1"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "最多读多少行，默认 2000"
                    }
                  },
                  "required": ["path"]
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pathArg = Args.requireString(args, "path");
        int offset = Math.max(1, Args.optInt(args, "offset", 1));
        int limit = Math.min(MAX_LINES, Math.max(1, Args.optInt(args, "limit", MAX_LINES)));

        Path file = workspace.resolve(pathArg);   // 越界会抛 IllegalArgumentException

        if (!Files.exists(file)) {
            return ToolResult.error("文件不存在: " + pathArg);
        }
        if (Files.isDirectory(file)) {
            return ToolResult.error(pathArg + " 是一个目录，不是文件。列出目录内容请用 list_dir。");
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (MalformedInputException e) {
            return ToolResult.error(pathArg + " 不是 UTF-8 文本文件，无法读取（可能是二进制文件）。");
        } catch (IOException e) {
            return ToolResult.error("读取失败: " + e.getMessage());
        }

        if (lines.isEmpty()) {
            return ToolResult.ok("(文件为空)");
        }
        if (offset > lines.size()) {
            return ToolResult.error("offset " + offset + " 超出文件总行数 " + lines.size());
        }

        int from = offset - 1;
        int to = Math.min(lines.size(), from + limit);

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + "... (本行过长已截断)";
            }
            sb.append(i + 1).append(": ").append(line).append('\n');
        }
        // 告诉模型还有多少没读，它才知道要不要再调一次
        if (to < lines.size()) {
            sb.append("... (文件共 ").append(lines.size()).append(" 行，已显示到第 ")
                    .append(to).append(" 行，继续读取请传 offset=").append(to + 1).append(')');
        }
        return ToolResult.ok(sb.toString());
    }
}
