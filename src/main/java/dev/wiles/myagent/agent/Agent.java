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
import dev.wiles.myagent.context.Interruption;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
 *   │      └─ 是 → 执行工具（只读的凑批并行，会动世界的串行） (Acting)
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
     * 单次 run 最多跑几轮。{@code <= 0} 表示不限——这是默认。
     *
     * <h3>为什么默认不限</h3>
     * 照 Claude Code：交互模式没有步数上限，无头模式的 {@code --max-turns} 是可选项、默认不设。
     * 原来的 25 步是按「改一个小 bug」定的，真实仓库上一个任务读、改、跑测试、再改，
     * 25 步根本不够；撞上限的代价不是少跑几步，是这一轮在半路被强制收尾。
     * AsynCodeBench cookiecutter 上三次单 agent 全是跑满步数被截断的，成绩差多少
     * 全看截断那一刻活干到哪儿（142 ~ 180/202），而不是能力。
     *
     * <h3>不限步数，那靠什么防失控</h3>
     * 和 Claude Code 一样：随时能按停（连带正在跑的工具和子 agent）；上下文涨到窗口的 80%
     * 起后台压缩；单个工具、单批并行调用、429 重试都各有时限。死循环烧钱的情况，
     * 要防就显式传上限（{@code --max-steps}，评测框架会传它自己的预算）。
     */
    public static final int DEFAULT_MAX_STEPS = 0;

    /**
     * 到了硬线、且后台正在算时，最多等它多久。
     *
     * <p>等超了就自己同步压一遍，后台那份留着——说不定下一轮就用上了。
     * 取 20 秒是因为一块摘要实测一两秒，几块也就几秒；再久多半是网络出了问题，
     * 那时候继续干等还不如自己去撞一次，好歹能拿到明确的错误。
     */
    private static final long FORCE_WAIT_MILLIS = 20_000;

    /**
     * 一批并行安全的工具调用最多同时跑几个。
     *
     * <p>能进批的都是本地磁盘 I/O（读文件、搜索），四个已经把普通磁盘喂饱了；
     * 再宽只是让一次读十个文件的模型把机器占满，收益早就平了。
     */
    private static final int MAX_PARALLEL_TOOLS = 4;

    /**
     * 一批并行调用<b>整批</b>的时限，毫秒。
     *
     * <p>能进批的都是本地磁盘 I/O，正常是毫秒级——60 秒不是给正常情况留的余量，
     * 是给「网络文件系统上的 grep 挂住了」那种情况设的止损线。
     * 没有它的话，一个卡死的调用会把整批、进而把整轮对话吊在那儿，
     * 而界面上只有一个转着的圈。
     *
     * <p>计整批而不是每个调用各一份：批的意义就是「这几件事一起做完」，
     * 逐个计时的话 4 个调用最坏能拖 4 倍，而在调用方眼里这仍然是「一步」。
     */
    public static final long DEFAULT_BATCH_TIMEOUT_MILLIS = 60_000;

    /**
     * 工具调用流水号，进程内全局。理由见 {@link #nextToolId()}。
     */
    private static final AtomicInteger TOOL_IDS = new AtomicInteger();

    /** {@link #emit} 的锁。批内调用会从工作线程发事件，监听者只想看到一次一条。 */
    private final Object emitLock = new Object();

    /** 整批并行调用的时限。见 {@link #DEFAULT_BATCH_TIMEOUT_MILLIS}。 */
    private long batchTimeoutMillis = DEFAULT_BATCH_TIMEOUT_MILLIS;

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
    /** 跑循环之外送进来的话（子 agent 的结果、追加的指示）。null 表示没有这条通道。 */
    private Mailbox mailbox;

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
    private volatile Interruption unfinished;   // 跑循环的线程写，首帧那边（WebSocket 线程）读

    /**
     * 在途这个请求已经吐到屏幕上的字。
     *
     * <p>请求断在半路（按停、断网）时，响应拼不完整，原来整条丢掉——
     * 可用户已经看着它说了半句「问题出在 Parser 第 42 行，空指针是因为」。
     * 他说「继续」，模型却不知道自己说过什么，只能从头再分析一遍，
     * 说出来的还可能和屏幕上那半句对不上。封口时把它补进历史，
     * 守住一条不变量：<b>屏幕上出现过的，历史里就有</b>。
     *
     * <p>就记在发 TextDelta 的这一处，而不是让客户端在异常里带出来：
     * 这里正是「用户看到了什么」的定义，换哪家模型客户端都一样成立。
     * 只有跑循环的线程读写它（流式回调是在发请求的线程上同步读出来的）。
     */
    private final StringBuilder streamed = new StringBuilder();

    /** 正在跑的那个串行工具所在的线程。{@link #cancel()} 靠它叫停工具。 */
    private volatile Thread toolThread;

    /** 叫停之后再等工具多久。它不配合（卡在网络读上，中断叫不醒）就不等了。 */
    private long toolStopGraceMillis = 3_000;

    /** 断点记时刻、续做时算隔了多久都用它。测试里换成拨得动的钟。 */
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    /** 把模型的流式增量转成 agent 事件发出去，顺手记下说过的字。 */
    private final StreamListener stream = new StreamListener() {
        @Override
        public void onTextDelta(String delta) {
            streamed.append(delta);
            emit(new AgentEvent.TextDelta(delta));
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

    /**
     * 改整批并行调用的时限。传 0 或负数表示不限时——只在测试里用得上，
     * 生产环境把它关掉等于把「一个卡死的工具吊住整轮对话」这条路重新打开。
     */
    public Agent withBatchTimeout(java.time.Duration timeout) {
        this.batchTimeoutMillis = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Long.MAX_VALUE / 2_000_000      // 够大，且乘 1e6 不溢出
                : timeout.toMillis();
        return this;
    }

    /** 接上收件箱：每一步请求前收一次，交卷前还有人在跑就等。见 {@link Mailbox}。 */
    public Agent withMailbox(Mailbox mailbox) {
        this.mailbox = mailbox;
        return this;
    }

    /** 系统提示词。子 agent 原样沿用主 agent 的这一份，前缀缓存才命中得上。 */
    public String systemPrompt() {
        return systemPrompt;
    }

    /** 告诉它哪些工具算「改了文件」，钉住块要用。 */
    public Agent withWritingTools(Set<String> writingTools) {
        this.writingTools = writingTools == null ? Set.of() : Set.copyOf(writingTools);
        return this;
    }

    /**
     * 该收手了吗：用户按了 Ctrl-C，<b>或者跑着这个循环的线程被中断了</b>。
     *
     * <p>后一半是给上层的超时用的。计划和流水线超时时会 {@code Future.cancel(true)}，
     * 那只是给工作线程设一个中断标志——不认它的话，一个已经被判超时、
     * 下游都已跳过的任务还会接着发模型调用、接着写文件，纯属烧钱加添乱。
     * 认了之后，它最多再走完手上这一步就收口。
     */
    private boolean shouldStop() {
        return cancelled || Thread.currentThread().isInterrupted();
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
        Thread running = toolThread;
        if (running != null) {
            running.interrupt();   // 正在跑的工具：bash 会当场杀掉子进程，见 runInterruptibly
        }
        if (mailbox != null) {
            mailbox.interruptAll();  // 它派出去的子 agent 一起停
        }
    }

    /** 叫停后等工具收尾的时限。仅供测试：真实的 3 秒太慢。 */
    Agent toolStopGrace(long millis) {
        this.toolStopGraceMillis = millis;
        return this;
    }

    /** 换一个钟。仅供测试：「隔了 45 分钟才接」不能真等 45 分钟。 */
    Agent clock(java.util.function.LongSupplier clock) {
        this.clock = clock;
        return this;
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
        if (mailbox != null) {
            mailbox.shutdown();      // 上一段对话派出去的子 agent，这一段不认识它们
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

    /**
     * 连断点一起接回来。关了终端再 {@code /continue}，界面照样提示「继续」，
     * 下一句照样拼上中断说明——而且隔了多久是按真实时间算的。
     *
     * <p>断点要和接回来的历史对得上账（条数相同）才认。对不上说明存档里那段被回退过、
     * 改过，或者恢复时被修剪过——断点描述的已经不是眼前的现场，提示「继续」就是误导。
     *
     * @param cut 存档里的断点，没有就是 null
     */
    public int restore(List<ChatMessage> conversation, ContextManager.Compaction known,
                       Interruption cut) {
        int restored = restore(conversation, known);
        if (cut != null && cut.matches(conversation())) {
            unfinished = cut;
        }
        return restored;
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
        // 断点之后的第一句话：前面拼上中断说明，告诉模型现场怎么读（见 Interruption.resumeNote）。
        // 「继续」也好、换了个说法也好，都走这一条——不认关键词。
        // 只拼一次：这一轮说完，unfinished 就清了
        Interruption cut = unfinished;
        history.add(ChatMessage.user(cut == null ? userInput
                : cut.resumeNote(clock.getAsLong()) + "\n\n" + userInput));
        // 断的时候要知道跑到第几步了，所以 step 活在 try 外面
        int step = 0;

        try {
            for (step = 1; maxSteps <= 0 || step <= maxSteps; step++) {
                if (shouldStop()) {
                    return abort(userInput, step);
                }
                deliverMail();
                emit(new AgentEvent.Thinking(step));

                List<ToolSpec> specs = tools.specs();
                // 窗口在请求之前现场组装。此刻记录是自洽的（上一轮的工具结果都已回填），
                // 所以尾巴从哪一条 user 消息切都不会留下无主的 tool 消息
                ContextManager.Result prepared = prepareWindow(specs);
                if (context.exceedsHardLimit(prepared.window(), specs)) {
                    return tooBigToSend(prepared, specs, userInput, step);
                }

                streamed.setLength(0);
                ChatResponse response = client.chat(prepared.window(), specs, stream);
                streamed.setLength(0);       // 说完整了，这些字会随 assistant 消息进历史，不再是半段话
                calibrate(response, prepared.tokensAfter());
                ChatMessage assistant = response.message();

                // 原样入历史。这条消息里的 tool_calls 是后面 tool 消息的锚点，
                // 少了它，下一轮请求里的 tool 消息就成了无主孤儿，API 直接 400。
                history.add(assistant);

                if (!assistant.hasToolCalls()) {
                    if (awaitMail()) {
                        continue;        // 子 agent 交了结果（或者有人追加了话）：读完接着干，不收口
                    }
                    String unfinishedWork = mailbox == null ? null : mailbox.beforeFinish();
                    if (unfinishedWork != null) {
                        history.add(ChatMessage.user(unfinishedWork));
                        emit(new AgentEvent.Thought("子 agent 改过的文件还没验证，先验证再收尾"));
                        continue;
                    }
                    String text = response.text();
                    unfinished = null;   // 这一轮走完了，「上一轮没做完」不再成立
                    emit(new AgentEvent.Done(text, step));
                    return text;
                }

                // 模型常常一边说「我先看看 pom.xml」一边发起调用，这句话就是 ReAct 的 Thought
                if (assistant.content() != null && !assistant.content().isBlank()) {
                    emit(new AgentEvent.Thought(assistant.content()));
                }

                // 模型可能一次点好几个工具。必须全部执行、全部回传，
                // 漏掉任何一个，那个 tool_call_id 就永远等不到结果，下轮请求必然报错。
                //
                // 连续的并行安全调用（读三个文件、搜两个词）凑成一批同时执行；
                // 会动世界的调用（写文件、bash）保持串行，且作为批的边界——
                // 「读 a → 改 a → 再读 a」这种序列里，顺序本身就是语义。
                List<ToolCall> calls = assistant.toolCallsOrEmpty();
                int next = 0;
                while (next < calls.size()) {
                    if (shouldStop()) {
                        return abort(userInput, step);
                    }
                    int end = next;
                    while (end < calls.size() && tools.parallelSafe(calls.get(end).name())) {
                        end++;
                    }
                    if (end - next >= 2) {
                        executeBatch(calls.subList(next, end));
                        next = end;
                    } else {
                        executeOne(calls.get(next));
                        next++;
                    }
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
            emit(new AgentEvent.Failed(e.getMessage(), unfinished));
            return "调用模型失败: " + e.getMessage();
        }
    }

    /**
     * 收件箱里的话作为 user 消息追加。只在步与步之间调：此刻上一步的工具结果都已回填，
     * 插一条 user 消息不会把 tool_calls 和它的结果拆开。
     */
    private void deliverMail() {
        if (mailbox == null) {
            return;
        }
        for (String message : mailbox.drain()) {
            history.add(ChatMessage.user(message));
        }
    }

    /**
     * 模型要交卷了：收件箱里有话，或者还有子 agent 在跑，就先别收口。
     *
     * @return true 表示接着循环（有话要读，或者被叫停了要回到循环顶上走 abort）
     */
    private boolean awaitMail() {
        if (mailbox == null) {
            return false;
        }
        boolean announced = false;
        while (!mailbox.hasMail()) {
            if (!mailbox.busy()) {
                return false;
            }
            if (shouldStop()) {
                return true;
            }
            if (!announced) {
                emit(new AgentEvent.Thought("还有子 agent 在跑，等它们交了结果再收尾"));
                announced = true;
            }
            try {
                mailbox.awaitChange(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return true;
    }

    /** 串行执行一次工具调用：发事件、执行、回填历史。 */
    private void executeOne(ToolCall call) {
        int id = nextToolId();
        emit(new AgentEvent.ToolStarted(id, call.name(), call.argumentsJson()));
        ToolResult result = runInterruptibly(call);
        emit(new AgentEvent.ToolFinished(
                id, call.name(), result.ok(), result.content(), result.diff()));
        history.add(ChatMessage.toolResult(call.id(), result.content()));
    }

    /**
     * 在单独的线程上跑一个工具，按停时能当场叫停它。
     *
     * <h3>为什么要换线程</h3>
     * 原来工具就在跑循环的线程上同步执行，{@link #cancel()} 只设标志、掐 HTTP 请求，
     * 碰不到它——一次 {@code mvn test} 按了 Esc 也得等它跑完（或者等满超时）。
     * 换到单独的线程上，cancel 就能打断<b>那个</b>线程：bash 收到中断当场杀子进程，
     * 带着叫停前的输出回来（{@code ProcessRunner}）。
     * 不直接打断跑循环的线程，是因为它是复用的工作线程，中断标志漏到下一轮就是一次误取消。
     *
     * <h3>工具不配合就不等了</h3>
     * 中断能不能叫醒工具取决于它卡在哪：sleep / waitFor 能醒，阻塞的网络读不一定。
     * 叫停后再给它 {@link #toolStopGraceMillis}，还不回来就<b>不等了</b>，回填「结果未知」——
     * 不能写「没有执行」，它确实跑了，而且可能还在后台跑（Codex 的提示里也特意写了这一句）。
     *
     * <p>上层超时（计划、流水线 {@code Future.cancel(true)}）打断的是跑循环的线程，
     * 这里接住、照样叫停工具，最后把中断标志还回去，让 {@link #shouldStop()} 看得见。
     */
    private ToolResult runInterruptibly(ToolCall call) {
        java.util.concurrent.CompletableFuture<ToolResult> result = new java.util.concurrent.CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                result.complete(tools.execute(call));   // 它保证不抛异常，兜的是 Error
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }, "tool-" + call.name());
        worker.setDaemon(true);
        toolThread = worker;
        worker.start();
        boolean selfInterrupted = false;
        long giveUpAt = -1;
        try {
            while (true) {
                if (giveUpAt < 0 && (cancelled || selfInterrupted)) {
                    worker.interrupt();          // cancel() 已经打过一次；这里兜「它赶在 toolThread 赋值前就来了」
                    giveUpAt = System.nanoTime() + toolStopGraceMillis * 1_000_000L;
                }
                if (giveUpAt >= 0 && System.nanoTime() > giveUpAt) {
                    log.warn("工具 {} 叫停后 {} 毫秒仍未结束，不再等它", call.name(), toolStopGraceMillis);
                    return ToolResult.error("(已取消：叫停时这个工具还在跑，等了 "
                            + Math.max(1, toolStopGraceMillis / 1000) + " 秒没停下来，结果未知。"
                            + "它可能已经部分生效，甚至还在后台继续——先检查现状再决定是否重试)");
                }
                try {
                    return result.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // 接着等，顺便看一眼有没有人按停
                } catch (InterruptedException e) {
                    selfInterrupted = true;      // 标志被这个异常吃掉了，记下来最后还回去
                } catch (ExecutionException e) {
                    log.error("工具 {} 执行时炸了", call.name(), e.getCause());
                    return ToolResult.error("工具执行失败: " + e.getCause());
                }
            }
        } finally {
            toolThread = null;
            if (selfInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 把一批并行安全的调用同时执行掉。
     *
     * <h3>历史只在这个线程动</h3>
     * 工作线程只负责「执行 + 发事件」，结果收回来之后由本线程<b>按点单顺序</b>回填。
     * 两个原因：{@link #history} 没加锁，收在一个线程里就不用加；
     * 回填顺序和 {@code tool_calls} 一致，回放这段历史的人不用先理解并发。
     *
     * <h3>取消不抢批内的调用</h3>
     * 能进批的都是只读工具，快而且没有副作用，等它们跑完比中途弃掉更省心——
     * 弃掉的那几个还得补占位消息，否则历史就残了。取消在批与批之间生效。
     *
     * <h3>整批共一个时限</h3>
     * 预算是<b>整批</b>的，不是每个调用各一份：批的意义就是「这几件事一起做完」，
     * 逐个计时的话 4 个调用最坏能拖 4 倍，而调用方眼里这仍然是「一步」。
     * 超时的那个当场判失败、给它补一条结果，<b>剩下的照收</b>——
     * 一个卡住的 grep 不该让另外三个已经读完的文件白读。
     */
    private void executeBatch(List<ToolCall> batch) {
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(batch.size(), MAX_PARALLEL_TOOLS), r -> {
                    Thread t = new Thread(r, "tool-batch");
                    t.setDaemon(true);
                    return t;
                });
        // 谁先把 id 放进去，谁负责发那条「结束」事件。主线程超时补发和工作线程
        // 姗姗来迟地发，只能有一个作数——否则界面上同一行会被更新两次，后到的还是旧结果
        Set<Integer> settled = ConcurrentHashMap.newKeySet();
        try {
            // 流水号在提交前就编好：超时的那个要由主线程补一条「结束」事件，
            // 它得先知道号。事件本身仍由工作线程在**真正开跑**时发——
            // 批比池大时后几个要排队，「开始」不该在提交那一刻就报出去
            List<Integer> ids = new ArrayList<>(batch.size());
            List<Future<ToolResult>> futures = new ArrayList<>(batch.size());
            for (ToolCall call : batch) {
                int id = nextToolId();
                ids.add(id);
                futures.add(pool.submit(() -> {
                    emit(new AgentEvent.ToolStarted(id, call.name(), call.argumentsJson()));
                    ToolResult result = tools.execute(call);
                    if (settled.add(id)) {
                        emit(new AgentEvent.ToolFinished(
                                id, call.name(), result.ok(), result.content(), result.diff()));
                    }
                    return result;
                }));
            }
            long deadline = System.nanoTime() + batchTimeoutMillis * 1_000_000L;
            for (int i = 0; i < batch.size(); i++) {
                ToolCall call = batch.get(i);
                String content = settled(futures.get(i), deadline, call.name(), ids.get(i),
                        settled);
                history.add(ChatMessage.toolResult(call.id(), content));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 等一个批内调用的结果。<b>每个 tool_call 都必须有条结果</b>，
     * 所以这里把超时和等待中的意外统统翻译成一条错误内容，绝不让某条消息缺席。
     *
     * @param deadline 整批共用的截止时刻（{@code System.nanoTime()} 口径）
     */
    private String settled(Future<ToolResult> future, long deadline, String tool, int id,
                           Set<Integer> settledIds) {
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            return future.get(remaining, TimeUnit.NANOSECONDS).content();
        } catch (TimeoutException e) {
            // 掐掉它，别让一个卡死的工具把线程占到会话结束。interrupt 能不能真的叫停
            // 取决于工具实现（BashTool 会醒，卡在网络文件系统上的读不一定），
            // 所以这只是止损：这一步的结果按超时算，后面的调用照收
            future.cancel(true);
            log.warn("工具 {} 超过整批 {} 毫秒的时限，按超时处理", tool, batchTimeoutMillis);
            String message = "这一步超时了（整批并行调用的时限是 " + batchTimeoutMillis
                    + " 毫秒）。如果这个工具本来就慢，换个更窄的范围再试。";
            if (settledIds.add(id)) {
                // 工作线程还没发过「结束」，界面上那一行正转着圈，得由这儿收尾
                emit(new AgentEvent.ToolFinished(id, tool, false, message));
            }
            return ToolResult.error(message).content();
        } catch (ExecutionException e) {
            // execute() 承诺不抛异常，这里兜的是线程池本身的意外
            log.error("并行工具调用炸在了池子里", e.getCause());
            return ToolResult.error("并行执行失败: " + e.getCause()).content();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("等待结果时被中断，这一步的结果没有拿到").content();
        }
    }

    /**
     * 事件的唯一出口。
     *
     * <p>批内的调用在工作线程上发事件，监听者（终端渲染、WebSocket 广播）
     * 未必扛得住并发——收在一把锁后面，它们看到的世界仍然是一次一条。
     */
    private void emit(AgentEvent event) {
        synchronized (emitLock) {
            listener.accept(event);
        }
    }

    /**
     * 取一个工具调用流水号。<b>进程内全局</b>递增而不是每个 Agent 各数各的：
     * 计划里几个子循环同时在跑，各数各的会撞号，界面把两次调用配成一行。
     */
    private static int nextToolId() {
        return TOOL_IDS.incrementAndGet();
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
     * <h3>进度</h3>
     * 全程会发 {@link AgentEvent.CompactProgress}，<b>包括最后那一条 {@code done}</b>——
     * 它必须无条件发出去，压成了、压不了、被取消了都一样。少发一次，界面上那根进度条
     * 就永远停在 87% 不动，而后台其实早就结束了。
     *
     * <h3>用现货时不闪进度条</h3>
     * 有待用件时整个过程是零调用、瞬间完成的。那一路<b>一条进度事件都不发</b>，
     * 免得界面上闪过一根来不及看清的条——界面只在收到第一条 map/reduce 时才建进度条。
     *
     * @return 压了返回结果，没压返回 null
     */
    public ContextManager.Result compactNow() {
        List<ToolSpec> specs = tools.specs();
        ContextManager.Result before = buildWindow(specs);
        if (precompute.ready()) {
            ContextManager.Compaction ready = precompute.take();
            // 现货也可能是过期货，见 advances()。这儿必须**返回 null** 而不是交给 land
            // 去默默拒绝：那样 /compact 会一声不吭地报成功，用户看不到任何变化，
            // 也不知道为什么
            return ready == null || !advances(ready) ? null : land(ready, before, specs);
        }
        if (!canAdvance()) {
            return null;
        }
        cancelled = false;      // 上一轮按过 Esc 的残留标志，不该让这次压缩一出生就作废
        ContextManager.Compaction fresh;
        try {
            fresh = context.compute(conversation(), segments, client, this::onCompactProgress);
        } finally {
            finishCompactProgress();
        }
        // 按了 Esc 就整份丢掉，一个段都不留。这里不能只看「有没有结果」：
        // summarizeChunk 把调用失败降级成一段占位文本，而段是**一辈子缓存**的，
        // 落地了就再也不会重算——于是用户按一次取消，换来的是这段历史永久变成
        // 「（摘要没能生成）」。宁可这次白跑
        if (cancelled) {
            log.info("压缩被取消，算出来的东西整份丢弃（段有缓存，留着会把占位文本焊死）");
            return null;
        }
        return fresh == null ? null : land(fresh, before, specs);
    }

    /**
     * 这次压缩一共几步。只为了让收尾那条事件报得出 {@code n/n} 而记——
     * 总步数是 {@link ContextManager} 切完块才知道的，{@link Agent} 这边只能听来。
     */
    private int compactSteps;

    private void onCompactProgress(String phase, int done, int total, int percent) {
        compactSteps = total;
        emit(new AgentEvent.CompactProgress(phase, done, total, percent));
    }

    /**
     * 收尾。<b>无条件发</b>——压成了、压不了、被取消了都一样，
     * 少发一次界面上那根条就永远停在 87% 不动。
     */
    private void finishCompactProgress() {
        emit(AgentEvent.CompactProgress.finished(compactSteps));
        compactSteps = 0;
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
                    // 到了硬线这一轮无论如何要卡住几十秒，而此刻界面上只有一个
                    // 转着的「思考中」——用户完全不知道它在压缩，只觉得这次特别慢。
                    // 报进度不改变任何行为，但把这段黑箱变成了看得见的事
                    try {
                        c = context.compute(conversation(), segments, client,
                                this::onCompactProgress);
                    } finally {
                        finishCompactProgress();
                    }
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
            emit(new AgentEvent.Compacted(
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
        emit(new AgentEvent.Compacted(
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
        emit(new AgentEvent.Failed(reason));
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
        emit(new AgentEvent.Failed("已取消", unfinished));
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
        unfinished = new Interruption(reason, userInput, step, clock.getAsLong(),
                conversation().size());
    }

    /**
     * 只封口，不记断点。
     *
     * <p>用在「重试也没用」的场合：断点的全部意义是告诉用户「说一句继续就接着干」，
     * 而有些失败重试一万次也还是同一个结果，那时候提示续做就是在骗人。
     */
    private void sealWithoutInterruption(String reason) {
        keepPartialReply(reason);
        List<ChatMessage> sealed = HistoryRepair.seal(conversation(), reason);
        history.subList(1, history.size()).clear();   // 留下 system
        history.addAll(sealed);
    }

    /**
     * 请求断在半路时，把已经吐到屏幕上的半段话收进历史。
     *
     * <p>末尾要标明是被打断的：不标的话，模型会把半句话当成自己说完了的结论。
     * 有了这条以 assistant 收尾的消息，{@link HistoryRepair#seal} 就不会再补
     * 「上一轮没有做完」——半段话自己就是封口，同一件事不说两遍。
     *
     * <p>半截的 tool_calls 不留：参数可能只到一半，留下就是一次必然失败的调用。
     */
    private void keepPartialReply(String reason) {
        if (streamed.isEmpty()) {
            return;
        }
        history.add(ChatMessage.assistant(streamed + "\n\n(上一轮" + reason + "，这段话没说完)"));
        streamed.setLength(0);
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
        if (mailbox != null) {
            mailbox.interruptAll();      // 主 agent 要收口了，派出去的别再接着改文件
        }
        history.add(ChatMessage.user(
                "已达到本轮最大工具调用次数，请不要再调用工具，直接用文字说明当前进展和还差什么。"));
        try {
            // tools 传 null = 没得调。发的是窗口，脚手架刚追加进记录，组装时自然带上
            streamed.setLength(0);
            ChatResponse response = client.chat(buildWindow(null).window(), null, stream);
            streamed.setLength(0);
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
                    clock.getAsLong(), conversation().size());
            emit(new AgentEvent.Failed("达到最大步数 " + maxSteps + "，已强制收尾", unfinished));
            return text + "\n\n(已达到最大步数 " + maxSteps + " 限制，任务可能未完成)";
        } catch (LlmException e) {
            history.remove(history.size() - 1);      // 脚手架也得摘，它现在是最后一条
            seal("达到最大步数 " + maxSteps + "，收尾请求也失败了", userInput, maxSteps);
            emit(new AgentEvent.Failed(e.getMessage(), unfinished));
            return "达到最大步数 " + maxSteps + "，且收尾请求失败: " + e.getMessage();
        }
    }
}
