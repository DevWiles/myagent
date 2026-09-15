package dev.wiles.myagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.llm.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注册表夹在「不可控的模型输出」和「可能炸的工具实现」中间，
 * 它的唯一职责就是：无论上下游怎么翻车，都要返回一个能交给模型的结果。
 */
class ToolRegistryTest {

    /** 一个可以按需翻车的假工具。 */
    private static Tool fake(String name, java.util.function.Function<JsonNode, ToolResult> body) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "测试用"; }
            public String parametersSchema() { return "{\"type\":\"object\"}"; }
            public ToolResult execute(JsonNode args) { return body.apply(args); }
        };
    }

    @Test
    @DisplayName("正常派发：找到工具并把参数传进去")
    void dispatches() {
        ToolRegistry registry = new ToolRegistry()
                .register(fake("echo", args -> ToolResult.ok("收到 " + args.get("msg").asText())));

        ToolResult result = registry.execute(ToolCall.of("call_1", "echo", "{\"msg\":\"你好\"}"));

        assertTrue(result.ok());
        assertEquals("收到 你好", result.content());
    }

    @Test
    @DisplayName("模型点了不存在的工具：返回 error 并列出可用工具，不抛异常")
    void unknownTool() {
        ToolRegistry registry = new ToolRegistry()
                .register(fake("read_file", args -> ToolResult.ok("")))
                .register(fake("write_file", args -> ToolResult.ok("")));

        ToolResult result = registry.execute(ToolCall.of("call_1", "delete_everything", "{}"));

        assertFalse(result.ok());
        assertTrue(result.content().contains("delete_everything"));
        assertTrue(result.content().contains("read_file"), "要告诉模型有哪些工具可用");
    }

    @Test
    @DisplayName("arguments 是烂 JSON：返回 error 让模型重新生成，不抛异常")
    void malformedArguments() {
        ToolRegistry registry = new ToolRegistry()
                .register(fake("echo", args -> ToolResult.ok("不该走到这")));

        ToolResult result = registry.execute(
                ToolCall.of("call_1", "echo", "{\"msg\": \"少了个右括号\""));

        assertFalse(result.ok());
        assertTrue(result.content().contains("JSON"));
    }

    @Test
    @DisplayName("arguments 是合法 JSON 但不是对象：同样兜住")
    void nonObjectArguments() {
        ToolRegistry registry = new ToolRegistry().register(fake("echo", args -> ToolResult.ok("x")));
        assertFalse(registry.execute(ToolCall.of("call_1", "echo", "[1,2,3]")).ok());
    }

    @Test
    @DisplayName("工具自己抛了异常：兜成 error，agent 循环不能挂")
    void toolThrows() {
        ToolRegistry registry = new ToolRegistry()
                .register(fake("boom", args -> { throw new RuntimeException("内部炸了"); }));

        ToolResult result = registry.execute(ToolCall.of("call_1", "boom", "{}"));

        assertFalse(result.ok());
        assertTrue(result.content().contains("内部炸了"));
    }

    @Test
    @DisplayName("参数问题（IllegalArgumentException）直接把原话给模型，不带异常类名")
    void illegalArgumentMessageIsPassedThrough() {
        ToolRegistry registry = new ToolRegistry()
                .register(fake("need_path", args -> Args.requireString(args, "path") == null
                        ? ToolResult.ok("") : ToolResult.ok("")));

        ToolResult result = registry.execute(ToolCall.of("call_1", "need_path", "{}"));

        assertFalse(result.ok());
        assertTrue(result.content().contains("缺少必填参数 path"));
        assertFalse(result.content().contains("IllegalArgumentException"),
                "异常类名对模型没用，只会占 token");
    }

    @Test
    @DisplayName("空 arguments 字符串当成空对象处理")
    void emptyArguments() {
        ToolRegistry registry = new ToolRegistry()
                .register(fake("noarg", args -> ToolResult.ok("ok")));
        assertTrue(registry.execute(ToolCall.of("call_1", "noarg", "")).ok());
    }

    @Test
    @DisplayName("specs 保持注册顺序，且内容来自各工具自己的定义")
    void specsKeepOrder() {
        ToolRegistry registry = new ToolRegistry()
                .register(fake("a", args -> ToolResult.ok("")))
                .register(fake("b", args -> ToolResult.ok("")));

        assertEquals(2, registry.specs().size());
        assertEquals("a", registry.specs().get(0).name());
        assertEquals("b", registry.specs().get(1).name());
    }
}
