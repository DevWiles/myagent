package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 派子 agent 的四条规矩，每条一个测试，一次模型调用都不发：
 * 菜单按白名单筛（写不了文件、派不了孙子）、只回结论不回过程、
 * 没收口要标明是半截、主 agent 叫停时它跟着停。
 */
class DelegateToolTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Path ROOT = Path.of("/tmp/ws");

    /** 按剧本回答的假模型，记下每次收到的工具菜单。 */
    static final class FakeLlm implements LlmClient {
        final Deque<Function<List<ChatMessage>, ChatMessage>> script = new ArrayDeque<>();
        final List<List<String>> toolMenus = new ArrayList<>();
        volatile boolean cancelled;
        CountDownLatch blockUntilCancelled;

        FakeLlm reply(String text) {
            script.add(m -> ChatMessage.assistant(text));
            return this;
        }

        FakeLlm call(String tool, String argsJson) {
            script.add(m -> ChatMessage.assistant("", List.of(ToolCall.of("c" + script.size(), tool, argsJson))));
            return this;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            toolMenus.add(tools == null ? List.of() : tools.stream().map(ToolSpec::name).toList());
            if (blockUntilCancelled != null) {
                try {
                    blockUntilCancelled.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new LlmException("请求已取消");
            }
            if (script.isEmpty()) {
                throw new LlmException("没词儿了");
            }
            ChatMessage reply = script.poll().apply(messages);
            return new ChatResponse("id", "fake", List.of(new ChatResponse.Choice(
                    0, reply, reply.hasToolCalls() ? "tool_calls" : "stop")), null);
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (blockUntilCancelled != null) {
                blockUntilCancelled.countDown();
            }
        }

        @Override
        public String modelName() {
            return "fake";
        }
    }

    /** 名字固定、输出固定的桩工具。 */
    static Tool stub(String name, String output) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return name; }
            public String parametersSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolResult execute(JsonNode args) { return ToolResult.ok(output); }
            public boolean parallelSafe() { return !name.equals("bash") && !name.equals("write_file"); }
        };
    }

    /** 主 agent 的全量注册表：读、写、bash、grep，外加 delegate 自己。 */
    private static ToolRegistry fullSet(DelegateTool delegate) {
        ToolRegistry r = new ToolRegistry()
                .register(stub("read_file", "SECRET-FILE-CONTENT"))
                .register(stub("write_file", "written"))
                .register(stub("grep", "a.txt:3: hit"))
                .register(stub("bash", "ran"));
        if (delegate != null) {
            r.register(delegate);
        }
        return r;
    }

    private static JsonNode args(String type, String task) throws Exception {
        return M.readTree(M.writeValueAsString(java.util.Map.of("type", type, "task", task)));
    }

    @Test
    @DisplayName("菜单按白名单筛：explore 只读；verify 多个 bash；谁都拿不到 write_file 和 delegate")
    void narrowsToolsByKind() throws Exception {
        FakeLlm llm = new FakeLlm().reply("查完了").reply("跑完了");
        ToolRegistry registry = new ToolRegistry();
        DelegateTool delegate = new DelegateTool(() -> llm, registry, ROOT, null);
        fullSet(delegate).all().forEach(registry::register);

        delegate.execute(args("explore", "查 a"));
        delegate.execute(args("verify", "跑测试"));

        assertEquals(List.of("read_file", "grep"), llm.toolMenus.get(0));
        assertEquals(List.of("read_file", "grep", "bash"), llm.toolMenus.get(1));
        for (List<String> menu : llm.toolMenus) {
            assertFalse(menu.contains("write_file"), "子 agent 不能改文件");
            assertFalse(menu.contains("delegate"), "子 agent 派不了孙子");
        }
    }

    @Test
    @DisplayName("只回结论：子 agent 读到的文件内容留在它自己那边，主 agent 只拿到最后那段话；事件带 delegate-N 的号")
    void returnsOnlyTheConclusion() throws Exception {
        FakeLlm llm = new FakeLlm().call("read_file", "{}").reply("结论：配置在 a.txt:3，用的是环境变量");
        List<String> eventIds = new CopyOnWriteArrayList<>();
        DelegateTool delegate = new DelegateTool(() -> llm, fullSet(null), ROOT, (id, e) -> eventIds.add(id));

        ToolResult result = delegate.execute(args("explore", "查配置在哪"));

        assertTrue(result.ok(), result.content());
        assertTrue(result.content().contains("结论：配置在 a.txt:3"));
        assertFalse(result.content().contains("SECRET-FILE-CONTENT"), "过程不回传");
        assertTrue(result.content().startsWith("[子 agent delegate-"), "标明是子 agent 的汇报，不是指令");
        assertFalse(eventIds.isEmpty());
        assertTrue(eventIds.stream().allMatch(id -> id.startsWith("delegate-")));
        assertEquals(1, eventIds.stream().distinct().count(), "一次派遣一个号");
    }

    @Test
    @DisplayName("type 不认、task 为空：当场拒绝，不派")
    void rejectsBadArguments() throws Exception {
        FakeLlm llm = new FakeLlm();
        DelegateTool delegate = new DelegateTool(() -> llm, fullSet(null), ROOT, null);

        assertFalse(delegate.execute(args("swarm", "x")).ok());
        // 空白的 task 和别的工具缺参数一个待遇：抛 IAE，由注册表翻成给模型看的错误
        assertThrows(IllegalArgumentException.class, () -> delegate.execute(args("explore", "  ")));
        assertTrue(llm.toolMenus.isEmpty(), "一次请求都没发");
    }

    @Test
    @DisplayName("结论太长就截断，并说明截了多少")
    void clipsLongResults() throws Exception {
        FakeLlm llm = new FakeLlm().reply("x".repeat(DelegateTool.MAX_RESULT_CHARS + 500));
        DelegateTool delegate = new DelegateTool(() -> llm, fullSet(null), ROOT, null);

        ToolResult result = delegate.execute(args("explore", "查"));

        assertTrue(result.ok());
        assertTrue(result.content().contains("截掉了"));
        assertTrue(result.content().length() < DelegateTool.MAX_RESULT_CHARS + 300);
    }

    @Test
    @DisplayName("子 agent 没收口（模型挂了）：按失败回报，带上它到此为止的产出")
    void reportsFailureAsError() throws Exception {
        FakeLlm llm = new FakeLlm();      // 剧本是空的：第一次请求就抛
        DelegateTool delegate = new DelegateTool(() -> llm, fullSet(null), ROOT, null);

        ToolResult result = delegate.execute(args("verify", "跑测试"));

        assertFalse(result.ok());
        assertTrue(result.content().contains("没能收口"), result.content());
    }

    @Test
    @DisplayName("主 agent 叫停（打断工具线程）：子 agent 的模型请求跟着被掐，结果是「已取消」")
    void cancelFollowsInterrupt() throws Exception {
        FakeLlm llm = new FakeLlm();
        llm.blockUntilCancelled = new CountDownLatch(1);
        DelegateTool delegate = new DelegateTool(() -> llm, fullSet(null), ROOT, null);
        ToolResult[] out = new ToolResult[1];
        Thread toolThread = new Thread(() -> {
            try {
                out[0] = delegate.execute(args("explore", "查"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        toolThread.start();
        // 等子 agent 真的卡在请求上，再像 Agent.cancel() 那样打断工具线程
        long deadline = System.currentTimeMillis() + 5_000;
        while (llm.toolMenus.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        toolThread.interrupt();
        toolThread.join(10_000);

        assertFalse(toolThread.isAlive(), "叫停后要能收口");
        assertTrue(llm.cancelled, "子 agent 的客户端被 cancel 了，不是干等 HTTP 超时");
        assertNotNull(out[0]);
        assertFalse(out[0].ok());
        assertTrue(out[0].content().contains("已取消"), out[0].content());
    }

    @Test
    @DisplayName("子 agent 的系统提示词：身份/语言/工作区 + 子 agent 规矩 + 这一类的角色规矩，不带记忆索引")
    void systemPromptIsFreshAndRoleSpecific() {
        DelegateTool delegate = new DelegateTool(() -> new FakeLlm(), fullSet(null), ROOT, null);

        String explore = delegate.systemPrompt(DelegateTool.EXPLORE);
        assertTrue(explore.contains("## 工作区") && explore.contains(ROOT.toString()));
        assertTrue(explore.contains("看不到主 agent 的对话"));
        assertTrue(explore.contains("grep 定位"), "带着 explorer 的规矩");
        assertFalse(explore.contains("记住的事"), "子 agent 没有 recall，别提记忆");

        String verify = delegate.systemPrompt(DelegateTool.VERIFY);
        assertTrue(verify.contains("不要改工作区里的任何文件"));
    }
}
