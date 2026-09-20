package dev.wiles.myagent.agent;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.StreamListener;
import dev.wiles.myagent.llm.ToolCall;
import dev.wiles.myagent.llm.ToolSpec;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.context.ContextWindow;
import dev.wiles.myagent.context.HistoryRepair;
import dev.wiles.myagent.context.PinnedFacts;
import dev.wiles.myagent.context.Precompute;
import dev.wiles.myagent.context.SummarySegment;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /**
     * 到了硬线、且后台正在算时，最多等它多久。
     *
     * <p>等超了就自己同步压一遍，后台那份留着——说不定下一轮就用上了。
     * 取 20 秒是因为一块摘要实测一两秒，几块也就几秒；再久多半是网络出了问题，
     * 那时候继续干等还不如自己去撞一次，好歹能拿到明确的错误。
     */
    private static final long FORCE_WAIT_MILLIS = 20_000;

    private final LlmClient client;
    private final ToolRegistry tools;
    /**
     * 会话记录：{@code [system, ...对话]}，<b>只追加</b>。
     *
     * <p>压缩<b>不再改写它</b>——这是这一版最重要的改动。压缩的产物是派生物
     * （{@link #segments} 加 {@link #summary}），每次请求由 {@link ContextManager#assemble}
     * 现场排版成窗口。原文永远在：存盘存的是它，{@code /resume} 列表里的首句取自它，
     * 调大 {@code contextTokens} 之后重新组装一次，细节就回来了。
     */
    private final List<ChatMessage> history = new ArrayList<>();
    private final String systemPrompt;
    private final int maxSteps;
    private final ContextManager context;

    /** 已落地的摘要段。下标是<b>记录</b>（不含 system）的下标。 */
    private List<SummarySegment> segments = List.of();
    /** 已落地的 reduce 产物。null 表示还没压过。 */
    private String summary;
    /** 摘要覆盖到记录的哪个下标（开区间）。窗口的尾巴从这里开始。 */
    private int coversUpTo;
    /** 哪些工具算「改了文件」，用来算钉住块。由外面传——context 包不该认识工具。 */
    private Set<String> writingTools = Set.of();
    /** 后台预压缩。默认是关掉的那一版，行为退化成「到硬线才同步压」。 */
    private Precompute precompute;

    /**
     * 估算值到实测值的换算系数。
     *
     * <p>{@link dev.wiles.myagent.context.TokenEstimator} 自述偏差 10~20%，而三条线里
     * 最紧的一条在 98.7%——拿估算值去卡那个位置，估低 15% 就是实际 107%，请求直接失败。
     * 所以每次响应回来都用真实的 {@code prompt_tokens} 除以我们对同一份窗口的估算值，
     * 把系数校准一次，下一轮判线时乘上去。
     *
     * <p>网关不认 {@code stream_options} 时 usage 一直是 null，系数保持 1.0，
     * {@link #measured} 也就一直是 false，三条线自动退到保守档。
     */
    private double calibration = 1.0;
    /** 至少拿到过一次真实 token 数了吗。false 时三条线走保守档。 */
    private boolean measured;

    /**
     * 「工具输出被裁了」这件事报过了吗。
     *
     * <p>降级阶梯每一轮都会跑，但事件只该发一次——每轮都发就成了刷屏。
     * 落地一次新摘要之后重置：那是新的一段，值得再报一次。
     */
    private boolean trimReported;

    private Consumer<AgentEvent> listener = event -> {
    };

    /** 用户按了 Ctrl-C。volatile 是因为置位的是另一个线程。 */
    private volatile boolean cancelled;

    /**
     * 上一轮断在半路的记录，没断就是 null。
     *
     * <p>断点本身留在历史里（见 {@link Interruption}），这个字段只是让界面
     * 知道该不该提醒一句。下一轮正常跑完就清掉——那时候「上一轮没做完」已经不成立了。
     */
    private Interruption unfinished;

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
        this.precompute = Precompute.disabled(context);
        reset();
    }

    /**
     * 接上后台预压缩。不接的话压缩只会在硬线上同步发生（能用，只是会卡一下）。
     *
     * <p>传进来的 {@link Precompute} 必须持有<b>独立的</b>客户端工厂，
     * 理由见那个类的说明——和主循环共用一个 {@code DeepSeekClient} 会互相踩取消状态。
     */
    public Agent withPrecompute(Precompute precompute) {
        this.precompute = precompute == null ? Precompute.disabled(context) : precompute;
        return this;
    }

    /** 告诉它哪些工具算「改了文件」，钉住块要用。 */
    public Agent withWritingTools(Set<String> writingTools) {
        this.writingTools = writingTools == null ? Set.of() : Set.copyOf(writingTools);
        return this;
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

    /** 清空对话，只留 system。压缩的派生物也一并清掉——它们是上一段会话的。 */
    public void reset() {
        history.clear();
        history.add(ChatMessage.system(systemPrompt));
        unfinished = null;
        segments = List.of();
        summary = null;
        coversUpTo = 0;
        trimReported = false;
        calibration = 1.0;
        measured = false;
        if (precompute != null) {
            precompute.discard();
        }
    }

    /**
     * 上一轮有没有断在半路。
     *
     * <p>界面靠它决定要不要提醒「说声继续就接着做」。不提醒的话，用户看到一句
     * 「调用模型失败」会以为这一轮白干了，而现场其实还在历史里。
     */
    public Optional<Interruption> unfinished() {
        return Optional.ofNullable(unfinished);
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
     * <h3>封口，而不是截断</h3>
     * 存档可能停在「工具点了、结果没回来」的当口（{@code kill -9} 的典型现场）。
     * 那种残缺留在记录里，之后<b>每一次</b>请求都会 400，重启也不会好——
     * 存档还在，一恢复又坏。所以必须处理。
     *
     * <p>处理方式是 {@link HistoryRepair#seal} 而不是 {@code repair}：
     * 前者给没回来的工具补一条「(中断，这一步没有执行)」，后者直接把那一轮切掉。
     * 补齐同样满足「每个 tool_call 都有结果」这条硬约束，而且<b>留得住现场</b>——
     * 断电前读过哪三个文件、改了哪两处，用户说一句「继续」就能接上。
     *
     * <p>这一条对新的记录模型尤其要紧：记录是只追加的，截掉的东西<b>再也回不来</b>，
     * 而存盘存的正是这份记录。截断等于把用户的历史永久删掉一段。
     *
     * <h3>压缩状态要整份接回来</h3>
     * 只接段、不接 {@code summary} 和 {@code coversUpTo} 是不够的（原先就是这样）：
     * 那样恢复之后 {@link #hasCompacted()} 报 false、钉住块不生成、尾巴从 0 起，
     * 整段历史原样进窗口，下一轮直接判到 {@code FORCE}，在主线程同步重跑一次 reduce。
     * 段是从原文 map 出来的所以不用重算，但 reduce 那一次是<b>白花的</b>，
     * 而且卡在用户刚接回会话、最想立刻干活的那一刻。
     *
     * @param conversation 不含 system 的对话内容
     * @param known        存档里带回来的压缩状态；下标要对得上这份 conversation
     * @return 真正恢复了多少条
     */
    public int restore(List<ChatMessage> conversation, ContextManager.Compaction known) {
        reset();        // 顺带清掉 unfinished：立起来的是另一段历史，上一段的断点不作数
        if (conversation == null || conversation.isEmpty()) {
            return 0;
        }
        List<ChatMessage> safe = HistoryRepair.isIntact(conversation)
                ? List.copyOf(conversation)
                : HistoryRepair.seal(conversation, "上次会话中断");
        history.addAll(safe);
        if (safe.size() != conversation.size()) {
            log.info("会话恢复：{} 条封口成 {} 条", conversation.size(), safe.size());
        }
        // 只在下标对得上时才认。对不上说明存档被改过，那时候摘要指的是别的消息，
        // 留着比丢掉危险——丢了大不了重算一次，认错了就是张冠李戴。
        // 注意封口可能改变长度：补占位会变长（下标不受影响），退回截断会变短（可能越界）
        int size = history.size() - 1;
        if (known == null) {
            return safe.size();
        }
        segments = known.segments().stream().filter(seg -> seg.fitsIn(size)).toList();
        if (known.summary() != null && known.coversUpTo() > 0 && known.coversUpTo() <= size) {
            summary = known.summary();
            coversUpTo = known.coversUpTo();
        } else if (known.summary() != null) {
            log.warn("存档里的摘要覆盖到 {}，而恢复出来只有 {} 条，整份丢弃（段保留，下次重算 reduce）",
                    known.coversUpTo(), size);
        }
        return safe.size();
    }

    /** 不带压缩状态的恢复。 */
    public int restore(List<ChatMessage> conversation) {
        return restore(conversation, null);
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
        cancelled = false;
        history.add(ChatMessage.user(userInput));
        // 断的时候要知道跑到第几步了，所以 step 活在 try 外面
        int step = 0;

        try {
            for (step = 1; step <= maxSteps; step++) {
                if (cancelled) {
                    return abort(userInput, step);
                }
                listener.accept(new AgentEvent.Thinking(step));

                List<ToolSpec> specs = tools.specs();
                // 窗口在请求之前现场组装。此刻记录是自洽的（上一轮的工具结果都已回填），
                // 所以尾巴从哪一条 user 消息切都不会留下无主的 tool 消息
                ContextManager.Result prepared = prepareWindow(specs);
                if (context.exceedsHardLimit(prepared.window(), specs)) {
                    return tooBigToSend(prepared, specs, userInput, step);
                }

                ChatResponse response = client.chat(prepared.window(), specs, stream);
                calibrate(response, prepared.tokensAfter());
                ChatMessage assistant = response.message();

                // 原样入历史。这条消息里的 tool_calls 是后面 tool 消息的锚点，
                // 少了它，下一轮请求里的 tool 消息就成了无主孤儿，API 直接 400。
                history.add(assistant);

                if (!assistant.hasToolCalls()) {
                    String text = response.text();
                    unfinished = null;   // 这一轮走完了，「上一轮没做完」不再成立
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
                        return abort(userInput, step);
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
            return forceConclusion(userInput);

        } catch (LlmException e) {
            if (cancelled) {
                return abort(userInput, step);   // 取消导致的请求失败，不算错误
            }
            // 不回滚，封口。残缺的消息序列绝不能留（之后每一次请求都会 400），
            // 但「截掉」和「补齐」都能做到自洽，而补齐还留得住现场：
            // 断网前读过哪些文件、改了哪两处、卡在哪一步，用户说一句「继续」就能接上。
            // 回滚的代价是用户以为这一轮白干了，实际上白干的只是最后那个请求。
            seal("网络或模型调用失败（" + brief(e.getMessage()) + "）", userInput, step);
            log.error("模型调用失败，已把现场封口留在历史里", e);
            listener.accept(new AgentEvent.Failed(e.getMessage()));
            return "调用模型失败: " + e.getMessage();
        }
    }

    /** 当前窗口占用（含每轮都要重发的工具菜单）。拿到过实测值就按实测校准。 */
    public int estimatedTokens() {
        return calibrated(buildWindow(tools.specs()).tokensAfter());
    }

    /** 上下文窗口上限。 */
    public int contextLimit() {
        return context.maxTokens();
    }

    /** 这个占用数是实测的还是估的——界面可以据此决定要不要标注。 */
    public boolean tokensAreMeasured() {
        return measured;
    }

    /** 已落地的摘要段。 */
    public List<SummarySegment> segments() {
        return segments;
    }

    /**
     * 已落地的压缩状态，存盘要用。
     *
     * <p>三个字段一起给，不让调用方只存一部分——只存段的话，
     * 恢复之后要在主线程白跑一次 reduce，见 {@link #restore}。
     */
    public ContextManager.Compaction compaction() {
        return new ContextManager.Compaction(segments, summary, coversUpTo);
    }

    /** 这段会话压过了吗——界面用它把「还没聊够」和「已经压到头了」说清楚。 */
    public boolean hasCompacted() {
        return summary != null;
    }

    /**
     * 立刻压缩一次，不管占用到没到线。{@code /compact} 走这条路。
     *
     * <p>有现货就用现货（零调用），没有就同步算。记录长度还不够三轮时什么都不做。
     *
     * @return 压了返回结果，没压返回 null
     */
    public ContextManager.Result compactNow() {
        List<ToolSpec> specs = tools.specs();
        ContextManager.Result before = buildWindow(specs);
        ContextManager.Compaction ready = precompute.ready() ? precompute.take()
                : canAdvance() ? context.compute(conversation(), segments, client) : null;
        // 过期货必须在这儿就拦掉，不能交给 land 去默默拒绝：那样 /compact 会一声不吭
        // 地报成功，用户看不到任何变化，也不知道为什么
        return ready == null || !advances(ready) ? null : land(ready, before, specs);
    }

    /**
     * 记录 + 已落地的派生物 → 这一次要发出去的窗口。纯本地，不发调用。
     *
     * <h3>钉住块只在压过之后才出现</h3>
     * 没压缩过的时候，「最初的要求」就明明白白躺在尾巴的第一条里，
     * 再钉一遍是白花钱，还把每一轮请求都多撑一条消息。
     * 钉住块要防的是<b>原文已经被摘要顶替掉</b>之后那个信息丢失——
     * 所以它的触发条件就是「确实有东西被压掉了」。
     */
    private ContextManager.Result buildWindow(List<ToolSpec> specs) {
        PinnedFacts pinned = coversUpTo > 0 ? PinnedFacts.of(conversation(), writingTools) : null;
        return context.assemble(history.isEmpty() ? null : history.get(0),
                pinned, summary, conversation(), coversUpTo, specs);
    }

    /**
     * 组装窗口，顺便按三条线决定要不要压。
     *
     * <ul>
     *   <li>{@code STEADY}：占用跌回线下，把没落地的待用件整个丢掉——
     *       它是照旧边界算的，留着只会张冠李戴</li>
     *   <li>{@code PRECOMPUTE}：后台开始算，<b>只算不换</b></li>
     *   <li>{@code APPLY}：<b>有现货才落地</b>；没有就接着跑，别为压缩把用户卡住</li>
     *   <li>{@code FORCE}：没现货也得压，此时才允许卡顿</li>
     * </ul>
     */
    private ContextManager.Result prepareWindow(List<ToolSpec> specs) {
        ContextManager.Result result = buildWindow(specs);
        // 判线用**不裁**的那个数：阶梯每轮都会把实发窗口压回软目标附近，
        // 拿裁后的数判线，占用率永远到不了 80%，摘要就一次都不会自动发生
        ContextManager.Stage stage = context.stageFor(calibrated(result.tokensRaw()), measured);
        // 这一行是三条线唯一的观测口。没有它，「80% 到底有没有起后台预压缩」
        // 只能靠猜——占用率、用的哪档阈值、判出什么，都得能对得上
        log.debug("压力 {}/{} ({}) -> {}，实发 {}", calibrated(result.tokensRaw()),
                context.maxTokens(), measured ? "实测" : "估算", stage,
                calibrated(result.tokensAfter()));

        switch (stage) {
            case STEADY -> precompute.discard();
            case PRECOMPUTE -> {
                if (canAdvance()) {
                    precompute.start(conversation(), segments);
                }
            }
            case APPLY -> {
                if (precompute.ready()) {
                    result = land(precompute.take(), result, specs);
                } else if (canAdvance()) {
                    precompute.start(conversation(), segments);   // 还没算完，先催一下
                }
            }
            case FORCE -> {
                ContextManager.Compaction c = precompute.ready() ? precompute.take() : null;
                if (c == null && precompute.hasPending()) {
                    // 后台八成正在算同一件事。反正到了硬线这一轮就要卡，
                    // 等它比再买一遍便宜——实测见过 [precompute] 和 [main]
                    // 前后脚跑同一个区间，token 白花一倍
                    c = precompute.awaitReady(FORCE_WAIT_MILLIS);
                }
                if (c == null && canAdvance()) {
                    c = context.compute(conversation(), segments, client);
                }
                if (c != null) {
                    result = land(c, result, specs);
                }
            }
        }
        reportTrimming(result);
        return result;
    }

    /**
     * 降级阶梯裁了东西就报一次。
     *
     * <h3>为什么这个信号不能省</h3>
     * 只有一轮对话时永远压不出摘要（「最近几轮原样」是硬约束，而那一轮就是全部），
     * 但阶梯照样会把工具输出裁掉。不报的话，用户看到的现象是
     * 「它刚读过的文件，怎么下一句就不记得了」——而界面一声不吭，
     * 没人会想到是上下文在背后被削了。
     */
    private void reportTrimming(ContextManager.Result result) {
        // 「裁了」不等于「变小了」：工具输出都短于裁剪阈值时，走了阶梯也一个字没省。
        // 那种时候报一行「已压缩 1598 → 1598」纯属噪音
        if (result.level() > 0 && result.tokensAfter() < result.tokensRaw() && !trimReported) {
            trimReported = true;
            // 两头都用**窗口**的口径：tokensRaw 是不裁的窗口，tokensAfter 是实发的窗口。
            // 不能拿 tokensBefore 当「压缩前」——那是记录的大小，<b>不含 system</b>，
            // 而窗口含。两把尺子一比，短会话里会打出「1302 → 1598」这种越压越大的行
            listener.accept(new AgentEvent.Compacted(
                    result.tokensRaw(), result.tokensAfter(), false));
        }
    }

    /**
     * 还有东西可压吗——摘要能不能往前推进一点。
     *
     * <h3>为什么非判不可</h3>
     * 到了「最近几轮本身就撑破预算」的地步（单轮里读了个超大文件是典型），
     * 压力会一直停在 FORCE 上。不判的话，<b>每一轮都会重跑一次 reduce</b>：
     * map 那边因为段都算过了不花钱，reduce 却每次都要发一个调用，
     * 而它算出来的东西和上次一模一样。用户看不见，账单看得见。
     */
    private boolean canAdvance() {
        int upTo = ContextWindow.startOfLastTurns(conversation(), ContextWindow.KEEP_RECENT_TURNS);
        return upTo > coversUpTo || (upTo > 0 && summary == null);
    }

    /**
     * 这份待用件真能把摘要边界往前推吗。
     *
     * <h3>边界只许往前</h3>
     * 待用件可能是<b>好几轮之前</b>算的：{@link Precompute#start} 在已有任务在途时是
     * 空操作，那份任务就会一直挂着不更新。中途用户 {@code /compact} 过一次、边界已经
     * 前推，它再落地就会把 {@code coversUpTo} 推回去——于是摘要和它本该顶替掉的原文
     * 同时出现在窗口里（内容重复、白花 token），而 {@link #canAdvance()} 随即又判 true，
     * 下一轮再压一遍。
     */
    private boolean advances(ContextManager.Compaction compaction) {
        if (compaction.coversUpTo() > coversUpTo) {
            return true;
        }
        log.debug("待用件的边界 {} 没超过已落地的 {}，是份过期货，丢掉",
                compaction.coversUpTo(), coversUpTo);
        return false;
    }

    /** 把待用件换进窗口。只动派生物，<b>记录一个字都不改</b>。 */
    private ContextManager.Result land(ContextManager.Compaction compaction,
                                       ContextManager.Result before, List<ToolSpec> specs) {
        if (compaction == null || compaction.isEmpty()) {
            return before;
        }
        if (!advances(compaction)) {
            return before;
        }
        segments = compaction.segments();
        summary = compaction.summary();
        coversUpTo = compaction.coversUpTo();
        trimReported = false;       // 新的一段，裁剪值得再报一次

        ContextManager.Result after = buildWindow(specs);
        // 摘要落地要报的是**压力**变化，所以两头都取 tokensRaw。
        // 取实发大小（tokensAfter）会得出荒唐的结论：阶梯本来就把实发压在软目标附近，
        // 落地前后都贴着那条线，于是一次把 8000 降到 2000 的压缩会显示成「2138 → 2160」
        listener.accept(new AgentEvent.Compacted(
                before.tokensRaw(), after.tokensRaw(), true));
        return after;
    }

    /**
     * 用实测值校准估算器。
     *
     * <p>校准而不是直接用实测值，是因为实测值只描述<b>刚发出去的那份窗口</b>，
     * 而判线要问的是「下一份窗口会有多大」——那份还没发出去，只能估。
     * 系数把估算器的系统性偏差抹掉，于是估出来的数也就有了实测的精度。
     */
    private void calibrate(ChatResponse response, int windowEstimate) {
        if (response.usage() == null || response.usage().promptTokens() <= 0
                || windowEstimate <= 0) {
            return;     // 网关没给，保持保守档
        }
        double factor = (double) response.usage().promptTokens() / windowEstimate;
        // 夹一下：网关偶尔会回明显离谱的数（比如把缓存命中的部分算进去），
        // 让一个异常值把系数带偏，后果是整段会话都判错线
        calibration = Math.max(0.5, Math.min(3.0, factor));
        measured = true;
    }

    private int calibrated(int estimate) {
        return (int) Math.ceil(estimate * calibration);
    }

    /**
     * 窗口超过上限，这一轮发不出去。
     *
     * <h3>为什么不偷偷把保留轮数降下来</h3>
     * 因为降了多半也没用：能撑破窗口的东西（粘进来的一大段文本、单轮里读的超大文件）
     * 很可能就在<b>最后一轮</b>里，降到一轮照样超。那样只会先悄悄丢掉用户的上下文，
     * 再报一个同样的错，两头不讨好。所以把话直说。
     *
     * <p>文案里<b>不要提示 {@code /compact}</b>——它同样保最近几轮原样，
     * 在这个状态下按了也没有任何效果，提了反而让用户白试一次。
     */
    private String tooBigToSend(ContextManager.Result prepared, List<ToolSpec> specs,
                                String userInput, int step) {
        String reason = "最近几轮的原文本身就超过了上下文窗口（约 "
                + context.estimate(prepared.window(), specs) + " / 上限 "
                + context.maxTokens() + " tokens）。压缩救不了——最近几轮是原样保留的。"
                + "开一段新会话（/clear），或者把 contextTokens 调大。";
        log.error("{}", reason);
        // 封口但**不记断点**。记了的话界面会提示「说一句『继续』就接着干」，
        // 而那是彻头彻尾的误导：撑破窗口的那条消息还在记录里（记录只追加），
        // 说一次「继续」就原地再撞一次同一堵墙，用户会一直循环下去。
        sealWithoutInterruption(reason);
        listener.accept(new AgentEvent.Failed(reason));
        return reason;
    }

    /**
     * 被取消时的收尾。
     *
     * <p>取消很可能发生在「assistant 已经要求调工具、但结果还没回填」的当口，
     * 那种残缺留在历史里会让之后每一次请求都 400，所以必须处理。
     * 处理方式是封口而不是回滚——用户按 Ctrl-C 多半是嫌它跑偏了或者太慢，
     * 而不是要把已经做完的部分一起作废。留着现场，他改一句话就能接着来。
     */
    private String abort(String userInput, int step) {
        seal("已取消", userInput, step);
        listener.accept(new AgentEvent.Failed("已取消"));
        return "已取消。";
    }

    /**
     * 把断在半路的这一轮封口，并记下断点。
     *
     * <p>封口后历史一定是自洽的，也就是说<b>下一句话照样发得出去</b>——
     * 这是「无感续做」的全部机制。做不到自洽的话，用户说「继续」只会换来一个 400。
     */
    /**
     * 把错误信息截短再放进历史。
     *
     * <p>错误体不一定是一句人话：网关挂了会回一整页 HTML，限流会回一大坨 JSON。
     * 那东西一旦进了封口消息，就会<b>每一次请求都跟着发一遍</b>，
     * 白烧 token，还把模型的注意力从「刚才做到哪」上面拽走。
     */
    private static String brief(String message) {
        if (message == null || message.isBlank()) {
            return "原因不明";
        }
        String oneLine = message.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }

    private void seal(String reason, String userInput, int step) {
        sealWithoutInterruption(reason);
        unfinished = new Interruption(reason, userInput, step, System.currentTimeMillis());
    }

    /**
     * 只封口，不记断点。
     *
     * <p>用在「重试也没用」的场合：断点的全部意义是告诉用户「说一句继续就接着干」，
     * 而有些失败重试一万次也还是同一个结果，那时候提示续做就是在骗人。
     */
    private void sealWithoutInterruption(String reason) {
        List<ChatMessage> sealed = HistoryRepair.seal(conversation(), reason);
        history.subList(1, history.size()).clear();   // 留下 system
        history.addAll(sealed);
    }

    /**
     * 不给工具再问一次，逼模型输出结论。
     *
     * <h3>那句逼收尾的话不留在历史里</h3>
     * 「请不要再调用工具」是一次性的<b>脚手架</b>，只为这一个请求服务。
     * 留在历史里的话，用户接着说「继续」时，模型仍然看着那条禁令，
     * 于是它会继续用文字描述要做什么而<b>迟迟不动手</b>——
     * 而这个现象长得很像「模型变笨了」，没人会想到是三十条消息之前的一句话在起作用。
     * 所以拿到回答就把它摘掉，只留模型那条结论。
     */
    private String forceConclusion(String userInput) {
        history.add(ChatMessage.user(
                "已达到本轮最大工具调用次数，请不要再调用工具，直接用文字说明当前进展和还差什么。"));
        try {
            // tools 传 null = 没得调。发的是窗口，脚手架刚追加进记录，组装时自然带上
            ChatResponse response = client.chat(buildWindow(null).window(), null, stream);
            history.remove(history.size() - 1);      // 摘掉脚手架，理由见上
            ChatMessage conclusion = response.message();
            if (conclusion.hasToolCalls()) {
                // 没给工具却仍然点了工具（极少数模型会）。原样入历史就是一串等不到结果的
                // tool_call，下一次请求必 400。只取它的文字
                conclusion = ChatMessage.assistant(conclusion.content());
            }
            history.add(conclusion);
            // 极少数情况下模型即使没有工具可用也不给文字，兜一句免得返回空答复
            String text = response.text().isBlank() ? "(模型未给出文字结论)" : response.text();
            unfinished = new Interruption("达到最大步数 " + maxSteps, userInput, maxSteps,
                    System.currentTimeMillis());
            listener.accept(new AgentEvent.Failed("达到最大步数 " + maxSteps + "，已强制收尾"));
            return text + "\n\n(已达到最大步数 " + maxSteps + " 限制，任务可能未完成)";
        } catch (LlmException e) {
            history.remove(history.size() - 1);      // 脚手架也得摘，它现在是最后一条
            seal("达到最大步数 " + maxSteps + "，收尾请求也失败了", userInput, maxSteps);
            listener.accept(new AgentEvent.Failed(e.getMessage()));
            return "达到最大步数 " + maxSteps + "，且收尾请求失败: " + e.getMessage();
        }
    }
}
