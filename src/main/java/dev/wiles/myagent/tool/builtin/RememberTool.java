package dev.wiles.myagent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.memory.MemoryEntry;
import dev.wiles.myagent.memory.MemoryScope;
import dev.wiles.myagent.memory.MemorySource;
import dev.wiles.myagent.memory.MemoryStore;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * 把一件事记进长期记忆，下次会话还在。
 *
 * <h3>这个类里最重要的东西是 {@link #description()} 那段文字，不是代码</h3>
 * 代码只有二十来行——取参数、落盘、回一句话。真正决定这个功能成不成的是提示词：
 * <b>模型什么时候该调它，什么时候该忍住</b>。
 *
 * <p>不写清楚「什么时候不该记」的话，默认失败模式非常明确——<b>模型会把一次性细节
 * 当成长期事实往里塞</b>：「刚才那个测试失败了」「用户让我看 Agent.java」。
 * 这些下一轮就没用了，但它们会永久占着索引的位置，每轮对话都要发一遍，
 * 还会把真正有用的几条淹掉。所以 description 里正例反例一起给，
 * 这和 {@code ReadFileTool} 把「不能用于二进制文件」写进说明是同一个道理。
 *
 * <h3>为什么让模型自己判断，而不是等用户喊「记住」</h3>
 * 因为有价值的记忆<b>大多数时候用户不会专门说</b>：用户纠正你一次
 * 「不对，这个项目用 bun 不用 npm」，他要的是当下这件事做对，
 * 不会额外想到「你该把这条记下来」。等指令的话，这条就丢了。
 */
public final class RememberTool implements Tool {

    private final MemoryStore store;
    private final Path workspace;

    public RememberTool(MemoryStore store, Path workspace) {
        this.store = store;
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "remember";
    }

    @Override
    public String description() {
        return """
                把一件**以后还用得上**的事写进长期记忆，跨会话保留。

                什么时候该用：
                - 用户纠正了你的做法（「这个项目用 bun 不用 npm」）——这类最该记
                - 用户说明了他的偏好或习惯（「提交信息要用中文」）
                - 你查明了一个短期内不会变的项目事实（构建命令、架构约定、某个坑的成因）
                - 用户明确说「记住…」

                什么时候**不要**用（这些写进去只会变成噪音，还挤掉有用的）：
                - 一次性的中间结果（「刚才那个测试失败了」「这个函数在第 42 行」）
                - 读一遍文件就能知道的事（代码结构、函数签名、依赖版本）
                - 这轮任务做完就没意义的进度（「已经改完 3 个文件」）
                - 你不确定下次还成不成立的事

                判断标准只有一条：**下次打开这个项目，不知道这件事会不会做错事**。
                会，就记；不会，就别记。

                同名会覆盖。记错了可以用同一个 name 重写一遍。
                """;
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string",
                      "description": "句柄，小写连字符风格，例如 build-uses-bun。以后靠它 recall，要好记、说得出是什么"
                    },
                    "description": {
                      "type": "string",
                      "description": "一句话摘要，20-50 字。这行会出现在你每一轮的系统提示词里，要能让你判断出它跟当前的事有没有关系"
                    },
                    "content": {
                      "type": "string",
                      "description": "正文。写清楚事实本身、以及是怎么知道的（用户说的？你跑出来的？）。只有 recall 时才会读到，可以写细"
                    },
                    "scope": {
                      "type": "string",
                      "enum": ["project", "global"],
                      "description": "project=只在当前项目有效（默认，绝大多数情况选它）；global=跨所有项目都成立，只有用户的个人偏好才配"
                    },
                    "source": {
                      "type": "string",
                      "enum": ["user_correction", "user_stated", "observation"],
                      "description": "user_correction=用户纠正了你；user_stated=用户主动说的；observation=你自己查出来的（默认）"
                    }
                  },
                  "required": ["name", "description", "content"]
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        MemoryEntry entry;
        try {
            entry = new MemoryEntry(
                    Args.requireString(args, "name"),
                    Args.requireString(args, "description"),
                    Args.requireString(args, "content"),
                    MemoryScope.from(Args.optString(args, "scope", null)),
                    MemorySource.from(Args.optString(args, "source", null)),
                    LocalDate.now());
        } catch (IllegalArgumentException e) {
            // 参数不合规（name 归一化后为空、description 空）——告诉模型怎么改，它能自己重来
            return ToolResult.error(e.getMessage());
        }

        if (!store.save(entry, workspace)) {
            // 盘写不了。这是环境问题，模型重试也没用，所以明说别再试
            return ToolResult.error("写入记忆失败（磁盘或权限问题），本次没记住。不用重试，"
                    + "继续当前任务即可。");
        }
        return ToolResult.ok("已记住 [" + entry.name() + "]（"
                + (entry.scope() == MemoryScope.GLOBAL ? "全局" : "本项目") + "）: "
                + entry.description());
    }
}
