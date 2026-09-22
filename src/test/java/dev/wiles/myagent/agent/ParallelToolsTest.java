package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型一次点几个工具时的并发执行。
 *
 * <p>三条铁律，每一条破了都是协议层面的事故：
 * <ol>
 *   <li>并行安全的调用要<b>真的同时</b>执行——否则「并发支持」只是个名字</li>
 *   <li>tool 消息按点单顺序回填，一条不少——乱序还能忍，缺一条下轮请求就 400</li>
 *   <li>会动世界的调用是批的边界，绝不和任何人同时跑</li>
 * </ol>
 */
class ParallelToolsTest {

    /** 等多久算「对方没来」。真的并发时两边互相放行，微秒级就过。 */
    private static final long WAIT_SECONDS = 5;

    /** 造一个并行安全的假工具。 */
    private static Tool safeTool(String name, Function<JsonNode, ToolResult> body) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "测试用"; }
            public String parametersSchema() { return "{\"type\":\"object\"}"; }
            public ToolResult execute(JsonNode args) { return body.apply(args); }
            @Override
            public boolean parallelSafe() { return true; }
        };
    }

    @Test
    @DisplayName("连续的并行安全调用真的同时执行——两个工具互相等对方开跑，串行永远等不到")
    void parallelSafeCallsRunConcurrently() throws Exception {
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);

        ToolRegistry tools = new ToolRegistry()
                .register(safeTool("ra", args -> {
                    aStarted.countDown();
                    return awaitOrFail(bStarted, "ra 等不到 rb 开跑——它们没有同时执行");
                }))
                .register(safeTool("rb", args -> {
                    bStarted.countDown();
                    return awaitOrFail(aStarted, "rb 等不到 ra 开跑——它们没有同时执行");
                }));

        AgentTest.FakeLlm llm = new AgentTest.FakeLlm()
                .calls(null,
                        ToolCall.of("c1", "ra", "{}"),
                        ToolCall.of("c2", "rb", "{}"))
                .says("都读完了");

        assertEquals("都读完了", new Agent(llm, tools, "测试").run("同时读两个"));

        // 两个工具的结果都得是「等到了」，缺一个就说明是串行在跑
        List<ChatMessage> toolMsgs = toolMessages(llm.received.get(1));
        assertEquals(2, toolMsgs.size());
        toolMsgs.forEach(m -> assertEquals("等到了", m.content(), m.content()));
    }

    @Test
    @DisplayName("结果按点单顺序回填历史——先点的后跑完，位置也不能变")
    void resultsKeepMenuOrder() throws Exception {
        CountDownLatch secondDone = new CountDownLatch(1);
        ToolRegistry tools = new ToolRegistry()
                .register(safeTool("slow", args -> {
                    // 第一个点的偏偏最后完成
                    awaitOrFail(secondDone, "slow 没等到 fast");
                    return ToolResult.ok("慢的");
                }))
                .register(safeTool("fast", args -> {
                    ToolResult r = ToolResult.ok("快的");
                    secondDone.countDown();
                    return r;
                }));

        AgentTest.FakeLlm llm = new AgentTest.FakeLlm()
                .calls(null,
                        ToolCall.of("c1", "slow", "{}"),
                        ToolCall.of("c2", "fast", "{}"))
                .says("好了");

        new Agent(llm, tools, "测试").run("跑");

        List<ChatMessage> toolMsgs = toolMessages(llm.received.get(1));
        assertEquals(List.of("c1", "c2"),
                toolMsgs.stream().map(ChatMessage::toolCallId).toList(),
                "tool 消息的顺序必须和 tool_calls 一致");
        assertEquals("慢的", toolMsgs.get(0).content());
        assertEquals("快的", toolMsgs.get(1).content());
    }

    @Test
    @DisplayName("会动世界的调用是批的边界：写那一下，场上必须只有它自己")
    void mutatingCallsRunExclusively() {
        AtomicInteger inFlight = new AtomicInteger();
        List<String> violations = new ArrayList<>();     // 只在断言时读，写时同步
        List<String> order = new ArrayList<>();

        Function<String, Function<JsonNode, ToolResult>> body = name -> args -> {
            int now = inFlight.incrementAndGet();
            synchronized (order) {
                order.add(name);
                if (name.equals("w") && now > 1) {
                    violations.add("写工具执行时场上还有别人");
                }
            }
            try {
                Thread.sleep(10);   // 撑开一点时间窗，让「同时」有机会被看见
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return ToolResult.ok(name);
        };

        ToolRegistry tools = new ToolRegistry()
                .register(safeTool("r1", body.apply("r1")))
                .register(safeTool("r2", body.apply("r2")))
                .register(safeTool("r3", body.apply("r3")))
                .register(safeTool("r4", body.apply("r4")))
                .register(AgentTest.tool("w", body.apply("w")));   // 默认 parallelSafe=false

        AgentTest.FakeLlm llm = new AgentTest.FakeLlm()
                .calls(null,
                        ToolCall.of("c1", "r1", "{}"),
                        ToolCall.of("c2", "r2", "{}"),
                        ToolCall.of("c3", "w", "{}"),
                        ToolCall.of("c4", "r3", "{}"),
                        ToolCall.of("c5", "r4", "{}"))
                .says("完事");

        new Agent(llm, tools, "测试").run("读两个、写一个、再读两个");

        assertTrue(violations.isEmpty(), violations.toString());
        // 写是边界：前一批读完了它才动，它动完了后一批才开始
        int w = order.indexOf("w");
        assertTrue(order.indexOf("r1") < w && order.indexOf("r2") < w,
                "写工具抢在前一批读完成之前跑了: " + order);
        assertTrue(order.indexOf("r3") > w && order.indexOf("r4") > w,
                "后一批读抢在写完成之前跑了: " + order);
        // 五条 tool 消息按点单顺序一条不少
        assertEquals(List.of("c1", "c2", "c3", "c4", "c5"),
                toolMessages(llm.received.get(1)).stream()
                        .map(ChatMessage::toolCallId).toList());
    }

    @Test
    @DisplayName("并行批里的工具事件靠流水号配对——开始和结束会交错，名字配不准")
    void toolEventsPairById() throws Exception {
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        ToolRegistry tools = new ToolRegistry()
                .register(safeTool("ra", args -> {
                    aStarted.countDown();
                    return awaitOrFail(bStarted, "没同时跑");
                }))
                .register(safeTool("rb", args -> {
                    bStarted.countDown();
                    return awaitOrFail(aStarted, "没同时跑");
                }));

        AgentTest.FakeLlm llm = new AgentTest.FakeLlm()
                .calls(null, ToolCall.of("c1", "ra", "{}"), ToolCall.of("c2", "rb", "{}"))
                .says("好了");

        Map<Integer, String> started = new HashMap<>();
        List<String> mismatches = new ArrayList<>();
        Agent agent = new Agent(llm, tools, "测试");
        // 事件由 emit 串行化，这个监听器不需要自己加锁
        agent.onEvent(event -> {
            if (event instanceof AgentEvent.ToolStarted e) {
                if (started.put(e.id(), e.tool()) != null) {
                    mismatches.add("流水号 " + e.id() + " 被用了两次");
                }
            } else if (event instanceof AgentEvent.ToolFinished e) {
                if (!e.tool().equals(started.get(e.id()))) {
                    mismatches.add("结束事件 " + e.tool() + " 的流水号 " + e.id()
                            + " 配到了 " + started.get(e.id()));
                }
            }
        });
        agent.run("跑");

        assertTrue(mismatches.isEmpty(), mismatches.toString());
        assertEquals(2, started.size(), "两次调用各有一条开始事件");
    }

    // ── 超时 ────────────────────────────────────────────────────

    @Test
    @DisplayName("批里有个卡死的：它按超时算，其余的结果照收，五条消息一条不少")
    void oneStuckCallDoesNotSinkTheBatch() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ToolRegistry tools = new ToolRegistry()
                .register(safeTool("ok1", args -> ToolResult.ok("读完了 1")))
                .register(safeTool("stuck", args -> {
                    try {
                        // 卡到测试结束。超时逻辑该在这之前就把它判掉
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.ok("我终于醒了");
                }))
                .register(safeTool("ok2", args -> ToolResult.ok("读完了 2")));

        AgentTest.FakeLlm llm = new AgentTest.FakeLlm()
                .calls(null,
                        ToolCall.of("c1", "ok1", "{}"),
                        ToolCall.of("c2", "stuck", "{}"),
                        ToolCall.of("c3", "ok2", "{}"))
                .says("有一个没读成");

        Agent agent = new Agent(llm, tools, "测试")
                .withBatchTimeout(java.time.Duration.ofMillis(300));
        try {
            assertEquals("有一个没读成", agent.run("读三个"));

            List<ChatMessage> toolMsgs = toolMessages(llm.received.get(1));
            assertEquals(List.of("c1", "c2", "c3"),
                    toolMsgs.stream().map(ChatMessage::toolCallId).toList(),
                    "超时的那个也必须有一条结果，否则下一轮请求直接 400");
            assertEquals("读完了 1", toolMsgs.get(0).content());
            assertTrue(toolMsgs.get(1).content().contains("超时"), toolMsgs.get(1).content());
            assertEquals("读完了 2", toolMsgs.get(2).content(), "别人不该被它拖下水");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("超时的调用也要发一条结束事件，否则界面上那一行永远转圈")
    void timedOutCallStillEmitsFinished() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ToolRegistry tools = new ToolRegistry()
                .register(safeTool("fast", args -> ToolResult.ok("好了")))
                .register(safeTool("stuck", args -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.ok("醒了");
                }));

        AgentTest.FakeLlm llm = new AgentTest.FakeLlm()
                .calls(null, ToolCall.of("c1", "fast", "{}"), ToolCall.of("c2", "stuck", "{}"))
                .says("完事");

        List<Integer> startedIds = new ArrayList<>();
        List<Integer> finishedIds = new ArrayList<>();
        Agent agent = new Agent(llm, tools, "测试")
                .withBatchTimeout(java.time.Duration.ofMillis(300));
        agent.onEvent(event -> {
            if (event instanceof AgentEvent.ToolStarted e) {
                startedIds.add(e.id());
            } else if (event instanceof AgentEvent.ToolFinished e) {
                finishedIds.add(e.id());
            }
        });
        try {
            agent.run("跑");

            assertEquals(2, startedIds.size());
            assertEquals(java.util.Set.copyOf(startedIds), java.util.Set.copyOf(finishedIds),
                    "每条开始都得有一条对应的结束: 开始=" + startedIds + " 结束=" + finishedIds);
            assertEquals(finishedIds.size(), java.util.Set.copyOf(finishedIds).size(),
                    "同一个流水号不能发两条结束（主线程补发和工作线程迟到只能有一个作数）");
        } finally {
            release.countDown();
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────

    private static ToolResult awaitOrFail(CountDownLatch latch, String complaint) {
        try {
            return latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    ? ToolResult.ok("等到了")
                    : ToolResult.error(complaint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("被中断");
        }
    }

    private static List<ChatMessage> toolMessages(List<ChatMessage> window) {
        return window.stream().filter(m -> ChatMessage.ROLE_TOOL.equals(m.role())).toList();
    }
}
