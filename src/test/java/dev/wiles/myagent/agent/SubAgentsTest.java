package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.context.ContextManager;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 照 Codex 做的子 agent 编排：异步派出、结果以通知注入、wait 拿过的不重复送、
 * 名额上限、深度 1、send_input / close / resume、主 agent 叫停连带停、fork 上下文。
 * 一次真模型调用都不发。
 */
class SubAgentsTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** 按剧本回答的假模型，记下每次收到的消息和工具菜单。 */
    static final class Script implements LlmClient {
        final Deque<Function<List<ChatMessage>, ChatMessage>> steps = new ArrayDeque<>();
        final List<List<ChatMessage>> seen = new CopyOnWriteArrayList<>();
        final List<List<String>> menus = new CopyOnWriteArrayList<>();
        CountDownLatch blockUntilCancelled;
        Runnable onCancel = () -> { };

        Script reply(String text) {
            steps.add(m -> ChatMessage.assistant(text));
            return this;
        }

        Script call(String tool, Function<List<ChatMessage>, String> args) {
            steps.add(m -> ChatMessage.assistant("", List.of(ToolCall.of("c" + steps.size() + tool, tool, args.apply(m)))));
            return this;
        }

        Script call(String tool, String args) {
            return call(tool, m -> args);
        }

        @Override
        public synchronized ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            seen.add(List.copyOf(messages));
            menus.add(tools == null ? List.of() : tools.stream().map(ToolSpec::name).toList());
            if (blockUntilCancelled != null) {
                try {
                    blockUntilCancelled.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new LlmException("请求已取消");
            }
            if (steps.isEmpty()) {
                throw new LlmException("没词儿了");
            }
            ChatMessage reply = steps.poll().apply(messages);
            return new ChatResponse("id", "fake", List.of(new ChatResponse.Choice(
                    0, reply, reply.hasToolCalls() ? "tool_calls" : "stop")), null);
        }

        @Override
        public void cancel() {
            onCancel.run();
            if (blockUntilCancelled != null) {
                blockUntilCancelled.countDown();
            }
        }

        @Override
        public String modelName() {
            return "fake";
        }
    }

    static Tool stub(String name) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return name; }
            public String parametersSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolResult execute(JsonNode args) { return ToolResult.ok(name + " ok"); }
        };
    }

    /** 一套主 agent + 子 agent。子 agent 的模型按派出顺序从 {@code children} 里取。 */
    static final class Rig {
        final Script parentLlm = new Script();
        final Deque<Script> children = new ConcurrentLinkedDeque<>();
        final ToolRegistry tools = new ToolRegistry()
                .register(stub("read_file")).register(stub("write_file")).register(stub("bash"));
        final List<String> eventIds = new CopyOnWriteArrayList<>();
        final SubAgents subagents;
        final Agent parent;

        Rig(SubAgents.Mode mode, Script... childScripts) {
            children.addAll(List.of(childScripts));
            subagents = new SubAgents(() -> {
                Script next = children.poll();
                return next == null ? new Script() : next;
            }, tools, ContextManager::new, 0, mode, (id, e) -> eventIds.add(id));
            tools.registerAll(subagents.tools());
            parent = new Agent(parentLlm, tools, "SYSTEM-PROMPT");
            subagents.attach(parent);
        }

        Rig(Script... childScripts) {
            this(SubAgents.Mode.EXPLICIT, childScripts);
        }

        ToolResult exec(String tool, Map<String, ?> args) throws Exception {
            return tools.execute(ToolCall.of("x", tool, M.writeValueAsString(args)));
        }

        String spawn(Map<String, ?> args) throws Exception {
            ToolResult r = exec("spawn_agent", args);
            assertTrue(r.ok(), r.content());
            return M.readTree(r.content()).path("agent_id").asText();
        }
    }

    /** 上一条 tool 消息里 spawn_agent 返回的 id。 */
    static String lastAgentId(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("tool".equals(m.role()) && m.content().contains("agent_id")) {
                try {
                    return M.readTree(m.content()).path("agent_id").asText();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("没找到 agent_id");
    }

    static boolean hasNotice(List<ChatMessage> messages) {
        return messages.stream().anyMatch(m -> "user".equals(m.role()) && SubAgents.isNotice(m.content()));
    }

    static void awaitIdle(SubAgents subagents) throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (subagents.busy() && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        assertFalse(subagents.busy(), "子 agent 5 秒内没停下来");
    }

    @Test
    @DisplayName("异步：spawn 立刻返回 id；主 agent 要交卷时子 agent 还没完就等，结果以通知注入后接着干")
    void spawnIsAsyncAndResultArrivesAsNotice() {
        CountDownLatch release = new CountDownLatch(1);
        Script child = new Script();
        child.steps.add(m -> {
            try {
                release.await(5, TimeUnit.SECONDS);       // 主 agent 交卷之后才放它完成
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatMessage.assistant("改了 src/a.py，测试通过");
        });
        Rig rig = new Rig(child);
        List<ChatMessage>[] finalSeen = new List[1];
        rig.parentLlm.call("spawn_agent", "{\"message\":\"修 a.py\",\"agent_type\":\"worker\"}");
        rig.parentLlm.steps.add(m -> {
            release.countDown();                          // spawn 已经返回了，子 agent 还卡着
            return ChatMessage.assistant("我这边做完了");
        });
        rig.parentLlm.steps.add(m -> {
            finalSeen[0] = m;
            return ChatMessage.assistant("全部完成");
        });

        String text = rig.parent.run("修 bug，用子 agent 并行");

        assertEquals("全部完成", text, "子 agent 的结果送到后主 agent 又跑了一步才收口");
        ChatMessage notice = finalSeen[0].get(finalSeen[0].size() - 1);
        assertTrue(SubAgents.isNotice(notice.content()));
        assertTrue(notice.content().contains("改了 src/a.py，测试通过"));
        assertTrue(notice.content().contains("\"completed\""));
        assertTrue(rig.eventIds.stream().allMatch(id -> id.startsWith("agent-")));
    }

    @Test
    @DisplayName("子 agent：沿用主 agent 的系统提示词；任务包在 <subagent_task> 里；菜单没有派遣工具（深度 1）")
    void childSharesSystemPromptAndHasNoSpawnTools() throws Exception {
        Script child = new Script().reply("查完了");
        Rig rig = new Rig(child);
        rig.spawn(Map.of("message", "查 a", "agent_type", "explorer"));
        awaitIdle(rig.subagents);

        List<ChatMessage> sent = child.seen.get(0);
        assertEquals("SYSTEM-PROMPT", sent.get(0).content(), "系统提示词逐字相同，前缀缓存才命中");
        assertTrue(sent.get(1).content().contains("<subagent_task>"));
        assertTrue(sent.get(1).content().contains("查 a"));
        List<String> menu = child.menus.get(0);
        assertEquals(List.of("read_file", "bash"), menu, "explorer 拿不到 write_file，谁都拿不到那五个工具");
    }

    @Test
    @DisplayName("wait_agent：拿到 completed 结果；同一份结果不再以通知重复送")
    void waitReturnsResultAndSuppressesNotice() {
        // 子 agent 卡到主 agent 发起 wait 才完成：不然它在下一步请求前就交了，
        // 通知先送到、模型再去 wait——那是模型自己的选择，不是这里要测的
        CountDownLatch waiting = new CountDownLatch(1);
        Script child = new Script();
        child.steps.add(m -> {
            try {
                waiting.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatMessage.assistant("结论：在 b.py 第 3 行");
        });
        Rig rig = new Rig(child);
        List<ChatMessage>[] lastSeen = new List[1];
        String[] waited = new String[1];
        rig.parentLlm.call("spawn_agent", "{\"message\":\"查 b\"}");
        rig.parentLlm.call("wait_agent", m -> {
            waiting.countDown();
            return "{\"targets\":[\"" + lastAgentId(m) + "\"],\"timeout_ms\":20000}";
        });
        rig.parentLlm.steps.add(m -> {
            lastSeen[0] = m;
            waited[0] = m.get(m.size() - 1).content();
            return ChatMessage.assistant("好");
        });

        assertEquals("好", rig.parent.run("查"));
        assertTrue(waited[0].contains("结论：在 b.py 第 3 行"));
        assertTrue(waited[0].contains("\"timed_out\" : false"));
        assertFalse(hasNotice(lastSeen[0]), "wait 已经拿到了，不该再来一条通知");
        assertFalse(rig.subagents.hasMail());
    }

    @Test
    @DisplayName("名额：开着的最多 6 个，做完没关的也算；close 一个就能再派")
    void openAgentsAreCapped() throws Exception {
        Script[] scripts = new Script[8];
        for (int i = 0; i < scripts.length; i++) {
            scripts[i] = new Script().reply("ok" + i);
        }
        Rig rig = new Rig(scripts);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < SubAgents.MAX_THREADS; i++) {
            ids.add(rig.spawn(Map.of("message", "活 " + i)));
        }
        awaitIdle(rig.subagents);
        ToolResult seventh = rig.exec("spawn_agent", Map.of("message", "第七个"));
        assertFalse(seventh.ok());
        assertTrue(seventh.content().contains("close_agent"));

        ToolResult closed = rig.exec("close_agent", Map.of("target", ids.get(0)));
        assertTrue(closed.content().contains("\"completed\""), "返回关之前的状态");
        assertNotNull(rig.spawn(Map.of("message", "第七个")));
    }

    @Test
    @DisplayName("send_input 让做完的子 agent 带着上下文再跑一轮；关掉后不能发，resume 之后又能发")
    void sendInputCloseResume() throws Exception {
        Script child = new Script().reply("第一轮").reply("第二轮").reply("第三轮");
        Rig rig = new Rig(child);
        String id = rig.spawn(Map.of("message", "先做 A"));
        awaitIdle(rig.subagents);

        assertTrue(rig.exec("send_input", Map.of("target", id, "message", "再做 B")).ok());
        awaitIdle(rig.subagents);
        List<ChatMessage> second = child.seen.get(1);
        assertTrue(second.stream().anyMatch(m -> m.content() != null && m.content().contains("先做 A")),
                "上下文还在");
        assertTrue(second.get(second.size() - 1).content().contains("<agent_message>"));

        rig.exec("close_agent", Map.of("target", id));
        ToolResult refused = rig.exec("send_input", Map.of("target", id, "message", "C"));
        assertFalse(refused.ok());
        assertTrue(refused.content().contains("resume_agent"));

        ToolResult resumed = rig.exec("resume_agent", Map.of("id", id));
        assertTrue(resumed.content().contains("第二轮"), "还原关之前的状态");
        assertTrue(rig.exec("send_input", Map.of("target", id, "message", "C")).ok());
        awaitIdle(rig.subagents);
        assertEquals(3, child.seen.size());
    }

    @Test
    @DisplayName("主 agent 被叫停：在跑的子 agent 一起停，状态 interrupted，不发通知")
    void parentCancelInterruptsChildren() throws Exception {
        Script child = new Script();
        child.blockUntilCancelled = new CountDownLatch(1);
        Rig rig = new Rig(child);
        String id = rig.spawn(Map.of("message", "慢活"));
        assertTrue(rig.subagents.busy());

        rig.parent.cancel();
        awaitIdle(rig.subagents);

        assertFalse(rig.subagents.hasMail(), "主 agent 自己叫停的，不用通知它");
        ToolResult waited = rig.exec("wait_agent", Map.of("targets", List.of(id)));
        assertTrue(waited.content().contains("interrupted"), waited.content());
    }

    @Test
    @DisplayName("send_input(interrupt=true)：打断子 agent 当前这一轮，马上拿新指示开一轮")
    void interruptRedirectsChild() throws Exception {
        Script child = new Script();
        CountDownLatch firstCall = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        child.steps.add(m -> {
            firstCall.countDown();
            try {
                cancelled.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new LlmException("请求已取消");
        });
        child.steps.add(m -> ChatMessage.assistant("改做 B 了"));
        child.onCancel = cancelled::countDown;
        Rig rig = new Rig(child);
        String id = rig.spawn(Map.of("message", "做 A"));
        assertTrue(firstCall.await(5, TimeUnit.SECONDS));

        assertTrue(rig.exec("send_input", Map.of("target", id, "message", "别做 A 了，做 B", "interrupt", true)).ok());
        awaitIdle(rig.subagents);

        ToolResult waited = rig.exec("wait_agent", Map.of("targets", List.of(id)));
        assertTrue(waited.content().contains("改做 B 了"), waited.content());
    }

    @Test
    @DisplayName("fork_context：子 agent 拿到主对话，截在发起派遣的那条 assistant 之前（不带无主的 tool_call）")
    void forkCopiesParentConversation() {
        Script child = new Script().reply("看过前情了");
        Rig rig = new Rig(child);
        rig.parentLlm.call("read_file", "{}");
        rig.parentLlm.call("spawn_agent", "{\"message\":\"接着查\",\"fork_context\":true}");
        rig.parentLlm.call("wait_agent", m -> "{\"targets\":[\"" + lastAgentId(m) + "\"]}");
        rig.parentLlm.reply("完");

        rig.parent.run("原始需求 XYZ");

        List<ChatMessage> sent = child.seen.get(0);
        assertTrue(sent.stream().anyMatch(m -> "原始需求 XYZ".equals(m.content())));
        assertTrue(sent.stream().anyMatch(m -> "read_file ok".equals(m.content())));
        assertTrue(sent.stream().noneMatch(m -> m.hasToolCalls()
                        && m.toolCalls().stream().anyMatch(c -> c.name().equals("spawn_agent"))),
                "发起派遣的那条 assistant 不能带过去");
    }

    @Test
    @DisplayName("触发方式：默认 explicit（用户没要求不派），proactive 换成能并行就派；off 不造")
    void modeControlsSpawnGuidance() throws Exception {
        assertEquals(SubAgents.Mode.EXPLICIT, SubAgents.Mode.parse(null));
        assertEquals(SubAgents.Mode.OFF, SubAgents.Mode.parse("off"));
        assertEquals(SubAgents.Mode.PROACTIVE, SubAgents.Mode.parse("Proactive"));
        assertThrows(IllegalArgumentException.class, () -> SubAgents.Mode.parse("sometimes"));

        String explicit = new Rig(SubAgents.Mode.EXPLICIT).subagents.spawnDescription();
        String proactive = new Rig(SubAgents.Mode.PROACTIVE).subagents.spawnDescription();
        assertTrue(explicit.contains("否则不要派子 agent"));
        assertFalse(proactive.contains("否则不要派子 agent"));
        assertTrue(proactive.contains("主动委派已开启"));
        for (String d : List.of(explicit, proactive)) {
            assertTrue(d.contains("关键路径"));
            assertTrue(d.contains("写入集合互不相交"));
            assertFalse(d.contains("{{"), "占位符都要替换掉");
        }
    }
}
