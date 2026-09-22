package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.memory.MemoryEntry;
import dev.wiles.myagent.memory.MemoryScope;
import dev.wiles.myagent.memory.MemoryStore;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 读出一条长期记忆的正文。
 *
 * <h3>为什么非得有这个工具，而不是把正文直接塞进提示词</h3>
 * 因为正文贵。系统提示词是<b>每一轮都要重发</b>的，几十条记忆的正文全塞进去，
 * 等于给每一轮对话都加一笔固定开销，而其中绝大多数条目跟当下这件事毫无关系。
 * 索引里一行摘要约 50 token，正文动辄十倍——差的就是这十倍。
 *
 * <h3>为什么不能用 read_file 代替</h3>
 * 记忆落在 {@code ~/.myagent/memory/} 下，在工作区<b>外面</b>。
 * {@code ReadFileTool} 走 {@code Workspace.resolve}，越界会直接抛——
 * 那道边界是故意设的（agent 不该能读用户主目录的任意文件），不能为了记忆开口子。
 * 所以记忆需要一个自己的、只能读记忆目录的入口。
 *
 * <h3>只读模式也注册它</h3>
 * 读记忆不改用户的任何东西。只读模式不给 {@code remember}，但给 {@code recall}——
 * 否则索引里那些摘要行就成了模型永远看不到正文的悬空引用。
 */
public final class RecallTool implements Tool {

    private final MemoryStore store;
    private final Path workspace;

    public RecallTool(MemoryStore store, Path workspace) {
        this.store = store;
        this.workspace = workspace;
    }

    /** 只读，不弹审批，怎么跑都无所谓——允许和别的调用同时执行。 */
    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public String name() {
        return "recall";
    }

    @Override
    public String description() {
        return "读出一条长期记忆的完整内容。系统提示词里「你记住的事」那份清单只有一句话摘要，"
                + "当你判断其中某条可能和当前任务有关时，用这个工具读出正文再下结论。"
                + "不要只凭摘要行动——摘要是用来筛选的，不是用来执行的。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string",
                      "description": "记忆的句柄，取自系统提示词里「你记住的事」清单每行开头的那个名字"
                    }
                  },
                  "required": ["name"]
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String name = Args.requireString(args, "name");

        Optional<MemoryEntry> hit = store.load(name, workspace);
        if (hit.isEmpty()) {
            // 扑空多半是名字打错了。把现有的名字列回去，模型能自己纠正，
            // 比单说一句「没找到」有用得多——否则它只会换个拼法再试一次，还是空。
            return ToolResult.error("没有叫 " + name + " 的记忆。" + available());
        }

        MemoryEntry e = hit.get();
        return ToolResult.ok("""
                [%s] %s
                范围: %s   来源: %s   记于: %s

                %s"""
                .formatted(e.name(), e.description(),
                        e.scope() == MemoryScope.GLOBAL ? "全局" : "本项目",
                        e.source().wire(), e.created(), e.content()));
    }

    private String available() {
        // 用模型视角而不是 scan()：load 已经把被取代的条目当作不存在了，
        // 这里再按审计视角列清单，错误信息就会自相矛盾——
        // 一边说「没有叫 X 的记忆」，一边把 X 列进「现有的是」
        List<MemoryEntry> all = store.scanActive(workspace);
        if (all.isEmpty()) {
            return "目前一条记忆都没有。";
        }
        StringBuilder sb = new StringBuilder("现有的是: ");
        for (int i = 0; i < all.size(); i++) {
            sb.append(i == 0 ? "" : "、").append(all.get(i).name());
        }
        return sb.toString();
    }
}
