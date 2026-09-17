package dev.wiles.myagent.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 计划执行器：拿一张图，按依赖顺序把任务一个个推到终态。
 *
 * <pre>
 *   while (图还没跑完) {
 *       ready = 图里所有依赖已满足的任务
 *       取一个 → start() → 交给 TaskRunner → succeed() 或 fail()
 *       失败了 → 顺着 dependents 把下游全标 SKIPPED
 *       是 PLANNING 且拆出了子任务 → 塞进图里，下一轮就能调度到
 *   }
 * </pre>
 *
 * <p><b>为什么每轮都重新问一次 ready()，而不是一开始就把层算好按层跑</b>：
 * 因为 PLANNING 任务会让图在执行过程中长大，预先算好的层次会立刻过期。
 * 每轮重算的代价是扫一遍几十个任务，可以忽略。
 *
 * <p>目前<b>串行</b>执行：同一层的任务也是一个个来。DAG 此刻只用来定顺序，
 * 并发是后面的事——那之前得先解决两个 FILE_WRITE 任务改同一个文件的隐式冲突。
 *
 * <p>这个类有一条硬性要求：<b>跑完之后图里不能留下非终态的任务</b>。
 * 失败、异常、取消，每条路都得给所有任务一个交代，否则界面上会挂着一个永远转圈的任务。
 */
public final class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final TaskRunner runner;

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

    /** 叫停。当前正在跑的那个任务停不下来（那是执行体自己的事），但不会再开新的。 */
    public void cancel() {
        cancelled = true;
    }

    public PlanResult execute(Plan plan) {
        cancelled = false;   // 上一轮的取消标志不能带到这一轮
        plan.start();
        TaskGraph graph = plan.graph();

        while (!graph.isDone()) {
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

            List<Task> ready = graph.ready();
            if (ready.isEmpty()) {
                // 图没跑完却没人能跑——只可能是失败扩散漏了谁。
                // 与其在这儿静默死循环，不如把剩下的了结掉并留个日志。
                log.error("调度停滞：图没跑完却没有就绪任务，剩余 {} 个", pendingCount(graph));
                skipAllPending(graph, "调度停滞，没有可执行的上游");
                break;
            }

            runOne(plan, graph, ready.get(0));
            checkpoint(plan);
        }

        return settle(plan);
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

    private void runOne(Plan plan, TaskGraph graph, Task task) {
        task.start();
        onStarted.accept(task);
        TaskOutcome outcome;
        try {
            outcome = runner.run(task, upstreamOf(graph, task));
        } catch (RuntimeException e) {
            // 执行体是外部实现，可能没兜住异常。绝不能让它把整个计划打穿——
            // 和 ToolRegistry 对工具的态度一样：翻译成一次失败，接着调度别的腿。
            log.error("任务 {} 的执行体抛出异常", task.id(), e);
            outcome = TaskOutcome.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

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
