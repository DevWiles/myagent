package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.StreamListener;
import dev.wiles.myagent.llm.ToolSpec;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 给模型客户端装个电表。包一层之后，<b>谁发的请求都算得上</b>。
 *
 * <h3>为什么在客户端这一层计量，而不是在 Agent 里</h3>
 * 因为一次会话里发请求的不止主循环：
 *
 * <ul>
 *   <li>ReAct 主循环——每步一次</li>
 *   <li>压缩的 map / reduce——同步那条路走主客户端</li>
 *   <li><b>后台预压缩</b>——{@code Precompute} 用的是<b>另一个客户端实例</b>，
 *       整条路径压根不经过 Agent</li>
 *   <li>规划器和每个子任务的执行体——各自的客户端</li>
 * </ul>
 *
 * 在 Agent 里数，会把后台和规划器那几笔全漏掉，而那恰恰是用户最容易
 * 「不知道自己花了钱」的部分——它们不在屏幕上产生任何动静。
 * 包在客户端上，漏不掉。
 *
 * <h3>线程安全</h3>
 * 后台预压缩和主循环会<b>同时</b>记账，所以账本放在 {@link AtomicReference} 里，
 * 用 {@code updateAndGet} 更新。{@link TokenLedger} 本身是不可变的，
 * 拿到的快照不会在读的过程中变形。
 */
public final class TokenMeter {

    private final AtomicReference<TokenLedger> ledger = new AtomicReference<>(TokenLedger.EMPTY);

    /** 当前账本。返回的是不可变快照。 */
    public TokenLedger snapshot() {
        return ledger.get();
    }

    /** 清零——换一段新会话时用。 */
    public void reset() {
        ledger.set(TokenLedger.EMPTY);
    }

    /** 把存档里的旧账接上，续聊时「这次会话花了多少」才不会归零。 */
    public void seed(TokenLedger previous) {
        if (previous != null && !previous.isEmpty()) {
            ledger.updateAndGet(current -> current.plus(previous));
        }
    }

    /**
     * 包一个客户端。返回的东西行为和原来一模一样，只是每次响应都会记一笔。
     *
     * <p>传 null 返回 null——调用方常常是「没配 key 就没有客户端」，
     * 让它在这儿判空比每个调用点各判一次省事。
     */
    public LlmClient meter(LlmClient delegate) {
        return delegate == null ? null : new Metered(delegate);
    }

    private void record(ChatResponse response) {
        ledger.updateAndGet(current -> current.plus(response));
    }

    /**
     * 装饰器。
     *
     * <p>两个容易写错的地方：
     * <ol>
     *   <li><b>流式那个重载必须自己转发</b>。不重写的话会落到接口的 default 实现上，
     *       那个实现退化成非流式——用户看到的现象是「打字机效果没了」，
     *       而原因藏在一个叫「电表」的类里，没人查得到。</li>
     *   <li><b>链式方法要返回 this 而不是 delegate</b>。返回 delegate 的话，
     *       {@code client.jsonMode(true)} 之后电表就被摘掉了，
     *       规划器那条路的账会凭空消失。</li>
     * </ol>
     */
    private final class Metered implements LlmClient {

        private final LlmClient delegate;

        private Metered(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            ChatResponse response = delegate.chat(messages, tools);
            record(response);
            return response;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools,
                                 StreamListener listener) {
            ChatResponse response = delegate.chat(messages, tools, listener);
            record(response);
            return response;
        }

        @Override
        public LlmClient jsonMode(boolean on) {
            delegate.jsonMode(on);
            return this;
        }

        @Override
        public LlmClient temperature(double value) {
            delegate.temperature(value);
            return this;
        }

        @Override
        public LlmClient maxTokens(int value) {
            delegate.maxTokens(value);
            return this;
        }

        @Override
        public String modelName() {
            return delegate.modelName();
        }

        @Override
        public void cancel() {
            delegate.cancel();
        }
    }
}
