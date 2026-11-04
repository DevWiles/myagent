package dev.wiles.myagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.llm.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按需拉取延迟加载的工具，照 Claude Code 的 {@code ToolSearch}。
 *
 * <h3>为什么要延迟</h3>
 * 每一步请求都要带上整份工具菜单。接一个浏览器 MCP 就是二三十个工具、上万 token 的 schema，
 * 一次都不用也得每步付一遍。延迟之后菜单上只剩它们的名字（列在本工具的说明里），
 * 模型真要用时先调本工具，拿到完整说明，这些工具从下一步起就进了菜单。
 *
 * <h3>「拉过哪些」记在对话里，不记在这儿</h3>
 * 拉取结果的第一行是 {@link #LOADED} 加工具名，agent 每一步从历史里把它们读出来（{@link #loadedIn}）。
 * 这样恢复会话之后照样认得；而压缩把那条结果摘要掉之后，工具也跟着退回延迟状态——
 * 那时模型手上已经没有它的说明了，留在菜单上反而是在引它瞎填参数。
 */
public final class ToolSearchTool implements Tool {

    public static final String NAME = "tool_search";
    /** 拉取结果的第一行就以它开头，后面是逗号分隔的工具名。 */
    public static final String LOADED = "已加载工具: ";
    private static final int DEFAULT_MAX = 5;

    private final ToolRegistry registry;

    public ToolSearchTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        StringBuilder names = new StringBuilder();
        for (Tool tool : registry.deferredTools()) {
            names.append("- ").append(tool.name()).append('\n');
        }
        return """
                拉取延迟加载的工具的完整说明。下面这些工具平时只给名字、没有参数说明，\
                直接调用会失败；先用本工具拉取，拉到之后它们就和别的工具一样能调了。

                query 的写法：
                - "select:名字1,名字2"：按名字精确拉取
                - 关键词，比如 "browser click"：在名字和说明里搜，返回最相关的几个
                - "+chrome 截图"：名字里必须含 chrome，再按其余关键词排序

                可以按需加载的工具:
                %s""".formatted(names.toString().strip());
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string", "description": "select:名字1,名字2 精确拉取；或者关键词搜索"},
                    "max_results": {"type": "integer", "description": "关键词搜索时最多返回几个，默认 5"}
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String query = Args.requireString(args, "query").strip();
        int max = Math.max(1, Args.optInt(args, "max_results", DEFAULT_MAX));
        List<Tool> found = query.startsWith("select:") ? select(query.substring("select:".length()))
                : search(query, max);
        if (found.isEmpty()) {
            return ToolResult.error("没有找到匹配「" + query + "」的工具。能加载的是: "
                    + String.join(", ", registry.deferredTools().stream().map(Tool::name).toList()));
        }
        StringBuilder out = new StringBuilder(LOADED)
                .append(String.join(", ", found.stream().map(Tool::name).toList()))
                .append("\n现在可以直接调用它们了。\n");
        for (Tool tool : found) {
            out.append("\n## ").append(tool.name()).append('\n')
                    .append(tool.description().strip()).append("\n参数: ")
                    .append(tool.parametersSchema().strip()).append('\n');
        }
        return ToolResult.ok(out.toString());
    }

    private List<Tool> select(String names) {
        List<Tool> out = new ArrayList<>();
        for (String raw : names.split(",")) {
            Tool tool = registry.get(raw.strip());
            if (tool != null && registry.isDeferred(tool.name()) && !out.contains(tool)) {
                out.add(tool);
            }
        }
        return out;
    }

    /** 关键词打分：名字里命中算 3 分，说明里命中算 1 分；{@code +词} 是名字里必须有的。 */
    private List<Tool> search(String query, int max) {
        List<String> required = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        for (String word : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (word.startsWith("+") && word.length() > 1) {
                required.add(word.substring(1));
            } else if (!word.isEmpty()) {
                terms.add(word);
            }
        }
        record Scored(Tool tool, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Tool tool : registry.deferredTools()) {
            String name = tool.name().toLowerCase(Locale.ROOT);
            String desc = tool.description().toLowerCase(Locale.ROOT);
            if (!required.stream().allMatch(name::contains)) {
                continue;
            }
            int score = required.isEmpty() ? 0 : 1;
            for (String term : terms) {
                score += (name.contains(term) ? 3 : 0) + (desc.contains(term) ? 1 : 0);
            }
            if (score > 0) {
                scored.add(new Scored(tool, score));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return scored.stream().limit(max).map(Scored::tool).toList();
    }

    /** 这段对话里拉取过的工具名。见类注释。 */
    public static Set<String> loadedIn(List<ChatMessage> messages) {
        Set<String> loaded = new LinkedHashSet<>();
        for (ChatMessage m : messages) {
            String content = m.content();
            if (!ChatMessage.ROLE_TOOL.equals(m.role()) || content == null || !content.startsWith(LOADED)) {
                continue;
            }
            int eol = content.indexOf('\n');
            String line = content.substring(LOADED.length(), eol < 0 ? content.length() : eol);
            for (String name : line.split(",")) {
                if (!name.isBlank()) {
                    loaded.add(name.strip());
                }
            }
        }
        return loaded;
    }
}
