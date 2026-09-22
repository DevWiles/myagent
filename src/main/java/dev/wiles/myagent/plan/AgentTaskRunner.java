package dev.wiles.myagent.plan;

import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 真正干活的执行体：<b>每个任务开一个独立的 ReAct 子循环</b>。
 *
 * <p>为什么一个任务一个 {@link Agent}，而不是所有任务共用一条历史：
 * <ul>
 *   <li><b>上下文不膨胀</b>。共用一条历史的话，第五个任务要背着前四个任务的全部工具输出，
 *       跑不了几步就触发压缩，早期细节反而丢了。各自独立，每个任务的上下文都只有
 *       「系统提示 + 自己的任务 + 上游那几句摘要」。</li>
 *   <li><b>产出被迫收敛成摘要</b>。下游只能看到上游的 {@code result}，
 *       这就逼着每个任务把结论写清楚，而不是指望下游自己去翻上游的对话。</li>
 *   <li><b>是并发的前提</b>。共享历史的方案永远没法并行。</li>
 * </ul>
 * 代价是每个子任务看不到全局对话——所以 {@link TaskPrompt} 必须把上游产出拼进去，
 * 那条边才不只是个先后顺序。
 *
 * <p>另一件它顺手做掉的事：<b>按任务的角色装配子循环</b>——工具菜单、步数上限、
 * 系统提示词，三样都从 {@link AgentRole} 上取。所以「派谁去」这个决定在这里
 * 才真正变成三处具体差别，而不是一个显示在界面上的标签。
 */
public final class AgentTaskRunner implements TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskRunner.class);

    /**
     * 不指定时，一个任务跑几轮由<b>角色</b>说了算（{@link AgentRole#maxSteps()}）。
     *
     * <p>这个常量只是历史入口的默认值，语义是「别指定，听角色的」。
     */
    public static final int DEFAULT_MAX_STEPS = 0;

    /**
     * 客户端工厂，<b>每个任务现造一个</b>，不是所有子循环共用一个。
     *
     * <p>{@code DeepSeekClient} 带可变状态（尤其是 {@code volatile Call currentCall}，
     * {@code cancel()} 靠它掐请求）。几个子循环同时跑时共用一个实例，
     * 谁最后发请求谁占着 {@code currentCall}，取消就只掐得断它一个——
     * 其余的照跑不误，用户按了停却像没按。和 {@code Precompute} 要独立客户端是同一个理由。
     */
    private final Supplier<LlmClient> clients;
    private final ToolRegistry allTools;
    private final Path workspaceRoot;
    /**
     * 调用方指定的步数。{@code <= 0} 表示不指定，听角色的。见 {@link #stepsFor}。
     */
    private final int maxSteps;

    private BiConsumer<Task, AgentEvent> listener = (task, event) -> {
    };

    /** 给 PLANNING 任务拆子任务用。没配就退化成一次普通的只读调研。 */
    private Planner planner;

    /**
     * 要不要执行角色的「结论行」契约（{@link AgentRole#requiresVerdict()}）。
     *
     * <p>默认执行。关掉它的是圆桌：<b>同一个 verifier，在计划里和在圆桌上，
     * 「测试没过」的意思完全不同</b>。
     * <ul>
     *   <li>计划里：下游全建立在「改好了」这个前提上，没过就必须落成失败，
     *       才能顺着依赖把下游跳过</li>
     *   <li>圆桌上：没有下游。「测试没过」是<b>一条有价值的意见</b>，
     *       把它判成这一席失败，等于唯一发现真问题的那个人被消音了</li>
     * </ul>
     * 这不是配置项，是两种场合下同一句话的语义差别，所以由调用方声明，不由角色声明。
     */
    private boolean verdictContract = true;

    /**
     * 所有正在跑的子循环，取消时要一个不漏地掐掉。
     *
     * <p>以前是一个 {@code volatile Agent current}——那时任务串行，同一时刻最多一个。
     * 并行之后一个字段装不下：任务 A 跑完清掉字段，正在跑的 B 就成了掐不断的孤儿。
     */
    private final Set<Agent> running = ConcurrentHashMap.newKeySet();

    /** 单客户端的便捷入口：串行场景和测试用。并行调用它的话取消会漏，见 {@link #clients}。 */
    public AgentTaskRunner(LlmClient client, ToolRegistry allTools, Path workspaceRoot) {
        this(() -> client, allTools, workspaceRoot, DEFAULT_MAX_STEPS);
    }

    public AgentTaskRunner(LlmClient client, ToolRegistry allTools, Path workspaceRoot,
                           int maxSteps) {
        this(() -> client, allTools, workspaceRoot, maxSteps);
    }

    public AgentTaskRunner(Supplier<LlmClient> clients, ToolRegistry allTools,
                           Path workspaceRoot) {
        this(clients, allTools, workspaceRoot, DEFAULT_MAX_STEPS);
    }

    public AgentTaskRunner(Supplier<LlmClient> clients, ToolRegistry allTools, Path workspaceRoot,
                           int maxSteps) {
        this.clients = clients;
        this.allTools = allTools;
        this.workspaceRoot = workspaceRoot;
        this.maxSteps = maxSteps;
    }

    /** 事件带着任务一起转发——界面得知道现在动的是哪个节点。 */
    public AgentTaskRunner onEvent(BiConsumer<Task, AgentEvent> listener) {
        this.listener = listener;
        return this;
    }

    /**
     * 配上规划器，{@link TaskType#PLANNING} 任务才能真的拆出子任务。
     *
     * <p>做成可选而不是构造参数：没有它整条链照样跑得通（PLANNING 退化成一次调研），
     * 而测试调度逻辑时也不必每次都捏一个规划器出来。
     */
    public AgentTaskRunner withPlanner(Planner planner) {
        this.planner = planner;
        return this;
    }

    /** 关掉结论行契约。理由见 {@link #verdictContract}。 */
    public AgentTaskRunner verdictContract(boolean on) {
        this.verdictContract = on;
        return this;
    }

    /** 掐断所有正在跑的子循环（连带它们正在进行的 HTTP 请求）。 */
    public void cancel() {
        for (Agent agent : running) {
            agent.cancel();
        }
    }

    @Override
    public TaskOutcome run(Task task, List<Task> upstream) {
        AgentRole role = task.role();
        Agent agent = new Agent(
                clients.get(),
                toolsFor(role),
                TaskPrompt.system(task, workspaceRoot),
                stepsFor(role));

        // 子循环失败（模型调用挂了、被取消、撞上最大步数）只通过事件暴露，
        // run() 的返回值是一段人话，靠字符串去猜成败太脆了
        String[] failure = new String[1];
        agent.onEvent(event -> {
            if (event instanceof AgentEvent.Failed f) {
                failure[0] = f.reason();
            }
            listener.accept(task, event);
        });

        running.add(agent);
        String text;
        try {
            text = agent.run(TaskPrompt.user(task, upstream));
        } finally {
            running.remove(agent);
        }

        if (failure[0] != null) {
            log.info("任务 {} 的子循环没能收口: {}", task.id(), failure[0]);
            return TaskOutcome.failed(compose(failure[0], text));
        }
        if (verdictContract && role.requiresVerdict() && !passed(text)) {
            // 验证不通过必须落成失败，才能顺着 dependents 把下游跳过。
            // 记成「成功但其实没过」是最糟的——后面每一步都建立在一个假前提上。
            return TaskOutcome.failed(text);
        }
        if (task.type() == TaskType.PLANNING && planner != null) {
            return expand(task, text);
        }
        return TaskOutcome.ok(text);
    }

    /**
     * 按角色收窄工具菜单。
     *
     * <p><b>模型看不到菜单上没有的工具，就根本不会去调</b>——比「注册了再在执行时拦住」
     * 可靠得多，后者要靠审批逻辑不出漏，前者压根没有那扇门。
     * 这个思路现成就有，{@code BuiltinTools.createReadOnly} 用的是同一套道理。
     *
     * <p>注册表里没有的名字直接跳过：全量注册表长什么样由上层决定
     * （只读模式、审批策略不同，工具实例就不同），这里不该假设它一定齐全。
     */
    private ToolRegistry toolsFor(AgentRole role) {
        ToolRegistry narrowed = new ToolRegistry();
        for (String name : role.tools()) {
            for (Tool tool : allTools.all()) {
                if (tool.name().equals(name)) {
                    narrowed.register(tool);
                    break;
                }
            }
        }
        return narrowed;
    }

    /**
     * 这一趟给几步。
     *
     * <h3>为什么调用方一旦指定就是它说了算，而不是取两者的小值</h3>
     * 角色上的数字是按<b>计划里的一小步</b>调的（「读 pom.xml 说清 Java 版本」那种）。
     * 同一个角色换个场合，活的大小可能完全不同：圆桌上的一席要把整个包审一遍，
     * 十步根本收不了口——而<b>撞上限的代价不是少跑几步，是这一席整个作废</b>
     * （撞上限会走 {@code AgentEvent.Failed}）。
     *
     * <p>这一条是真机跑出来的：圆桌头一回开，两席都把话说完了，
     * 却因为撞上限双双被判失败，屏幕上是「0/2 位给了意见」。
     * 只有调用方知道这一趟的活有多大，所以它有权改这个数。
     */
    private int stepsFor(AgentRole role) {
        return maxSteps > 0 ? maxSteps : role.maxSteps();
    }

    /**
     * PLANNING 任务的第二幕：拿着刚摸到的情况，再问模型一次，拆出下一层子任务。
     *
     * <p>两次调用是刻意分开的——第一次带只读工具去<b>看</b>，第二次不带工具只<b>想</b>。
     * 合成一次的话，模型得一边调工具一边惦记着输出 JSON，两头都做不好。
     *
     * <p>拆不出来就是这个任务失败：它存在的唯一意义就是产出子任务，
     * 产不出来还报成功，下游会拿着一句空话往下跑。
     */
    private TaskOutcome expand(Task task, String findings) {
        try {
            List<Task> subTasks = planner.planSubTasks(task, findings);
            log.info("任务 {} 拆出 {} 个子任务", task.id(), subTasks.size());
            return TaskOutcome.planned(findings, subTasks);
        } catch (RuntimeException e) {
            log.warn("任务 {} 拆子任务失败: {}", task.id(), e.getMessage());
            return TaskOutcome.failed(compose("拆子任务失败: " + e.getMessage(), findings));
        }
    }

    /**
     * 验证任务的判定：只看最后一行非空文本。
     *
     * <p>不整段搜「不通过」是因为模型很爱在过程里写「之前那版不通过」这种话，
     * 整段搜必然误判。只认最后一行，规矩明确，提示词里也是这么要求它的。
     */
    private static boolean passed(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String[] lines = text.strip().split("\\R");
        String last = lines[lines.length - 1].strip();
        return !last.contains("不通过");
    }

    /** 失败时也把已经说出来的过程带上，不然用户只看到一句「已取消」，什么线索都没有。 */
    private static String compose(String reason, String text) {
        return text == null || text.isBlank() ? reason : text + "\n(" + reason + ")";
    }
}
