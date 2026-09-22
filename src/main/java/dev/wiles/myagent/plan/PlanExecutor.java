package dev.wiles.myagent.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 计划执行器：拿一张图，按依赖顺序把任务推到终态，互不依赖的<b>同时</b>推。
 *
 * <pre>
 *   while (图还没跑完) {
 *       把就绪的任务放进池子（只读的并行，会动世界的独占）
 *       等一个跑完 → succeed() / fail()
 *       失败了 → 顺着 dependents 把下游全标 SKIPPED
 *       是 PLANNING 且拆出了子任务 → 塞进图里，下一轮就能调度到
 *   }
 * </pre>
 *
 * <p><b>为什么每轮都重新问一次 ready()，而不是一开始就把层算好按层跑</b>：
 * 因为 PLANNING 任务会让图在执行过程中长大，预先算好的层次会立刻过期。
 * 每轮重算的代价是扫一遍几十个任务，可以忽略。
 *
 * <h3>并发的规矩：读共享，写独占</h3>
 * 就绪不等于能一起跑。两个 {@code coder} 任务改同一个文件的隐式冲突，图上看不出来
 * （依赖是模型填的，它只声明「谁要谁的产出」，不声明「谁碰哪个文件」），
 * 所以并发按角色分流：{@link AgentRole#mutates()} 的任务<b>独占</b>运行——
 * 它在跑时不放任何新任务，别的任务在跑时它也不进场；只读的任务随便一起跑，
 * 它们怎么跑都不会互相踩。这条规矩顺带保住了两件事：
 * <ul>
 *   <li>审批框一次只弹一个——会开口要审批的只有 mutates 角色手里的工具</li>
 *   <li>审批的「是谁在问」不会张冠李戴——独占意味着弹框那一刻场上只有它一个</li>
 * </ul>
 *
 * <h3>状态只在调度线程改</h3>
 * {@link Task} 和 {@link TaskGraph} 都没加锁，靠的是纪律：工作线程只干
 * {@code runner.run()} 这一件事，start/succeed/fail/传播/扩图/存档全部发生在调度线程上。
 * 交回结果那一下由 {@code Future.get()} 提供 happens-before，两边不会看到半截状态。
 *
 * <p>这个类有一条硬性要求：<b>跑完之后图里不能留下非终态的任务</b>。
 * 失败、异常、取消，每条路都得给所有任务一个交代，否则界面上会挂着一个永远转圈的任务。
 */
public final class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    /**
     * 只读任务默认最多同时跑几个。
     *
     * <p>一个任务是一整个 ReAct 子循环，几步模型调用起步——瓶颈在模型服务的限流，
     * 不在本机。三个已经能把「五个调研任务排队等」的时间砍掉大半；
     * 再宽先撞上的是 429，反而更慢。
     */
    public static final int DEFAULT_PARALLELISM = 3;

    /**
     * 一个任务最多跑多久，毫秒。
     *
     * <p>十分钟是按<b>最慢的角色</b>算的：coder 有 12 步预算，每步一次模型调用
     * （读超时 5 分钟）外加若干工具。正常跑完远用不到，所以这不是性能阈值，
     * 是止损线——它防的是「某一步卡死，调度线程跟着吊住，
     * 已经跑完的任务连存档都推不进去」。
     *
     * <p>并发之后这条线才真正要紧：串行时一个任务卡住只卡它自己，
     * 现在卡的是整个调度循环。
     */
    public static final long DEFAULT_TASK_TIMEOUT_MILLIS = 600_000;

    /** 没人跑完时，多久回头扫一次超时。扫一遍是几个元素的遍历，这个间隔的开销可以忽略。 */
    private static final long SWEEP_INTERVAL_MILLIS = 200;

    private final TaskRunner runner;

    /** 并发宽度。1 就是原来的串行行为，测试要确定顺序时用它。 */
    private int parallelism = DEFAULT_PARALLELISM;

    /** 单个任务的时限。{@code <= 0} 表示不限时。 */
    private long taskTimeoutMillis = DEFAULT_TASK_TIMEOUT_MILLIS;

    private Consumer<Task> onStarted = task -> {
    };
    private Consumer<Task> onFinished = task -> {
    };
    private BiConsumer<Task, List<Task>> onExpanded = (parent, added) -> {
    };
    private BiConsumer<Task, List<Task>> onReplanned = (failed, recovery) -> {
    };

    /** 没配就退化成原来的行为：一步倒了，下游全跳过，计划就此打住。 */
    private Replanner replanner;

    /** 该存档了。没配就不存，计划照跑，只是断了续不上。 */
    private Consumer<Plan> onCheckpoint = plan -> {
    };

    /** 用户叫停了。volatile 是因为置位的通常是另一个线程（监听 Ctrl-C 的那个）。 */
    private volatile boolean cancelled;

    public PlanExecutor(TaskRunner runner) {
        this.runner = runner;
    }

    /** 某个任务开跑。界面靠它画出「正在做第几步」。 */
    public PlanExecutor onTaskStarted(Consumer<Task> listener) {
        this.onStarted = listener;
        return this;
    }

    /**
     * 某个任务了结。<b>被连带跳过的任务也会走这里</b>——
     * 它们同样是「这一步有了结果」，界面不该只显示跑过的那几个。
     */
    public PlanExecutor onTaskFinished(Consumer<Task> listener) {
        this.onFinished = listener;
        return this;
    }

    /**
     * 图长大了：某个 PLANNING 任务拆出了一批子任务。
     *
     * <p>界面必须知道这件事，否则它画的还是最初那张图，
     * 而屏幕上却在跑一些图里没有的任务。
     */
    public PlanExecutor onTasksAdded(BiConsumer<Task, List<Task>> listener) {
        this.onExpanded = listener;
        return this;
    }

    /**
     * 配上重规划器，任务失败时才会尝试补救。
     *
     * <p>可选而不是构造参数，理由和 {@code AgentTaskRunner.withPlanner} 一样：
     * 没有它整条链照样跑得通（失败就是失败），测试调度逻辑时也不必捏一个规划器出来。
     */
    public PlanExecutor withReplanner(Replanner replanner) {
        this.replanner = replanner;
        return this;
    }

    /**
     * 某一步失败之后补出了一批救场任务。
     *
     * <p>界面必须知道——否则用户看到的是「某一步红了」，然后屏幕上莫名其妙
     * 冒出几个计划里根本没有的任务在跑。
     */
    public PlanExecutor onReplanned(BiConsumer<Task, List<Task>> listener) {
        this.onReplanned = listener;
        return this;
    }

    /**
     * 每了结一个任务就喊一次，让调用方把当前进度存下来。
     *
     * <p><b>为什么是一个任务一次</b>：存档的粒度直接决定崩溃后能恢复到多细。
     * 整个计划跑完才存等于没存；一个任务一次，最坏情况只丢正在跑的那一个。
     * 一次写盘几十 KB，和一次模型调用比可以忽略。
     *
     * <p>放在这个位置（一轮调度的末尾）而不是塞进 {@code onTaskFinished}：
     * 失败传播和重规划都发生在一轮之内，等这一轮整个走完再存，
     * 落盘的才是一个自洽的状态，而不是「任务已失败但补救任务还没进图」这种中间态。
     */
    public PlanExecutor onCheckpoint(Consumer<Plan> listener) {
        this.onCheckpoint = listener;
        return this;
    }

    /** 叫停。正在跑的那些任务停不下来（那是执行体自己的事），但不会再开新的。 */
    public void cancel() {
        cancelled = true;
    }

    /** 调多宽。小于 1 一律按 1 算——0 个并发的调度器什么都跑不了。 */
    public PlanExecutor parallelism(int n) {
        this.parallelism = Math.max(1, n);
        return this;
    }

    /**
     * 单个任务的时限，从它<b>进场</b>那一刻算起。
     *
     * <p>传 null 或非正值表示不限时，行为退回到「一直等」——只在测试里用得上。
     * 生产环境关掉它，等于把「一个卡死的任务吊住整个调度循环」这条路重新打开。
     */
    public PlanExecutor taskTimeout(Duration timeout) {
        this.taskTimeoutMillis = timeout == null || timeout.isZero() || timeout.isNegative()
                ? 0 : timeout.toMillis();
        return this;
    }

    public PlanResult execute(Plan plan) {
        cancelled = false;   // 上一轮的取消标志不能带到这一轮
        plan.start();
        TaskGraph graph = plan.graph();

        // 守护线程：存档在调度线程上、每了结一个存一次，进程退出时最多丢正在跑的那几个
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "plan-task");
            t.setDaemon(true);
            return t;
        });
        CompletionService<Finished> finish = new ExecutorCompletionService<>(pool);
        // 正在池子里的任务。只有调度线程碰它，所以不用加锁
        Map<String, Running> inFlight = new LinkedHashMap<>();

        try {
            while (true) {
                if (!cancelled) {
                    launchReady(graph, finish, inFlight);
                }
                if (inFlight.isEmpty()) {
                    if (cancelled) {
                        // 顺序要紧：**先存档，再把剩下的了结掉**。
                        // 存档要能续，靠的是里面那些任务还停在 PENDING、计划还停在 RUNNING；
                        // 等 skipAllPending 跑完再存，落盘的就是一份「全都了结了」的死图，
                        // 续无可续。而 Ctrl-C 恰恰是最常见的中断方式——这一行决定了
                        // 断点续跑到底是真能用，还是只在进程被 kill 时才用得上。
                        checkpoint(plan);
                        skipAllPending(graph, "已取消");
                        break;
                    }
                    if (graph.isDone()) {
                        break;
                    }
                    // 图没跑完、池子空着、也没人能进场——只可能是失败扩散漏了谁。
                    // 与其在这儿静默死循环，不如把剩下的了结掉并留个日志。
                    log.error("调度停滞：图没跑完却没有就绪任务，剩余 {} 个", pendingCount(graph));
                    skipAllPending(graph, "调度停滞，没有可执行的上游");
                    break;
                }

                Future<Finished> ready;
                try {
                    // 有时限就轮着等，好腾出手去扫超时；不限时就老老实实挂在 take() 上
                    ready = taskTimeoutMillis > 0
                            ? finish.poll(SWEEP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                            : finish.take();
                } catch (InterruptedException e) {
                    // 调度线程自己被中断了。没法再等池子里的结果，按取消收场：
                    // 存档留在「还能续」的状态，正在跑的那几个由 settle() 记一条调度漏账
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    checkpoint(plan);
                    skipAllPending(graph, "已取消");
                    break;
                }
                if (ready == null) {
                    // 这一轮没人跑完。看看有没有谁超时了——超时的当场判失败，
                    // 别的任务照跑；不扫的话，一个卡死的任务会把调度线程吊住，
                    // 连带已经跑完的那些**结果收不回来、存档也推不进去**
                    if (sweepOverdue(plan, graph, inFlight)) {
                        checkpoint(plan);
                    }
                    continue;
                }
                Finished done = resolve(ready);
                if (done == null) {
                    continue;   // 被超时扫描掐掉的，那边已经了结过它了
                }
                inFlight.remove(done.task().id());
                settleOne(plan, graph, done.task(), done.outcome());
                checkpoint(plan);
            }
        } finally {
            pool.shutdownNow();
        }

        return settle(plan);
    }

    /**
     * 把当前能进场的任务放进池子。
     *
     * <p>规矩见类注释：会动世界的任务（{@link AgentRole#mutates()}）独占——
     * 场上空着才进，进了别人就得等；只读任务共享，受 {@link #parallelism} 封顶。
     * 只读任务可以越过一个在等的写任务先跑（图是有限的，写任务不会饿死），
     * 反过来不行——写任务永远等到场上清空。
     *
     * <p>{@code start()} 和 {@code onStarted} 都在调度线程上做完<b>再</b>提交，
     * 状态迁移不出调度线程，这是全类不加锁的前提。
     */
    private void launchReady(TaskGraph graph, CompletionService<Finished> finish,
                             Map<String, Running> inFlight) {
        boolean mutatingInFlight = inFlight.values().stream()
                .anyMatch(r -> r.task().role().mutates());
        for (Task task : graph.ready()) {
            if (inFlight.containsKey(task.id())) {
                continue;
            }
            if (task.role().mutates()) {
                if (!inFlight.isEmpty()) {
                    continue;       // 等场上清空，独占进场
                }
            } else if (mutatingInFlight || inFlight.size() >= parallelism) {
                continue;
            }

            task.start();
            onStarted.accept(task);
            List<Task> upstream = upstreamOf(graph, task);   // 在调度线程上取好再交出去
            Future<Finished> future =
                    finish.submit(() -> new Finished(task, runSafely(task, upstream)));
            // 计时从**进场**算起，不是从任务被规划出来算起：排队等在外面的时间
            // 不是这一步的成本，算进去的话，宽度调小一点就会开始有任务凭空超时
            inFlight.put(task.id(), new Running(task, future,
                    System.nanoTime() + taskTimeoutMillis * 1_000_000L));

            if (task.role().mutates()) {
                return;             // 独占：这一轮不再放任何人
            }
        }
    }

    /**
     * 把超时的任务判掉。
     *
     * <h3>为什么超时是「这一步失败」，而不是「整个计划失败」</h3>
     * 失败是图上已有的语义：下游顺着 dependents 跳过、重规划还有机会补救、
     * 存档照常推进。超时只是失败的一个新来源，不该另起一套收场方式。
     *
     * <h3>掐得干净吗：不保证</h3>
     * {@code cancel(true)} 只给工作线程设一个中断标志。{@link dev.wiles.myagent.agent.Agent}
     * 认这个标志（见它的 {@code shouldStop}），所以子循环最多再走完手上这一步就收口——
     * 但那一步里如果正卡在 HTTP 读上（最长 5 分钟）或者一条 bash 上（最长 5 分钟），
     * 它还会跑一会儿，期间<b>甚至可能还会写文件</b>。
     *
     * <p>这是刻意接受的：硬杀一个正在写文件的线程，留下的半截文件比多跑一会儿更糟。
     * 保证的是<b>调度这边不再等它</b>——下游已经跳过，它的产出不会被任何人采信。
     *
     * @return 有没有判掉谁（判掉了就该存一次档）
     */
    private boolean sweepOverdue(Plan plan, TaskGraph graph, Map<String, Running> inFlight) {
        long now = System.nanoTime();
        List<Running> overdue = inFlight.values().stream()
                .filter(r -> now - r.deadline() >= 0)
                .toList();
        for (Running r : overdue) {
            log.warn("任务 {} 超过 {} 毫秒还没跑完，按失败处理", r.task().id(), taskTimeoutMillis);
            r.future().cancel(true);
            inFlight.remove(r.task().id());
            settleOne(plan, graph, r.task(), TaskOutcome.failed(
                    "这一步超时了（上限 " + taskTimeoutMillis + " 毫秒），没能在时限内收口"));
        }
        return !overdue.isEmpty();
    }

    /**
     * 在工作线程上跑一个任务。<b>只跑，不动状态</b>——状态迁移是调度线程的事。
     *
     * <p>执行体是外部实现，可能没兜住异常。绝不能让它把整个计划打穿——
     * 和 ToolRegistry 对工具的态度一样：翻译成一次失败，接着调度别的腿。
     */
    private TaskOutcome runSafely(Task task, List<Task> upstream) {
        try {
            return runner.run(task, upstream);
        } catch (RuntimeException e) {
            log.error("任务 {} 的执行体抛出异常", task.id(), e);
            return TaskOutcome.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 取一个已经跑完的结果。<b>返回 null 表示这个结果不用管了</b>——
     * 它是被超时扫描掐掉的那个，任务在那边已经了结过，再settle一次会撞状态机。
     */
    private static Finished resolve(Future<Finished> ready) {
        try {
            return ready.get();
        } catch (CancellationException e) {
            return null;                    // 超时扫描掐的，那边已经判过了
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            // runSafely 把 RuntimeException 都翻译掉了，能到这儿的只有 Error 级的意外
            throw new IllegalStateException("任务执行线程出了兜不住的意外", e.getCause());
        }
    }

    /** 一个任务的了结现场：谁、跑成什么样。 */
    private record Finished(Task task, TaskOutcome outcome) {
    }

    /** 池子里正跑着的一个任务：是谁、怎么掐它、什么时候算超时。 */
    private record Running(Task task, Future<Finished> future, long deadline) {
    }

    /**
     * 给整个计划盖棺定论。
     *
     * <p>顺序有讲究：<b>取消优先于失败</b>。用户叫停时，没跑的任务全被标成跳过，
     * 单看任务状态和「跑砸了」分不出来——但这两件事对用户的意义完全不同，
     * 不能因为「跳过数 > 0」就报告说计划失败了。
     */
    private PlanResult settle(Plan plan) {
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        for (Task task : plan.graph().tasks()) {
            switch (task.status()) {
                case SUCCEEDED -> succeeded++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                default -> log.error("任务 {} 跑完了还停在 {}，调度有漏", task.id(), task.status());
            }
        }

        if (cancelled) {
            plan.cancel();
        } else if (plan.isRecovered()) {
            // 有任务红过，但补救任务把活全干完了。这跟「跑砸了」是两回事，
            // 报成 FAILED 会让用户以为目标没达成，而其实达成了——只是绕了个弯
            plan.complete();
        } else if (failed > 0 || skipped > 0) {
            plan.fail();
        } else {
            plan.complete();
        }
        return new PlanResult(plan.status(), succeeded, failed, skipped, plan.replans());
    }

    /**
     * 存档永远不该把计划带走。
     *
     * <p>回调是外部实现（要写盘、可能碰到只读文件系统、磁盘满、权限不对）。
     * 为了存不下这件事让一份正跑着的计划挂掉，是本末倒置——存档的意义本来就是
     * 「万一断了还能接上」，它自己成了断的原因就荒唐了。
     */
    private void checkpoint(Plan plan) {
        try {
            onCheckpoint.accept(plan);
        } catch (RuntimeException e) {
            log.warn("存档回调抛异常，忽略: {}", e.toString());
        }
    }

    /** 把一个跑完的任务了结掉。只在调度线程上调——它会动任务状态、动图、动计划。 */
    private void settleOne(Plan plan, TaskGraph graph, Task task, TaskOutcome outcome) {
        if (!outcome.ok()) {
            failAndPropagate(plan, graph, task, outcome.output());
            return;
        }

        // 子任务要在 succeed() 之前落图：万一这批任务成环或撞 id，
        // 此刻任务还是 RUNNING，可以干净地转成失败；succeed() 之后就没有回头路了。
        if (!outcome.subTasks().isEmpty()) {
            try {
                graph.addSubTasks(outcome.subTasks());
            } catch (IllegalArgumentException e) {
                log.warn("任务 {} 拆出的子任务不合法: {}", task.id(), e.getMessage());
                failAndPropagate(plan, graph, task, "子任务不合法: " + e.getMessage());
                return;
            }
            onExpanded.accept(task, outcome.subTasks());
        }
        task.succeed(outcome.output());
        onFinished.accept(task);
    }

    private void failAndPropagate(Plan plan, TaskGraph graph, Task task, String error) {
        task.fail(error);
        onFinished.accept(task);

        List<Task> skipped = graph.propagateFailure(task.id());
        if (!skipped.isEmpty()) {
            log.info("任务 {} 失败，连带跳过 {} 个下游任务", task.id(), skipped.size());
        }
        skipped.forEach(onFinished);

        // 传播完再重规划，顺序有讲究：模型要看到「哪些活被连累了」才知道补救要覆盖什么
        tryReplan(plan, graph, task, error);
    }

    /**
     * 一步倒了，试着补救一次。
     *
     * <p><b>这个方法里没有一条路会让计划跑不下去。</b>重规划本身是锦上添花——
     * 模型不肯给、给的图不合法、调用直接挂了，统统只是「没救回来」，
     * 照原样收场就是了。为了补救的失败而让整个计划炸掉，纯属得不偿失。
     *
     * <p>取消时不补救：用户已经说不要了，这会儿再去发一次模型调用只是在烧钱。
     */
    private void tryReplan(Plan plan, TaskGraph graph, Task failed, String error) {
        if (replanner == null || cancelled || !plan.canReplan()) {
            return;
        }

        List<Task> recovery;
        try {
            recovery = replanner.replan(plan, failed, error);
        } catch (RuntimeException e) {
            log.info("任务 {} 失败后重规划没成: {}", failed.id(), e.getMessage());
            return;
        }
        if (recovery == null || recovery.isEmpty()) {
            // 模型明说这事儿做不成。这是个有用的答案，别当成错误
            log.info("任务 {} 失败后，重规划认为补不了", failed.id());
            return;
        }

        try {
            graph.addTasks(recovery);
        } catch (IllegalArgumentException e) {
            log.warn("补救任务不合法，照原样收场: {}", e.getMessage());
            return;
        }

        plan.recordReplan(recovery);
        log.info("任务 {} 失败后补出 {} 个救场任务", failed.id(), recovery.size());
        onReplanned.accept(failed, recovery);
    }

    /** 依赖的任务，顺序按 dependencies 写的来，下游 prompt 里的上下文顺序才稳定。 */
    private static List<Task> upstreamOf(TaskGraph graph, Task task) {
        List<Task> upstream = new ArrayList<>(task.dependencies().size());
        for (String id : task.dependencies()) {
            upstream.add(graph.get(id));
        }
        return upstream;
    }

    private void skipAllPending(TaskGraph graph, String reason) {
        for (Task task : graph.tasks()) {
            if (task.status() == TaskStatus.PENDING) {
                task.skip(reason);
                onFinished.accept(task);
            }
        }
    }

    private static long pendingCount(TaskGraph graph) {
        return graph.tasks().stream().filter(t -> !t.isTerminal()).count();
    }
}
