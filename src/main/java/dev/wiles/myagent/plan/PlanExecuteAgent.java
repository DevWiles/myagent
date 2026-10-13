package dev.wiles.myagent.plan;

import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 先规划后执行的<b>编排层</b>：把规划器、执行器、存档三样串成一条能用的链。
 *
 * <pre>
 *   规划 / 载档  →  让人过目  →  装配执行体和调度器  →  逐个任务跑  →  存档收尾
 * </pre>
 *
 * <h3>为什么要有这一层</h3>
 * 这条链原来在终端版和无头版里<b>各写了一遍</b>，两份代码除了「怎么显示」「怎么确认」
 * 之外一模一样。代价是实实在在的：加重规划要改两处、加存档要改两处，
 * 而两处但凡有一处忘了，症状是「某个界面上这个功能就是不灵」，还很难想到是漏改。
 *
 * <p>所以这里收走全部编排，只把两件真正因界面而异的事留给外面：
 * <ul>
 *   <li>{@link PlanApproval}——怎么问「跑不跑」</li>
 *   <li>{@link PlanListener}——怎么把过程画出来</li>
 * </ul>
 *
 * <h3>取消要掐三处</h3>
 * 调度器（不再开新任务）、执行体（掐断正在跑的 ReAct 子循环）、规划器（掐断可能正在
 * 进行的子规划请求）。<b>漏掉任何一处，用户按下停止都像没反应</b>——
 * 这三处原来散在调用方手里，各自记着，现在收进 {@link #cancel()} 一处。
 *
 * <h3>它不管什么</h3>
 * 不管 busy 标志、不管审计日志、不管界面状态——那些是调用方的事，
 * 通过 {@link PlanListener} 挂上去就行。这一层只负责「这条链本身怎么走」。
 */
public final class PlanExecuteAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);

    private final Planner planner;
    private final Supplier<LlmClient> clients;
    private final ToolRegistry tools;
    private final Path workspaceRoot;

    /** 可以没有：那就不存档，计划照跑，只是断了续不上。 */
    private final PlanStore store;

    private PlanApproval approval = PlanApproval.denyAll();
    private PlanListener listener = PlanListener.none();

    /** 正在跑的那两个，取消时要掐。volatile：置位的通常是另一个线程。 */
    private volatile PlanExecutor executor;
    private volatile AgentTaskRunner runner;

    /**
     * @param clients <b>每次要一个新的模型客户端</b>，不是共用一个。规划器和执行体可能
     *                同时在跑（PLANNING 任务会在子循环里再发一次规划请求），
     *                共用一个的话，取消其中一个会把另一个也掐掉
     */
    public PlanExecuteAgent(Planner planner, Supplier<LlmClient> clients,
                            ToolRegistry tools, Path workspaceRoot, PlanStore store) {
        this.planner = planner;
        this.clients = clients;
        this.tools = tools;
        this.workspaceRoot = workspaceRoot;
        this.store = store;
    }

    /** 怎么问「跑不跑」。<b>不设就一律不跑</b>——没人能接话时，安全的默认值是拒绝。 */
    public PlanExecuteAgent approval(PlanApproval approval) {
        this.approval = approval == null ? PlanApproval.denyAll() : approval;
        return this;
    }

    public PlanExecuteAgent listener(PlanListener listener) {
        this.listener = listener == null ? PlanListener.none() : listener;
        return this;
    }

    /**
     * 规划一份新计划并跑起来。
     *
     * @return 用户否决时是 empty；规划失败抛 {@link PlanningException}，由调用方决定怎么说
     */
    public Optional<PlanResult> run(String goal) {
        // 检查必须在规划**之前**：规划是一次真金白银的模型调用，
        // 等它跑完才发现「上一份还在跑」，那次钱就白花了
        requireIdle();
        return execute(planner.plan(goal));
    }

    /**
     * 接着跑上次没跑完的那份。
     *
     * <p>和新计划走<b>同一条路</b>，包括「让人过目」这一步：续跑同样要花钱，
     * 而隔了一段时间，用户未必还记得当初让它干什么，代码也可能已经变了。
     *
     * @return 没有存档、存档坏了、或用户否决，都是 empty
     */
    public Optional<PlanResult> resume() {
        requireIdle();
        Optional<Plan> loaded = store == null ? Optional.empty() : store.load(workspaceRoot);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        return execute(loaded.get());
    }

    /** 上次没跑完的那份计划的摘要，没有就是 empty。界面靠它决定要不要摆出「接着跑」。 */
    public Optional<PlanCheckpoint> savedPlan() {
        return store == null ? Optional.empty() : store.peek(workspaceRoot);
    }

    public boolean hasSavedPlan() {
        return savedPlan().isPresent();
    }

    /** 还在跑吗。 */
    public boolean isRunning() {
        return executor != null;
    }

    /**
     * 叫停。三处一起掐，漏一处按下去就像没反应。
     *
     * <p>正在跑的那个任务停不下来（那是执行体自己的事），但不会再开新的；
     * 而且<b>存档会留着</b>，用户随时可以接着跑。
     */
    public void cancel() {
        PlanExecutor current = executor;
        if (current != null) {
            current.cancel();
        }
        AgentTaskRunner tasks = runner;
        if (tasks != null) {
            tasks.cancel();
        }
        planner.cancel();
    }

    /**
     * 两份计划同时跑，会抢同一个工作区、同一份存档，还会让两批文件改动交叉。
     * 与其让它悄悄跑出一团乱麻，不如当场说清楚——
     * 调用方本来就该用 busy 标志之类的东西挡在前面，这里是最后一道。
     */
    private void requireIdle() {
        if (executor != null) {
            throw new IllegalStateException("上一份计划还在跑，不能同时开第二份");
        }
    }

    private Optional<PlanResult> execute(Plan plan) {
        requireIdle();
        listener.onPlanCreated(plan);
        if (!approval.confirm(plan)) {
            plan.cancel();
            deleteCheckpoint();
            listener.onRejected(plan);
            return Optional.empty();
        }
        // 确认完就断电的话，至少这份计划本身还在，不必重新花钱规划一遍
        saveCheckpoint(plan);
        listener.onApproved(plan);

        // 工厂整个交下去，不是 get() 一次：并行的任务各要各的客户端，理由见 AgentTaskRunner
        AgentTaskRunner taskRunner = new AgentTaskRunner(clients, tools, workspaceRoot)
                .withPlanner(planner)
                // 原话和需求清单跟着计划走（存档里也有），续跑的计划同样带得上
                .withBrief(TaskBrief.of(plan))
                .onEvent(listener::onAgentEvent);
        PlanExecutor planExecutor = new PlanExecutor(taskRunner)
                .onTaskStarted(listener::onTaskStarted)
                .onTaskFinished(listener::onTaskFinished)
                .onTasksAdded(listener::onTasksAdded)
                .withReplanner(planner::replan)
                .onReplanned(listener::onReplanned)
                .onCheckpoint(this::saveCheckpoint);

        this.runner = taskRunner;
        this.executor = planExecutor;
        try {
            PlanResult result = planExecutor.execute(plan);
            // 被叫停的**留着存档**——按下停止多半是想先去干别的，不是不要了。
            // 跑完的（不管成没成）就删：留着它，下次启动会提示续一份早已了结的计划
            if (!result.cancelled()) {
                deleteCheckpoint();
            }
            listener.onFinished(plan, result);
            return Optional.of(result);
        } finally {
            this.executor = null;
            this.runner = null;
        }
    }

    private void saveCheckpoint(Plan plan) {
        if (store != null) {
            store.save(plan, workspaceRoot);
        }
    }

    private void deleteCheckpoint() {
        if (store != null) {
            store.delete(workspaceRoot);
        }
    }
}
