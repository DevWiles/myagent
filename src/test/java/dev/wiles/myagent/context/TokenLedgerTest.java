package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.StreamListener;
import dev.wiles.myagent.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token 账本与电表。
 *
 * <p>这一组要守住的是一句话：<b>账要算全，算不全就得说出来</b>。
 * 用户会拿这个数去判断「我是不是花超了」，宁可承认不完整，也不能给个精确的假数。
 */
class TokenLedgerTest {

    private static ChatResponse response(Integer prompt, Integer completion) {
        ChatResponse.Usage usage = prompt == null ? null
                : new ChatResponse.Usage(prompt, completion, prompt + completion);
        return new ChatResponse("id", "fake",
                List.of(new ChatResponse.Choice(0, ChatMessage.assistant("答"), "stop")), usage);
    }

    private static LlmClient fake(Integer prompt, Integer completion) {
        return new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                return response(prompt, completion);
            }

            public String modelName() { return "fake"; }
        };
    }

    @Test
    @DisplayName("累加实测用量")
    void accumulates() {
        TokenLedger ledger = TokenLedger.EMPTY
                .plus(response(100, 20))
                .plus(response(250, 30));

        assertEquals(350, ledger.promptTokens());
        assertEquals(50, ledger.completionTokens());
        assertEquals(400, ledger.total());
        assertEquals(2, ledger.requests());
        assertTrue(ledger.isComplete());
    }

    @Test
    @DisplayName("没有 usage 的响应只记「缺了一笔」，绝不拿估算值去凑")
    void missingUsageIsAdmitted() {
        TokenLedger ledger = TokenLedger.EMPTY.plus(response(100, 20)).plus(response(null, null));

        assertEquals(100, ledger.promptTokens(), "不能替没给数的那次编一个");
        assertEquals(2, ledger.requests());
        assertEquals(1, ledger.missingUsage());
        assertFalse(ledger.isComplete(), "界面得知道这个数是低报的");
    }

    @Test
    @DisplayName("平均提示词长度比总量更能说明问题")
    void averageTellsContextSize() {
        // 总量涨只说明聊得久；平均涨才说明上下文在膨胀
        TokenLedger longChat = TokenLedger.EMPTY
                .plus(response(100, 10)).plus(response(100, 10)).plus(response(100, 10));
        TokenLedger fatContext = TokenLedger.EMPTY.plus(response(300, 30));

        assertEquals(fatContext.promptTokens(), longChat.promptTokens(), "总量一样");
        assertEquals(100, longChat.averagePrompt());
        assertEquals(300, fatContext.averagePrompt(), "但单次上下文差三倍");
        assertEquals(0, TokenLedger.EMPTY.averagePrompt(), "别除以零");
    }

    @Test
    @DisplayName("续聊时把存档里的旧账接上")
    void seedsFromArchive() {
        TokenMeter meter = new TokenMeter();
        meter.seed(new TokenLedger(1_000, 200, 5, 1));
        meter.meter(fake(100, 20)).chat(List.of(ChatMessage.user("问")), null);

        TokenLedger now = meter.snapshot();
        assertEquals(1_100, now.promptTokens());
        assertEquals(6, now.requests());
        assertEquals(1, now.missingUsage(), "旧账里的缺口也要接过来");
    }

    @Test
    @DisplayName("电表包一层之后，谁发的请求都算得上")
    void metersEveryCaller() {
        TokenMeter meter = new TokenMeter();
        LlmClient main = meter.meter(fake(100, 10));
        LlmClient background = meter.meter(fake(50, 5));   // 后台预压缩用的是另一个实例

        main.chat(List.of(ChatMessage.user("问")), null);
        background.chat(List.of(ChatMessage.user("摘要")), null);

        assertEquals(150, meter.snapshot().promptTokens(),
                "后台那条路不经过 Agent，在客户端这层包才漏不掉");
        assertEquals(2, meter.snapshot().requests());
    }

    @Test
    @DisplayName("流式那个重载必须自己转发，不能落到接口的 default 上")
    void keepsStreaming() {
        AtomicInteger deltas = new AtomicInteger();
        LlmClient streaming = new LlmClient() {
            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t) {
                fail("包了电表之后不该退化成非流式——打字机效果会没");
                return null;
            }

            public ChatResponse chat(List<ChatMessage> m, List<ToolSpec> t, StreamListener l) {
                l.onTextDelta("一");
                l.onTextDelta("二");
                return response(80, 8);
            }

            public String modelName() { return "fake"; }
        };

        TokenMeter meter = new TokenMeter();
        meter.meter(streaming).chat(List.of(ChatMessage.user("问")), null,
                new StreamListener() {
                    public void onTextDelta(String d) {
                        deltas.incrementAndGet();
                    }
                });

        assertEquals(2, deltas.get());
        assertEquals(80, meter.snapshot().promptTokens());
    }

    @Test
    @DisplayName("链式方法返回的还是带电表的那个，不能把电表摘掉")
    void chainingKeepsTheMeter() {
        TokenMeter meter = new TokenMeter();
        LlmClient client = meter.meter(fake(70, 7));

        client.jsonMode(true).temperature(0.2).maxTokens(100)
                .chat(List.of(ChatMessage.user("问")), null);

        assertEquals(70, meter.snapshot().promptTokens(),
                "返回 delegate 的话，规划器那条路的账会凭空消失");
    }

    @Test
    @DisplayName("主循环和后台同时记账也不会丢")
    void isThreadSafe() throws Exception {
        TokenMeter meter = new TokenMeter();
        LlmClient client = meter.meter(fake(10, 1));
        int threads = 8, each = 50;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await(5, TimeUnit.SECONDS);
                    for (int j = 0; j < each; j++) {
                        client.chat(List.of(ChatMessage.user("问")), null);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        go.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(threads * each, meter.snapshot().requests());
        assertEquals(threads * each * 10L, meter.snapshot().promptTokens());
    }

    @Test
    @DisplayName("清零之后从头算")
    void resets() {
        TokenMeter meter = new TokenMeter();
        meter.meter(fake(100, 10)).chat(List.of(ChatMessage.user("问")), null);
        meter.reset();

        assertTrue(meter.snapshot().isEmpty());
        assertEquals(0, meter.snapshot().promptTokens());
    }

    @Test
    @DisplayName("包 null 得到 null——调用方常常是「没配 key 就没有客户端」")
    void meteringNullIsNull() {
        assertNull(new TokenMeter().meter(null));
    }
}
