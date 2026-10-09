package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.context.ContextManager;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.prompt.PromptRepository;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 主 agent 的子 agent 编排，照 Codex（{@code codex-rs/core/src/tools/handlers/multi_agents}）的 v1 做：
 *
 * <ul>
 *   <li><b>异步</b>：{@code spawn_agent} 立刻返回 id，子 agent 在后台线程上跑，主 agent 接着干自己的。
 *       做完时结果作为 {@code <subagent_notification>} 注入主 agent 的对话（见 {@link Mailbox}），
 *       不用主 agent 去问；真要卡在它的结果上才用 {@code wait_agent}。</li>
 *   <li><b>会写</b>：{@code worker} 直接改工作区——和主 agent、和别的 worker 共用一个目录。
 *       Codex 只靠提示词让各方守住<b>写入集合</b>；这里多做一步，由框架锁住（见 {@link OwnedWrite}）：
 *       派 worker 必须写明 {@code owns}，它跑着的时候别人写不了这些路径，它也只能写这些路径。
 *       cookiecutter 上主 agent 把 prompt.py 派给 agent-5 之后自己也接着改，两边反复互相覆盖——
 *       光靠提示词约束不住。{@code explorer} 没有写文件的工具。</li>
 *   <li><b>能复用</b>：{@code send_input} 给已有的子 agent 追加话（排队或打断），它的上下文还在；
 *       {@code close_agent} 关掉释放名额，{@code resume_agent} 再打开。</li>
 *   <li><b>上限</b>：同时开着的最多 {@link #MAX_THREADS} 个（做完没关的也算），深度 1——
 *       子 agent 的菜单上没有这五个工具（Codex 的 {@code agent_max_threads = 6}、{@code agent_max_depth = 1}）。</li>
 *   <li><b>默认不主动派</b>：{@link Mode#EXPLICIT} 时工具描述里写明「用户没明确要求就不派」，
 *       这是 Codex 的默认；{@link Mode#PROACTIVE} 换成「能并行就派」；{@link Mode#AUTO} 是开工前由
 *       {@link DelegationDecider} 决定，不委派就不给这几个工具，委派就把计划放进主 agent 的对话。</li>
 * </ul>
 *
 * <h3>子 agent 的系统提示词就是主 agent 那一份</h3>
 * 身份和规矩放在它第一条消息的 {@code <subagent_task>} 里，而不是另起一份系统提示词：
 * 系统提示词和主 agent 逐字相同，前缀缓存就命中；{@code fork_context} 时整段主对话也命中。
 */
public final class SubAgents implements Mailbox {

    private static final Logger log = LoggerFactory.getLogger(SubAgents.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PromptRepository REPO = PromptRepository.createDefault();

    /** 同时开着的子 agent 上限，做完没关的也算。Codex 默认 6。 */
    public static final int MAX_THREADS = 6;

    /** 交回主 agent 的一次结果封顶。子 agent 被要求写短，这是兜底。 */
    static final int MAX_RESULT_CHARS = 8_000;

    static final long DEFAULT_WAIT_MILLIS = 30_000;
    static final long MIN_WAIT_MILLIS = 10_000;
    static final long MAX_WAIT_MILLIS = 3_600_000;

    /** 这五个工具。子 agent 的菜单上一个都没有——深度 1。 */
    public static final Set<String> TOOL_NAMES =
            Set.of("spawn_agent", "send_input", "wait_agent", "close_agent", "resume_agent");

    /** 写入方是主 agent 时的名字。 */
    static final String MAIN = "主 agent";

    /** 通知的开头。界面按 user 消息数截转录时要跳过它——那不是人说的话。 */
    public static final String NOTICE_TAG = "<subagent_notification>";

    /** 开局委派计划的开头（{@link DelegationDecider.Decision#render()}）。同样不是人说的话。 */
    public static final String PLAN_TAG = "<delegation_plan>";

    /** 什么时候派。见类注释。 */
    public enum Mode {
        OFF, EXPLICIT, PROACTIVE, AUTO;

        /** 环境变量 {@code MYAGENT_SUBAGENTS} 的值：off / explicit（默认）/ proactive / auto。 */
        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return EXPLICIT;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "off", "0", "false", "no" -> OFF;
                case "proactive" -> PROACTIVE;
                case "explicit" -> EXPLICIT;
                case "auto" -> AUTO;
                default -> throw new IllegalArgumentException(
                        "MYAGENT_SUBAGENTS 只认 off / explicit / proactive / auto，收到: " + value);
            };
        }
    }

    /** 一种子 agent。{@code excluded} 是从主 agent 菜单里拿掉的工具。 */
    record Role(String id, String description, String rulesPath, Set<String> excluded) {
    }

    static final Role DEFAULT = new Role("default",
            "默认 agent，和你有同样的工具（派子 agent 的除外）。", "subagents/roles/default.md", Set.of());
    static final Role EXPLORER = new Role("explorer", """
            用来问代码库里具体的问题。explorer 又快又可信，问题要具体、范围要窄。
              - 别让自己去查 explorer 已经查过的东西；一般直接采信它的结论，不用再核实（需要上下文时自己读代码也可以）。
              - 有几个能各自独立回答的问题时，同时派几个 explorer。等结果的时候接着做不依赖这些结果的活——并行是委派最大的好处。
              - 相关的追问用 send_input 交给已有的 explorer，不要新派。
              它没有写文件的工具。""",
            "subagents/roles/explorer.md", Set.of("write_file", "edit_file", "create_project", "remember"));
    static final Role WORKER = new Role("worker", """
            用来干活：实现功能的一部分、修测试或 bug、把大重构拆成独立的几块。
              - 用 owns 明确交代它**负责哪些文件/模块**（所有权，必填），比如「worker 1 负责鉴权模块，worker 2 负责数据库层」。它跑着的时候这些路径只有它能写，你也写不了。
              - 告诉它**不是一个人在改这个代码库**：别人改的不要回退，要调整自己的实现去适配。""",
            "subagents/roles/worker.md", Set.of());
    static final Map<String, Role> ROLES = Map.of(
            DEFAULT.id(), DEFAULT, EXPLORER.id(), EXPLORER, WORKER.id(), WORKER);

    static {
        // 提示词文件缺了不会有任何症状，只是子 agent 少了规矩。启动就读一遍
        for (String path : List.of("subagents/spawn.md", "subagents/explicit.md",
                "subagents/proactive.md", "subagents/auto.md", "subagents/child.md")) {
            REPO.loadRequired(path);
        }
        ROLES.values().forEach(role -> REPO.loadRequired(role.rulesPath()));
    }

    /** 进程内全局递增：计划里的子任务也在发带号的事件，各数各的会撞号。 */
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger SUBMISSIONS = new AtomicInteger();

    private enum State { RUNNING, COMPLETED, ERRORED, INTERRUPTED, SHUTDOWN }

    /** 一个子 agent。字段都在 {@link #lock} 下读写。 */
    private final class Child {
        final String id;
        final Role role;
        final Agent agent;
        State state = State.RUNNING;
        /** 关掉之前的状态，resume 时还原。 */
        State beforeClose;
        String result;
        String failure;
        boolean running;
        boolean closed;
        /** 主 agent 被叫停连带停的：不发通知，主 agent 自己知道。 */
        boolean stoppedByParent;
        /** send_input(interrupt=true) 送来的话：这一轮停下后马上拿它开新的一轮。 */
        String interruptWith;
        /** send_input 排队的话，它下一步请求前收到（{@link Agent} 的收件箱）。 */
        final List<String> queued = new ArrayList<>();
        /** 它负责的文件/目录（绝对、规范化）。它跑着的时候只有它能写；空表示不独占。 */
        final List<Path> owns;

        Child(String id, Role role, Agent agent, List<Path> owns) {
            this.id = id;
            this.role = role;
            this.agent = agent;
            this.owns = owns;
        }
    }

    /** 主 agent 收件箱里的一条：哪个子 agent 的、说什么。wait_agent 拿过结果的会被撤掉。 */
    private record Notice(String agentId, String text) {
    }

    private final Object lock = new Object();
    private final Map<String, Child> children = new LinkedHashMap<>();
    private final List<Notice> notices = new ArrayList<>();
    private final AtomicInteger spawned = new AtomicInteger();

    private final Supplier<LlmClient> clients;
    private final ToolRegistry allTools;
    private final Path root;
    private final Supplier<ContextManager> contexts;
    private final int childMaxSteps;
    private final Mode mode;
    private final BiConsumer<String, AgentEvent> events;
    private volatile Agent parent;

    /**
     * @param clients       <b>每个子 agent 现造一个</b>客户端：{@code DeepSeekClient} 带可变状态，共用会互相踩取消
     * @param allTools      主 agent 的全量注册表，子 agent 的菜单在派出那一刻从这里筛
     * @param workspaceRoot 工作区根目录，{@code owns} 和写文件的路径都按它解析
     * @param contexts      每个子 agent 一份上下文管理器（和主 agent 同样的窗口）
     * @param childMaxSteps 子 agent 每一轮的步数上限，{@code <= 0} 不限（和主 agent 一样）
     * @param events        子 agent 的动静往哪发，第一个参数是它的 id（{@code agent-N}）；传 null 不发
     */
    public SubAgents(Supplier<LlmClient> clients, ToolRegistry allTools, Path workspaceRoot,
                     Supplier<ContextManager> contexts, int childMaxSteps, Mode mode,
                     BiConsumer<String, AgentEvent> events) {
        if (mode == Mode.OFF) {
            throw new IllegalArgumentException("OFF 就别造它");
        }
        this.clients = clients;
        this.allTools = allTools;
        this.root = workspaceRoot.toAbsolutePath().normalize();
        this.contexts = contexts;
        this.childMaxSteps = childMaxSteps;
        this.mode = mode;
        this.events = events == null ? (id, e) -> { } : events;
    }

    /**
     * 接到主 agent 上：它的系统提示词子 agent 沿用，它的收件箱就是这里。
     * 同时把注册表里的写文件工具换成带归属检查的那一版（写入方记作主 agent）。
     */
    public SubAgents attach(Agent parent) {
        this.parent = parent;
        parent.withMailbox(this);
        for (Tool tool : List.copyOf(allTools.all())) {
            if (BuiltinTools.WRITING_TOOLS.contains(tool.name()) && !(tool instanceof OwnedWrite)) {
                allTools.replace(new OwnedWrite(tool, MAIN));
            }
        }
        return this;
    }

    /**
     * 叫停所有在跑的子 agent，最多等 {@code graceMillis} 让它们停稳。
     * 无头运行退出前调：不然进程一退，子 agent 写到一半的文件就留在那儿。
     *
     * @return 都停下了
     */
    public boolean stopAll(long graceMillis) {
        interruptAll();
        long until = System.nanoTime() + graceMillis * 1_000_000L;
        synchronized (lock) {
            while (children.values().stream().anyMatch(c -> c.running)) {
                long left = (until - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return false;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** 五个工具。注册到主 agent 的注册表里。 */
    public List<Tool> tools() {
        return List.of(new Spawn(), new SendInput(), new Wait(), new Close(), new Resume());
    }

    /** 一共派出过几个。评测摘要用。 */
    public int spawned() {
        return spawned.get();
    }

    public static boolean isNotice(String content) {
        return content != null && (content.startsWith(NOTICE_TAG) || content.startsWith(PLAN_TAG));
    }

    /**
     * 往主 agent 的收件箱里放一段话，它下一次请求模型前收到。
     * 开局委派计划走这条：排在用户原话之后、第一次请求之前，用户原话仍然是记录里的第一条。
     */
    public void post(String text) {
        synchronized (lock) {
            notices.add(new Notice("", text));
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- 主 agent 的收件箱

    @Override
    public List<String> drain() {
        synchronized (lock) {
            List<String> out = notices.stream().map(Notice::text).toList();
            notices.clear();
            return out;
        }
    }

    @Override
    public boolean hasMail() {
        synchronized (lock) {
            return !notices.isEmpty();
        }
    }

    @Override
    public boolean busy() {
        synchronized (lock) {
            return children.values().stream().anyMatch(c -> c.running && !c.closed);
        }
    }

    @Override
    public void awaitChange(long millis) throws InterruptedException {
        synchronized (lock) {
            lock.wait(Math.max(1, millis));
        }
    }

    @Override
    public void interruptAll() {
        synchronized (lock) {
            for (Child child : children.values()) {
                if (child.running) {
                    child.stoppedByParent = true;
                    child.interruptWith = null;
                    child.agent.cancel();
                }
            }
        }
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            for (Child child : children.values()) {
                child.closed = true;
                if (child.running) {
                    child.agent.cancel();
                }
            }
            children.clear();
            notices.clear();
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** 开一轮。调用方持有 {@link #lock}。 */
    private void startTurn(Child child, String input) {
        child.running = true;
        child.state = State.RUNNING;
        child.failure = null;
        child.stoppedByParent = false;
        Thread worker = new Thread(() -> {
            String text = null;
            Throwable error = null;
            try {
                text = child.agent.run(input);
            } catch (Throwable t) {
                error = t;
                log.error("子 agent {} 炸了", child.id, t);
            }
            finished(child, text, error);
        }, child.id);
        worker.setDaemon(true);
        worker.start();
    }

    /** 一轮跑完（正常收口、出错、被叫停都走这儿），在子 agent 自己的线程上。 */
    private void finished(Child child, String text, Throwable error) {
        synchronized (lock) {
            child.running = false;
            try {
                if (child.closed) {
                    child.state = State.SHUTDOWN;
                    return;
                }
                if (child.interruptWith != null) {
                    String message = child.interruptWith;
                    child.interruptWith = null;
                    child.state = State.INTERRUPTED;
                    startTurn(child, agentMessage(message));
                    return;
                }
                if (child.stoppedByParent) {
                    child.state = State.INTERRUPTED;
                    return;
                }
                if (error != null) {
                    child.state = State.ERRORED;
                    child.result = "子 agent 执行失败: " + error;
                } else if (child.failure != null) {
                    child.state = State.ERRORED;
                    child.result = child.failure + (text == null || text.isBlank() ? ""
                            : "。它到此为止的产出（可能不完整）:\n" + text);
                } else {
                    child.state = State.COMPLETED;
                    child.result = text == null ? "" : text;
                }
                if (!child.queued.isEmpty()) {
                    // 收口那一刻又有话排进来了：接着跑一轮，结果等这一轮完了再交
                    List<String> more = new ArrayList<>(child.queued);
                    child.queued.clear();
                    startTurn(child, String.join("\n\n", more.stream().map(SubAgents::agentMessage).toList()));
                    return;
                }
                notices.add(new Notice(child.id, notice(child)));
                log.info("子 agent {} 结束: {}", child.id, child.state);
            } finally {
                lock.notifyAll();
            }
        }
    }

    /** 开着的（没关的）有几个。调用方持有 {@link #lock}。 */
    private long open() {
        return children.values().stream().filter(c -> !c.closed).count();
    }

    // ---------------------------------------------------------------- 状态的 JSON 形状（照 Codex）

    /** "running" / {"completed": "..."} / {"errored": "..."} / "interrupted" / "shutdown" / "not_found"。 */
    private static JsonNode status(Child child) {
        if (child == null) {
            return JSON.getNodeFactory().textNode("not_found");
        }
        return switch (child.state) {
            case RUNNING -> JSON.getNodeFactory().textNode("running");
            case INTERRUPTED -> JSON.getNodeFactory().textNode("interrupted");
            case SHUTDOWN -> JSON.getNodeFactory().textNode("shutdown");
            case COMPLETED -> JSON.createObjectNode().put("completed", clip(child.result));
            case ERRORED -> JSON.createObjectNode().put("errored", clip(child.result));
        };
    }

    private static boolean isFinal(Child child) {
        return child == null || !child.running;
    }

    private static String notice(Child child) {
        ObjectNode body = JSON.createObjectNode();
        body.put("agent_id", child.id);
        body.put("agent_type", child.role.id());
        body.set("status", status(child));
        return NOTICE_TAG + "\n" + body.toPrettyString() + "\n</subagent_notification>";
    }

    private static String agentMessage(String message) {
        return "<agent_message>\n" + message + "\n</agent_message>";
    }

    static String clip(String text) {
        String t = text == null ? "" : text.strip();
        if (t.length() <= MAX_RESULT_CHARS) {
            return t;
        }
        return t.substring(0, MAX_RESULT_CHARS)
                + "\n…（共 " + t.length() + " 字符，后面截掉了；需要细节就自己去读对应文件）";
    }

    private static ToolResult json(JsonNode node) {
        return ToolResult.ok(node.toPrettyString());
    }

    // ---------------------------------------------------------------- 子 agent 的构造

    /**
     * 按角色从全量注册表里筛：拿掉这五个工具（深度 1），再拿掉角色不许用的。
     * 写文件的工具换成以这个子 agent 为写入方的那一版。
     */
    private ToolRegistry toolsFor(Role role, String id) {
        ToolRegistry narrowed = new ToolRegistry();
        for (Tool tool : allTools.all()) {
            if (TOOL_NAMES.contains(tool.name()) || role.excluded().contains(tool.name())) {
                continue;
            }
            narrowed.register(tool instanceof OwnedWrite w ? new OwnedWrite(w.inner, id) : tool);
        }
        return narrowed;
    }

    /** 相对工作区的路径解析成绝对、规范化的。 */
    private Path resolve(String path) {
        Path p = Path.of(path.strip());
        return (p.isAbsolute() ? p : root.resolve(p)).normalize();
    }

    /** 给人看的路径：工作区里的写成相对的。 */
    private String show(Path p) {
        return p.startsWith(root) ? String.valueOf(root.relativize(p)) : p.toString();
    }

    /**
     * 这次写入能不能做。null 表示可以，否则是拒绝的理由。
     *
     * <ul>
     *   <li>路径归一个<b>正在跑</b>的子 agent，写入方又不是它——拒绝</li>
     *   <li>写入方自己有 owns，路径不在里面——拒绝（worker 只写自己负责的）</li>
     * </ul>
     * 子 agent 交了结果（不在跑了），它那片就放开，主 agent 可以接着整合。
     */
    String checkWrite(String writer, List<String> paths) {
        synchronized (lock) {
            Child self = children.get(writer);
            for (String raw : paths) {
                Path p = resolve(raw);
                for (Child c : children.values()) {
                    if (c.id.equals(writer) || !c.running || c.closed) {
                        continue;
                    }
                    for (Path o : c.owns) {
                        if (p.startsWith(o)) {
                            return show(p) + " 现在归 " + c.id + "（正在跑，负责 " + owned(c) + "）。"
                                    + "等它交回结果再改，或者用 send_input 让它改，或者 close_agent 收回。"
                                    + "派出去的活不要自己再做一遍。";
                        }
                    }
                }
                if (self != null && !self.owns.isEmpty() && self.owns.stream().noneMatch(p::startsWith)) {
                    return "你只负责 " + owned(self) + "，" + show(p) + " 不归你。"
                            + "需要改别的文件，就在最后的答复里写清楚要改什么，交给主 agent。";
                }
            }
            return null;
        }
    }

    private String owned(Child c) {
        return String.join("、", c.owns.stream().map(this::show).toList());
    }

    /**
     * fork_context：主 agent 的对话截到发起这次派遣的那条 assistant 之前。
     * 那条消息里的 tool_calls 此刻还没有结果，带过去就是一条无主的调用，请求直接 400。
     */
    private static List<ChatMessage> forked(List<ChatMessage> conversation) {
        int cut = conversation.size();
        for (int i = conversation.size() - 1; i >= 0; i--) {
            ChatMessage m = conversation.get(i);
            if ("assistant".equals(m.role()) && m.hasToolCalls()) {
                cut = i;
                break;
            }
        }
        return conversation.subList(0, cut);
    }

    String brief(String id, Role role, String message) {
        return REPO.loadRequired("subagents/child.md")
                .replace("{{id}}", id)
                .replace("{{role}}", role.id())
                .replace("{{roleRules}}", REPO.loadRequired(role.rulesPath()).strip())
                .replace("{{message}}", message);
    }

    String spawnDescription() {
        StringBuilder roles = new StringBuilder();
        for (Role role : List.of(DEFAULT, EXPLORER, WORKER)) {
            roles.append("- ").append(role.id()).append("：").append(role.description().strip()).append('\n');
        }
        String modeText = REPO.loadRequired(switch (mode) {
            case PROACTIVE -> "subagents/proactive.md";
            case AUTO -> "subagents/auto.md";
            default -> "subagents/explicit.md";
        }).strip();
        return REPO.loadRequired("subagents/spawn.md")
                .replace("{{mode}}", modeText)
                .replace("{{roles}}", roles.toString().strip())
                .strip();
    }

    // ---------------------------------------------------------------- 五个工具

    private final class Spawn implements Tool {
        @Override
        public String name() {
            return "spawn_agent";
        }

        @Override
        public String description() {
            return spawnDescription();
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "message": {"type": "string", "description": "交给新 agent 的任务。它默认看不到你的对话，要把它需要知道的都写进来"},
                        "agent_type": {"type": "string", "enum": ["default", "explorer", "worker"], "description": "不传就是 default"},
                        "fork_context": {"type": "boolean", "description": "true 把你到目前为止的对话复制给它；不传或 false 时它只看到 message"},
                        "owns": {"type": "array", "items": {"type": "string"}, "description": "它负责（独占写）的文件或目录，相对工作区。worker 必填。它跑着的时候你和别的 agent 写不了这些路径，它也只能写这些路径；它交回结果后放开"}
                      },
                      "required": ["message"]
                    }
                    """;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            String message = Args.requireString(args, "message").strip();
            String type = args.path("agent_type").asText("default").strip().toLowerCase(Locale.ROOT);
            Role role = ROLES.get(type.isEmpty() ? "default" : type);
            if (role == null) {
                return ToolResult.error("agent_type 只认 default / explorer / worker，收到: " + type);
            }
            Agent parentAgent = parent;
            if (parentAgent == null) {
                return ToolResult.error("子 agent 没接到主 agent 上（SubAgents.attach 没调）");
            }
            List<Path> owns = Args.optStringList(args, "owns").stream()
                    .filter(o -> !o.isBlank()).map(SubAgents.this::resolve).distinct().toList();
            if (role == WORKER && owns.isEmpty()) {
                return ToolResult.error("派 worker 要用 owns 写明它负责哪些文件或目录——"
                        + "它跑着的时候只有它能写这些路径，别人（包括你）写不了，这样才不会互相覆盖");
            }
            for (Path o : owns) {
                if (!o.startsWith(root) || o.equals(root)) {
                    return ToolResult.error("owns 要是工作区里的具体文件或目录，不能是整个工作区: " + o);
                }
            }
            synchronized (lock) {
                if (open() >= MAX_THREADS) {
                    return ToolResult.error("已经开着 " + MAX_THREADS + " 个子 agent 了（做完没关的也占名额）。"
                            + "先用 close_agent 关掉不再需要的，或者自己做。");
                }
            }
            synchronized (lock) {
                for (Child c : children.values()) {
                    if (!c.running || c.closed) {
                        continue;
                    }
                    for (Path o : owns) {
                        for (Path theirs : c.owns) {
                            if (o.startsWith(theirs) || theirs.startsWith(o)) {
                                return ToolResult.error(show(o) + " 和正在跑的 " + c.id + " 负责的 " + show(theirs)
                                        + " 重叠了。写入范围要互不相交：换一种拆法，或者等它做完");
                            }
                        }
                    }
                }
            }
            String id = "agent-" + SEQ.incrementAndGet();
            Agent agent = new Agent(clients.get(), toolsFor(role, id), parentAgent.systemPrompt(),
                    childMaxSteps, contexts.get())
                    .withWritingTools(BuiltinTools.WRITING_TOOLS);
            if (args.path("fork_context").asBoolean(false)) {
                agent.restore(forked(parentAgent.conversation()));
            }
            Child child = new Child(id, role, agent, owns);
            agent.withMailbox(new ChildInbox(child));
            agent.onEvent(event -> {
                if (event instanceof AgentEvent.Failed f) {
                    synchronized (lock) {
                        child.failure = f.reason();
                    }
                }
                events.accept(id, event);
            });
            synchronized (lock) {
                if (open() >= MAX_THREADS) {      // 两次检查之间别人也派了
                    return ToolResult.error("已经开着 " + MAX_THREADS + " 个子 agent 了，先 close_agent 关掉不需要的");
                }
                children.put(id, child);
                startTurn(child, brief(id, role, owns.isEmpty() ? message
                        : message + "\n\n你负责（只有你能写、你也只能写）：" + owned(child)));
            }
            spawned.incrementAndGet();
            log.info("派出 {}（{}）: {}", id, role.id(), message.length() > 80 ? message.substring(0, 80) + "…" : message);
            ObjectNode out = JSON.createObjectNode();
            out.put("agent_id", id);
            out.putNull("nickname");
            return json(out);
        }
    }

    private final class SendInput implements Tool {
        @Override
        public String name() {
            return "send_input";
        }

        @Override
        public String description() {
            return "给一个已有的子 agent 发消息。interrupt=true 立刻打断它手上的事、改做这个；"
                    + "不传或 false 就排队，它在下一步看到（它已经做完的话，就以这条消息开始新的一轮）。"
                    + "要派的活和某个子 agent 之前做的高度相关时，用 send_input 复用它——它的上下文还在——而不是新派一个。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "target": {"type": "string", "description": "子 agent 的 id（spawn_agent 返回的）"},
                        "message": {"type": "string", "description": "要发给它的话"},
                        "interrupt": {"type": "boolean", "description": "true 打断它当前的事立刻处理这条；不传或 false 排队"}
                      },
                      "required": ["target", "message"]
                    }
                    """;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            String target = Args.requireString(args, "target").strip();
            String message = Args.requireString(args, "message").strip();
            boolean interrupt = args.path("interrupt").asBoolean(false);
            synchronized (lock) {
                Child child = children.get(target);
                if (child == null) {
                    return ToolResult.error("没有这个子 agent: " + target);
                }
                if (child.closed) {
                    return ToolResult.error(target + " 已经关掉了，先 resume_agent 再发");
                }
                if (!child.running) {
                    notices.removeIf(n -> n.agentId().equals(target));   // 上一轮的结果它已经不需要了
                    startTurn(child, agentMessage(message));
                } else if (interrupt) {
                    child.interruptWith = message;
                    child.agent.cancel();
                } else {
                    child.queued.add(message);
                }
            }
            return json(JSON.createObjectNode().put("submission_id", "sub-" + SUBMISSIONS.incrementAndGet()));
        }
    }

    private final class Wait implements Tool {
        @Override
        public String name() {
            return "wait_agent";
        }

        @Override
        public String description() {
            return "等子 agent 进入最终状态。传多个 id 时，哪个先结束就返回。completed 里带着它的最终答复。"
                    + "超时返回空的 status。子 agent 结束时，同样的结果也会以通知的形式自动送到你这里——"
                    + "所以只在关键路径的下一步马上要用它的结果、不拿到就做不下去时才等。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "targets": {"type": "array", "items": {"type": "string"}, "description": "要等的子 agent id；传多个时任一个结束就返回"},
                        "timeout_ms": {"type": "number", "description": "超时毫秒数。默认 %d，最小 %d，最大 %d。宁可等久一点（几分钟），别反复轮询"}
                      },
                      "required": ["targets"]
                    }
                    """.formatted(DEFAULT_WAIT_MILLIS, MIN_WAIT_MILLIS, MAX_WAIT_MILLIS);
        }

        @Override
        public ToolResult execute(JsonNode args) {
            List<String> targets = Args.optStringList(args, "targets");
            if (targets.isEmpty()) {
                return ToolResult.error("targets 不能为空");
            }
            long timeout = args.has("timeout_ms") ? args.get("timeout_ms").asLong(DEFAULT_WAIT_MILLIS)
                    : DEFAULT_WAIT_MILLIS;
            if (timeout <= 0) {
                return ToolResult.error("timeout_ms 必须大于 0");
            }
            timeout = Math.max(MIN_WAIT_MILLIS, Math.min(MAX_WAIT_MILLIS, timeout));
            long deadline = System.nanoTime() + timeout * 1_000_000L;
            synchronized (lock) {
                while (true) {
                    ObjectNode statuses = JSON.createObjectNode();
                    for (String id : targets) {
                        Child child = children.get(id);
                        if (isFinal(child)) {
                            statuses.set(id, status(child));
                        }
                    }
                    if (!statuses.isEmpty()) {
                        // 结果在这儿拿到了，同一份通知就不再送一遍
                        notices.removeIf(n -> statuses.has(n.agentId()));
                        ObjectNode out = JSON.createObjectNode();
                        out.set("status", statuses);
                        out.put("timed_out", false);
                        return json(out);
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        ObjectNode out = JSON.createObjectNode();
                        out.set("status", JSON.createObjectNode());
                        out.put("timed_out", true);
                        return json(out);
                    }
                    try {
                        lock.wait(Math.max(1, remaining / 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();     // 主 agent 被叫停，还给它的工具线程
                        return ToolResult.error("(已取消) 等待被叫停");
                    }
                }
            }
        }
    }

    private final class Close implements Tool {
        @Override
        public String name() {
            return "close_agent";
        }

        @Override
        public String description() {
            return "不再需要时关掉一个子 agent，返回它关之前的状态。做完了的子 agent 也一直开着、占着名额，直到被关掉。"
                    + "不需要的别一直开着。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {"target": {"type": "string", "description": "要关的子 agent id"}},
                      "required": ["target"]
                    }
                    """;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            String target = Args.requireString(args, "target").strip();
            synchronized (lock) {
                Child child = children.get(target);
                JsonNode previous = status(child);
                if (child != null && !child.closed) {
                    child.closed = true;
                    child.beforeClose = child.state;
                    child.queued.clear();
                    child.interruptWith = null;
                    if (child.running) {
                        child.agent.cancel();
                    } else {
                        child.state = State.SHUTDOWN;
                    }
                    notices.removeIf(n -> n.agentId().equals(target));
                    lock.notifyAll();
                }
                ObjectNode out = JSON.createObjectNode();
                out.set("previous_status", previous);
                return json(out);
            }
        }
    }

    private final class Resume implements Tool {
        @Override
        public String name() {
            return "resume_agent";
        }

        @Override
        public String description() {
            return "按 id 重新打开一个关掉的子 agent，之后就能对它 send_input、wait_agent。它之前的上下文还在。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {"id": {"type": "string", "description": "要重新打开的子 agent id"}},
                      "required": ["id"]
                    }
                    """;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            String id = Args.requireString(args, "id").strip();
            synchronized (lock) {
                Child child = children.get(id);
                if (child == null) {
                    return ToolResult.error("没有这个子 agent: " + id);
                }
                if (child.closed) {
                    if (open() >= MAX_THREADS) {
                        return ToolResult.error("已经开着 " + MAX_THREADS + " 个子 agent 了，先 close_agent 关掉不需要的");
                    }
                    if (child.running) {
                        return ToolResult.error(id + " 还在收尾，稍后再试");
                    }
                    child.closed = false;
                    child.state = child.beforeClose == null || child.beforeClose == State.RUNNING
                            ? State.INTERRUPTED : child.beforeClose;
                }
                ObjectNode out = JSON.createObjectNode();
                out.set("status", status(child));
                return json(out);
            }
        }
    }

    /**
     * 带归属检查的写文件工具。包在 {@code write_file} / {@code edit_file} / {@code create_project} 外面，
     * 每个写入方（主 agent、各个子 agent）各一份，写之前问 {@link #checkWrite}。
     *
     * <p>bash 也能写文件，这里拦不住——和 {@link BuiltinTools#WRITING_TOOLS} 是同一个口径：
     * 绝大多数改动走写文件工具，挡住它就挡住了「两边反复覆盖同一个文件」的主路。
     */
    final class OwnedWrite implements Tool {
        final Tool inner;
        private final String writer;

        OwnedWrite(Tool inner, String writer) {
            this.inner = inner;
            this.writer = writer;
        }

        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public String description() {
            return inner.description();
        }

        @Override
        public String parametersSchema() {
            return inner.parametersSchema();
        }

        @Override
        public boolean parallelSafe() {
            return inner.parallelSafe();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            List<String> paths = new ArrayList<>();
            if (args.hasNonNull("path") && args.get("path").isTextual()) {
                paths.add(args.get("path").asText());
            }
            args.path("files").forEach(f -> {
                if (f.path("path").isTextual()) {
                    paths.add(f.path("path").asText());
                }
            });
            args.path("dirs").forEach(d -> {
                if (d.isTextual()) {
                    paths.add(d.asText());
                }
            });
            String refused = checkWrite(writer, paths);
            return refused == null ? inner.execute(args) : ToolResult.error(refused);
        }
    }

    /** 子 agent 自己的收件箱：主 agent 用 send_input 排队的话。 */
    private final class ChildInbox implements Mailbox {
        private final Child child;

        ChildInbox(Child child) {
            this.child = child;
        }

        @Override
        public List<String> drain() {
            synchronized (lock) {
                List<String> out = child.queued.stream().map(SubAgents::agentMessage).toList();
                child.queued.clear();
                return out;
            }
        }

        @Override
        public boolean hasMail() {
            synchronized (lock) {
                return !child.queued.isEmpty();
            }
        }

        @Override
        public boolean busy() {
            return false;          // 子 agent 没有自己的子 agent
        }

        @Override
        public void awaitChange(long millis) {
        }

        @Override
        public void interruptAll() {
        }

        @Override
        public void shutdown() {
        }
    }
}
