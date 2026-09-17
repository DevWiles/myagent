package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.cli.Cli;
import dev.wiles.myagent.cli.CliOptions;
import dev.wiles.myagent.config.Config;
import dev.wiles.myagent.llm.DeepSeekClient;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.PlanCheckpoint;
import dev.wiles.myagent.plan.PlanExecuteAgent;
import dev.wiles.myagent.plan.PlanListener;
import dev.wiles.myagent.plan.PlanContext;
import dev.wiles.myagent.plan.PlanResult;
import dev.wiles.myagent.plan.PlanStore;
import dev.wiles.myagent.plan.Planner;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.safety.WorkspaceGuard;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final ToolRegistry tools;
    private final Agent agent;
    private final RemoteApproval approval;
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

    /** 一次性访问令牌，随进程生灭。 */
    private final String token;

    /** agent.run() 的跑道。单线程：同一时刻只允许一轮对话。 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-run");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** 工具调用流水号，用来把 tool_started / tool_finished 配成一对。 */
    private final AtomicInteger toolSeq = new AtomicInteger(0);
    private final CountDownLatch stopped = new CountDownLatch(1);

    public AgentServer(CliOptions options, int port) {
        super(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        setReuseAddr(true);

        this.options = options;

        Config loaded = Config.load();
        this.config = options.model() == null ? loaded
                : new Config(loaded.apiKey(), loaded.baseUrl(), options.model());

        this.workspace = new Workspace(options.workspace());
        this.token = newToken();
        this.approval = new RemoteApproval(this::publish, () -> !getConnections().isEmpty());
        this.planConfirm = new PlanConfirmation(this::publish, () -> !getConnections().isEmpty());

        this.tools = options.readOnly()
                ? BuiltinTools.createReadOnly(workspace)
                : BuiltinTools.createDefault(workspace, approval);

        this.agent = config.hasApiKey()
                ? new Agent(new DeepSeekClient(config), tools,
                        SystemPrompt.forWorkspace(workspace.root()))
                        .onEvent(this::onAgentEvent)
                : null;
        this.planner = config.hasApiKey()
                ? new Planner(new DeepSeekClient(config))
                        .readOnly(options.readOnly())
                        .context(PlanContext.of(workspace.root(), tools))
                : null;
        // 编排全交出去，这边只留「怎么问」和「怎么发事件」
        this.planAgent = planner == null ? null
                : new PlanExecuteAgent(planner, () -> new DeepSeekClient(config), tools,
                        workspace.root(), PlanStore.inUserHome())
                        .approval(plan -> planConfirm.confirm())
                        .listener(new ProtocolPlanView());
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
            publish(Protocol.encodePlan(plan));
        }

        @Override
        public void onRejected(Plan plan) {
            publish(Protocol.event("plan_finished").put("status", plan.status().name()));
        }

        @Override
        public void onTaskStarted(Task task) {
            publish(Protocol.encodeTaskStarted(task));
        }

        @Override
        public void onTaskFinished(Task task) {
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
            // 必须写全限定的外部类，否则解析到的是本类那个同名的两参方法，自己递归调自己
            AgentServer.this.onAgentEvent(event);
        }

        @Override
        public void onFinished(Plan plan, PlanResult result) {
            publish(Protocol.encodePlanFinished(plan, result));
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
        conn.send(ready().toString());
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
        JsonNode cmd;
        try {
            cmd = Protocol.mapper().readTree(message);
        } catch (Exception e) {
            send(conn, error("报文不是合法 JSON"));
            return;
        }
        String type = Protocol.str(cmd, "t", "");
        switch (type) {
            case "prompt" -> handlePrompt(conn, Protocol.str(cmd, "text", "").trim());
            case "cancel" -> handleCancel();
            case "approval_reply" -> approval.reply(
                    Protocol.num(cmd, "id", -1), Protocol.str(cmd, "decision", "no"));
            case "plan" -> handlePlan(conn, Protocol.str(cmd, "goal", "").trim());
            case "resume" -> handleResume(conn);
            case "plan_confirm_reply" -> planConfirm.reply(
                    Protocol.num(cmd, "id", -1), Protocol.bool(cmd, "approved", false));
            case "reset" -> handleReset(conn);
            case "ping" -> send(conn, Protocol.event("pong"));
            default -> send(conn, error("未知命令: " + type));
        }
    }

    private void handlePrompt(WebSocket conn, String text) {
        if (agent == null) {
            send(conn, error("没有配置 API key，无法对话。设置环境变量 DEEPSEEK_API_KEY "
                    + "或写进 ~/.myagent/config.json"));
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            send(conn, error("正在处理上一条，请先等它结束或按 Esc 中断"));
            return;
        }
        publish(Protocol.event("busy").put("busy", true));
        worker.submit(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                agent.run(text);                 // 事件在 onAgentEvent 里已经播出去了
            } catch (RuntimeException e) {
                log.error("agent 跑挂了", e);
                publish(Protocol.event("failed").put("reason", String.valueOf(e.getMessage())));
            } finally {
                busy.set(false);
                ObjectNode idle = Protocol.event("busy");
                idle.put("busy", false);
                idle.put("durationMs", System.currentTimeMillis() - startedAt);
                idle.put("tokens", agent.estimatedTokens());
                idle.put("contextLimit", agent.contextLimit());
                publish(idle);
            }
        });
    }

    /**
     * 先规划后执行，无头版。和终端版 {@code Repl.runPlan} 是同一套三幕流程：
     * 规划 → 让界面确认 → 逐个任务跑，只是「给用户看」换成了「发事件」，
     * 「读一行 y/n」换成了 {@link PlanConfirmation}。
     */
    private void handlePlan(WebSocket conn, String goal) {
        if (planner == null) {
            send(conn, error("没有配置 API key，无法规划"));
            return;
        }
        if (goal.isEmpty()) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            send(conn, error("正在处理上一条，请先等它结束或按 Esc 中断"));
            return;
        }
        publish(Protocol.event("busy").put("busy", true));
        worker.submit(() -> runPlan(goal));
    }

    /**
     * 接着跑上次没跑完的计划。
     *
     * <p>「有没有存档」这件事在这儿就查掉，而不是丢进后台线程再说——
     * 没有存档是个该定向回给发命令那个连接的错误（`error` 只发给他一个人），
     * 而后台线程手上没有 conn，只能广播，等于打扰了所有连着的界面。
     */
    private void handleResume(WebSocket conn) {
        if (planner == null) {
            send(conn, error("没有配置 API key，无法执行计划"));
            return;
        }
        if (!planAgent.hasSavedPlan()) {
            send(conn, error("这个工作区没有没跑完的计划"));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            send(conn, error("正在处理上一条，请先等它结束或按 Esc 中断"));
            return;
        }
        publish(Protocol.event("busy").put("busy", true));
        worker.submit(this::runResume);
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
        planConfirm.cancelPending();            // 卡在计划确认上的也要放行
        approval.cancelPending();               // 卡在审批上的那轮也要放行
    }

    private void handleReset(WebSocket conn) {
        if (busy.get()) {
            send(conn, error("正在处理中，不能清空"));
            return;
        }
        if (agent != null) {
            agent.reset();
        }
        approval.reset();
        publish(Protocol.event("reset_done"));
    }

    // ---------------------------------------------------------------- 发事件

    private void onAgentEvent(AgentEvent event) {
        int seq = event instanceof AgentEvent.ToolStarted
                ? toolSeq.incrementAndGet()
                : toolSeq.get();
        publish(Protocol.encode(event, seq));
    }

    /** 首帧：把界面画第一屏需要的所有静态信息一次给全，省掉一轮往返。 */
    private ObjectNode ready() {
        ObjectNode n = Protocol.event("ready");
        n.put("protocol", Protocol.VERSION);
        n.put("version", Cli.VERSION);
        n.put("workspace", workspace.root().toString());
        n.put("model", config.model());
        n.put("planMode", options.readOnly());   // 线上字段名保持不变，前端认的是这个
        n.put("hasApiKey", config.hasApiKey());
        // 能力清单：界面据此决定要不要放出「先规划后执行」的入口
        ArrayNode features = n.putArray("features");
        Protocol.FEATURES.forEach(features::add);
        n.put("busy", busy.get());
        if (agent != null) {
            n.put("tokens", agent.estimatedTokens());
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
        String rejection = WorkspaceGuard.rejectionReason(workspace.root());
        if (rejection != null) {
            n.put("workspaceWarning", rejection);   // 能走到这儿说明是 --unsafe 放行的
        }
        ArrayNode list = n.putArray("tools");
        for (Tool tool : tools.all()) {
            list.addObject().put("name", tool.name()).put("description", tool.description());
        }
        return n;
    }

    private static ObjectNode error(String message) {
        return Protocol.event("error").put("message", message);
    }

    private void publish(ObjectNode node) {
        broadcast(node.toString());
    }

    private void send(WebSocket conn, ObjectNode node) {
        if (conn != null && conn.isOpen()) {
            conn.send(node.toString());
        }
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
