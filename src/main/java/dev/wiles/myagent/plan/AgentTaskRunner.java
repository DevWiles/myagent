package dev.wiles.myagent.plan;

import dev.wiles.myagent.agent.Agent;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

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
 * <p>另一件它顺手做掉的事：按 {@link TaskType} 收窄工具菜单（见 {@link TaskTools}）。
 */
public final class AgentTaskRunner implements TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskRunner.class);

    /**
     * 单个任务最多跑几轮 ReAct。
     *
     * <p>比整段对话的上限（{@link Agent#DEFAULT_MAX_STEPS}）小得多，因为这里是<b>子任务</b>：
     * 一个拆好的步骤要是 12 轮还收不了口，多半是计划拆得太粗，
     * 让它撞上限收敛，比放任它跑 25 轮烧掉额度强。
     */
    public static final int DEFAULT_MAX_STEPS = 12;

    private final LlmClient client;
    private final ToolRegistry allTools;
    private final Path workspaceRoot;
    private final int maxSteps;

    private BiConsumer<Task, AgentEvent> listener = (task, event) -> {
    };

    /** 给 PLANNING 任务拆子任务用。没配就退化成一次普通的只读调研。 */
    private Planner planner;

    /** 当前正在跑的子循环，取消时要掐它。volatile：置位的是监听 Ctrl-C 的那个线程。 */
    private volatile Agent current;

    public AgentTaskRunner(LlmClient client, ToolRegistry allTools, Path workspaceRoot) {
        this(client, allTools, workspaceRoot, DEFAULT_MAX_STEPS);
    }

    public AgentTaskRunner(LlmClient client, ToolRegistry allTools, Path workspaceRoot,
                           int maxSteps) {
        this.client = client;
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

    /** 掐断当前子循环（连带它正在进行的 HTTP 请求）。 */
    public void cancel() {
        Agent agent = current;
        if (agent != null) {
            agent.cancel();
        }
    }

    @Override
    public TaskOutcome run(Task task, List<Task> upstream) {
        Agent agent = new Agent(
                client,
                TaskTools.forType(allTools, task.type()),
                TaskPrompt.system(task, workspaceRoot),
                maxSteps);

        // 子循环失败（模型调用挂了、被取消、撞上最大步数）只通过事件暴露，
        // run() 的返回值是一段人话，靠字符串去猜成败太脆了
        String[] failure = new String[1];
        agent.onEvent(event -> {
            if (event instanceof AgentEvent.Failed f) {
                failure[0] = f.reason();
            }
            listener.accept(task, event);
        });

        current = agent;
        String text;
        try {
            text = agent.run(TaskPrompt.user(task, upstream));
        } finally {
            current = null;
        }

        if (failure[0] != null) {
            log.info("任务 {} 的子循环没能收口: {}", task.id(), failure[0]);
            return TaskOutcome.failed(compose(failure[0], text));
        }
        if (task.type() == TaskType.VERIFICATION && !passed(text)) {
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
