package dev.wiles.myagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：一边向模型提供菜单，一边负责把模型的点单派发给对应工具。
 *
 * 这个类是 agent 里唯一「不许抛异常」的地方。
 * 它夹在模型和工具之间，上游是不可控的模型输出（名字可能瞎编、参数可能是烂 JSON），
 * 下游是可能炸的工具实现。**任何一处炸了都不能让 agent 循环停下来**——
 * 全部翻译成 ToolResult.error 交还给模型，让它自己看着办。
 */
public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ObjectMapper mapper = new ObjectMapper();
    /** 用 LinkedHashMap 保证发给模型的工具顺序稳定，便于调试和缓存命中。 */
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        Tool old = tools.put(tool.name(), tool);
        if (old != null) {
            log.warn("工具 {} 被重复注册，后者覆盖前者", tool.name());
        }
        return this;
    }

    public ToolRegistry registerAll(Collection<Tool> list) {
        list.forEach(this::register);
        return this;
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    /** 发给模型的完整菜单。 */
    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools.values()) {
            specs.add(tool.toSpec());
        }
        return specs;
    }

    /**
     * 执行模型的一次点单。永不抛异常。
     *
     * 三道防线，对应三种模型/工具可能的翻车方式：
     *   1. 工具名不存在   —— 模型把名字记岔了，或者幻觉出一个工具
     *   2. arguments 不是合法 JSON —— 协议只规定它是字符串，从没保证内容合法
     *   3. 工具自身抛异常 —— 实现里的漏网之鱼
     */
    public ToolResult execute(ToolCall call) {
        String name = call.name();
        Tool tool = tools.get(name);

        if (tool == null) {
            log.warn("模型点了不存在的工具: {}", name);
            return ToolResult.error("没有名为 " + name + " 的工具。可用的工具: "
                    + String.join(", ", tools.keySet()));
        }

        JsonNode args;
        try {
            args = mapper.readTree(call.argumentsJson());
        } catch (Exception e) {
            log.warn("工具 {} 的参数不是合法 JSON: {}", name, call.argumentsJson());
            return ToolResult.error("参数不是合法的 JSON，请重新生成。收到的内容: "
                    + call.argumentsJson());
        }
        if (args == null || !args.isObject()) {
            return ToolResult.error("参数必须是一个 JSON 对象，收到的是: " + call.argumentsJson());
        }

        long startedAt = System.nanoTime();
        try {
            ToolResult result = tool.execute(args);
            log.debug("工具 {} 执行{}，耗时 {}ms", name, result.ok() ? "成功" : "失败",
                    (System.nanoTime() - startedAt) / 1_000_000);
            return result;
        } catch (IllegalArgumentException e) {
            // 参数问题（缺字段、类型不对、路径越界）直接把原话给模型，它能据此修正
            log.debug("工具 {} 参数不合法: {}", name, e.getMessage());
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            // 工具实现没兜住的异常，在这里最后兜一次，绝不能让 agent 循环挂掉
            log.error("工具 {} 抛出未捕获异常", name, e);
            return ToolResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
