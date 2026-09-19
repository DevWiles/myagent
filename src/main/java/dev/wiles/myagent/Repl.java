package dev.wiles.myagent;

import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.audit.AuditLog;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.agent.Interruption;
import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.context.Precompute;
import dev.wiles.myagent.context.SessionId;
import dev.wiles.myagent.context.SessionSnapshot;
import dev.wiles.myagent.context.SessionStore;
import dev.wiles.myagent.context.SessionSummary;
import dev.wiles.myagent.memory.MemoryEntry;
import dev.wiles.myagent.memory.MemoryIndex;
import dev.wiles.myagent.memory.MemoryScope;
import dev.wiles.myagent.memory.MemorySource;
import dev.wiles.myagent.memory.MemoryStore;
import dev.wiles.myagent.cli.Ansi;
import dev.wiles.myagent.cli.Banner;
import dev.wiles.myagent.cli.Cli;
import dev.wiles.myagent.cli.CliOptions;
import dev.wiles.myagent.cli.FullScreenUi;
import dev.wiles.myagent.cli.ContextBar;
import dev.wiles.myagent.cli.InputFrame;
import dev.wiles.myagent.cli.InterruptWatcher;
import dev.wiles.myagent.cli.LineReader;
import dev.wiles.myagent.cli.MarkdownRenderer;
import dev.wiles.myagent.cli.Moment;
import dev.wiles.myagent.cli.SessionList;
import dev.wiles.myagent.cli.OutputDetail;
import dev.wiles.myagent.cli.ToolSummary;
import dev.wiles.myagent.cli.StatusLine;
import dev.wiles.myagent.cli.TerminalMode;
import dev.wiles.myagent.config.Config;
import dev.wiles.myagent.llm.DeepSeekClient;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.PlanApproval;
import dev.wiles.myagent.plan.PlanContext;
import dev.wiles.myagent.plan.PlanExecuteAgent;
import dev.wiles.myagent.plan.ModeHeuristic;
import dev.wiles.myagent.plan.PlanListener;
import dev.wiles.myagent.plan.PlanResult;
import dev.wiles.myagent.plan.PlanStore;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.Planner;
import dev.wiles.myagent.cli.PlanView;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.WorkspaceGuard;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 交互式命令行外壳：读输入、交给 Agent、把事件画到屏幕上。
 * 真正的逻辑都在 Agent 里，这里只管人机交互。
 */
public final class Repl {

    /** 提示符。全屏模式下整屏重绘时也要用它。 */
    private static final String PROMPT = "› ";

    /** 工具输出默认只露一行——过程细节平时没人看，真要看再展开。 */
    private static final int COLLAPSED_OUTPUT_LINES = 1;
    /** /verbose 打开后显示这么多行。完整内容无论如何都会原样发给模型。 */
    private static final int VERBOSE_OUTPUT_LINES = 20;

    private static final List<String> SLASH_COMMANDS =
            List.of("/help", "/plan", "/resume", "/resume-plan", "/continue", "/clear",
                    "/compact", "/tools", "/history", "/expand",
                    "/quiet", "/verbose", "/config", "/exit", "/quit");

    /** 退出词。带不带斜杠都认——没人愿意在想退出的时候还猜该敲哪个。 */
    private static final List<String> EXIT_WORDS =
            List.of("/exit", "/quit", "/q", "exit", "quit", "q", ":q", ":wq");

    private final CliOptions options;
    private final Config config;
    private final Workspace workspace;
    private final MemoryStore memory;
    private final SessionStore sessions;
    private final ToolRegistry tools;
    private final TerminalMode terminal = new TerminalMode();
    private final LineReader reader;
    private final Agent agent;
    /** 规划器。没配 key 时和 agent 一样是 null。 */
    private final Planner planner;
    /** 审计轨迹，整个会话一份。 */
    private final AuditLog audit = AuditLog.forNewRun();
    /** 当前正在执行的任务 id；不在计划模式下是 null。工具调用靠它归到某个任务名下。 */
    private String currentTaskId;

    /**
     * 这次运行往<b>哪一份</b>会话存档里写。
     *
     * <p>一个工作区下并排放着多份会话（见 {@link SessionStore}），所以「存哪儿」
     * 不再是个定死的答案，得有人攥着当前这一份的 id。接回一段旧会话时把它换成那段的 id，
     * 于是后面每一轮都续在同一份上——否则接着聊的内容会另起一份，
     * 而原来那段永远停在被接回来的那一刻。
     */
    private String sessionId = SessionId.create(System.currentTimeMillis());

    /** 当前这段对话最早那一句的时刻。接回旧会话时要换成那段的，不能刷成现在。 */
    private long sessionStartedAt = System.currentTimeMillis();

    /** 攒够一行再渲染：Markdown 的行内标记要整行到齐才能判断闭合。 */
    private final StringBuilder pendingLine = new StringBuilder();
    private final MarkdownRenderer markdown = new MarkdownRenderer();
    /** 当前这段文字是否已经开了头（用来决定要不要先空一行）。 */
    private boolean textStarted = false;
    /** 过程信息显示到什么程度。 */
    private OutputDetail detail = OutputDetail.NORMAL;
    /**
     * 精简模式下攒着的文字。
     *
     * 流式输出时没法预先知道哪段是「过程说明」哪段是「最终答案」——
     * 所以先攒着：后面要是来了工具调用，说明刚才那段只是过程说明，丢掉；
     * 一直攒到这一轮结束还没有工具调用，那就是最终答案，打出来。
     */
    private final StringBuilder quietBuffer = new StringBuilder();

    /**
     * 当前这组工具调用在屏幕上占了几行，以及都调了些什么。
     *
     * 跑的时候细节要显示——用户得知道它在干嘛；跑完之后这些细节就没用了，
     * 留着只会把结论挤出屏幕。所以等模型重新开口（或这一轮结束）时，
     * 把这几行擦掉，换成一句「读取 3 个文件 · 执行 1 条命令」。
     */
    private int toolGroupLines;
    private final Map<String, Integer> toolGroupCounts = ToolSummary.newCounter();
    private boolean toolGroupHasFailure;

    /**
     * 最近一轮每次工具调用的完整记录。
     *
     * 过程收起来之后细节就从屏幕上消失了，但人经常会想回头看一眼
     * ——「刚才那条命令到底输出了什么」。终端没法把已经打印的内容再展开，
     * 所以留一份在内存里，按 Ctrl-O 或 /expand 重新打一遍。
     */
    private record ToolRecord(String tool, String arguments, String output, boolean ok) {
    }

    private final List<ToolRecord> lastRunTools = new ArrayList<>();

    /**
     * 摘要行之后又打印了哪些内容。
     *
     * 「原地展开」靠的就是它：把光标退回摘要行的位置、清掉，
     * 先打详情、再把这些内容原样重放一遍，看起来就是那一行展开了。
     * 没有它只能把详情追加打印在最下面，那不叫展开，叫又打了一遍。
     */
    private final List<String> replayBuffer = new ArrayList<>();
    private boolean recording;
    private String pendingToolName;
    private String pendingToolArgs;

    /** 本轮统计，跑完在输入框上方汇报一行。 */
    private long runStartedAt;
    private int runSteps;
    private int runToolCalls;
    /** 是否处于 raw 模式：只有这时才能后台监听 Ctrl-C。 */
    private final boolean rawMode;
    /** 终端宽度，用来画框和分隔线。 */
    private final int width;
    /** 全屏模式的画布；为 null 表示走老的顺序打印模式。 */
    private final FullScreenUi fullscreen;
    /** 「思考中…」那一行。 */
    private final StatusLine status;

    public Repl(CliOptions options) {
        this.options = options;
        Ansi.setEnabled(options.color() && TerminalMode.isInteractive());

        Config loaded = Config.load();
        this.config = options.model() == null ? loaded
                : new Config(loaded.apiKey(), loaded.baseUrl(), options.model(),
                        loaded.contextTokens());

        this.workspace = new Workspace(options.workspace());
        // raw 模式拿不到就退化成按行读，管道和重定向下都是这条路径
        boolean raw = terminal.enterRaw();
        this.rawMode = raw;
        this.width = TerminalMode.width();
        this.fullscreen = options.fullscreen() && raw ? new FullScreenUi(System.out) : null;
        // 全屏模式下转圈会和整屏重绘打架，状态直接写在底部状态栏里更稳
        this.status = new StatusLine(System.out,
                raw && options.color() && options.spinner() && fullscreen == null);
        this.reader = new LineReader(System.in, System.out, raw)
                .width(TerminalMode.width())
                .completions(SLASH_COMMANDS)
                .onExpand(this::expandLastRun)
                .onClick(raw && options.mouse() && fullscreen == null ? this::handleClick : null)
                .renderer(fullscreen == null ? null : fullscreen::render)
                .onScroll(fullscreen == null ? null : fullscreen::scroll)
                .historyFile(Path.of(System.getProperty("user.home"), ".myagent", "history"));

        this.memory = MemoryStore.inUserHome();
        this.sessions = SessionStore.inUserHome();
        this.tools = options.readOnly()
                ? BuiltinTools.createReadOnly(workspace, memory)
                : BuiltinTools.createDefault(workspace,
                        audit.audited(new TerminalApproval(reader)), memory);

        // 索引在这里扫一次就定了，不随对话刷新：系统提示词是 history 的第 0 条，
        // 改它等于改已经发生过的历史。本轮新记的东西下次启动生效——
        // 而模型刚 remember 完，那条事实本来就还在它眼前的上下文里。
        this.agent = config.hasApiKey()
                ? new Agent(newClient(), tools,
                        SystemPrompt.forWorkspace(workspace.root(), memory.index(workspace.root(),
                                MemoryIndex.budgetFor(config.contextTokens()))),
                        Agent.DEFAULT_MAX_STEPS, contextManager(options.readOnly()))
                        // 后台预压缩用独立客户端：DeepSeekClient 有可变状态，
                        // 和主循环共用会互相踩 currentCall，表现为「按了 Esc 停不下来」
                        .withPrecompute(new Precompute(
                                contextManager(options.readOnly()), this::newClient))
                        .withWritingTools(BuiltinTools.WRITING_TOOLS)
                        .onEvent(this::render)
                : null;
        // 规划器另起一个客户端：它和 agent 可能同时在跑（子任务里的 PLANNING），
        // 共用一个客户端的话，取消一个会把另一个也掐了
        this.planner = config.hasApiKey()
                ? new Planner(newClient())
                        .readOnly(options.readOnly())
                        // 现场信息现采一份：规划器看得到仓库长什么样、手上有哪些工具，
                        // 就不必再拿探路任务去摸，也编不出不存在的文件名
                        .context(PlanContext.of(workspace.root(), tools))
                : null;
        // 编排全交出去，这边只留「怎么问」和「怎么画」
        this.planAgent = planner == null ? null
                : new PlanExecuteAgent(planner, this::newClient, tools, workspace.root(),
                        PlanStore.inUserHome())
                        .approval(plan -> confirmPlan())
                        .listener(new TerminalPlanView());
    }

/**
     * 上下文管理器，接上「压缩时顺手记一笔」。
     *
     * <p>压缩那一刻手上正握着一段马上要被永久丢弃的历史，而且已经在发一次模型调用了。
     * 多问一句「有什么值得跨会话记住的」，额外成本是零。
     *
     * <p>来源记 OBSERVATION——这是模型自己的判断，不是用户亲口说的，
     * 可信度最低的那一档。<b>只读模式不捡</b>：它承诺不动用户的任何东西，磁盘也算。
     */
    private ContextManager contextManager(boolean readOnly) {
        ContextManager context = new ContextManager(config.contextTokens());
        if (readOnly) {
            return context;
        }
        return context.onHarvest(h -> memory.save(new MemoryEntry(
                h.name(), h.description(), h.content(),
                MemoryScope.PROJECT, MemorySource.OBSERVATION, LocalDate.now()),
                workspace.root()));
    }

    private LlmClient newClient() {
        return new DeepSeekClient(config);
    }

    /**
     * 「先规划后执行」的编排层。没有 API key 时是 null——
     * 规划、续跑、存档提示全都无从谈起，调用处一律先判空。
     */
    private final PlanExecuteAgent planAgent;

    /** 执行期间接管 Ctrl-C 的那个监听，见 {@link #startPlanWatcher()}。 */
    private InterruptWatcher planWatcher;

    public void run() {
        try {
            if (!checkWorkspace()) {
                return;
            }
            if (options.initialPrompt() != null) {
                runOnce(options.initialPrompt());       // 一次性模式，跑完就走
                return;
            }
            if (fullscreen != null) {
                fullscreen.enter();
            } else if (options.clearScreen() && rawMode) {
                // 招牌置顶，一眼看到的就是本次会话的开头。
                // 只清可见的这一屏，往上翻还能看到启动之前的终端内容。
                System.out.print(Ansi.clearScreen());
            }
            printBanner();
            announceResumable();
            loop();
        } finally {
            if (fullscreen != null) {
                fullscreen.exit();                       // 把终端原样还回去
            }
            System.out.print(Ansi.mouseOff());            // 把文本选择还给终端
            terminal.restore();                          // 无论如何都要把终端还回去
            audit.close();
        }
    }

    private boolean checkWorkspace() {
        String rejection = WorkspaceGuard.rejectionReason(workspace.root());
        if (rejection == null) {
            return true;
        }
        if (options.unsafe()) {
            emit(Ansi.yellow("⚠ 警告: " + rejection));
            emit(Ansi.yellow("⚠ 你已强制在此目录运行，请格外小心。") + "\n");
            return true;
        }
        emit(Ansi.red("拒绝在此目录启动: ") + workspace.root());
        emit(rejection);
        emit("请 cd 到某个具体的项目目录再运行。");
        emit(Ansi.dim("（确实需要的话加 --unsafe）"));
        return false;
    }

    /**
     * 跑一轮：说话、存盘、断了就提一句。
     *
     * <p>一次性模式（{@code -p}）和交互模式都走这儿。<b>两边必须走同一个方法</b>——
     * 这三件事里漏掉任何一件都不报错，只是悄悄少一个功能，
     * 而「存盘漏了」的现象是「/continue 说没有会话可接」，
     * 谁也不会想到问题出在两条路各写了一遍。
     */
    private void runOnce(String prompt) {
        if (agent == null) {
            emit(noKeyMessage());
            return;
        }
        runInterruptible(prompt);   // 内容已经通过流式回调打印过了
        saveSession();
        announceUnfinished();
        emit("");
    }

    /**
     * 这一轮断在半路时提一句「还能接着干」。
     *
     * <p>这句话是整个断点续做里<b>用户唯一能感知到的部分</b>。机制全在下面两层：
     * {@code Agent} 把断掉的现场封口留在历史里，{@code saveSession} 顺手存了盘。
     * 但用户看到的只是一行「调用模型失败」——他会以为这一轮白干了，
     * 于是把原来那句话重新打一遍，让模型把已经做完的事再做一次。
     * 那个信息差就是这句提示要消掉的东西。
     *
     * <p>不需要任何关键词：说「继续」管用，是因为现场就在上下文里，
     * 模型自己看得懂断在哪。说「接着来」「往下」一样管用。
     */
    private void announceUnfinished() {
        if (agent == null) {
            return;
        }
        agent.unfinished().ifPresent(broke -> emit(Ansi.yellow("⚠ ")
                + Ansi.dim("这一轮" + broke.describe()
                + "。已经做完的部分都留着了，说一句「继续」就接着干。")));

    }

    /**
     * 一轮说完存一次盘。
     *
     * <p>粒度和计划存档的「每了结一个任务存一次」对齐：都是在一个<b>自洽的边界</b>上存。
     * 更细的粒度没有意义——一轮对话中途的历史带着没回填的工具调用，
     * 存下来也是要被 {@code HistoryRepair} 截掉的。
     */
    private void saveSession() {
        if (agent != null) {
            sessions.save(sessionId, agent.history(), workspace.root(),
                    config.model(), sessionStartedAt, agent.segments());
        }
    }

    /**
     * 把上次没聊完的对话接回来。
     *
     * <p>和计划续跑一样<b>不自动做</b>，要用户开口。隔了不知道多久，
     * 代码可能已经变了，他也未必还记得当时在聊什么——
     * 悄悄把一段旧上下文塞回去，模型会拿着过时的结论一路往下推。
     */
    private void continueSession() {
        if (agent == null) {
            emit(noKeyMessage());
            return;
        }
        var snapshot = sessions.latest(workspace.root());
        if (snapshot.isEmpty()) {
            emit(Ansi.dim("这个工作区没有可以接着聊的会话。"));
            return;
        }
        if (agent.history().size() > 1) {
            // 当前已经聊上了。恢复会把眼前这段直接冲掉，得让用户自己决定
            emit(Ansi.dim("当前已经有对话了。要接上次的，先 /clear 再 /continue。"));
            return;
        }
        adopt(snapshot.get());
    }

    /**
     * 从会话历史里挑一段接着聊。
     *
     * <p>和 {@code /continue} 的分工：{@code /continue} 是「接最近那段」，不用挑；
     * 这里是「翻一下都聊过什么」。两个都留着，因为绝大多数时候用户要的就是最近那段，
     * 为那种情况还要看一遍列表、再敲一个编号，是白加的两步。
     *
     * @param argument {@code /resume 2} 里的那个 2。空的话就把列表画出来问一句
     */
    private void resumeSession(String argument) {
        if (agent == null) {
            emit(noKeyMessage());
            return;
        }
        List<SessionSummary> history = sessions.list(workspace.root());
        if (history.isEmpty()) {
            emit(Ansi.dim("这个工作区还没有会话历史。"));
            return;
        }
        if (agent.history().size() > 1) {
            // 接一段进来会把眼前这段冲掉，这个决定得用户自己做。和 /continue 同一道闸
            emit(Ansi.dim("当前已经有对话了。要挑一段接着聊，先 /clear。"));
            return;
        }
        int pickable = Math.min(history.size(), SessionList.MAX_ROWS);

        String answer = argument;
        if (answer == null || answer.isEmpty()) {
            SessionList.render(history, sessionId, width, System.currentTimeMillis())
                    .forEach(this::emit);
            answer = reader.readLine(Ansi.cyan("挑一段 › "));
            if (SessionList.isCancel(answer)) {
                emit(Ansi.dim("那就不接了。"));
                return;
            }
        }
        var index = SessionList.pick(answer, pickable);
        if (index.isEmpty()) {
            emit(Ansi.dim("认不出这个编号，1 到 " + pickable + " 之间。什么都没动。"));
            return;
        }

        String id = history.get(index.getAsInt()).id();
        var snapshot = sessions.load(workspace.root(), id);
        if (snapshot.isEmpty()) {
            // 列出来的时候还在，这会儿读不出来了——另一个终端刚把它清掉是最可能的
            emit(Ansi.dim("这段会话已经没了，可能是别处清掉的。"));
            return;
        }
        adopt(snapshot.get());
    }

    /**
     * 把一段存档立成当前会话。
     *
     * <p>{@code /continue} 和 {@code /resume} 最后都落到这儿，就一个理由：
     * 「换 sessionId 和 sessionStartedAt」这一步漏了不会报错，
     * 只会让接着聊的内容另起一份新档，而原来那段永远停在被接回来的那一刻。
     * 这种错只在两条路里的一条上犯，查起来是「有时候会丢对话」。
     */
    private void adopt(SessionSnapshot snapshot) {
        // 段一起带回来：它们是从原文 map 出来的，而记录只追加，所以永远有效，
        // 续聊时直接复用，不用重新花钱 map 一遍
        int restored = agent.restore(snapshot.restorable(), snapshot.usableSegments());
        sessionId = snapshot.id();
        sessionStartedAt = snapshot.startedAt();
        emit(Ansi.dim("接上了（" + restored + " 条消息，" + snapshot.turns() + " 轮，"
                + Moment.describe(snapshot.savedAt(), System.currentTimeMillis())
                + "）。首句是：" + snapshot.firstUserMessage()));
    }

    /**
     * 跑一轮任务，期间允许 Ctrl-C 中断。
     *
     * 主线程这会儿阻塞在 agent.run() 里，自己读不了键盘，
     * 所以要另起一个线程盯着 Ctrl-C。非 raw 模式下不能这么做——
     * 那个线程会把管道里后续的输入全吃掉。
     */
    private void runInterruptible(String prompt) {
        markdown.reset();          // 上一轮没闭合的代码块不能影响这一轮
        runStartedAt = System.nanoTime();
        runSteps = 0;
        runToolCalls = 0;
        quietBuffer.setLength(0);
        lastRunTools.clear();
        pendingToolName = null;
        replayBuffer.clear();
        recording = false;
        // 立刻起转。等 Agent 发出第一个 Thinking 事件才转的话，
        // 中间那段（建连接、压缩上下文）屏幕上是死的，用户会以为程序挂了。
        status.start("思考中…");
        if (!rawMode) {
            agent.run(prompt);
            status.stop();
            flushText();
            return;
        }
        try (InterruptWatcher watcher = new InterruptWatcher(System.in, agent::cancel)) {
            watcher.start();
            agent.run(prompt);
        } finally {
            status.stop();          // 无论如何都不能把转圈留在屏幕上
            flushText();
            collapseToolGroup();
            flushQuietBuffer();     // 中途被打断时也不能把答案吞掉
            printRunSummary();
        }
    }

    /**
     * 跑完在输入框上方留一行小结。
     *
     * 过程细节被折叠之后，用户看不出这一轮到底忙了些什么——
     * 这行摘要既是交代，也是耗时的反馈。
     */
    private void printRunSummary() {
        long seconds = (System.nanoTime() - runStartedAt) / 1_000_000_000L;
        if (seconds < 1 && runToolCalls == 0) {
            return;                 // 一问一答的小事不必汇报
        }
        StringBuilder sb = new StringBuilder("✻ 用时 ").append(seconds).append("s");
        if (runSteps > 1) {
            sb.append(" · ").append(runSteps).append(" 轮");
        }
        if (runToolCalls > 0) {
            sb.append(" · ").append(runToolCalls).append(" 次工具调用");
        }
        emit("");
        emit(Ansi.dim(sb.toString()));
    }


    private void loop() {
        int consecutiveInterrupts = 0;
        while (true) {
            String line;
            try {
                if (fullscreen != null) {
                    fullscreen.setStatus(statusBar());
                }
                line = reader.readLine(PROMPT, inputFrame());
            } catch (LineReader.Interrupted e) {
                // 空行上按 Ctrl-C。我们关掉了 SIGINT，必须自己提供这条退路，
                // 但也不能一按就退——手滑的代价太大，所以要求连按两次。
                if (++consecutiveInterrupts >= 2) {
                    break;
                }
                emit(Ansi.dim("再按一次 Ctrl-C 退出，或输入 /exit"));
                continue;
            }
            consecutiveInterrupts = 0;

            if (line == null) {                          // Ctrl-D
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (isExitCommand(line)) {
                break;
            }
            if (line.equals("/plan") || line.startsWith("/plan ")) {
                runPlan(line.substring("/plan".length()).trim());
                emit("");
                continue;
            }
            if (line.equals("/resume-plan")) {
                resumePlan();
                emit("");
                continue;
            }
            if (line.equals("/resume") || line.startsWith("/resume ")) {
                resumeSession(line.substring("/resume".length()).trim());
                emit("");
                continue;
            }
            if (line.equals("/continue")) {
                continueSession();
                emit("");
                continue;
            }
            if (handleSlashCommand(line)) {
                continue;
            }
            if (agent == null) {
                emit(noKeyMessage());
                continue;
            }
            if (maybePlanInstead(line)) {
                emit("");
                continue;
            }
            // 走 runOnce 而不是直接 runInterruptible：存盘和「这轮没做完」的提示都挂在它上面。
            // 原来这里绕开了它，于是交互模式下每轮存一次根本没发生过——
            // 盘上那些存档全是无头版（AgentServer）留下的，终端自己聊的从来没留下过。
            // 现象是「/continue 说没有可以接着聊的会话」，而你明明刚聊了半小时
            runOnce(line);
        }
        emit(Ansi.dim("bye."));
    }

    /**
     * 这句话要不要改走规划。
     *
     * <p><b>只影响直接输入的那句话，不影响 {@code /plan}</b>——显式敲了命令就是明确表态，
     * 启发式没资格插嘴。
     *
     * @return true 表示已经按规划跑完了，调用方不必再走普通对话
     */
    private boolean maybePlanInstead(String request) {
        if (planAgent == null) {
            return false;
        }
        ModeHeuristic.Decision decision = ModeHeuristic.assess(request);
        return switch (decision.mode()) {
            case DIRECT -> false;
            case PLAN -> {
                // 说清为什么——自动进了规划却不解释，用户只会觉得它突然变啰嗦
                emit(Ansi.dim("（" + decision.explain() + "，先拆个计划。/plan 可强制，"
                        + "计划出来还能否掉）"));
                runPlan(request);
                yield true;
            }
            case ASK -> {
                if (!askWhetherToPlan(decision)) {
                    yield false;
                }
                runPlan(request);
                yield true;
            }
        };
    }

    /**
     * 拿不准时问一句。
     *
     * <p>默认是「拆」（{@code [Y/n]}），因为判错的代价不对称：白拆一次计划，用户在确认那步
     * 当场就能否掉；而该拆没拆，是跑偏一路改了一堆文件之后才发现。
     */
    private boolean askWhetherToPlan(ModeHeuristic.Decision decision) {
        emit(Ansi.dim("（" + decision.explain() + "）"));
        try {
            String answer = reader.readLine("先拆个计划再做? [Y/n] ", InputFrame.none());
            if (answer == null) {                       // Ctrl-D
                return false;
            }
            String normalized = answer.trim().toLowerCase();
            return !normalized.equals("n") && !normalized.equals("no") && !normalized.equals("否");
        } catch (LineReader.Interrupted e) {            // Ctrl-C：当他不想拆
            return false;
        }
    }

    private static boolean isExitCommand(String line) {
        return EXIT_WORDS.contains(line.toLowerCase());
    }

    private String noKeyMessage() {
        return Ansi.red("未配置 API key。") + "请设置环境变量 DEEPSEEK_API_KEY，"
                + "或在 ~/.myagent/config.json 里写 {\"apiKey\": \"sk-...\"}";
    }

    /**
     * 把 agent 的事件画到终端。
     *
     * 不只是好看：一次 run 可能跑十几轮、花上一分钟，
     * 全程不出声的话，用户没法判断它是在干活还是卡死了。
     */
    private void render(AgentEvent event) {
        // 状态行和正文抢同一块屏幕，所以打印任何东西之前先把它擦掉
        status.stop();

        if (event instanceof AgentEvent.TextDelta d) {
            collapseToolGroup();        // 模型又开口了，先把上一组工具收起来
            if (detail == OutputDetail.QUIET) {
                quietBuffer.append(d.text());       // 先攒着，还不知道是不是最终答案
            } else {
                appendText(d.text());
            }
            return;
        }
        // 其余事件都要先把攒着的半行吐出来，否则会被后面的内容截断
        flushText();

        if (event instanceof AgentEvent.Thinking t) {
            runSteps = t.step();
            status.start("思考中…");

        } else if (event instanceof AgentEvent.Thought t) {
            // 文本已经由 TextDelta 打出来了，这里不再重复渲染

        } else if (event instanceof AgentEvent.ToolStarted t) {
            runToolCalls++;
            pendingToolName = t.tool();
            pendingToolArgs = t.arguments();
            if (detail == OutputDetail.QUIET) {
                // 既然来了工具调用，说明刚才攒的那段只是过程说明，不是答案
                quietBuffer.setLength(0);
            } else {
                if (toolGroupLines == 0) {
                    toolPrintln("");    // 一组工具开始前空一行
                }
                // 减去 "⏺ " 和工具名的占位，保证整行不会折行
                int room = width - 4 - Ansi.displayWidth(t.tool());
                toolPrintln(Ansi.green("⏺ ") + Ansi.bold(t.tool()) + " "
                        + Ansi.dim(oneLine(t.arguments(), Math.max(20, room))));
                toolGroupCounts.merge(t.tool(), 1, Integer::sum);
            }
            status.start("执行 " + t.tool() + "…");

        } else if (event instanceof AgentEvent.ToolFinished t) {
            audit.toolCall(currentTaskId, t.tool(), pendingToolArgs, t.ok(), t.output());
            if (pendingToolName != null) {
                lastRunTools.add(new ToolRecord(pendingToolName, pendingToolArgs,
                        t.output(), t.ok()));
                pendingToolName = null;
            }
            renderToolOutput(t);

        } else if (event instanceof AgentEvent.Done d) {
            collapseToolGroup();
            flushQuietBuffer();

        } else if (event instanceof AgentEvent.Compacted c) {
            collapseToolGroup();
            // 必须让用户知道：摘要意味着早期细节已经丢了，后面它"想不起来"是正常的
            emit("\n" + Ansi.dim("… 上下文已压缩 " + c.tokensBefore() + " → "
                    + c.tokensAfter() + " tokens"
                    + (c.summarized() ? "（早期对话已摘要）" : "（裁剪了旧的工具输出）")));

        } else if (event instanceof AgentEvent.Failed f) {
            collapseToolGroup();
            flushQuietBuffer();
            emit(Ansi.red("⚠ " + f.reason()));
        }
    }

    /**
     * 收到新的文本增量。
     *
     * 攒够一整行才渲染输出：Markdown 的行内标记要等整行到齐才能判断闭合，
     * 逐字打的话用户会眼看着星号一个个冒出来再消失。
     */
    private void appendText(String delta) {
        if (!textStarted) {
            emit("");                     // 新一段文字和上面的内容隔开
            textStarted = true;
        }
        pendingLine.append(delta);
        int newline;
        while ((newline = pendingLine.indexOf("\n")) >= 0) {
            String line = pendingLine.substring(0, newline);
            pendingLine.delete(0, newline + 1);
            emit(markdown.renderLine(line));
        }
        System.out.flush();
    }

    /**
     * 把最近一轮的工具调用详情重新打一遍。
     *
     * 终端做不到「点一下原地展开」——内容一旦打进滚动缓冲区就改不了了，
     * 除非整个界面改成全屏重绘。这里的做法是把详情追加打印在下面，
     * 信息一样拿得到。
     */
    private void expandLastRun() {
        if (lastRunTools.isEmpty()) {
            emit(Ansi.dim("最近一轮没有调用工具。"));
            return;
        }
        if (fullscreen != null) {
            // 摘要行和它之后的内容都在缓冲区里，删掉重新铺一遍就是原地展开
            List<String> tail = new ArrayList<>(replayBuffer);
            fullscreen.buffer().removeLast(tail.size() + 1);
            recording = false;
            printToolDetails();
            tail.forEach(this::emit);
            return;
        }
        // 要退回去多少行：摘要行自己 1 行，加上它之后记下来的那些
        int rewind = replayBuffer.size() + 1;
        boolean inPlace = rawMode && recording && rewind < TerminalMode.height() - 3;

        if (!inPlace) {
            // 退不回去就只能追加打印：内容已经滚出屏幕，或者压根不在交互终端里。
            // 硬退的话会把屏幕上别的东西啃掉。
            printToolDetails();
            return;
        }
        // 回到摘要行的位置，清掉它和它下面的一切，
        // 先打详情、再把原来那些内容一行不差地重放——看起来就是这一行展开了
        System.out.print(Ansi.up(rewind) + Ansi.toLineStart() + Ansi.clearToEnd());
        printToolDetails();
        replayBuffer.forEach(System.out::println);
        recording = false;          // 已经是展开状态了，不用再记账
    }

    /**
     * 用户点了输入行上方第 linesAbove 行。
     *
     * 摘要行在哪：输入行上面是上边线（1 行），再往上是摘要行之后记下的那些内容，
     * 所以摘要行正好在上方第 replayBuffer.size() + 2 行。点中了才展开，
     * 点别处什么也不做——不然点哪儿都弹一堆东西太吵。
     */
    private void handleClick(int linesAbove) {
        if (recording && linesAbove == replayBuffer.size() + 2) {
            expandLastRun();
        }
    }

    /** 把最近一轮每次工具调用的完整参数和输出打出来。 */
    private void printToolDetails() {
        for (ToolRecord record : lastRunTools) {
            emit(Ansi.green("⏺ ") + Ansi.bold(record.tool()) + " "
                    + Ansi.dim(oneLine(record.arguments(), Math.max(20, width - 8))));
            String mark = record.ok() ? Ansi.dim("  ⎿ ") : Ansi.red("  ⎿ ✗ ");
            String[] lines = record.output().split("\n");
            for (int i = 0; i < Math.min(lines.length, VERBOSE_OUTPUT_LINES); i++) {
                emit((i == 0 ? mark : "    ")
                        + Ansi.dim(oneLine(lines[i], Math.max(20, width - 6))));
            }
            if (lines.length > VERBOSE_OUTPUT_LINES) {
                emit(Ansi.dim("    … 另有 "
                        + (lines.length - VERBOSE_OUTPUT_LINES) + " 行"));
            }
        }
    }

    /** 精简模式攒下来的最终答案，到这一轮结束才打出来。 */
    private void flushQuietBuffer() {
        if (quietBuffer.length() == 0) {
            return;
        }
        emit("");
        for (String line : quietBuffer.toString().split("\n", -1)) {
            emit(markdown.renderLine(line));
        }
        quietBuffer.setLength(0);
    }

    /** 把攒着还没换行的最后半行吐出去。 */
    private void flushText() {
        if (pendingLine.length() > 0) {
            emit(markdown.renderLine(pendingLine.toString()));
            pendingLine.setLength(0);
        }
        textStarted = false;
    }

    /**
     * 工具输出默认只露第一行。
     *
     * 过程细节平时没人看，一屏全是文件内容反而把结论挤没了。
     * 注意这只影响**显示**——完整内容照样原样发给模型，它需要看全。
     */
    private void renderToolOutput(AgentEvent.ToolFinished event) {
        if (detail == OutputDetail.QUIET) {
            if (event.ok()) {
                return;             // 成功的过程一概不显示
            }
            // 失败是必须让人看见的，否则用户完全不知道出了什么事
            emit(Ansi.red("⏺ " + event.tool() + " ✗ ")
                    + Ansi.dim(oneLine(event.output(), Math.max(20, width - 8))));
            return;
        }
        if (!event.ok()) {
            toolGroupHasFailure = true;   // 出过错的这一组不收起来，得让人看见
        }
        String mark = event.ok() ? Ansi.dim("  ⎿ ") : Ansi.red("  ⎿ ✗ ");
        String[] lines = event.output().split("\n");
        int limit = detail == OutputDetail.VERBOSE ? VERBOSE_OUTPUT_LINES : COLLAPSED_OUTPUT_LINES;

        for (int i = 0; i < Math.min(lines.length, limit); i++) {
            toolPrintln((i == 0 ? mark : "    ")
                    + Ansi.dim(oneLine(lines[i], Math.max(20, width - 6))));
        }
        int hidden = lines.length - limit;
        if (hidden > 0) {
            toolPrintln(Ansi.dim("    … 另有 " + hidden + " 行"
                    + (detail == OutputDetail.VERBOSE ? "" : "（/verbose 展开）")));
        }
    }

    /** 打印一行；摘要行之后的内容要记下来，展开时原样重放。 */
    private void emit(String line) {
        if (fullscreen != null) {
            fullscreen.append(line);
            // agent 干活时输入行是空的，重绘用空输入即可
            fullscreen.render(PROMPT, "", 0);
        } else {
            System.out.println(line);
        }
        if (recording) {
            replayBuffer.add(line);
        }
    }

    /** 打印属于当前工具组的一行，记着行数好在收起时擦掉。 */
    private void toolPrintln(String text) {
        emit(text);
        toolGroupLines++;
    }

    /**
     * 把这一组工具调用的细节从屏幕上擦掉，换成一行概括。
     *
     * 三种情况不收：非交互终端（擦除靠 ANSI 光标控制，管道里只会留下乱码）、
     * 组里有失败（错误必须留在屏幕上）、以及 /verbose（用户明确要看细节，
     * 而且那时候行数可能超出一屏，擦除也不可靠）。
     */
    private void collapseToolGroup() {
        if (toolGroupLines == 0) {
            return;
        }
        if (!rawMode || toolGroupHasFailure || detail == OutputDetail.VERBOSE) {
            resetToolGroup();
            return;
        }
        if (fullscreen != null) {
            // 全屏模式下不用摆弄光标：那几行就在缓冲区里，删掉换成摘要即可，
            // 也不怕它们已经滚出屏幕
            fullscreen.buffer().removeLast(toolGroupLines);
            replayBuffer.clear();
            recording = true;
            emit(Ansi.green("⏺ ") + Ansi.dim(ToolSummary.describe(toolGroupCounts)));
            resetToolGroup();
            return;
        }
        StringBuilder erase = new StringBuilder();
        for (int i = 0; i < toolGroupLines; i++) {
            erase.append(Ansi.up(1)).append(Ansi.clearLine());
        }
        System.out.print(erase);
        emit(Ansi.green("⏺ ") + Ansi.dim(ToolSummary.describe(toolGroupCounts)));
        // 从这里开始记账：展开时要把摘要行之后的内容原样重放
        replayBuffer.clear();
        recording = true;
        resetToolGroup();
    }

    private void resetToolGroup() {
        toolGroupLines = 0;
        toolGroupHasFailure = false;
        toolGroupCounts.clear();
    }

    /**
     * 压成一行并截断到指定列数。
     *
     * 必须按<b>显示列数</b>截而不是字符数：一个汉字占两列，按字符数截出来的行
     * 仍可能超出终端宽度、被终端自动折行。而折行会让一行变成两行，
     * 收起工具组时按行数擦除就会多擦一行，把上面的正文吃掉。
     */
    private static String oneLine(String text, int maxColumns) {
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (Ansi.displayWidth(flat) <= maxColumns) {
            return flat;
        }
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (char c : flat.toCharArray()) {
            int columns = Ansi.charWidth(c);
            if (used + columns > maxColumns - 1) {
                break;
            }
            kept.append(c);
            used += columns;
        }
        return kept + "…";
    }

    /**
     * 手动压一次。
     *
     * <h3>为什么要有手动这条路</h3>
     * 自动压缩是按占用触发的，而用户比占用率更早知道「这一段聊完了」。
     * 在话题切换的当口主动压一次，摘要的边界正好落在语义边界上，
     * 比等到 93.5% 时机器随便挑一个切点要干净得多。
     *
     * <p>有后台算好的现货就直接用（零调用），没有就现算。
     */
    private void compactNow() {
        if (agent == null) {
            emit(Ansi.dim("(未配 API key，压缩无从谈起)"));
            return;
        }
        var result = agent.compactNow();
        if (result == null) {
            // 两种「压不了」长得一样但含义完全不同，混着说会让人以为命令坏了
            emit(Ansi.dim(agent.hasCompacted()
                    ? "已经压到头了——剩下的就是最近几轮，那几轮是原样保留的。"
                    : "还没有可压的内容——最近几轮原样保留，得再聊几轮才有东西可摘。"));
            return;
        }
        // 不在这儿再打一遍 token 数：压缩本身会发 Compacted 事件，render 已经画了一行。
        // 这里再打一行的话，两行的「压缩前」口径还不一样——
        // 事件说的是压缩前那份**窗口**，这里的 tokensBefore 说的是**整份记录**，
        // 于是用户会在相邻两行看到两个对不上的数字
        emit(Ansi.dim("原文一个字没丢，还留在存档里。"));
        saveSession();
    }

    private boolean handleSlashCommand(String line) {
        switch (line) {
            case "/clear" -> {
                if (agent != null) {
                    agent.reset();
                }
                // 存档留着，不删：清空的意思是「眼前这段我不要了」，
                // 不是「这段从来没发生过」。真想找回来还能 /resume 挑出来。
                // 换个 id 从头记，于是刚才那段就定在盘上不再被续写了
                sessionId = SessionId.create(System.currentTimeMillis());
                sessionStartedAt = System.currentTimeMillis();
                emit(Ansi.dim("对话历史已清空，开了一段新会话。"
                        + "（刚才那段还在 /resume 里）"));
                return true;
            }
            case "/compact" -> {
                compactNow();
                return true;
            }
            case "/tools" -> {
                for (Tool tool : tools.all()) {
                    emit("  " + Ansi.bold(tool.name()) + "  "
                            + Ansi.dim(tool.description()));
                }
                return true;
            }
            case "/history" -> {
                if (agent == null) {
                    emit(Ansi.dim("(未启用)"));
                    return true;
                }
                emit(Ansi.dim("当前上下文共 " + agent.history().size() + " 条消息，约 "
                        + agent.estimatedTokens() + " tokens（含每轮重发的工具菜单）:"));
                agent.history().forEach(m -> emit(
                        "  " + Ansi.cyan("[" + m.role() + "]") + " "
                                + (m.hasToolCalls() ? "调用 " + m.toolCallsOrEmpty().size() + " 个工具 " : "")
                                + Ansi.dim(oneLine(m.content() == null ? "" : m.content(),
                                        Math.max(20, width - 20)))));
                return true;
            }
            case "/expand" -> {
                expandLastRun();
                return true;
            }
            case "/verbose" -> {
                detail = detail.toggle(OutputDetail.VERBOSE);
                emit(Ansi.dim("过程信息：" + detail.label()));
                return true;
            }
            case "/quiet" -> {
                detail = detail.toggle(OutputDetail.QUIET);
                emit(Ansi.dim("过程信息：" + detail.label()));
                return true;
            }
            case "/config" -> {
                emit(config.toString());
                // 终端相关的实际状态，排查布局问题时一眼能看出卡在哪一步
                emit(Ansi.dim("终端: raw=" + rawMode
                        + "  交互式=" + TerminalMode.isInteractive()
                        + "  彩色=" + Ansi.isEnabled()
                        + "  stty尺寸=" + TerminalMode.width() + "x" + TerminalMode.height()
                        + "  全屏=" + (fullscreen == null ? "off"
                        : "on " + fullscreen.width() + "x" + fullscreen.height())));
                return true;
            }
            case "/help" -> {
                emit("""
                          /plan 目标  先规划后执行：拆成任务依赖图，确认后逐个跑
                                    （直接说话时，看着像好几步的活会自动走这条路，
                                      拿不准会先问你一句；查询和小改动照常直接做）
                          /resume   从这个工作区的会话历史里挑一段接着聊
                                    （/resume 2 直接挑第 2 段，不看列表）
                          /continue 接最近那段会话，不用挑
                          /resume-plan  接着跑上次没跑完的计划（做成的步骤不重来）
                          /clear    清空对话、开一段新会话（旧的还能 /resume 找回来）
                          /tools    查看已注册的工具（即发给模型的菜单）
                          /history  查看当前上下文攒了哪些消息
                          /expand   把最近一轮的工具调用详情重新打出来（等同 Ctrl-O）
                          /quiet    整个推理过程收起，只看最终答案
                          /verbose  展开工具输出（再按一次回到正常）
                          /config   查看当前配置（密钥已脱敏）
                          /exit     退出（exit / quit / q 也行）

                        展开: Ctrl-O 展开最近一轮的过程详情
                              （--mouse 可以改成点击展开，但那样终端就滚不动了）
                        退出: Ctrl-D，或空行连按两次 Ctrl-C，或输入 exit
                        中断: 任务跑到一半按 Ctrl-C 可以中止它，会话继续
                        编辑: ↑↓ 历史   Tab 补全   Ctrl-A/E 行首尾
                              Ctrl-U/K 删到行首/尾   Ctrl-W 删词""");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 先规划后执行。
     *
     * <p>三幕：规划 → 让用户过目并确认 → 逐个任务跑。
     * 中间那一幕是整个特性的价值所在——模型理解错了、拆得不合理，
     * 在这儿毙掉只花一次规划的钱；放它跑完再发现，代价是十倍。
     */
    private void runPlan(String goal) {
        if (planAgent == null) {
            emit(noKeyMessage());
            return;
        }
        if (goal.isEmpty()) {
            emit(Ansi.dim("用法: /plan <要做的事>   例如  /plan 给 Task 加上重试能力"));
            return;
        }
        beginPlanRun();
        status.start("规划中…");
        try {
            planAgent.run(goal);
        } catch (RuntimeException e) {
            emit(Ansi.red("规划失败: ") + e.getMessage());
        } finally {
            endPlanRun();
        }
    }

    /**
     * 接着跑上次没跑完的计划。
     *
     * <p>「有没有存档」在这儿就查掉，这样「没有」是一句平静的说明，
     * 而不是等编排层返回一个空 Optional 之后再猜它为什么空。
     */
    private void resumePlan() {
        if (planAgent == null) {
            emit(noKeyMessage());
            return;
        }
        if (!planAgent.hasSavedPlan()) {
            emit(Ansi.dim("这个工作区没有没跑完的计划。"));
            return;
        }
        beginPlanRun();
        try {
            planAgent.resume();
        } catch (RuntimeException e) {
            emit(Ansi.red("续跑失败: ") + e.getMessage());
        } finally {
            endPlanRun();
        }
    }

    /** 让用户拍板。看不懂的计划不该跑，跑错的代价比多问一句大得多。 */
    private boolean confirmPlan() {
        try {
            String answer = reader.readLine("执行这份计划? [Y/n] ", InputFrame.none());
            if (answer == null) {                       // Ctrl-D
                return false;
            }
            String normalized = answer.trim().toLowerCase();
            return !normalized.equals("n") && !normalized.equals("no") && !normalized.equals("否");
        } catch (LineReader.Interrupted e) {            // Ctrl-C
            return false;
        }
    }

    private void beginPlanRun() {
        markdown.reset();
        runStartedAt = System.nanoTime();
        runSteps = 0;
        runToolCalls = 0;
    }

    private void endPlanRun() {
        currentTaskId = null;
        status.stop();
        stopPlanWatcher();
    }

    /**
     * 执行期间接管 Ctrl-C。
     *
     * <p><b>不能更早开</b>：确认那一步还等着 {@code reader.readLine} 读一行，
     * 两个东西同时抢标准输入，按键会被吃掉一半。所以要等编排层说「确认通过了」
     * （{@link PlanListener#onApproved}）才开。
     */
    private void startPlanWatcher() {
        if (!rawMode || planAgent == null) {
            return;
        }
        stopPlanWatcher();
        planWatcher = new InterruptWatcher(System.in, planAgent::cancel);
        planWatcher.start();
    }

    private void stopPlanWatcher() {
        if (planWatcher != null) {
            planWatcher.close();
            planWatcher = null;
        }
    }

    /**
     * 启动时提一句「这儿有东西可以接着干」。不提的话，那些存档等于不存在。
     *
     * <p>会话这句话在改成多会话之后重写过。原来说的是「上次在这儿聊过 N 轮」，
     * 那句话有个前提：上次就只有一段。现在一个工作区下躺着好几段，而且
     * {@code /clear} 之后旧的那段还留着——照原样说，用户会被反复提醒一段
     * 他刚刚明确表示不要的对话，而且完全看不出还有别的可选。
     * 所以现在报的是<b>总数 + 最近一段长什么样</b>，两条路都摆出来。
     */
    private void announceResumable() {
        if (planAgent != null) {
            planAgent.savedPlan().ifPresent(checkpoint -> {
                emit(Ansi.dim("上次有一份计划没跑完：" + checkpoint.goal()
                        + "（还剩 " + checkpoint.remaining() + " 步）  输入 /resume-plan 接着跑"));
                emit("");
            });
        }
        // 会话存档同理：不提一句，它就等于不存在
        if (agent != null) {
            List<SessionSummary> history = sessions.list(workspace.root());
            if (!history.isEmpty()) {
                SessionSummary newest = history.get(0);
                emit(Ansi.dim("这个工作区有 " + history.size() + " 段会话。最近一段："
                        + "「" + oneLine(newest.title(), Math.max(20, width - 52)) + "」"
                        + newest.turns() + " 轮 · "
                        + Moment.describe(newest.savedAt(), System.currentTimeMillis())));
                emit(Ansi.dim("  /continue 直接接最近一段"
                        + (history.size() > 1 ? "  ·  /resume 挑一段" : "")));
                emit("");
            }
        }
    }

    /**
     * 把一次「先规划后执行」画到终端上。
     *
     * <p>这个类里<b>只有显示</b>——什么时候规划、什么时候存档、失败了要不要改道，
     * 全归 {@link PlanExecuteAgent}。这条界线是这次重构的全部意义：
     * 同一套编排原来在终端版和无头版各写了一遍，加个功能得改两处。
     */
    private final class TerminalPlanView implements PlanListener {

        @Override
        public void onPlanCreated(Plan plan) {
            status.stop();
            audit.planCreated(plan);
            long done = plan.graph().tasks().stream().filter(Task::isTerminal).count();
            if (done > 0) {
                emit(Ansi.dim("接着跑上次的计划（" + plan.graph().size()
                        + " 步里已经做完 " + done + " 步）"));
            }
            PlanView.plan(plan, width).forEach(Repl.this::emit);
            emit("");
        }

        @Override
        public void onRejected(Plan plan) {
            audit.note("plan_rejected", "goal", plan.goal());
            emit(Ansi.dim("已放弃这份计划。"));
        }

        @Override
        public void onApproved(Plan plan) {
            startPlanWatcher();
        }

        @Override
        public void onTaskStarted(Task task) {
            currentTaskId = task.id();
            audit.taskStarted(task);
            status.stop();
            emit("");
            emit(PlanView.taskStarted(task));
            status.start("执行中…");
        }

        @Override
        public void onTaskFinished(Task task) {
            audit.taskFinished(task);
            status.stop();
            flushText();
            collapseToolGroup();
            emit(PlanView.taskFinished(task));
        }

        @Override
        public void onTasksAdded(Task parent, List<Task> added) {
            emit(Ansi.dim("  ↳ 拆出 " + added.size() + " 个子任务: "
                    + added.stream().map(Task::id).collect(Collectors.joining(", "))));
        }

        @Override
        public void onReplanned(Task failed, List<Task> recovery) {
            emit(Ansi.dim("  ↻ " + failed.id() + " 失败，改走另一条路，补出 "
                    + recovery.size() + " 步: "
                    + recovery.stream().map(Task::id).collect(Collectors.joining(", "))));
        }

        @Override
        public void onAgentEvent(Task task, AgentEvent event) {
            render(event);
        }

        @Override
        public void onFinished(Plan plan, PlanResult result) {
            currentTaskId = null;
            audit.planFinished(plan, result);
            status.stop();
            flushText();
            flushQuietBuffer();
            PlanView.summary(plan, result).forEach(Repl.this::emit);
            if (result.cancelled()) {
                emit(Ansi.dim("已中断，进度留着了。输入 /resume-plan 接着跑。"));
            }
        }
    }


    private void printBanner() {
        Banner.render(Cli.VERSION, config.model(), workspace.root(),
                        tools.all().stream().map(Tool::name).toList(),
                        options.readOnly(), agent != null, width)
                .lines().forEach(this::emit);
    }

    /** 输入框的上下横线，撑满整个终端宽度。 */
    private String rule() {
        return Ansi.dim("─".repeat(Math.max(20, width)));
    }

    /** 全屏模式下常驻底部的状态栏内容。 */
    private String statusBar() {
        // 带上屏幕尺寸：布局一旦错位，第一眼就能看出是尺寸没量对
        String size = fullscreen == null ? ""
                : Ansi.dim("  ·  " + fullscreen.width() + "x" + fullscreen.height());
        if (agent == null) {
            return Ansi.dim("  未配置 API key") + size;
        }
        return ContextBar.render(agent.estimatedTokens(), agent.contextLimit(),
                agent.history().size(), config.model()) + size;
    }

    /** 输入行的上下横线，外加一行上下文用量。 */
    private InputFrame inputFrame() {
        if (fullscreen != null) {
            return InputFrame.none();    // 全屏模式的边框是整屏布局的一部分
        }
        if (agent == null) {
            return new InputFrame(rule(), rule(), null);
        }
        return new InputFrame(rule(), rule(), ContextBar.render(
                agent.estimatedTokens(), agent.contextLimit(),
                agent.history().size(), config.model()));
    }
}
