package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 后台预压缩：在真正需要之前把摘要算好，到线时零调用落地。
 *
 * <p>这一组的重点是<b>生命周期</b>，不是摘要质量：什么时候起、什么时候丢、
 * 算挂了会不会把主循环带下水。
 */
class PrecomputeTest {

    private static List<ChatMessage> record(int turns) {
        List<ChatMessage> record = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            record.add(ChatMessage.user("第 " + i + " 个问题"));
            record.add(ChatMessage.assistant(null,
                    List.of(ToolCall.of("c" + i, "read_file", "{}"))));
            record.add(ChatMessage.toolResult("c" + i, "内容 ".repeat(30)));
            record.add(ChatMessage.assistant("第 " + i + " 个回答"));
        }
        return record;
    }

    private static LlmClient saying(String text) {
        return new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                return new ChatResponse("x", "f", List.of(new ChatResponse.Choice(
                        0, ChatMessage.assistant(text), "stop")), null);
            }

            public String modelName() { return "fake"; }
        };
    }

    /** 等到条件成立，最多等 5 秒——后台线程的进度没法同步等。 */
    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("等了 5 秒还没等到");
    }

    @Test
    @DisplayName("起一次就有待用件，算完之后是现货")
    void computesInBackground() {
        ContextManager context = new ContextManager(64_000);
        try (Precompute pre = new Precompute(context, () -> saying("后台算的摘要"))) {
            pre.start(record(8), List.of());

            assertTrue(pre.hasPending());
            await(pre::ready);

            var taken = pre.take();
            assertNotNull(taken);
            assertTrue(taken.summary().contains("后台算的摘要"));
            assertFalse(pre.hasPending(), "取走之后就不该还挂着");
        }
    }

    @Test
    @DisplayName("每次都现造一个客户端——和主循环共用会互相踩取消状态")
    void usesAFreshClientEveryTime() {
        AtomicInteger made = new AtomicInteger();
        ContextManager context = new ContextManager(64_000);
        try (Precompute pre = new Precompute(context, () -> {
            made.incrementAndGet();
            return saying("摘要");
        })) {
            pre.start(record(8), List.of());
            await(pre::ready);
            pre.take();
            pre.start(record(9), List.of());
            await(pre::ready);

            assertEquals(2, made.get());
        }
    }

    @Test
    @DisplayName("已经有任务在跑就不重复起——重复起只会白烧 token")
    void doesNotStartTwice() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        ContextManager context = new ContextManager(64_000);

        try (Precompute pre = new Precompute(context, () -> {
            started.incrementAndGet();
            return new LlmClient() {
                public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ChatResponse("x", "f", List.of(new ChatResponse.Choice(
                            0, ChatMessage.assistant("摘要"), "stop")), null);
                }

                public String modelName() { return "fake"; }
            };
        })) {
            pre.start(record(8), List.of());
            await(() -> started.get() == 1);
            pre.start(record(8), List.of());
            pre.start(record(8), List.of());

            assertEquals(1, started.get(), "第一个还没跑完，后面两次都该被挡掉");
            release.countDown();
        }
    }

    @Test
    @DisplayName("跌回线下就整个丢掉——它是照旧边界算的，留着只会张冠李戴")
    void discardsPendingWork() {
        ContextManager context = new ContextManager(64_000);
        try (Precompute pre = new Precompute(context, () -> saying("摘要"))) {
            pre.start(record(8), List.of());
            await(pre::ready);

            pre.discard();

            assertFalse(pre.hasPending());
            assertFalse(pre.ready());
            assertNull(pre.take(), "丢掉之后取不到东西");
        }
    }

    @Test
    @DisplayName("后台算挂了只是没现货，主循环照样能跑——到硬线时退回同步压缩")
    void backgroundFailureIsNotFatal() {
        ContextManager context = new ContextManager(64_000);
        try (Precompute pre = new Precompute(context, () -> {
            throw new LlmException("后台挂了");
        })) {
            pre.start(record(8), List.of());
            // ready() 顺手把失败的那一份清掉，所以轮询它本身就是在推进状态
            await(() -> {
                pre.ready();
                return !pre.hasPending();
            });

            assertFalse(pre.ready(), "挂了就不算现货");
            assertFalse(pre.hasPending(), "失败之后要清掉，下次还能重来");
        }
    }

    @Test
    @DisplayName("记录不够三轮时算不出东西，不能把它当现货")
    void tooShortIsNotStock() {
        ContextManager context = new ContextManager(64_000);
        try (Precompute pre = new Precompute(context, () -> saying("摘要"))) {
            pre.start(record(2), List.of());
            await(pre::settled);

            assertFalse(pre.ready(), "算出来是 null，不算现货");
            assertNull(pre.take());
        }
    }

    @Test
    @DisplayName("到硬线时可以等在途的那一份，不用把同一件事买两遍")
    void awaitsInFlightWork() {
        ContextManager context = new ContextManager(64_000);
        try (Precompute pre = new Precompute(context, () -> saying("后台算的"))) {
            pre.start(record(8), List.of());

            var taken = pre.awaitReady(5_000);

            assertNotNull(taken, "在途的那份算完就该拿得到");
            assertTrue(taken.summary().contains("后台算的"));
            assertFalse(pre.hasPending(), "拿走了就不该还挂着");
        }
    }

    @Test
    @DisplayName("等超时就返回 null 让调用方自己压，但后台那份留着——下一轮可能就用上了")
    void awaitTimesOutWithoutKillingTheTask() {
        CountDownLatch release = new CountDownLatch(1);
        ContextManager context = new ContextManager(64_000);
        try (Precompute pre = new Precompute(context, () -> new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ChatResponse("x", "f", List.of(new ChatResponse.Choice(
                        0, ChatMessage.assistant("慢摘要"), "stop")), null);
            }

            public String modelName() { return "fake"; }
        })) {
            pre.start(record(8), List.of());

            assertNull(pre.awaitReady(150), "没算完就别干等");
            assertTrue(pre.hasPending(), "超时不等于取消，那份还得留着");
            release.countDown();
        }
    }

    @Test
    @DisplayName("没有在途任务时等也是白等，立刻返回 null")
    void awaitWithNothingPending() {
        ContextManager context = new ContextManager(64_000);
        try (Precompute pre = new Precompute(context, () -> saying("x"))) {
            assertNull(pre.awaitReady(5_000));
        }
        assertNull(Precompute.disabled(new ContextManager(64_000)).awaitReady(5_000));
    }

    @Test
    @DisplayName("关掉后台的那一版什么都不做，压缩退化成到硬线才同步压")
    void disabledNeverStarts() {
        Precompute pre = Precompute.disabled(new ContextManager(64_000));

        pre.start(record(8), List.of());

        assertFalse(pre.enabled());
        assertFalse(pre.hasPending());
        assertFalse(pre.ready());
        assertNull(pre.take());
    }

    @Test
    @DisplayName("交给后台线程之前先把记录定格——主循环还在往里追加")
    void snapshotsTheRecord() {
        ContextManager context = new ContextManager(64_000);
        List<ChatMessage> live = new ArrayList<>(record(8));
        try (Precompute pre = new Precompute(context, () -> saying("摘要"))) {
            pre.start(live, List.of());
            live.clear();       // 主循环那边在动这个 list

            await(pre::ready);
            assertNotNull(pre.take(), "后台拿的是副本，不受影响");
        }
    }
}
