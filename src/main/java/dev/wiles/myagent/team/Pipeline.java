package dev.wiles.myagent.team;

import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.plan.AgentRoles;
import dev.wiles.myagent.plan.AgentTaskRunner;
import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.PlanExecutor;
import dev.wiles.myagent.plan.Planner;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskGraph;
import dev.wiles.myagent.plan.TaskOutcome;
import dev.wiles.myagent.plan.TaskRunner;
import dev.wiles.myagent.plan.TaskStatus;
import dev.wiles.myagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 流水线：<b>规划拆活、几个 worker 并行施工、验收把关</b>，外加一条反馈回路。
 *
 * <pre>
 *   plan(scout 摸清现场、拆出任务) → workers 并行施工 → review(reviewer 验收)
 *           ↑___________重新规划___________|________重新执行________↑
 * </pre>
 *
 * <h3>它取代了圆桌，为什么</h3>
 * 圆桌是「同一件事几个角色各看一遍，最后合议」——只产出意见，不产出工作，
 * 花三倍的钱换不来一行改动。流水线反过来：分工而不是复看，
 * 产出从「一份带分歧的报告」变成「一次有人验收过的施工」。
 *
 * <h3>和 /plan 的区别，一句话</h3>
 * 计划跑完就收工，成没成用户自己看；流水线在后面站了一个<b>有权打回的验收</b>——
 * 它可以要求按原方案重做（{@code 重新执行}），也可以宣判方案本身有问题（{@code 重新规划}），
 * 最多 {@link #MAX_ROUNDS} 轮验收，用完还过不了就如实报 {@link TeamStatus#PARTIAL}。
 * worker 失败也<b>不直接结束流水线</b>：验收会看到失败，由它决定是返工还是重拟——
 * 失败恢复统一走验收这一条路，不再另配失败重规划。
 *
 * <h3>拆活和跑活都是借来的</h3>
 * 规划工序复用 PLANNING 任务的二幕机制（先带只读工具<b>看</b>，再不带工具<b>拆</b>，
 * 见 {@code AgentTaskRunner.expand}）；worker 阶段直接把拆出的任务装进
 * {@link PlanExecutor}——只读并行、会动手的独占、超时、失败扩散，全套纪律现成的。
 * 这层只负责回路本身：轮次、验收判定、返工/重拟的材料交接。
 *
 * <h3>上游怎么交接：锚点任务</h3>
 * 每轮的任务图里塞着几个<b>已经成功的锚点</b>（这轮的方案席、上一轮的验收席），
 * 每个 worker 都依赖它们。于是 {@code upstreamOf} 自动把方案原文和打回意见
 * 送进每个 worker 的提示词——不用为「怎么把方案发给工人」另造一条通道。
 * {@code ready()} 只放行 PENDING 的任务，成功的锚点不会被再跑一遍。
 */
public final class Pipeline {

    private static final Logger log = LoggerFactory.getLogger(Pipeline.class);

    /** 这条流水线的分工说明，进 team_started 帧。 */
    public static final String REASON = "拟方案 → 并行施工 → 验收；打回可重新执行或重新规划";

    /**
     * 最多几轮验收。第 {@code MAX_ROUNDS} 轮还打回就不再返工，如实报 PARTIAL——
     * 一个永远过不了验收的目标，多半是方案和验收标准在打架，烧再多轮也烧不出通过。
     */
    public static final int MAX_ROUNDS = 3;

    /**
     * 规划/验收工序能调几轮工具（worker 用角色自带的上限，它们的活是拆好的一小步）。
     *
     * <p>沿用圆桌一席的数字：真机教训是角色自带上限按「计划里的一小步」调，
     * 而这两道工序面对的是整个目标，给小了撞上限直接作废。
     */
    public static final int STAGE_STEPS = 24;

    /**
     * 验收席单独的步数。它要跑测试、对每条需求写探针、再读改动清单里的每个文件——
     * 活的大小随施工规模长，16 步在四个 coder 的任务上不够用：真机跑出过验收席
     * 已经写下「重新执行」却因撞上限被判失败、整场落成 PARTIAL 的情况。
     */
    public static final int REVIEW_STEPS = 28;

    /**
     * worker 的步数。以前和规划席共用 {@link #STAGE_STEPS}——16 步在真实仓库上不够：
     * 读几张几十 KB 的表、写、跑测试、再改，AsynCodeBench wcwidth 的 coder 40 秒就撞上限，
     * 明明已经写了 160 行却被判失败、下游全跳、验收打回、下一轮同样预算同样结果。
     */
    public static final int WORKER_STEPS = 32;

    /**
     * 单道工序的时限，毫秒。worker 也按它算（交给 {@link PlanExecutor#taskTimeout}）。
     * 一小时，和 {@link PlanExecutor#DEFAULT_TASK_TIMEOUT_MILLIS} 同一个理由：是止损线不是性能阈值，
     * 验收席要跑测试、写探针、读每个改过的文件，给紧了撞线就是整轮作废。
     */
    public static final long DEFAULT_STAGE_TIMEOUT_MILLIS = 3_600_000;

    private final TaskRunner runner;

    /** 验收席用的执行体：步数按 {@link #REVIEW_STEPS}。测试注入执行体时和 {@link #runner} 是同一个。 */
    private final TaskRunner reviewRunner;

    /** worker 用的执行体：步数按 {@link #WORKER_STEPS}。测试注入执行体时和 {@link #runner} 是同一个。 */
    private final TaskRunner workerRunner;

    private final AgentTaskRunner workerCancellable;

    /** 内建执行体时留一份引用，取消要掐它。外部注入执行体（测试）时是 null。 */
    private final AgentTaskRunner cancellable;

    /** 验收席的执行体，取消时一起掐。测试注入时是 null。 */
    private final AgentTaskRunner reviewCancellable;

    /** 只为取消留着：拆任务那次结构化调用在它手里。测试注入执行体时是 null。 */
    private final Planner planner;

    private TeamListener listener = TeamListener.none();

    /** 用户叫停了。volatile：置位的是监听 Ctrl-C / cancel 帧的那个线程。 */
    private volatile boolean cancelled;

    /** 正在跑 worker 的那台调度器，取消要掐它。没在 worker 阶段时是 null。 */
    private volatile PlanExecutor executing;

    /** 单道工序的时限。{@code <= 0} 表示不限时（只在测试里用）。 */
    private long stageTimeoutMillis = DEFAULT_STAGE_TIMEOUT_MILLIS;

    /**
     * 拍改动快照的工作区。null 就不拍，验收材料里没有改动清单（测试注入执行体时的默认）。
     * 见 {@link ChangeSet}：验收看的「改了什么」由程序算，不由工人自述。
     */
    private Path changeRoot;

    /**
     * @param clients 每道工序现造一个模型客户端：几个 worker 并行时共用一个
     *                {@code DeepSeekClient} 会互相踩 {@code currentCall}，取消掐不准
     * @param planner PLANNING 工序拆子任务用的规划器（也负责被取消）。
     *                和 /plan 共用一个实例没问题——busy 闸保证同一时刻只有一件事在跑
     */
    public Pipeline(Supplier<LlmClient> clients, ToolRegistry tools, Path workspaceRoot,
                    Planner planner) {
        AgentTaskRunner built = new AgentTaskRunner(clients, tools, workspaceRoot, STAGE_STEPS)
                .withPlanner(planner);
        // 子循环的动静原样转出去，界面才画得出「t2 正在改哪个文件」。
        // 读的是字段不是构造时的值——listener() 是构造之后才调的
        built.onEvent((stage, event) -> listener.onAgentEvent(stage, event));
        AgentTaskRunner reviewing = new AgentTaskRunner(clients, tools, workspaceRoot, REVIEW_STEPS);
        reviewing.onEvent((stage, event) -> listener.onAgentEvent(stage, event));
        AgentTaskRunner working = new AgentTaskRunner(clients, tools, workspaceRoot, WORKER_STEPS);
        working.onEvent((stage, event) -> listener.onAgentEvent(stage, event));
        this.runner = built;
        this.reviewRunner = reviewing;
        this.workerRunner = working;
        this.cancellable = built;
        this.reviewCancellable = reviewing;
        this.workerCancellable = working;
        this.planner = planner;
        this.changeRoot = workspaceRoot;
    }

    /** 给测试用：换掉执行体，整套编排（轮次、判定、返工、取消）就能测干净，一次调用都不发。 */
    Pipeline(TaskRunner runner) {
        this.runner = runner;
        this.reviewRunner = runner;
        this.workerRunner = runner;
        this.cancellable = null;
        this.reviewCancellable = null;
        this.workerCancellable = null;
        this.planner = null;
    }

    /** 指定拍改动快照的目录。给测试用：假执行体也能测「验收材料里有改动清单」。 */
    Pipeline trackChanges(Path root) {
        this.changeRoot = root;
        return this;
    }

    public Pipeline listener(TeamListener listener) {
        this.listener = listener == null ? TeamListener.none() : listener;
        return this;
    }

    /** 单道工序的时限，从它开跑那一刻算起。传 null 或非正值表示不限时（只在测试里用）。 */
    public Pipeline stageTimeout(Duration timeout) {
        this.stageTimeoutMillis = timeout == null || timeout.isZero() || timeout.isNegative()
                ? 0 : timeout.toMillis();
        return this;
    }

    /**
     * 叫停。四处都要掐：不开下一道工序、掐正在跑的子循环、掐可能正在拆任务的规划请求、
     * 掐 worker 调度器（不再放新任务进场）。漏掉任何一处，按下停止都像没反应。
     */
    public void cancel() {
        cancelled = true;
        if (cancellable != null) {
            cancellable.cancel();
        }
        if (reviewCancellable != null) {
            reviewCancellable.cancel();
        }
        if (workerCancellable != null) {
            workerCancellable.cancel();
        }
        if (planner != null) {
            planner.cancel();
        }
        PlanExecutor current = executing;
        if (current != null) {
            current.cancel();
        }
    }

    /**
     * 跑一条流水线。
     *
     * <p>只读模式不用单独传：规划器构造时已经带了 {@code readOnly}，
     * 会动手的角色在拆任务那一步就派不出来。
     */
    public TeamResult run(String goal) {
        cancelled = false;          // 上一场的取消标志不能带到这一场

        List<Task> stages = new ArrayList<>();
        int round = 1;
        Task plan = planSeat(round, goal);
        stages.add(plan);
        listener.onStarted(goal, REASON, List.of(plan));

        Task review = null;
        TeamStatus status;
        // 蓝本 id → 它最近一次落地的那道工序。返工只重跑挑出来的那几道，其余的拿这张表当锚点
        Map<String, Task> latest = new LinkedHashMap<>();
        Set<String> redo = null;                // null = 整份蓝本都跑（第一轮、重拟之后、验收没点名）
        RoundGuard guard = RoundGuard.open(changeRoot);

        List<Task> blueprint = advancePlan(plan, List.of());
        if (blueprint == null) {
            status = cancelled ? TeamStatus.CANCELLED : TeamStatus.FAILED;
        } else {
            status = null;
            while (status == null) {
                List<Task> workers = materialize(blueprint, round, plan, review, latest, redo);
                stages.addAll(workers);
                listener.onStagesAdded(workers);
                Map<String, String> before = ChangeSet.snapshot(changeRoot);
                runWorkers(goal, workers, anchorsFor(workers, latest), plan, review);
                if (cancelled) {
                    status = TeamStatus.CANCELLED;
                    break;
                }
                for (Task worker : workers) {
                    latest.put(blueprintId(worker.id()), worker);
                }
                String changes = ChangeSet.report(before, ChangeSet.snapshot(changeRoot));

                // 验收看的是<b>整份</b>活的现状，不只是这一轮返工的那几道
                review = reviewSeat(round, goal, plan, List.copyOf(latest.values()), changes);
                stages.add(review);
                listener.onStagesAdded(List.of(review));
                boolean reviewed = advance(review, List.of(), reviewRunner);
                if (cancelled) {
                    status = TeamStatus.CANCELLED;
                } else if (!reviewed) {
                    status = TeamStatus.PARTIAL;        // 没审成：活在，担保不在
                } else if (guard.regressed(round, testScoreOf(review.result()))) {
                    // 返工把测试改少了：工作区退回最好的那一轮，不再接着返工——
                    // 再来一轮多半是在更差的底子上继续改，cookiecutter 上就是这么从好改到差的
                    status = TeamStatus.PARTIAL;
                } else {
                    switch (verdictOf(review.result())) {
                        case PASS -> status = TeamStatus.COMPLETED;
                        case REWORK -> {
                            if (round >= MAX_ROUNDS) {
                                status = TeamStatus.PARTIAL;
                            } else {
                                // 蓝本不变；只重跑验收点名的那几道（加上没跑成的和它们的下游），
                                // 打回意见走锚点进 worker 的上游
                                round++;
                                redo = reworkSet(blueprint, latest, reworkIdsOf(review.result()));
                            }
                        }
                        case REPLAN -> {
                            if (round >= MAX_ROUNDS) {
                                status = TeamStatus.PARTIAL;
                                break;
                            }
                            round++;
                            plan = planSeat(round, goal);
                            stages.add(plan);
                            listener.onStagesAdded(List.of(plan));
                            // 打回意见作上游：新方案是对着验收的问题拟的，不是从零再想一遍
                            blueprint = advancePlan(plan, List.of(review));
                            latest.clear();     // 新蓝本，旧工序的 id 对不上了
                            redo = null;
                            if (blueprint == null) {
                                status = cancelled ? TeamStatus.CANCELLED : TeamStatus.PARTIAL;
                            }
                        }
                    }
                }
            }
        }

        guard.close();

        // 没轮到的工序也要落终态，不然界面上那一道永远转圈
        String reason = cancelled ? "已取消" : "上游没跑成";
        for (Task stage : stages) {
            if (stage.status() == TaskStatus.PENDING) {
                stage.skip(reason);
                listener.onStageFinished(stage);
            }
        }

        TeamResult result = new TeamResult(goal, REASON, stages, review, round, status);
        listener.onFinished(result);
        return result;
    }

    // ── 三种工序 ────────────────────────────────────────────────

    /** 规划席。id 带轮次（plan、plan2…），每次重拟都是一道新工序，历史在座位表里留着。 */
    private static Task planSeat(int round, String goal) {
        return new Task(round == 1 ? "plan" : "plan" + round,
                TeamPrompt.plannerTitle(goal, round), AgentRoles.SCOUT,
                List.of(), TeamPrompt.plannerAcceptance(STAGE_STEPS));
    }

    /**
     * 验收席。角色是 {@link AgentRoles#ACCEPTOR}（只读 + bash）而不是 reviewer：
     * 验收要能跑测试和探针。以前用只读的 reviewer，它只能读 {@code .pytest_cache}
     * 猜测试有没有过、对着施工报告「手工推演」——四次隐藏测试挂掉它四次都判了通过。
     */
    private static Task reviewSeat(int round, String goal, Task plan, List<Task> workers,
                                   String changes) {
        return new Task(round == 1 ? "review" : "review" + round,
                TeamPrompt.reviewBrief(round, goal, plan, workers, changes), AgentRoles.ACCEPTOR,
                List.of(), TeamPrompt.reviewerAcceptance(REVIEW_STEPS));
    }

    /**
     * 跑规划席，交回拆出的任务蓝本；没拆成（失败、超时、取消、拆出来是空的）返回 null。
     *
     * <p>「拆出来是空的」按失败算：这道工序存在的唯一意义就是产出任务，
     * 产不出来还报成功，后面就是零个 worker 加一场对着空气的验收。
     */
    private List<Task> advancePlan(Task plan, List<Task> upstream) {
        if (cancelled) {
            return null;
        }
        plan.start();
        listener.onStageStarted(plan);
        TaskOutcome outcome = settle(plan, upstream, runner);
        if (outcome.ok() && outcome.subTasks().isEmpty()) {
            outcome = TaskOutcome.failed("方案没拆出可执行的任务");
        }
        apply(plan, outcome);
        listener.onStageFinished(plan);
        return plan.status() == TaskStatus.SUCCEEDED && !cancelled ? outcome.subTasks() : null;
    }

    /**
     * 把蓝本落成这一轮真正要跑的任务。
     *
     * <p>三件事：id 带上轮次前缀（第 2 轮起，{@code r2.t1}——蓝本会被返工反复使用，
     * 座位表上的 id 必须全场唯一）；依赖和 <code>{{引用}}</code> 跟着 id 一起改写；
     * 挂上锚点依赖（方案席、上一轮验收席），worker 的上游材料全靠这两条边。
     *
     * <p>{@code redo} 不为 null 时只落这几道：其余的不重跑，它们的依赖和引用改指向
     * {@code latest} 里上一次落地的那道工序（由 {@link #anchorsFor} 作为已成功的锚点送进图）。
     */
    private static List<Task> materialize(List<Task> blueprint, int round, Task plan, Task review,
                                          Map<String, Task> latest, Set<String> redo) {
        Map<String, String> renames = new LinkedHashMap<>();
        for (Task raw : blueprint) {
            boolean rerun = redo == null || redo.contains(raw.id()) || !latest.containsKey(raw.id());
            renames.put(raw.id(), rerun
                    ? (round == 1 ? raw.id() : "r" + round + "." + raw.id())
                    : latest.get(raw.id()).id());
        }

        List<Task> out = new ArrayList<>(blueprint.size());
        for (Task raw : blueprint) {
            if (redo != null && !redo.contains(raw.id()) && latest.containsKey(raw.id())) {
                continue;               // 这一道不返工，上一轮的成果原样留着
            }
            String description = rewriteRefs(raw.description(), renames);
            String acceptance = raw.acceptance() == null ? null
                    : rewriteRefs(raw.acceptance(), renames);
            List<String> deps = new ArrayList<>();
            for (String dep : raw.dependencies()) {
                // 蓝本外的依赖（模型手滑写了方案席的 id 之类）丢掉——锚点马上会统一挂
                if (renames.containsKey(dep)) {
                    deps.add(renames.get(dep));
                }
            }
            deps.add(plan.id());
            if (review != null) {
                deps.add(review.id());
            }
            out.add(plan.subTask(renames.get(raw.id()), description, raw.role(), deps, acceptance,
                    raw.files()));
        }
        return out;
    }

    /**
     * 这一轮的 worker 依赖、但这一轮不重跑的那些上一轮工序。
     *
     * <p>送进图的是<b>没有依赖的成功副本</b>：原件挂着上上一轮的验收席之类的依赖，
     * 那些节点不在这张图里，建图会报悬空依赖。副本只带 id、描述和产出——
     * 下游要的 <code>{{引用}}</code> 和上游材料就这么多。
     */
    private static List<Task> anchorsFor(List<Task> workers, Map<String, Task> latest) {
        Set<String> running = new HashSet<>();
        workers.forEach(w -> running.add(w.id()));
        Map<String, Task> byId = new LinkedHashMap<>();
        latest.values().forEach(t -> byId.put(t.id(), t));
        List<Task> anchors = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (Task worker : workers) {
            for (String dep : worker.dependencies()) {
                Task prev = byId.get(dep);
                if (prev != null && !running.contains(dep) && added.add(dep)) {
                    Task copy = new Task(prev.id(), prev.description(), prev.role(), List.of(),
                            prev.acceptance());
                    copy.start();
                    copy.succeed(prev.result() == null ? "" : prev.result());
                    anchors.add(copy);
                }
            }
        }
        return anchors;
    }

    /** {@code r3.t2} → {@code t2}：返工的工序和蓝本里那一道对上号。 */
    static String blueprintId(String id) {
        return id.matches("r\\d+\\..+") ? id.substring(id.indexOf('.') + 1) : id;
    }

    /**
     * 这一轮返工要重跑哪几道。
     *
     * <p>验收点名的（{@code 返工: t2, t5}）+ 上一轮没跑成的 + 它们在蓝本里的全部下游——
     * 上游改了，下游拿到的就是过期材料，得跟着重来。验收没点名、或者点的名一个都对不上，
     * 返回 null：退回老做法，整份蓝本重跑。宁可多跑，也不能因为一行没写对就什么都不修。
     */
    static Set<String> reworkSet(List<Task> blueprint, Map<String, Task> latest, List<String> named) {
        Set<String> ids = new HashSet<>();
        blueprint.forEach(t -> ids.add(t.id()));
        Set<String> redo = new HashSet<>();
        for (String n : named) {
            String id = blueprintId(n);
            if (ids.contains(id)) {
                redo.add(id);
            }
        }
        if (redo.isEmpty()) {
            return null;
        }
        for (Task raw : blueprint) {
            Task prev = latest.get(raw.id());
            if (prev == null || prev.status() != TaskStatus.SUCCEEDED) {
                redo.add(raw.id());
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Task raw : blueprint) {
                if (!redo.contains(raw.id()) && raw.dependencies().stream().anyMatch(redo::contains)) {
                    redo.add(raw.id());
                    grew = true;
                }
            }
        }
        return redo;
    }

    private static String rewriteRefs(String text, Map<String, String> renames) {
        String rewritten = text;
        for (Map.Entry<String, String> e : renames.entrySet()) {
            if (!e.getKey().equals(e.getValue())) {
                rewritten = rewritten.replace("{{" + e.getKey() + "}}", "{{" + e.getValue() + "}}");
            }
        }
        return rewritten;
    }

    /**
     * 把这一轮的 worker 交给计划执行器跑：只读并行、会动手的独占、超时、失败扩散，
     * 全套纪律都是它的。方案席和上一轮验收席作为<b>已经成功的锚点</b>一起进图——
     * 调度器不会再跑它们（{@code ready()} 只放行 PENDING），但 worker 依赖它们，
     * 上游材料就这么送进去。
     *
     * <p>worker 失败在这儿不算流水线失败：验收会看到失败的任务和原因，
     * 由它决定重新执行还是重新规划。所以这里不配失败重规划（{@code withReplanner}），
     * 两套补救叠在一起，谁救的、救没救成就说不清了。
     */
    private void runWorkers(String goal, List<Task> workers, List<Task> anchors, Task plan,
                            Task review) {
        if (cancelled) {
            return;
        }
        List<Task> nodes = new ArrayList<>(workers);
        nodes.addAll(anchors);
        nodes.add(plan);
        if (review != null) {
            nodes.add(review);
        }
        PlanExecutor executor = new PlanExecutor(workerRunner)
                .onTaskStarted(listener::onStageStarted)
                .onTaskFinished(listener::onStageFinished)
                .taskTimeout(Duration.ofMillis(stageTimeoutMillis));
        this.executing = executor;
        try {
            executor.execute(new Plan(goal, TaskGraph.of(nodes)));
        } catch (RuntimeException e) {
            // 图不合法（蓝本的依赖成环之类）不该炸掉整条流水线：worker 全灭，
            // 验收会看到零产出并给出打回——和「施工全失败」走同一条收场路
            log.error("这一轮 worker 没跑起来", e);
            for (Task worker : workers) {
                if (worker.status() == TaskStatus.PENDING) {
                    worker.skip("这一轮没跑起来: " + e.getMessage());
                    listener.onStageFinished(worker);
                }
            }
        } finally {
            this.executing = null;
        }
    }

    /**
     * 跑一道单席工序（验收），把它推到终态。
     *
     * @return 这道工序成了、且没被叫停
     */
    private boolean advance(Task stage, List<Task> upstream, TaskRunner with) {
        if (cancelled) {
            return false;
        }
        stage.start();
        listener.onStageStarted(stage);
        apply(stage, settle(stage, upstream, with));
        listener.onStageFinished(stage);
        return stage.status() == TaskStatus.SUCCEEDED && !cancelled;
    }

    private void apply(Task stage, TaskOutcome outcome) {
        try {
            if (outcome.ok()) {
                stage.succeed(outcome.output());
            } else {
                stage.fail(outcome.output());
            }
        } catch (IllegalStateException e) {
            log.error("工序 {} 的状态迁移非法，执行体自己动过它", stage.id(), e);
        }
    }

    /** 在工作线程上跑一道工序，带时限地等。<b>不抛异常</b>：一道倒了要的是如实落账，不是整条线炸掉。 */
    private TaskOutcome settle(Task stage, List<Task> upstream, TaskRunner with) {
        // 工序在工作线程上跑，Task 的状态迁移留在编排线程——和 PlanExecutor 同一条纪律。
        // 要线程只是为了能对它计时、能从别的线程掐断它
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stage-" + stage.id());
            t.setDaemon(true);
            return t;
        });
        Future<TaskOutcome> future = pool.submit(() -> with.run(stage, upstream));
        try {
            return stageTimeoutMillis > 0
                    ? future.get(stageTimeoutMillis, TimeUnit.MILLISECONDS)
                    : future.get();
        } catch (TimeoutException e) {
            // 先掐子循环再取消 future：掐得干不干净的保留意见和 PlanExecutor 一样，
            // cancel(true) 只是设中断标志，子循环最多再走完手上这一步
            if (cancellable != null) {
                cancellable.cancel();
            }
            if (reviewCancellable != null) {
                reviewCancellable.cancel();
            }
            future.cancel(true);
            log.warn("工序 {} 超过 {} 毫秒还没完，按失败处理", stage.id(), stageTimeoutMillis);
            return TaskOutcome.failed("这道工序超时了（上限 " + stageTimeoutMillis + " 毫秒）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
            future.cancel(true);
            return TaskOutcome.failed("等待工序完成时被中断");
        } catch (ExecutionException e) {
            // 执行体是外部实现，可能没兜住异常。一道炸了不该把整条线带走
            Throwable cause = e.getCause();
            log.error("工序 {} 的执行体抛了异常", stage.id(), cause);
            return TaskOutcome.failed(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    // ── 验收判定 ────────────────────────────────────────────────

    enum Verdict { PASS, REWORK, REPLAN }

    /**
     * 只看最后一行非空文本。规矩和 {@code AgentTaskRunner.passed} 同一条：
     * 模型很爱在过程里写「上一版该重新执行」这种话，整段搜必然误判。
     *
     * <p>「重新规划」优先于「重新执行」（一句话俩词都有时，重的那个才是结论）；
     * 「打回」单独出现按重新执行算——那是这套提示词上一版的用词，模型的语感里还留着；
     * 三个词都没有按通过算，和验证任务「找不到否决词就是过」同向。
     */
    /**
     * 验收报的测试成绩：{@code 测试: 通过 133 / 共 202}。取最后一次出现的那行；
     * 没报（项目没有测试、或者模型漏写）返回 null，那一轮就不参与保底比较。
     */
    static int[] testScoreOf(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("测试\\s*[:：]\\s*通过\\s*(\\d+)\\s*/\\s*共\\s*(\\d+)").matcher(text);
        int[] score = null;
        while (m.find()) {
            score = new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return score;
    }

    /** 验收点名要返工的工序：{@code 返工: t2, r2.t5}。没写就是空表。 */
    static List<String> reworkIdsOf(String text) {
        if (text == null) {
            return List.of();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("返工\\s*[:：]\\s*(.+)").matcher(text);
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            ids.clear();        // 只认最后一行
            for (String part : m.group(1).split("[,，、\\s]+")) {
                if (part.matches("[A-Za-z0-9_.-]+")) {
                    ids.add(part);
                }
            }
        }
        return ids;
    }

    static Verdict verdictOf(String text) {
        if (text == null || text.isBlank()) {
            return Verdict.PASS;
        }
        String[] lines = text.strip().split("\\R");
        String last = lines[lines.length - 1];
        if (last.contains("重新规划")) {
            return Verdict.REPLAN;
        }
        if (last.contains("重新执行") || last.contains("打回")) {
            return Verdict.REWORK;
        }
        return Verdict.PASS;
    }
}
