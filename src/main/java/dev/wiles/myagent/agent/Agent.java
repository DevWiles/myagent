package dev.wiles.myagent.agent;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.StreamListener;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.context.HistoryRepair;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ReAct 循环——整个 agent 的心脏，但代码短得让人意外。
 *
 * <pre>
 *   用户输入
 *      ↓
 *   ┌─► 把完整历史 + 工具菜单发给模型          (Reasoning)
 *   │      ↓
 *   │   模型返回 tool_calls？
 *   │      ├─ 否 → 这就是最终答复，退出循环
 *   │      └─ 是 → 逐个执行工具               (Acting)
 *   │               ↓
 *   └────────── 把结果作为 tool 消息追加进历史  (Observation)
 * </pre>
 *
 * 和原始 ReAct 论文的区别：论文靠提示词让模型吐出 "Thought:/Action:" 文本再正则解析，
 * 我们用协议原生的 tool_calls，结构由 API 保证，不用去猜模型写了什么。
 * 保留下来的是那个骨架：想 → 做 → 看结果 → 再想。
 *
 * 循环里三处地方错了就会一路报错到下一次对话，写的时候要格外小心：
 *   1. 模型那条 assistant 消息必须**原样**进历史（连 tool_calls 一起）
 *   2. 模型点了几个工具，就必须回传几条 tool 消息，一条都不能少
 *   3. 中途失败要把历史回滚干净，不能留下「有 tool_calls 却没有结果」的残缺对话
 */
public final class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    /**
     * 单次 run 最多跑几轮。
     * 模型是可能陷进死循环的（反复读同一个文件、反复试同一条命令），
     * 没有这个上限，一次对话就能把你的额度烧光。
     */
    public static final int DEFAULT_MAX_STEPS = 25;

    private final LlmClient client;
    private final ToolRegistry tools;
    private final List<ChatMessage> history = new ArrayList<>();
    private final String systemPrompt;
    private final int maxSteps;
    private final ContextManager context;

    private Consumer<AgentEvent> listener = event -> {
    };

    /** 用户按了 Ctrl-C。volatile 是因为置位的是另一个线程。 */
    private volatile boolean cancelled;

    /** 把模型的流式增量转成 agent 事件发出去。 */
    private final StreamListener stream = new StreamListener() {
        @Override
        public void onTextDelta(String delta) {
            listener.accept(new AgentEvent.TextDelta(delta));
        }
    };

    public Agent(LlmClient client, ToolRegistry tools, String systemPrompt) {
        this(client, tools, systemPrompt, DEFAULT_MAX_STEPS);
    }

    public Agent(LlmClient client, ToolRegistry tools, String systemPrompt, int maxSteps) {
        this(client, tools, systemPrompt, maxSteps, new ContextManager());
    }

    public Agent(LlmClient client, ToolRegistry tools, String systemPrompt, int maxSteps,
                 ContextManager context) {
        this.client = client;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.maxSteps = maxSteps;
        this.context = context;
        reset();
    }

    /**
     * 中止当前这轮 run()。由监听 Ctrl-C 的线程调用。
     *
     * 两件事都要做：置标志让循环在下一个检查点退出，
     * 同时掐断正在进行的 HTTP 请求——否则主线程还要傻等模型把话说完。
     */
    public void cancel() {
        cancelled = true;
        client.cancel();
    }

    public Agent onEvent(Consumer<AgentEvent> listener) {
        this.listener = listener;
        return this;
    }

    /** 清空对话，只留 system。 */
    public void reset() {
        history.clear();
        history.add(ChatMessage.system(systemPrompt));
    }

    /**
     * 把一段存下来的对话立回来，接着往下聊。
     *
     * <h3>用的是<b>当前</b>的 system 提示词，不是存档里那条</h3>
     * 这里先 {@link #reset()} 再追加，所以第 0 条一定是本次构造时生成的那份——
     * 带着此刻最新的长期记忆索引。照搬存档里的 system 的话，
     * 上次会话之后新记的东西会<b>永远不生效</b>，而现象是「记成功了但它不知道」，
     * 属于最难查的那一类。{@code SessionSnapshot} 干脆不存 system，两头都堵上。
     *
     * <h3>先修再用</h3>
     * 存档可能停在「工具点了、结果没回来」的当口。那种残缺留在历史里，
     * 之后<b>每一次</b>请求都会 400，而且重启也不会好——存档还在，一恢复又坏。
     * 所以必须过一遍 {@link HistoryRepair}。
     *
     * @param conversation 不含 system 的对话内容
     * @return 真正恢复了多少条（可能少于传入的，残缺部分被截掉了）
     */
    public int restore(List<ChatMessage> conversation) {
        reset();
        List<ChatMessage> safe = HistoryRepair.repair(conversation);
        history.addAll(safe);
        if (conversation != null && safe.size() < conversation.size()) {
            log.info("会话恢复：{} 条里有 {} 条不完整，已截掉",
                    conversation.size(), conversation.size() - safe.size());
        }
        return safe.size();
    }

    /** 只读视图，供界面查看当前上下文。 */
    public List<ChatMessage> history() {
        return List.copyOf(history);
    }

    /** 对话内容，<b>不含</b> index 0 的 system——这是要存盘的那部分。 */
    public List<ChatMessage> conversation() {
        return history.size() <= 1 ? List.of() : List.copyOf(history.subList(1, history.size()));
    }

    /**
     * 跑一轮完整的 ReAct 循环，直到模型给出不带工具调用的答复。
     *
     * @return 模型的最终文本答复
     */
    public String run(String userInput) {
        // 记下起点：中途任何环节失败，都回滚到这里，保证历史永远是完整的一问一答
        final int snapshot = history.size();
        cancelled = false;
        history.add(ChatMessage.user(userInput));

        try {
            for (int step = 1; step <= maxSteps; step++) {
                if (cancelled) {
                    return abort(snapshot);
                }
                listener.accept(new AgentEvent.Thinking(step));

                List<ToolSpec> specs = tools.specs();
                // 压缩必须发生在请求之前、且此刻历史是自洽的（上一轮的工具结果都已回填），
                // 在循环中间压会切出无主的 tool 消息
                compactIfNeeded(specs);

                ChatResponse response = client.chat(history, specs, stream);
                ChatMessage assistant = response.message();

                // 原样入历史。这条消息里的 tool_calls 是后面 tool 消息的锚点，
                // 少了它，下一轮请求里的 tool 消息就成了无主孤儿，API 直接 400。
                history.add(assistant);

                if (!assistant.hasToolCalls()) {
                    String text = response.text();
                    listener.accept(new AgentEvent.Done(text, step));
                    return text;
                }

                // 模型常常一边说「我先看看 pom.xml」一边发起调用，这句话就是 ReAct 的 Thought
                if (assistant.content() != null && !assistant.content().isBlank()) {
                    listener.accept(new AgentEvent.Thought(assistant.content()));
                }

                // 模型可能一次点好几个工具。必须全部执行、全部回传，
                // 漏掉任何一个，那个 tool_call_id 就永远等不到结果，下轮请求必然报错。
                for (ToolCall call : assistant.toolCallsOrEmpty()) {
                    if (cancelled) {
                        return abort(snapshot);
                    }
                    listener.accept(new AgentEvent.ToolStarted(call.name(), call.argumentsJson()));

                    ToolResult result = tools.execute(call);   // 它保证不抛异常

                    listener.accept(new AgentEvent.ToolFinished(
                            call.name(), result.ok(), result.content()));
                    history.add(ChatMessage.toolResult(call.id(), result.content()));
                }
            }

            // 跑满了还没收敛。再给最后一次机会，但**不带工具**——
            // 模型没得选，只能用文字交代目前进展，循环就此收口。
            log.warn("达到最大步数 {}，强制收敛", maxSteps);
            return forceConclusion();

        } catch (LlmException e) {
            if (cancelled) {
                return abort(snapshot);      // 取消导致的请求失败，不算错误
            }
            // 回滚到本轮之前。宁可丢掉这次提问，也不能让残缺的消息序列留在历史里——
            // 那会让之后**每一次**请求都失败，整个会话报废。
            rollbackTo(snapshot);
            log.error("模型调用失败，已回滚本轮对话", e);
            listener.accept(new AgentEvent.Failed(e.getMessage()));
            return "调用模型失败: " + e.getMessage();
        }
    }

    /** 估算当前上下文占用（含每轮都要重发的工具菜单）。 */
    public int estimatedTokens() {
        return context.estimate(history, tools.specs());
    }

    /** 上下文窗口上限。 */
    public int contextLimit() {
        return context.maxTokens();
    }

    private void compactIfNeeded(List<ToolSpec> specs) {
        if (!context.needsCompaction(history, specs)) {
            return;
        }
        ContextManager.Result result = context.compact(history, specs, client);
        history.clear();
        history.addAll(result.messages());
        listener.accept(new AgentEvent.Compacted(
                result.tokensBefore(), result.tokensAfter(), result.summarized()));
    }

    /**
     * 被取消时的收尾。
     *
     * 必须回滚：取消很可能发生在「assistant 已经要求调工具、但结果还没回填」的当口，
     * 这种残缺序列留在历史里会让之后每一次请求都 400。
     */
    private String abort(int snapshot) {
        rollbackTo(snapshot);
        listener.accept(new AgentEvent.Failed("已取消"));
        return "已取消。";
    }

    /** 不给工具再问一次，逼模型输出结论。 */
    private String forceConclusion() {
        history.add(ChatMessage.user(
                "已达到本轮最大工具调用次数，请不要再调用工具，直接用文字说明当前进展和还差什么。"));
        try {
            ChatResponse response = client.chat(history, null, stream);   // tools 传 null = 没得调
            history.add(response.message());
            // 极少数情况下模型即使没有工具可用也不给文字，兜一句免得返回空答复
            String text = response.text().isBlank() ? "(模型未给出文字结论)" : response.text();
            listener.accept(new AgentEvent.Failed("达到最大步数 " + maxSteps + "，已强制收尾"));
            return text + "\n\n(已达到最大步数 " + maxSteps + " 限制，任务可能未完成)";
        } catch (LlmException e) {
            listener.accept(new AgentEvent.Failed(e.getMessage()));
            return "达到最大步数 " + maxSteps + "，且收尾请求失败: " + e.getMessage();
        }
    }

    private void rollbackTo(int size) {
        while (history.size() > size) {
            history.remove(history.size() - 1);
        }
    }
}
