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

            runOne(graph, ready.get(0));
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
        } else if (failed > 0 || skipped > 0) {
            plan.fail();
        } else {
            plan.complete();
        }
        return new PlanResult(plan.status(), succeeded, failed, skipped);
    }

    private void runOne(TaskGraph graph, Task task) {
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
            failAndPropagate(graph, task, outcome.output());
            return;
        }

        // 子任务要在 succeed() 之前落图：万一这批任务成环或撞 id，
        // 此刻任务还是 RUNNING，可以干净地转成失败；succeed() 之后就没有回头路了。
        if (!outcome.subTasks().isEmpty()) {
            try {
                graph.addSubTasks(outcome.subTasks());
            } catch (IllegalArgumentException e) {
                log.warn("任务 {} 拆出的子任务不合法: {}", task.id(), e.getMessage());
                failAndPropagate(graph, task, "子任务不合法: " + e.getMessage());
                return;
            }
            onExpanded.accept(task, outcome.subTasks());
        }
        task.succeed(outcome.output());
        onFinished.accept(task);
    }

    private void failAndPropagate(TaskGraph graph, Task task, String error) {
        task.fail(error);
        onFinished.accept(task);

        List<Task> skipped = graph.propagateFailure(task.id());
        if (!skipped.isEmpty()) {
            log.info("任务 {} 失败，连带跳过 {} 个下游任务", task.id(), skipped.size());
        }
        skipped.forEach(onFinished);
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
