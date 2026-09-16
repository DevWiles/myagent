package dev.wiles.myagent.plan;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 规划器：把用户的一句话，变成一张能跑的任务图。
 *
 * <pre>
 *   [调模型] → [抠 JSON] → [解析成 Task] → [建图校验] → Plan
 *       ↑           ↓            ↓             ↓
 *       └───────── 把错误写成人话，追加一条 user 消息，再问一次（最多 2 轮）
 * </pre>
 *
 * <p><b>回灌重试是这个类的全部价值</b>。模型输出 JSON 的翻车方式太多了：答非所问、
 * 裹在围栏里、类型编错、依赖成环。每一种都不该直接报给用户——把错误原话发回去，
 * 模型多半一次就改对了。这和工具调用失败时的处理是同一个思路（见 {@code ToolResult}）：
 * <b>失败是一段要交给模型的反馈，不是异常</b>。
 *
 * <p><b>无状态</b>：字段只有协作者，没有可变状态。重试计数、上次错在哪这些
 * 只在一次调用的生命周期内有意义，塞进字段就会在两次规划之间残留。
 */
public final class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    /**
     * 默认重试次数（总调用次数 = 这个数 + 1）。
     *
     * <p>同样的错误改两次还不对，多半是任务本身超出了模型能力，再试就是烧钱。
     */
    public static final int DEFAULT_MAX_RETRIES = 2;

    private final LlmClient client;
    private final int maxRetries;

    /** 只读模式：提示词里要说明，产出里也要再校验一遍。 */
    private boolean readOnly;

    public Planner(LlmClient client) {
        this(client, DEFAULT_MAX_RETRIES);
    }

    public Planner(LlmClient client, int maxRetries) {
        this.client = client;
        this.maxRetries = maxRetries;
    }

    /**
     * 告诉规划器当前是只读模式。
     *
     * <p>两道防线：提示词里说明（模型多半会听），产出里再校验（它没听的时候打回重写）。
     * 光靠提示词不够——规划出一个 FILE_WRITE 任务，跑到那儿才发现没有写工具，
     * 那一步必然失败，还连累整条下游。
     */
    public Planner readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /** 掐断正在进行的规划请求。 */
    public void cancel() {
        client.cancel();
    }

    /** 规划：用户的一句话 → 一张校验过的图。 */
    public Plan plan(String goal) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(PlanPrompt.system(readOnly)));
        messages.add(ChatMessage.user(PlanPrompt.user(goal)));

        return attempt(messages, "规划", reply -> {
            PlanParser.ParsedPlan parsed = PlanParser.parse(reply);
            requireRunnableTypes(parsed.tasks());
            // 建图这一步同样可能失败（id 重复、悬空依赖、成环），
            // 它的报错一样要回灌——尤其是环的路径，模型看了才知道该断哪条边
            return new Plan(parsed.goal(), TaskGraph.of(parsed.tasks()));
        });
    }

    /**
     * 子规划：{@link TaskType#PLANNING} 任务摸完情况之后，拆出下一层要做的事。
     *
     * @param findings 父任务在 ReAct 子循环里摸到的情况，原样交给模型
     */
    public List<Task> planSubTasks(Task parent, String findings) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(PlanPrompt.subPlanSystem()));
        messages.add(ChatMessage.user(PlanPrompt.subPlanUser(parent, findings)));

        return attempt(messages, "子规划", reply -> PlanParser.parseSubTasks(reply, parent));
    }

    /** 只读模式下，能跑的只有这三种类型；其余的规划出来也是白搭，当场打回。 */
    private void requireRunnableTypes(List<Task> tasks) {
        if (!readOnly) {
            return;
        }
        for (Task task : tasks) {
            TaskType type = task.type();
            if (type != TaskType.FILE_READ && type != TaskType.ANALYSIS
                    && type != TaskType.PLANNING) {
                throw new IllegalArgumentException("任务 " + task.id() + " 的类型是 " + type
                        + "，但当前是只读模式，只能用 FILE_READ / ANALYSIS / PLANNING。"
                        + "请改用这三种类型重新规划。");
            }
        }
    }

    /**
     * 问一次，解析一次；解析不过就把错误回灌，再问。
     *
     * <p>注意重试时<b>模型上一轮的回答要留在对话里</b>：只发一句「你写错了」而不给它看
     * 自己刚才写的东西，等于让它盲改。
     *
     * <p>{@code LlmException} 不在这里接——网络层的重试是
     * {@code DeepSeekClient} 的职责，这里再套一层只会把一次限流放大成三次。
     */
    private <T> T attempt(List<ChatMessage> messages, String what, Function<String, T> parse) {
        String lastError = null;

        for (int round = 0; round <= maxRetries; round++) {
            ChatResponse response = client.chat(messages, null);   // 规划不给工具
            String reply = response.text();

            try {
                return parse.apply(reply);
            } catch (IllegalArgumentException | IllegalStateException e) {
                lastError = e.getMessage();
                log.info("{}第 {} 次输出不合格: {}", what, round + 1, lastError);

                messages.add(response.message());                  // 让它看见自己刚写的
                messages.add(ChatMessage.user(
                        "你的上一份输出有问题: " + lastError + "\n请修正后重新输出完整的 JSON。"));
            }
        }
        throw new PlanningException(what + "失败，模型连续 " + (maxRetries + 1)
                + " 次都没给出可用的计划。最后一次的问题: " + lastError);
    }
}
