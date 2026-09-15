package dev.wiles.myagent.agent;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolCall;
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

    private Consumer<AgentEvent> listener = event -> {
    };

    public Agent(LlmClient client, ToolRegistry tools, String systemPrompt) {
        this(client, tools, systemPrompt, DEFAULT_MAX_STEPS);
    }

    public Agent(LlmClient client, ToolRegistry tools, String systemPrompt, int maxSteps) {
        this.client = client;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.maxSteps = maxSteps;
        reset();
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

    /** 只读视图，供界面查看当前上下文。 */
    public List<ChatMessage> history() {
        return List.copyOf(history);
    }

    /**
     * 跑一轮完整的 ReAct 循环，直到模型给出不带工具调用的答复。
     *
     * @return 模型的最终文本答复
     */
    public String run(String userInput) {
        // 记下起点：中途任何环节失败，都回滚到这里，保证历史永远是完整的一问一答
        final int snapshot = history.size();
        history.add(ChatMessage.user(userInput));

        try {
            for (int step = 1; step <= maxSteps; step++) {
                listener.accept(new AgentEvent.Thinking(step));

                ChatResponse response = client.chat(history, tools.specs());
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
            // 回滚到本轮之前。宁可丢掉这次提问，也不能让残缺的消息序列留在历史里——
            // 那会让之后**每一次**请求都失败，整个会话报废。
            rollbackTo(snapshot);
            log.error("模型调用失败，已回滚本轮对话", e);
            listener.accept(new AgentEvent.Failed(e.getMessage()));
            return "调用模型失败: " + e.getMessage();
        }
    }

    /** 不给工具再问一次，逼模型输出结论。 */
    private String forceConclusion() {
        history.add(ChatMessage.user(
                "已达到本轮最大工具调用次数，请不要再调用工具，直接用文字说明当前进展和还差什么。"));
        try {
            ChatResponse response = client.chat(history, null);   // tools 传 null = 没得调
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
