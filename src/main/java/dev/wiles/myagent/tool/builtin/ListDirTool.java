package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;

import java.util.Set;
import java.util.stream.Stream;
import java.util.List;
import java.util.Comparator;
import java.util.ArrayList;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.io.IOException;
import dev.wiles.myagent.tool.Args;

/**
 * 列出目录内容。
 *
 * 【待你实现】测试见 ListDirToolTest。
 */
public final class ListDirTool implements Tool {

    /**
     * 这些目录永远不要列出去。
     * 不是为了安全，是为了省 token——node_modules 一个目录几万个文件，
     * 列出来既烧钱又把有用信息淹没了。
     */
    private static final Set<String> IGNORED = Set.of(
            ".git", "target", "build", "node_modules", ".idea", ".gradle", "__pycache__");

    /** 最多列多少条，理由同 ReadFileTool 的行数上限：输出会变成 token。 */
    private static final int MAX_ENTRIES = 200;
    /** 递归深度上限，防止碰上超深目录树。 */
    private static final int MAX_DEPTH = 10;

    private final Workspace workspace;

    public ListDirTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "列出工作区内某个目录下的文件和子目录。"
                + "探索项目结构时先用它，再用 read_file 读具体文件。"
                + "会自动跳过 .git、target、node_modules 等无需关心的目录。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "相对于工作区根目录的目录路径，默认为根目录 ."
                    },
                    "recursive": {
                      "type": "boolean",
                      "description": "是否递归列出子目录内容，默认 false"
                    }
                  }
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pathArg = Args.optString(args, "path", ".");
        boolean recursive = Args.optBoolean(args, "recursive", false);

        Path dir = workspace.resolve(pathArg);

        if (!Files.exists(dir)) {
            return ToolResult.error("目录不存在: " + pathArg);
        }
        if (!Files.isDirectory(dir)) {
            return ToolResult.error(pathArg + " 是一个文件，不是目录。读取文件内容请用 read_file。");
        }

        List<Entry> entries;
        try {
            entries = recursive ? walkRecursive(dir) : listFlat(dir);
        } catch (IOException e) {
            return ToolResult.error("读取目录失败: " + e.getMessage());
        }

        if (entries.isEmpty()) {
            // 必须明说是空的，返回空串会让模型分不清「空目录」和「调用失败」
            return ToolResult.ok("(空目录)");
        }

        // 目录在前、同类按名字排，输出稳定模型才能对比前后变化
        entries.sort(Comparator.comparing((Entry e) -> !e.directory).thenComparing(e -> e.name));

        boolean truncated = entries.size() > MAX_ENTRIES;
        List<Entry> shown = truncated ? entries.subList(0, MAX_ENTRIES) : entries;

        StringBuilder sb = new StringBuilder();
        for (Entry e : shown) {
            sb.append(e.directory ? e.name + "/" : e.name + "  (" + humanSize(e.size) + ")").append('\n');
        }
        if (truncated) {
            sb.append("... (共 ").append(entries.size()).append(" 项，仅显示前 ")
                    .append(MAX_ENTRIES).append(" 项)");
        }
        return ToolResult.ok(sb.toString());
    }

    private List<Entry> listFlat(Path dir) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (IGNORED.contains(name)) {
                    continue;
                }
                entries.add(toEntry(name, p));
            }
        }
        return entries;
    }

    /**
     * 递归用 walkFileTree 而不是 Files.walk，是为了能在 preVisitDirectory 里
     * 返回 SKIP_SUBTREE 整棵剪掉 node_modules —— Files.walk 只能事后过滤，
     * 遇到几万个文件的目录照样会先全部遍历一遍。
     */
    private List<Entry> walkRecursive(Path dir) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Files.walkFileTree(dir, java.util.Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes attrs) {
                if (p.equals(dir)) {
                    return FileVisitResult.CONTINUE;
                }
                if (IGNORED.contains(p.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                entries.add(new Entry(dir.relativize(p).toString(), true, 0));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) {
                if (!IGNORED.contains(p.getFileName().toString())) {
                    entries.add(new Entry(dir.relativize(p).toString(), false, attrs.size()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path p, IOException e) {
                return FileVisitResult.CONTINUE;   // 没权限的条目跳过，不能让整次调用失败
            }
        });
        return entries;
    }

    private Entry toEntry(String name, Path p) {
        boolean isDir = Files.isDirectory(p);
        long size = 0;
        if (!isDir) {
            try {
                size = Files.size(p);
            } catch (IOException ignored) {
                // 拿不到大小不影响列目录
            }
        }
        return new Entry(name, isDir, size);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private record Entry(String name, boolean directory, long size) {
    }
}
