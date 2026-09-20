package dev.wiles.myagent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从会话记录里直接算出来的、压缩绝不能丢的那几件事。
 *
 * <h3>为什么这件事值得单独做一遍</h3>
 * 摘要提示词里写着「必须保留：用户最初的目标、改过哪些文件、……」，但那是<b>请求模型这么做</b>，
 * 没有任何一处代码检查它做没做到。模型摘歪了，系统完全无感——日志只会说
 * 「上下文压缩: 48000 -> 24000 tokens」。
 *
 * <p>记录改成只追加之后，这两样根本不需要经过模型：
 * <ul>
 *   <li><b>最初的目标</b>就是记录里第一条 user 消息，取出来即可</li>
 *   <li><b>改过哪些文件</b>可以扫记录里的工具调用参数，一个字都不用猜</li>
 * </ul>
 * 于是保证从「请模型保留」变成「根本不经过模型」，强度差一个量级。
 *
 * <h3>为什么工具名字要从外面传进来</h3>
 * {@code context} 包不该知道有哪些工具、它们叫什么——那是 {@code tool} 包的事，
 * 而且只读模式下工具集还不一样。调用方（{@code AgentServer}）
 * 手上有注册表，让它说了算。和 {@code ContextManager.Harvested} 不引用
 * {@code MemoryEntry} 是同一个处理：低层只认自己的数据形状。
 *
 * <h3>路径为什么按 {@code path} 这个键名收</h3>
 * 内置的两个写工具是这么约定的：{@code write_file} 是顶层 {@code path}，
 * {@code create_project} 是 {@code files[].path}。所以<b>递归收集所有叫 path 的字符串</b>
 * 两种形状都能覆盖，将来再加写工具只要沿用这个键名就自动生效，不用回来改这里。
 */
public record PinnedFacts(String goal, List<String> files) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 文件清单最多列这么多条。改了几百个文件时，列表本身就成了上下文负担。 */
    public static final int MAX_FILES = 40;

    public PinnedFacts {
        files = List.copyOf(files == null ? List.of() : files);
    }

    /**
     * 从记录里算出来。
     *
     * @param record       会话记录，<b>不含</b> system
     * @param writingTools 哪些工具算「改了文件」；null 或空表示不统计文件
     */
    public static PinnedFacts of(List<ChatMessage> record, Set<String> writingTools) {
        if (record == null || record.isEmpty()) {
            return new PinnedFacts(null, List.of());
        }
        String goal = null;
        Set<String> paths = new LinkedHashSet<>();   // 保持首次出现的顺序，且天然去重

        for (ChatMessage message : record) {
            if (goal == null && ChatMessage.ROLE_USER.equals(message.role())
                    && message.content() != null && !message.content().isBlank()) {
                goal = message.content().strip();
            }
            if (writingTools == null || writingTools.isEmpty()) {
                continue;
            }
            for (ToolCall call : message.toolCallsOrEmpty()) {
                if (writingTools.contains(call.name())) {
                    collectPaths(call.argumentsJson(), paths);
                }
            }
        }
        return new PinnedFacts(goal, List.copyOf(paths));
    }

    /**
     * 把参数 JSON 里所有叫 {@code path} 的字符串收走。
     *
     * <p>整段吞异常：参数是模型生成的，半截 JSON、类型写错都是常态。
     * 钉住块是<b>顺带</b>算的，解析失败只该让这一条不计入，
     * 绝不能把整个窗口组装带崩——那是会话能不能继续的关键路径。
     */
    private static void collectPaths(String argumentsJson, Set<String> into) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return;
        }
        try {
            walk(MAPPER.readTree(argumentsJson), into);
        } catch (Exception e) {
            // 参数都解析不了，这次调用大概率也没真的写成文件，跳过即可
        }
    }

    private static void walk(JsonNode node, Set<String> into) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode path = node.get("path");
            if (path != null && path.isTextual() && !path.asText().isBlank()) {
                into.add(path.asText().strip());
            }
            node.forEach(child -> walk(child, into));
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, into));
        }
    }

    public boolean isEmpty() {
        return (goal == null || goal.isBlank()) && files.isEmpty();
    }

    /**
     * 排成窗口里那一块文字。空的时候返回 null，调用方据此整块略过——
     * 一句「本次会话还没有改动过文件」不值得每轮都付钱。
     */
    public ChatMessage render() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[本次会话的固定事实，不随上下文压缩丢失]\n");
        if (goal != null && !goal.isBlank()) {
            sb.append("最初的要求：").append(goal).append('\n');
        }
        if (!files.isEmpty()) {
            sb.append("已经动过的文件：");
            List<String> shown = files.size() > MAX_FILES ? files.subList(0, MAX_FILES) : files;
            sb.append(String.join("、", shown));
            if (files.size() > MAX_FILES) {
                sb.append("…（另有 ").append(files.size() - MAX_FILES).append(" 个）");
            }
            sb.append('\n');
        }
        return ChatMessage.user(sb.toString().stripTrailing());
    }

    /** 合并两份——续聊时把存档里的和新产生的并起来用。 */
    public PinnedFacts merge(PinnedFacts other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        List<String> merged = new ArrayList<>(files);
        other.files.stream().filter(f -> !merged.contains(f)).forEach(merged::add);
        return new PinnedFacts(goal != null && !goal.isBlank() ? goal : other.goal, merged);
    }
}
