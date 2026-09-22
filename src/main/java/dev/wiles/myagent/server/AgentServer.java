package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.context.Precompute;
import dev.wiles.myagent.context.TokenLedger;
import dev.wiles.myagent.context.TokenMeter;
import dev.wiles.myagent.context.SessionId;
import dev.wiles.myagent.context.SessionSnapshot;
import dev.wiles.myagent.context.SessionStore;
import dev.wiles.myagent.context.SessionSummary;
import dev.wiles.myagent.memory.MemoryEntry;
import dev.wiles.myagent.memory.MemoryMetadata;
import dev.wiles.myagent.memory.MemoryIndex;
import dev.wiles.myagent.memory.MemoryScope;
import dev.wiles.myagent.memory.MemorySource;
import dev.wiles.myagent.memory.MemoryStore;
import dev.wiles.myagent.audit.AuditLog;
import dev.wiles.myagent.cli.Cli;
import dev.wiles.myagent.cli.CliOptions;
import dev.wiles.myagent.config.Config;
import dev.wiles.myagent.llm.DeepSeekClient;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.browser.BrowserSites;
import dev.wiles.myagent.browser.GuardedBrowserTool;
import dev.wiles.myagent.mcp.McpConfig;
import dev.wiles.myagent.mcp.McpServers;
import dev.wiles.myagent.relay.Pairing;
import dev.wiles.myagent.relay.RelayLink;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.GitBranch;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.plan.ExecutionMode;
import dev.wiles.myagent.plan.ModeHeuristic;
import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.PlanCheckpoint;
import dev.wiles.myagent.plan.PlanExecuteAgent;
import dev.wiles.myagent.plan.PlanListener;
import dev.wiles.myagent.plan.PlanContext;
import dev.wiles.myagent.plan.PlanResult;
import dev.wiles.myagent.plan.PlanStore;
import dev.wiles.myagent.plan.AgentRole;
import dev.wiles.myagent.plan.AgentRoles;
import dev.wiles.myagent.team.Pipeline;
import dev.wiles.myagent.team.TeamListener;
import dev.wiles.myagent.team.TeamResult;
import dev.wiles.myagent.plan.Planner;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.ApprovalOrigin;
import dev.wiles.myagent.tool.safety.PolicyGate;
import dev.wiles.myagent.tool.safety.PolicyStore;
import dev.wiles.myagent.tool.safety.WorkspaceGuard;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无头模式：把 agent 挂到一个只监听本机的 WebSocket 上，界面由外部进程负责。
 *
 * <p>这一层刻意只做三件事——装配、翻译、调度：
 * <ul>
 *   <li><b>装配</b>：按 {@link CliOptions} 拼出和交互式 REPL 完全一样的
 *       Workspace / ToolRegistry / Agent，一个参数都不打折</li>
 *   <li><b>翻译</b>：{@link AgentEvent} → JSON（{@link Protocol}），反向把命令解回来</li>
 *   <li><b>调度</b>：agent.run() 是阻塞的，必须挪到工作线程，否则 WebSocket 线程被占住，
 *       中断和审批都送不进来</li>
 * </ul>
 * 后端的 agent / llm / tool / context 一行都没有改动。
 *
 * <h2>为什么要 token</h2>
 * 监听在 127.0.0.1 不等于安全：本机任何进程都能连，而且浏览器里的网页也能对
 * ws://127.0.0.1:端口 发起连接（WebSocket 不受同源策略限制）。一个能读写你磁盘、
 * 能执行 shell 命令的 agent 裸奔在本地端口上，代价太大。所以：启动时生成一次性
 * token，客户端必须带上；同时拒绝任何带 Origin 头的连接——原生客户端不会发
 * Origin，发了的就是浏览器。
 */
public final class AgentServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(AgentServer.class);

    private final CliOptions options;
    private final Config config;
    private final Workspace workspace;
    private final MemoryStore memory;
    private final SessionStore sessions;
    private final ToolRegistry tools;
    /** 接入的 MCP server 们。它们是子进程，停服时必须跟着死，不能变孤儿。 */
    private final McpServers mcp;
    /**
     * 会话级 token 电表，按会话清零。
     *
     * <p>字段初始化器在构造体之前跑完，所以构造体里的 {@code newClient()} 拿得到它。
     */
    private final TokenMeter meter = new TokenMeter();
    private final Agent agent;
    private final RemoteApproval approval;
    /** 审批的总入口。{@link #approval} 只是它问不出答案时才走的那一层。 */
    private final PolicyGate policy;

    /**
     * 当前往哪一份会话存档里写，以及这段对话是什么时候开始的。
     *
     * <p>{@code volatile}：存盘发生在 worker 线程，换 id 发生在连接线程。
     */
    private volatile String sessionId = SessionId.create(System.currentTimeMillis());
    private volatile long sessionStartedAt = System.currentTimeMillis();
    /**
     * 审计轨迹。
     *
     * 早先只有那套（已经删掉的）自带终端界面会建审计，无头模式一条记录都不留——
     * 也就是说用外部界面时，agent 改了什么文件、跑了什么命令、你批准过什么，全都查无对证。
     * 那是个缺口，不是有意的设计。
     */
    private final AuditLog audit = AuditLog.forNewRun();
    /** 计划存档。断了能接着跑，靠的是它。 */
    private final PlanConfirmation planConfirm;
    /** 规划器。和 agent 用各自的客户端：取消一个不该把另一个也掐了。 */
    private final Planner planner;

    /** 当前这一轮的计划执行器，Esc/cancel 要能够到它。 */
    /**
     * 「先规划后执行」的编排层。没有 API key 时是 null。
     *
     * <p>它顺带收走了原来那两个 {@code runningPlan} / {@code runningTasks} 字段——
     * 那是为了叫停时能掐到正在跑的东西才挂在这儿的，现在取消归它管了。
     */
    private final PlanExecuteAgent planAgent;

    /** 流水线。没 API key 时是 null，和 planAgent 一样。 */
    private final Pipeline pipeline;

    /** 一次性访问令牌，随进程生灭。 */
    private final String token;

    /**
     * 广播事件的流水账（编号 + 留底），断线重连补发用（协议 1.2 节）。
     *
     * <p>按字节封顶（8MB），不按条数——一轮长回答的 text_delta 就有几千条，
     * 按条数留 4096 的话，手机重连拿到的永远只有最新一轮。8MB 大约兜得住
     * 几十轮对话；补发时连续 delta 会合并，实际发出去远小于这个数。
     */
    private final EventLog events = new EventLog(8 * 1024 * 1024);

    /**
     * 中继连接。没加 {@code --relay} 时是 null，那时一切和以前一模一样。
     *
     * <p>它是一条**出站**连接，所以家里、4G、公司网都能用——
     * 我们从不监听公网端口（协议 1.3）。
     */
    private volatile RelayLink relay;

    /** 房间号 + 配对密钥，跨重启保持。没开中继时是 null。 */
    private final Pairing pairing;

    /** agent.run() 的跑道。单线程：同一时刻只允许一轮对话。 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-run");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean busy = new AtomicBoolean(false);
    /**
     * 每个 tool_started 的参数，按流水号存着，等对应的 tool_finished 到了一起写进审计。
     *
     * <p>以前是一个 volatile 字段——那时工具严格串行，「上一个开始的」就是「这次结束的」。
     * 并行安全的调用凑批同时跑之后，开始和结束会交错到达，只有按号建表才配得对。
     */
    private final Map<Integer, String> pendingToolArgs = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);

    public AgentServer(CliOptions options, int port) {
        // 默认只听环回是安全边界；--lan 才绑 0.0.0.0，此时唯一的门禁是 token
        // ——局域网里任何设备都摸得到端口（docs/protocol.md 1.1）
        super(options.lan()
                ? new InetSocketAddress(port)
                : new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        setReuseAddr(true);

        this.options = options;

        Config loaded = Config.load();
        this.config = options.model() == null ? loaded
                : new Config(loaded.apiKey(), loaded.baseUrl(), options.model(),
                        loaded.contextTokens());

        this.workspace = new Workspace(options.workspace());
        this.token = newToken();
        // 配对身份跨重启保持——每次换一套的话，手机每次都要重新扫码，
        // 而「任何时候都能连」正是中继模式的全部目标
        this.pairing = options.relay() == null ? null
                : Pairing.loadOrCreate(Pairing.defaultFile(), options.relayReset());
        this.approval = new RemoteApproval(this::publish, this::hasPeers);
        this.planConfirm = new PlanConfirmation(this::publish, this::hasPeers);

        // 审批策略是三层套起来的，从外到里：
        //   PolicyGate        先算规则——命中就不打扰人，这是治「被问烦」的那一层
        //   audit.audited     问到人的那些留一条记录，否则事后没法回答「谁同意改的」
        //   RemoteApproval    真的去问界面，阻塞等答案
        // 顺序要紧：审计包在闸门里面，被规则直接放行的不产生审批记录——
        // 那条规则本身在存下来的那一刻已经记过一次了，再记一遍只会淹掉真正的决策
        this.policy = new PolicyGate(audit.audited(approval), PolicyStore.inUserHome());
        this.memory = MemoryStore.inUserHome();
        this.sessions = SessionStore.inUserHome();
        this.tools = options.readOnly()
                ? BuiltinTools.createReadOnly(workspace, memory)
                : BuiltinTools.createDefault(workspace, policy, memory);
        // MCP 是外挂工具：~/.myagent/mcp.json 里配了才有，没配这行是零成本。
        // 只读模式**不接**——MCP 工具做什么我们无从判断（它叫 query 也可能在写库），
        // 而只读模式承诺的是「不改任何东西」，兑现不了的承诺不能开这个口子
        this.mcp = options.readOnly() ? McpServers.start(new McpConfig(Map.of()))
                : McpServers.start(McpConfig.load());
        tools.registerAll(guardBrowserTools(mcp.tools(), policy));

        // 索引扫一次就定：它是 history 的第 0 条，改它等于改已经发生过的历史
        this.agent = config.hasApiKey()
                ? new Agent(newClient(), tools,
                        SystemPrompt.forWorkspace(workspace.root(), memory.index(workspace.root(),
                                MemoryIndex.budgetFor(config.contextTokens())),
                                options.readOnly()),
                        Agent.DEFAULT_MAX_STEPS, contextManager(options.readOnly()))
                        // 后台预压缩用独立客户端：DeepSeekClient 有可变状态，
                        // 和主循环共用会互相踩 currentCall，取消就失灵
                        .withPrecompute(new Precompute(contextManager(options.readOnly()),
                                () -> newClient()))
                        .withWritingTools(BuiltinTools.WRITING_TOOLS)
                        .onEvent(this::onAgentEvent)
                : null;
        this.planner = config.hasApiKey()
                ? new Planner(newClient())
                        .readOnly(options.readOnly())
                        .context(PlanContext.of(workspace.root(), tools))
                : null;
        // 编排全交出去，这边只留「怎么问」和「怎么发事件」
        this.planAgent = planner == null ? null
                : new PlanExecuteAgent(planner, () -> newClient(), tools,
                        workspace.root(), PlanStore.inUserHome())
                        .approval(plan -> planConfirm.confirm())
                        .listener(new ProtocolPlanView());
        // 给的是工厂：几个 worker 并行，各要各的客户端，取消才掐得准。
        // 规划器和 /plan 共用一个实例——busy 闸保证同一时刻只有一件事在跑
        this.pipeline = config.hasApiKey()
                ? new Pipeline(this::newClient, tools, workspace.root(), planner)
                        .listener(new ProtocolTeamView())
                : null;
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
    /**
     * 造一个客户端。<b>一律经过电表</b>——主循环、压缩、后台预压缩、规划器、
     * 每个子任务的执行体都走这一个入口，包在这儿就漏不掉账。
     */
    private LlmClient newClient() {
        return meter.meter(new DeepSeekClient(config));
    }

    /**
     * 浏览器工具外面套一层按站点的审批，别的 MCP 工具照旧直通。
     *
     * <p>「哪些算浏览器工具」用的是 {@link AgentRoles#BROWSER} 那条白名单——
     * <b>和规划器派活用的是同一个判据</b>。两处各写一份的话，
     * 迟早出现「这个工具规划器认得、审批层不认得」，而那个缺口的症状是
     * 一次未经同意的页面操作，从日志里根本看不出来。
     *
     * <p>一份 {@link BrowserSites} 给所有浏览器工具共用：页面停在哪个站是
     * <b>浏览器的状态</b>，不是某个工具的状态。{@code list_pages} 认到的路，
     * {@code click} 那边也得认。
     */
    private static List<Tool> guardBrowserTools(List<Tool> mcpTools, ApprovalPolicy approval) {
        BrowserSites sites = new BrowserSites();
        List<Tool> guarded = new ArrayList<>();
        for (Tool tool : mcpTools) {
            guarded.add(AgentRoles.BROWSER.matches(tool.name())
                    ? new GuardedBrowserTool(tool, approval, sites)
                    : tool);
        }
        return guarded;
    }

    private ContextManager contextManager(boolean readOnly) {
        ContextManager context = new ContextManager(config.contextTokens());
        if (readOnly) {
            return context;
        }
        return context
                // 主题词喂回给模型，让它复用已有的而不是每次另起一个近义词。
                // 取代靠主题匹配，而模型不知道历史时给出的词会随机漂移
                .withTopicHints(() -> memory.activeTopicKeys(workspace.root()))
                .onHarvest(h -> memory.save(withProvenance(h), workspace.root()));
    }

    /**
     * 把顺手捡到的一条排成记忆条目，带上溯源信息。
     *
     * <p>{@code topic_key} 让同主题的后续纠正能正确顶掉这一条；
     * {@code turn_id} 记下它是从哪一段原文里捡的——用户问「你怎么知道的」时，
     * 这是唯一能指回去的线索。
     */
    private static MemoryEntry withProvenance(ContextManager.Harvested h) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (h.topicKey() != null && !h.topicKey().isBlank()) {
            metadata.put(MemoryMetadata.TOPIC_KEY, h.topicKey().strip());
        }
        if (h.turnId() != null && !h.turnId().isBlank()) {
            metadata.put(MemoryMetadata.TURN_ID, h.turnId().strip());
        }
        return new MemoryEntry(h.name(), h.description(), h.content(),
                MemoryScope.PROJECT, MemorySource.OBSERVATION, LocalDate.now(), metadata);
    }

    /**
     * 把一次「先规划后执行」翻成协议事件。
     *
     * <p>这个类里<b>只有翻译</b>：什么时候规划、什么时候存档、失败了要不要改道，
     * 全归 {@link PlanExecuteAgent}。终端版那边是同一套编排配另一个 {@link PlanListener}——
     * 这正是把它抽出来的理由，从前加个功能得在两处各改一遍，漏一处就是
     * 「某个界面上这功能就是不灵」。
     */
    private final class ProtocolPlanView implements PlanListener {

        @Override
        public void onPlanCreated(Plan plan) {
            audit.planCreated(plan);
            publish(Protocol.encodePlan(plan));
        }

        @Override
        public void onRejected(Plan plan) {
            audit.note("plan_rejected", "goal", plan.goal());
            publish(Protocol.event("plan_finished").put("status", plan.status().name()));
        }

        @Override
        public void onTaskStarted(Task task) {
            audit.taskStarted(task);
            // 接下来弹出的确认框要说清是谁要干这事。屏幕上七八个任务依次跑过时，
            // 一个没有出处的「执行命令: rm -rf target」，用户想不清楚就只会点同意
            approval.origin(originOf(task));
            publish(Protocol.encodeTaskStarted(task));
        }

        @Override
        public void onTaskFinished(Task task) {
            audit.taskFinished(task);
            approval.origin(null);
            publish(Protocol.encodeTaskFinished(task));
        }

        @Override
        public void onTasksAdded(Task parent, List<Task> added) {
            publish(Protocol.encodeAddedTasks(parent.id(), added));
        }

        @Override
        public void onReplanned(Task failed, List<Task> recovery) {
            publish(Protocol.encodeReplanned(failed, recovery));
        }

        @Override
        public void onAgentEvent(Task task, AgentEvent event) {
            // 必须写全限定的外部类，否则解析到的是本类这个同名方法，自己递归调自己
            AgentServer.this.onAgentEvent(task, event);
        }

        @Override
        public void onFinished(Plan plan, PlanResult result) {
            audit.planFinished(plan, result);
            publish(Protocol.encodePlanFinished(plan, result));
        }
    }

    /** 任务 → 审批框上那行「谁在问」。 */
    private static ApprovalOrigin originOf(Task task) {
        return new ApprovalOrigin(task.id(), task.role().id(), task.role().title());
    }

    /** 流水线怎么发到线上。 */
    private final class ProtocolTeamView implements TeamListener {

        @Override
        public void onStarted(String goal, String reason, List<Task> stages) {
            audit.note("team_started", "goal", goal);
            publish(Protocol.encodeTeamStarted(goal, reason, stages));
        }

        @Override
        public void onStagesAdded(List<Task> stages) {
            publish(Protocol.encodeStagesAdded(stages));
        }

        @Override
        public void onStageStarted(Task stage) {
            audit.taskStarted(stage);
            approval.origin(originOf(stage));     // 理由同计划任务
            publish(Protocol.encodeStageStarted(stage));
        }

        @Override
        public void onStageFinished(Task stage) {
            audit.taskFinished(stage);
            approval.origin(null);
            publish(Protocol.encodeStageFinished(stage));
        }

        @Override
        public void onAgentEvent(Task stage, AgentEvent event) {
            // 同 ProtocolPlanView：不写全限定会解析到本类这个同名方法，自己递归调自己
            AgentServer.this.onAgentEvent(stage, event);
        }

        @Override
        public void onFinished(TeamResult result) {
            audit.note("team_finished", "status",
                    result.status() + " · " + result.spoke() + "/" + result.stages().size());
            publish(Protocol.encodeTeamFinished(result));
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String token() {
        return token;
    }

    /**
     * 启动前的工作区安全检查，语义和交互式模式保持一致。
     *
     * @return 不能启动时返回拒绝原因，可以启动返回 null
     */
    public String workspaceRejection() {
        String rejection = WorkspaceGuard.rejectionReason(workspace.root());
        return (rejection == null || options.unsafe()) ? null : rejection;
    }

    /** 阻塞到服务停止。前台跑的时候用它挂住主线程。 */
    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    /**
     * 停服时把审计日志关掉。
     *
     * 不关的话缓冲区里最后几条就丢了——而最后几条往往正是最想看的那几条
     * （用户是因为哪一步不对才退出的）。
     */
    @Override
    public void stop(int timeout) throws InterruptedException {
        try {
            super.stop(timeout);
        } finally {
            RelayLink link = relay;
            if (link != null) {
                link.close();
            }
            audit.close();
            mcp.close();     // MCP server 是子进程，不关就是孤儿
        }
    }

    // ---------------------------------------------------------------- 连接

    @Override
    public void onStart() {
        setConnectionLostTimeout(30);
        // 端口传 0 时要等到真正绑定完才知道系统给了哪个端口，所以握手信息在这里打。
        // stdout 只走这一行结构化输出，界面进程读到它就知道该连哪儿；日志一律走 stderr。
        ObjectNode hello = Protocol.event("listening");
        hello.put("port", getPort());
        hello.put("token", token);
        hello.put("protocol", Protocol.VERSION);
        if (options.lan()) {
            // 替客户端把「该连哪块网卡」挑好。挑不出（无网）就不打这个字段——
            // 界面看不到 lan 字段自然不会摆出二维码入口，比给一个连不上的地址诚实
            LanAddress.pick().ifPresentOrElse(
                    addr -> hello.put("lan", addr),
                    () -> log.warn("--lan 已开但找不到局域网地址，二维码配对不可用"));
            log.warn("局域网模式：0.0.0.0:{} 对整个网络可见，门禁只有 token（明文 ws）",
                    getPort());
        }
        if (pairing != null) {
            // 配对 URL 里带着密钥，**只打给本机的界面看**（stdout），不发给任何人
            hello.put("pair", pairing.pairingUrl(options.relay()));
            relay = new RelayLink(options.relay(), pairing,
                    // 中继拆封出来的就是普通命令，和本机连接走同一条处理路径
                    message -> handleCommand(relay, message),
                    present -> log.info(present ? "手机连上来了" : "手机断开了"));
            relay.start();
        }
        System.out.println(hello.toString());
        System.out.flush();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // 浏览器一定会带 Origin，原生客户端不会。想接 Web 前端时在这里换成白名单。
        if (handshake.hasFieldValue("Origin")) {
            conn.close(1008, "origin not allowed");
            return;
        }
        if (!token.equals(queryParam(handshake.getResourceDescriptor(), "token"))) {
            conn.close(1008, "bad token");
            return;
        }
        // since = 客户端上次看到的 seq：0 是「把留底全给我」（手机首连），
        // 不带就是「只要新的」（终端每次都是全新会话，补发只会搅乱它）
        greet(Peer.of(conn), parseSince(queryParam(handshake.getResourceDescriptor(), "since")));
    }

    /**
     * 首帧 + 补发。本机连接在握手后走这儿，中继来的客户端靠一条 {@code hello} 命令走这儿。
     *
     * <p>为什么中继那边要用命令而不是握手参数：手机连的是 relay 不是我们，
     * 它的 {@code since} 塞不进我们看得见的 URL。而这一层本来就该由**内层协议**回答，
     * 中继只是信封（协议 1.3）。
     *
     * <p>锁住流水账再发：这期间不许有新广播插队，否则「ready 里的 lastSeq」
     * 和补发内容之间会漏帧。补发完释放锁，新事件照常广播过去，正好接上。
     */
    private void greet(Peer peer, long since) {
        synchronized (events) {
            peer.send(ready());
            if (since >= 0) {
                EventLog.Replay replay = events.replay(since);
                if (replay.gap()) {
                    // 如实报缺口，别静默跳过——转录豁了口子还看不出来，没人查得动
                    peer.send(Protocol.event("replay_gap")
                            .put("from", replay.gapFrom())
                            .put("to", replay.gapTo()));
                }
                for (String line : replay.events()) {
                    peer.send(line);
                }
            }
        }
    }

    /** since 参数：不带或不是数字 = -1（不补发）。 */
    private static long parseSince(String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        // 最后一个界面走了就没人能审批了，卡在审批上的那轮得放行，否则线程永远醒不过来
        if (getConnections().isEmpty()) {
            approval.cancelPending();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.warn("WebSocket 出错", ex);
        if (conn == null) {
            // 连接级的错误已经由框架关掉了；这里是服务级的，救不回来
            stopped.countDown();
        }
    }

    // ---------------------------------------------------------------- 收命令

    @Override
    public void onMessage(WebSocket conn, String message) {
        handleCommand(Peer.of(conn), message);
    }

    /**
     * 一条命令，不管它是从本机 WebSocket 来的还是从中继拆封出来的。
     *
     * <p>两条入口在这儿汇合：分开写的话，每加一条命令就要在两处各写一遍，
     * 而漏写一处的症状是「手机上这个功能就是不响应」——极难查（devlog 记过同类）。
     */
    void handleCommand(Peer peer, String message) {
        JsonNode cmd;
        try {
            cmd = Protocol.mapper().readTree(message);
        } catch (Exception e) {
            peer.send(error("报文不是合法 JSON"));
            return;
        }
        String type = Protocol.str(cmd, "t", "");
        switch (type) {
            case "prompt" -> handlePrompt(peer, Protocol.str(cmd, "text", "").trim(),
                    Protocol.str(cmd, "cid", ""));
            case "cancel" -> handleCancel();
            case "approval_reply" -> {
                int id = Protocol.num(cmd, "id", -1);
                if (approval.reply(id, Protocol.str(cmd, "decision", "no"),
                        Protocol.strings(cmd, "rules"), Protocol.str(cmd, "feedback", ""))) {
                    // 谁答的都行，但**所有**界面都得立刻收框——手机上点了同意，
                    // 终端上的框却挂到整轮结束，看起来就是「问题还在等我」（协议 3.3）
                    publish(Protocol.event("approval_resolved").put("id", id));
                }
            }
            case "plan" -> handlePlan(peer, Protocol.str(cmd, "goal", "").trim(),
                    Protocol.str(cmd, "cid", ""));
            case "team" -> handleTeam(peer, Protocol.str(cmd, "goal", "").trim(),
                    Protocol.strings(cmd, "roles"), Protocol.str(cmd, "cid", ""));
            // resume 是老名字（那会儿只有计划能续）。终端那边已经改叫 /resume-plan，
            // 协议这边两个都认：改名不该让还没更新的界面连不上
            case "resume", "resume_plan" -> handleResume(peer);
            case "continue" -> handleContinue(peer);
            case "sessions" -> handleSessions(peer);
            case "session_resume" -> handleSessionResume(peer, Protocol.str(cmd, "id", ""));
            case "plan_confirm_reply" -> {
                int id = Protocol.num(cmd, "id", -1);
                if (planConfirm.reply(id, Protocol.bool(cmd, "approved", false))) {
                    publish(Protocol.event("plan_confirm_resolved").put("id", id));
                }
            }
            case "compact" -> handleCompact(peer);
            case "memory" -> handleMemory(peer, Protocol.str(cmd, "action", "list"));
            case "reset" -> handleReset(peer);
            // 中继来的客户端用它要首帧 + 补发（本机连接靠握手参数，见 greet）
            case "hello" -> greet(peer, Protocol.num(cmd, "since", -1));
            case "ping" -> peer.send(Protocol.event("pong"));
            default -> peer.send(error("未知命令: " + type));
        }
    }

    private void handlePrompt(Peer peer, String text, String cid) {
        if (agent == null) {
            peer.send(error("没有配置 API key，无法对话。设置环境变量 DEEPSEEK_API_KEY "
                    + "或写进 ~/.myagent/config.json"));
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            peer.send(error("正在处理上一条，请先等它结束或按 Esc 中断"));
            return;
        }
        // 先播「用户说了这句话」，再播 busy——顺序就是屏幕上该出现的顺序。
        // 少了这一条，另一块屏上会凭空冒出一段回答（协议 3.15）
        publish(Protocol.encodeUserMessage(text, "normal", cid));
        publish(Protocol.event("busy").put("busy", true));

        // 看一眼这句话该不该改走规划。**ASK 在这儿并到 PLAN**：无头模式问不了，
        // 而规划本来就带一道确认（plan_confirm_request），「问一句」的效果它已经有了，
        // 代价只是多花一次规划调用。为此单开一条协议消息不划算
        ModeHeuristic.Decision decision = ModeHeuristic.assess(text);
        if (planAgent != null && decision.mode() != ExecutionMode.DIRECT) {
            publish(Protocol.encodeModeSwitch(decision.explain()));
            worker.submit(() -> runPlan(text));
            return;
        }

        worker.submit(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                agent.run(text);                 // 事件在 onAgentEvent 里已经播出去了
            } catch (RuntimeException e) {
                log.error("agent 跑挂了", e);
                publish(Protocol.event("failed").put("reason", String.valueOf(e.getMessage())));
            } finally {
                // 一轮说完存一次盘，粒度和计划存档的「每了结一个任务存一次」对齐
                sessions.save(sessionId, agent.history(), workspace.root(),
                        config.model(), sessionStartedAt, agent.compaction(), meter.snapshot());
                busy.set(false);
                ObjectNode idle = Protocol.event("busy");
                idle.put("busy", false);
                idle.put("durationMs", System.currentTimeMillis() - startedAt);
                idle.put("tokens", agent.estimatedTokens());
                idle.put("sentTokens", meter.snapshot().promptTokens());
                idle.put("contextLimit", agent.contextLimit());
                publish(idle);
            }
        });
    }

    /**
     * 先规划后执行。三幕：规划 → 让界面确认 → 逐个任务跑。
     * 编排本身归 {@link PlanExecuteAgent}，这儿只负责把过程翻译成事件发出去，
     * 以及用 {@link PlanConfirmation} 等界面回一句「跑不跑」。
     */
    private void handlePlan(Peer peer, String goal, String cid) {
        if (planner == null) {
            peer.send(error("没有配置 API key，无法规划"));
            return;
        }
        if (goal.isEmpty()) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            peer.send(error("正在处理上一条，请先等它结束或按 Esc 中断"));
            return;
        }
        publish(Protocol.encodeUserMessage(goal, "plan", cid));
        publish(Protocol.event("busy").put("busy", true));
        worker.submit(() -> runPlan(goal));
    }

    /**
     * 开一条流水线。
     *
     * <p>和 {@code plan} 一样先占 busy 再丢后台：三道工序是好几次完整的子循环，
     * 期间再来一条 prompt 会把两段历史搅在一起。
     *
     * <p><b>没有确认那一幕</b>。理由和计划不同：计划要确认的是「怎么拆」且拆完才执行；
     * 流水线的拆法是执行的一部分（规划工序拆出 worker 就接着跑），
     * 中途不满意按 Esc 就是了。
     *
     * <p>{@code roles} 字段已废弃：分工由规划工序决定（它给每个任务派角色）。
     * 老界面传了就忽略——废弃字段报错等于把还没更新的界面锁在门外。
     */
    private void handleTeam(Peer peer, String goal, List<String> roleIds, String cid) {
        if (pipeline == null) {
            peer.send(error("没有配置 API key，无法开流水线"));
            return;
        }
        if (goal.isEmpty()) {
            return;
        }
        if (!roleIds.isEmpty()) {
            log.info("team 命令带了已废弃的 roles 字段，忽略: {}", roleIds);
        }
        if (!busy.compareAndSet(false, true)) {
            peer.send(error("正在处理上一条，请先等它结束或按 Esc 中断"));
            return;
        }
        publish(Protocol.encodeUserMessage(goal, "team", cid));
        publish(Protocol.event("busy").put("busy", true));
        worker.submit(() -> runTeam(goal));
    }

    private void runTeam(String goal) {
        long startedAt = System.currentTimeMillis();
        try {
            pipeline.run(goal);
        } catch (RuntimeException e) {
            log.error("流水线跑挂了", e);
            publish(Protocol.event("failed").put("reason", String.valueOf(e.getMessage())));
        } finally {
            settleBusy(startedAt);
        }
    }

    /**
     * 接着跑上次没跑完的计划。
     *
     * <p>「有没有存档」这件事在这儿就查掉，而不是丢进后台线程再说——
     * 没有存档是个该定向回给发命令那个连接的错误（`error` 只发给他一个人），
     * 而后台线程手上没有 conn，只能广播，等于打扰了所有连着的界面。
     */
    private void handleResume(Peer peer) {
        if (planner == null) {
            peer.send(error("没有配置 API key，无法执行计划"));
            return;
        }
        if (!planAgent.hasSavedPlan()) {
            peer.send(error("这个工作区没有没跑完的计划"));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            peer.send(error("正在处理上一条，请先等它结束或按 Esc 中断"));
            return;
        }
        publish(Protocol.event("busy").put("busy", true));
        worker.submit(this::runResume);
    }

    /**
     * 把上次没聊完的对话接回来。
     *
     * <p>和 {@code handleResume} 一样是<b>同步</b>完成的，不丢进 worker：
     * 恢复只是往内存里塞一段历史，没有模型调用，占不了多久。
     * 丢进后台反而要处理「恢复期间又来了一条 prompt」这种本来不存在的竞态。
     *
     * <p>三道闸都过不了就定向回一条 error 给发命令的那个连接，
     * 而不是广播——别的界面没做错什么，不该被打扰。
     */
    private void handleContinue(Peer peer) {
        if (agent == null) {
            peer.send(error("没有配置 API key，无法对话"));
            return;
        }
        if (busy.get()) {
            peer.send(error("正在处理中，不能恢复会话"));
            return;
        }
        var snapshot = sessions.latest(workspace.root());
        if (snapshot.isEmpty()) {
            peer.send(error("这个工作区没有可以接着聊的会话"));
            return;
        }
        if (agent.history().size() > 1) {
            // 恢复会把眼前这段直接冲掉，这个决定得由用户来做
            peer.send(error("当前已经有对话了。要接上次的，先 reset 再 continue"));
            return;
        }

        adopt(snapshot.get());
    }

    /**
     * 把这个工作区的会话历史发给界面，让它自己画选择器。
     *
     * <p>为什么不塞进 {@code ready} 帧：一个工作区能攒下几十段会话，
     * 而首帧是每次连上来都要发的。要列表的界面自己问一句，代价是一轮往返，
     * 换来的是首帧不会随着历史变长而慢慢膨胀。
     *
     * <p>只回给问的那个连接。别的界面没在挑会话，不该收到一坨列表。
     */
    private void handleSessions(Peer peer) {
        ObjectNode list = Protocol.event("session_list");
        list.put("currentId", sessionId);
        ArrayNode rows = list.putArray("sessions");
        for (SessionSummary summary : sessions.list(workspace.root())) {
            ObjectNode row = rows.addObject();
            row.put("id", summary.id());
            row.put("title", summary.title());
            row.put("turns", summary.turns());
            row.put("model", summary.model());
            row.put("branch", summary.branch());       // 可能是 null：不在 git 仓库里，或是老存档
            row.put("startedAt", summary.startedAt());
            row.put("savedAt", summary.savedAt());
            row.put("bytes", summary.bytes());
        }
        peer.send(list);
    }

    /**
     * 按 id 接一段历史会话。
     *
     * <p>和 {@code continue} 的分工和终端那边一样：{@code continue} 是「接最近那段」，
     * 不用挑；这个是界面从列表里挑了一段之后发过来的。三道闸完全一致，
     * 只多一道「这个 id 还在不在」——列出来到挑中之间隔着用户的思考时间，
     * 那段会话可能已经被另一个界面清掉了。
     */
    private void handleSessionResume(Peer peer, String id) {
        if (agent == null) {
            peer.send(error("没有配置 API key，无法对话"));
            return;
        }
        if (busy.get()) {
            peer.send(error("正在处理中，不能恢复会话"));
            return;
        }
        if (agent.history().size() > 1) {
            peer.send(error("当前已经有对话了。要接另一段，先 reset 再 session_resume"));
            return;
        }
        var snapshot = sessions.load(workspace.root(), id);
        if (snapshot.isEmpty()) {
            peer.send(error("这段会话找不到了，可能是别处清掉的"));
            return;
        }
        adopt(snapshot.get());
    }

    /**
     * 把一段存档立成当前会话，并广播回执。
     *
     * <p>{@code continue} 和 {@code session_resume} 都落到这儿。抽出来是因为
     * 「换 sessionId 和 sessionStartedAt」漏了不会报错，
     * 只会让接着聊的内容另起一份新档，而原来那段永远停在被接回来的那一刻。
     *
     * <p>这条是<b>广播</b>而不是定向回执：连着的每个界面都得知道当前会话被换掉了，
     * 否则它们还在画上一段的上下文。
     */
    private void adopt(SessionSnapshot snapshot) {
        // 段一起带回来，续聊时不用重新 map 一遍
        int restored = agent.restore(snapshot.restorable(), snapshot.usableCompaction());
        // 账本也接回来：对话是接着的，账就不该归零
        meter.reset();
        meter.seed(snapshot.ledger());
        sessionId = snapshot.id();
        sessionStartedAt = snapshot.startedAt();
        ObjectNode done = Protocol.event("session_restored");
        done.put("id", snapshot.id());
        done.put("messages", restored);
        done.put("turns", snapshot.turns());
        done.put("firstMessage", snapshot.firstUserMessage());
        done.put("savedAt", snapshot.savedAt());
        done.put("tokens", agent.estimatedTokens());
        done.put("sentTokens", meter.snapshot().promptTokens());
        done.put("contextLimit", agent.contextLimit());
        publish(done);
    }

    private void runResume() {
        long startedAt = System.currentTimeMillis();
        try {
            if (planAgent.resume().isEmpty() && !planAgent.hasSavedPlan()) {
                // 从 handleResume 查过到这儿之间，存档可能被删了（另一个会话跑完了它）。
                // 用户否决也会返回 empty，但那时存档还在，且 onRejected 已经发过话了
                publish(Protocol.event("failed").put("reason", "存档没了，没法接着跑"));
            }
        } catch (RuntimeException e) {
            log.error("续跑挂了", e);
            publish(Protocol.event("failed").put("reason", String.valueOf(e.getMessage())));
        } finally {
            settleBusy(startedAt);
        }
    }

    private void runPlan(String goal) {
        long startedAt = System.currentTimeMillis();
        try {
            planAgent.run(goal);
        } catch (RuntimeException e) {
            log.error("计划跑挂了", e);
            publish(Protocol.event("failed").put("reason", String.valueOf(e.getMessage())));
        } finally {
            settleBusy(startedAt);
        }
    }

    /** 上次没跑完的计划。没配 API key 时没有编排层，也就无从谈起。 */
    private Optional<PlanCheckpoint> savedPlan() {
        return planAgent == null ? Optional.empty() : planAgent.savedPlan();
    }

    /** 收工：清 busy 标志并广播。规划和续跑两条路都要走，抽出来免得漏一条。 */
    private void settleBusy(long startedAt) {
        busy.set(false);
        ObjectNode idle = Protocol.event("busy");
        idle.put("busy", false);
        idle.put("durationMs", System.currentTimeMillis() - startedAt);
        publish(idle);
    }

    private void handleCancel() {
        if (agent != null) {
            agent.cancel();
        }
        // 计划模式下要叫停的那三处（调度器、执行体、规划器）归编排层管了
        if (planAgent != null) {
            planAgent.cancel();
        }
        if (pipeline != null) {
            pipeline.cancel();                  // 流水线那两处（不开下一道工序、掐正在跑的）归它管
        }
        planConfirm.cancelPending();            // 卡在计划确认上的也要放行
        approval.cancelPending();               // 卡在审批上的那轮也要放行
    }

    /**
     * 手动压一次。
     *
     * <p>用户比占用率更早知道「这一段聊完了」——在话题切换的当口压，
     * 摘要边界正好落在语义边界上，比等到 93.5% 时机器随便挑一个切点干净得多。
     * 有后台算好的现货就直接用，没有就现算。
     */
    private void handleCompact(Peer peer) {
        if (agent == null) {
            peer.send(error("没配 API key，压缩无从谈起"));
            return;
        }
        // 和 handlePrompt 用同一个闸：压缩改写 agent 的派生物（segments/summary/coversUpTo），
        // 和一轮对话并发跑会互相踩
        if (!busy.compareAndSet(false, true)) {
            peer.send(error("正在处理中，压缩要等这一轮跑完"));
            return;
        }
        publish(Protocol.event("busy").put("busy", true));
        long startedAt = System.currentTimeMillis();

        // 必须扔到 worker 上。原先是直接在这儿跑的，而这里是 WebSocket 的解码线程：
        // 同一条连接的后续报文全排在它后面，于是压缩那几十秒里 `cancel` **根本送不进来**，
        // 用户按 Esc 毫无反应，界面上也没有 busy——看起来就是整个客户端死了
        worker.submit(() -> {
            try {
                if (agent.compactNow() == null) {
                    publish(error(agent.hasCompacted()
                            ? "已经压到头了——剩下的就是最近几轮，那几轮是原样保留的"
                            : "还没有可压的内容——最近几轮原样保留，得再聊几轮才有东西可摘"));
                    return;
                }
                // 不在这儿 publish compacted —— 压缩落地时 Agent 已经发过一次，
                // 经 onAgentEvent 播出去了。这儿再发一条，界面上就是同一次压缩画两遍
                sessions.save(sessionId, agent.history(), workspace.root(),
                        config.model(), sessionStartedAt, agent.compaction(), meter.snapshot());
            } catch (RuntimeException e) {
                log.error("压缩跑挂了", e);
                publish(error("压缩失败：" + e.getMessage()));
            } finally {
                busy.set(false);
                ObjectNode idle = Protocol.event("busy");
                idle.put("busy", false);
                idle.put("durationMs", System.currentTimeMillis() - startedAt);
                idle.put("tokens", agent.estimatedTokens());
                idle.put("sentTokens", meter.snapshot().promptTokens());
                idle.put("contextLimit", agent.contextLimit());
                publish(idle);
            }
        });
    }

    /**
     * 长期记忆的查看与清理。
     *
     * <h3>为什么读的是审计视角，而注入提示词用的是另一份</h3>
     * 这里给 {@code scan()}（含已被取代的条目），提示词那边给 {@code scanActive()}。
     * 两份刻意不同：过期事实注入进去，模型会同时收到「用 npm」和「用 bun」
     * 而无从判断哪条新；但用户要能看见<b>系统曾经记过什么</b>，
     * 否则「它为什么突然改口了」永远查不清。
     *
     * <h3>不拦 busy</h3>
     * 和 {@code /compact} 不一样——这三个动作都不碰会话记录，也不发模型调用，
     * 跑着任务时查一眼记忆是完全安全的。
     */
    private void handleMemory(Peer peer, String action) {
        switch (action) {
            case "list" -> {
                ObjectNode list = Protocol.event("memory_list");
                ArrayNode rows = list.putArray("entries");
                for (MemoryEntry e : memory.scan(workspace.root())) {
                    ObjectNode row = rows.addObject();
                    row.put("name", e.name());
                    row.put("description", e.description());
                    row.put("scope", e.scope().wire());
                    row.put("source", e.source().wire());
                    row.put("sourceLabel", e.source().label());
                    row.put("created", e.created().toString());
                    row.put("topicKey", e.topicKey());              // 可能是 null
                    row.put("supersededBy", e.supersededBy());      // 非空即已失效
                    row.put("turnId", e.metadata().get(MemoryMetadata.TURN_ID));
                }
                peer.send(list);
            }
            case "undo" -> memory.undoLastAutoWrite(workspace.root()).ifPresentOrElse(
                    e -> peer.send(Protocol.event("memory_changed")
                            .put("action", "undo")
                            .put("name", e.name())
                            .put("description", e.description())),
                    () -> peer.send(error("没有可撤销的自动记录"
                            + "（只认压缩时顺手捡的那些，你自己让记的不在此列）")));
            case "clear_auto" -> peer.send(Protocol.event("memory_changed")
                    .put("action", "clear_auto")
                    .put("removed", memory.clearAutoMemories(workspace.root())));
            default -> peer.send(error("认不出的 memory action: " + action
                    + "（认 list / undo / clear_auto）"));
        }
    }

    private void handleReset(Peer peer) {
        if (busy.get()) {
            peer.send(error("正在处理中，不能清空"));
            return;
        }
        if (agent != null) {
            agent.reset();
        }
        // 存档留着，不删：清空的意思是「眼前这段我不要了」，不是「这段从来没发生过」。
        // 想找回来还能从 sessions 列表里挑出来。和终端的 /clear 是同一条语义——
        // 两边不一致的话，同一个存档在两个界面里表现不同，那种 bug 没人查得动
        sessionId = SessionId.create(System.currentTimeMillis());
        sessionStartedAt = System.currentTimeMillis();
        meter.reset();      // 新会话新账本，和终端的 /clear 是同一条语义
        // 授权规则**不清**。以前这里有一句 approval.reset()，清的是那个
        // 「本会话都同意」的全局开关——它本来就该跟着会话走。
        // 现在的授权是用户一条条挑定、并且存进了 ~/.myagent/policy.json 的东西，
        // 清空对话没有理由把它撤销；要撤销就去改那个文件
        publish(Protocol.event("reset_done"));
    }

    // ---------------------------------------------------------------- 发事件

    private void onAgentEvent(AgentEvent event) {
        recordToAudit(event);
        publish(Protocol.encode(event));
    }

    /**
     * 计划/流水线里子任务的事件：标上任务号再播。
     *
     * <p>并行的任务各自在发事件，不标出处的话，界面上两条线的动静会被
     * 当成同一条线上的先后两步。主对话走上面那个不带号的入口。
     */
    private void onAgentEvent(Task task, AgentEvent event) {
        recordToAudit(event);
        publish(Protocol.encode(event, task.id()));
    }

    /**
     * 把工具调用记进审计。
     *
     * 参数只在 ToolStarted 上有、结果只在 ToolFinished 上有，所以得把参数暂存一手
     * 等结果到了再一起落盘，按流水号配对（并行的调用开始和结束会交错到达）。
     */
    private void recordToAudit(AgentEvent event) {
        if (event instanceof AgentEvent.ToolStarted t) {
            pendingToolArgs.put(t.id(), t.arguments());
        } else if (event instanceof AgentEvent.ToolFinished t) {
            audit.toolCall(null, t.tool(), pendingToolArgs.remove(t.id()), t.ok(), t.output());
        }
    }

    /** 首帧：把界面画第一屏需要的所有静态信息一次给全，省掉一轮往返。 */
    private ObjectNode ready() {
        ObjectNode n = Protocol.event("ready");
        n.put("protocol", Protocol.VERSION);
        n.put("version", Cli.VERSION);
        n.put("workspace", workspace.root().toString());
        n.put("model", config.model());
        // 真正的 git 分支。界面原来在状态栏上显示工作区的目录名冒充分支
        // （myagent 仓库在 dev 上，状态栏却写 git:(myagent)），那不是简写，是错的
        n.put("branch", GitBranch.of(workspace.root()));
        n.put("planMode", options.readOnly());   // 线上字段名保持不变，前端认的是这个
        n.put("hasApiKey", config.hasApiKey());
        // 界面退出时会把这个路径显示给用户，好让人知道去哪儿查这趟做了什么。
        // 新增字段不升协议版本——界面本来就该忽略不认识的字段（见 docs/protocol.md 第 2 节）
        n.put("auditLog", audit.file().toString());
        // 能力清单：界面据此决定要不要放出「先规划后执行」的入口
        ArrayNode features = n.putArray("features");
        Protocol.FEATURES.forEach(features::add);
        // 到此刻为止的最后一个事件号。重连的客户端拿它和自己看到的比对
        n.put("lastSeq", events.lastSeq());
        n.put("busy", busy.get());
        if (agent != null) {
            n.put("tokens", agent.estimatedTokens());
            n.put("sentTokens", meter.snapshot().promptTokens());
            n.put("contextLimit", agent.contextLimit());
        }
        // 上次没跑完的计划。放进首帧而不是让界面再问一次——
        // 界面画第一屏时就该知道要不要摆出「接着跑」那个入口
        savedPlan().ifPresent(checkpoint -> {
            ObjectNode resumable = n.putObject("resumable");
            resumable.put("goal", checkpoint.goal());
            resumable.put("remaining", checkpoint.remaining());
            resumable.put("total", checkpoint.tasks().size());
            resumable.put("savedAt", checkpoint.savedAt());
        });
        // 上次没聊完的对话，同理放进首帧
        if (agent != null) {
            List<SessionSummary> history = sessions.list(workspace.root());
            if (!history.isEmpty()) {
                SessionSummary newest = history.get(0);
                ObjectNode continuable = n.putObject("continuable");
                continuable.put("id", newest.id());
                continuable.put("branch", newest.branch());
                continuable.put("turns", newest.turns());
                continuable.put("firstMessage", newest.title());
                continuable.put("savedAt", newest.savedAt());
                // messages 是文档里写明的字段，摘要里算不出来（那是修剪之后的条数），
                // 为它多读一次存档。首帧一个连接只发一次，这点代价换契约不变是值得的
                sessions.latest(workspace.root()).ifPresent(
                        snapshot -> continuable.put("messages", snapshot.restorable().size()));
                // 一共几段。界面靠它决定要不要放出「挑一段」那个入口——
                // 只有一段时摆个选择器是纯噪音，直接 continue 就行
                continuable.put("count", history.size());
            }
        }
        String rejection = WorkspaceGuard.rejectionReason(workspace.root());
        if (rejection != null) {
            n.put("workspaceWarning", rejection);   // 能走到这儿说明是 --unsafe 放行的
        }
        // 角色名册。界面靠它给每一步标出「派了谁」，不用自己维护一张表
        n.set("roles", Protocol.encodeRoles());
        ArrayNode list = n.putArray("tools");
        for (Tool tool : tools.all()) {
            list.addObject().put("name", tool.name()).put("description", tool.description());
        }
        return n;
    }

    private static ObjectNode error(String message) {
        return Protocol.event("error").put("message", message);
    }

    /**
     * 广播，带编号留底。
     *
     * <p>锁把「编号 + 播出」箍成一步：编号顺序必须等于发送顺序，
     * 不然重连补发出去的序列和当时播的对不上。锁内只有内存操作和
     * 往各连接的发送队列里塞一条（java-websocket 的 send 不等网络），占不了多久。
     */
    private void publish(ObjectNode node) {
        synchronized (events) {
            String json = events.stamp(node);
            broadcast(json);                      // 本机连着的界面（终端）
            RelayLink link = relay;
            if (link != null) {
                link.send(json);                  // 中继那头的手机
            }
        }
    }

    /** 有没有人在看。审批要问人，一个界面都没有时只能按否决处理。 */
    private boolean hasPeers() {
        RelayLink link = relay;
        return !getConnections().isEmpty() || (link != null && link.isOpen());
    }

    /** 从 "/?token=abc&x=1" 里抠出一个查询参数。够用就行，不引入 URL 解析库。 */
    private static String queryParam(String resource, String key) {
        if (resource == null) {
            return null;
        }
        int q = resource.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : resource.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
