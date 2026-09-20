package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.PlanResult;
import dev.wiles.myagent.plan.Task;

import java.util.List;
import dev.wiles.myagent.agent.AgentEvent;

/**
 * 前后端之间的线上协议：一行一个 JSON 对象，用字段 {@code t} 区分类型。
 *
 * <p>为什么把序列化单独拎出来：{@link AgentEvent} 是后端的领域模型，它不该
 * 知道前端长什么样，更不该为了传输而被塞进 Jackson 注解。这里做一次单向翻译，
 * 后端那边一个字都不用改。
 *
 * <p>协议是「事件流 + 命令」两个方向：
 * <pre>
 *   服务端 → 客户端   ready / thinking / text_delta / thought / tool_started
 *                     tool_finished / compacted / done / failed
 *                     approval_request / busy / error / pong
 *                     plan_created / plan_confirm_request / plan_task_started
 *                     plan_task_finished / plan_tasks_added / plan_revised / plan_finished
 *   客户端 → 服务端   prompt / cancel / approval_reply / reset / ping
 *                     plan / plan_confirm_reply</pre>
 *
 * <p>「先规划后执行」的一轮长这样:
 * <pre>
 *   客户端 {"t":"plan","goal":"给 Task 加重试"}
 *   服务端 {"t":"busy","busy":true}
 *          {"t":"plan_created","goal":...,"tasks":[...],"layers":[["t1","t2"],["t3"]]}
 *          {"t":"plan_confirm_request","id":1}
 *   客户端 {"t":"plan_confirm_reply","id":1,"approved":true}
 *   服务端 {"t":"plan_task_started","taskId":"t1",...}
 *          ...执行期间照常发 thinking / text_delta / tool_started 等事件...
 *          {"t":"plan_task_finished","taskId":"t1","status":"SUCCEEDED",...}
 *          {"t":"plan_tasks_added",...}        (PLANNING 任务拆出子任务时才有)
 *          {"t":"plan_revised",...}            (某一步失败后补出救场任务时才有)
 *          {"t":"plan_finished","status":"COMPLETED","succeeded":5,...}
 *          {"t":"busy","busy":false}
 * </pre>
 */
public final class Protocol {

    /**
     * 协议版本。两边对不上时应当直接拒绝连接，而不是带着半懂不懂往下跑。
     *
     * <p>什么时候升：<b>只有破坏性改动才升</b>——删字段、改字段含义、改一条消息的时序。
     * 新增消息类型、新增字段都不升，前端本来就必须忽略不认识的 {@code t} 和字段。
     * 「先规划后执行」那一批消息就是纯新增，所以还是 1。
     */
    public static final int VERSION = 1;

    /**
     * 服务端支持的可选能力，随 ready 帧发给界面。
     *
     * <p>为什么要有它而不是靠版本号：新增能力如果靠升版本来宣告，老界面会因为版本对不上
     * 直接拒连——明明它只是用不了新功能，却连旧功能也没得用了。
     * 能力清单让界面自己决定「这个按钮画不画」，两边可以各自独立演进。
     */
    public static final List<String> FEATURES =
            List.of("plan", "replan", "resume", "resume_plan", "auto_mode",
                    "session", "sessions", "compact", "memory");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Protocol() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode event(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", type);
        return node;
    }

    /**
     * 把一个 agent 事件翻成线上报文。
     *
     * <p>{@code toolSeq} 是服务端自己编的流水号：{@link AgentEvent.ToolStarted} 和
     * {@link AgentEvent.ToolFinished} 本身不带 id，而前端要靠它把「开始」和「结束」
     * 配成同一行来原地更新。Agent 的工具调用是严格串行的（见 Agent.run 里的 for 循环），
     * 所以同一个流水号一定对应同一次调用。
     */
    public static ObjectNode encode(AgentEvent event, int toolSeq) {
        if (event instanceof AgentEvent.Thinking e) {
            ObjectNode n = event("thinking");
            n.put("step", e.step());
            return n;
        }
        if (event instanceof AgentEvent.TextDelta e) {
            ObjectNode n = event("text_delta");
            n.put("text", e.text());
            return n;
        }
        if (event instanceof AgentEvent.Thought e) {
            ObjectNode n = event("thought");
            n.put("text", e.text());
            return n;
        }
        if (event instanceof AgentEvent.ToolStarted e) {
            ObjectNode n = event("tool_started");
            n.put("id", toolSeq);
            n.put("tool", e.tool());
            n.put("arguments", e.arguments());
            return n;
        }
        if (event instanceof AgentEvent.ToolFinished e) {
            ObjectNode n = event("tool_finished");
            n.put("id", toolSeq);
            n.put("tool", e.tool());
            n.put("ok", e.ok());
            n.put("output", e.output());
            return n;
        }
        if (event instanceof AgentEvent.CompactProgress e) {
            ObjectNode n = event("compacting");
            n.put("phase", e.phase());
            n.put("done", e.done());
            n.put("total", e.total());
            n.put("percent", e.percent());
            return n;
        }
        if (event instanceof AgentEvent.Compacted e) {
            ObjectNode n = event("compacted");
            n.put("tokensBefore", e.tokensBefore());
            n.put("tokensAfter", e.tokensAfter());
            n.put("summarized", e.summarized());
            return n;
        }
        if (event instanceof AgentEvent.Done e) {
            ObjectNode n = event("done");
            n.put("text", e.text());
            n.put("steps", e.steps());
            return n;
        }
        if (event instanceof AgentEvent.Failed e) {
            ObjectNode n = event("failed");
            n.put("reason", e.reason());
            return n;
        }
        // sealed interface 保证走不到这里，但 Java 17 的 instanceof 链没法让编译器确信
        throw new IllegalStateException("未知事件类型: " + event.getClass());
    }

    /**
     * 一份新鲜出炉的计划。
     *
     * <p>{@code layers} 是拓扑分层的结果，直接把「哪几步互不依赖」算好给前端——
     * 让前端自己按 dependsOn 推一遍，等于把同一套图算法在两种语言里各实现一次，
     * 迟早会有一边算错。
     */
    public static ObjectNode encodePlan(Plan plan) {
        ObjectNode n = event("plan_created");
        n.put("goal", plan.goal());
        ArrayNode tasks = n.putArray("tasks");
        for (Task task : plan.graph().tasks()) {
            tasks.add(encodeTask(task));
        }
        ArrayNode layers = n.putArray("layers");
        for (List<Task> layer : plan.graph().layers()) {
            ArrayNode ids = layers.addArray();
            layer.forEach(task -> ids.add(task.id()));
        }
        return n;
    }

    /** PLANNING 任务拆出了新任务，图长大了。前端要能就地插进去，而不是重画整张图。 */
    public static ObjectNode encodeAddedTasks(String parentId, List<Task> added) {
        ObjectNode n = event("plan_tasks_added");
        n.put("parentId", parentId);
        ArrayNode tasks = n.putArray("tasks");
        added.forEach(task -> tasks.add(encodeTask(task)));
        return n;
    }

    public static ObjectNode encodeTaskStarted(Task task) {
        ObjectNode n = event("plan_task_started");
        n.put("taskId", task.id());
        n.put("taskType", task.type().name());
        n.put("description", task.description());
        return n;
    }

    public static ObjectNode encodeTaskFinished(Task task) {
        ObjectNode n = event("plan_task_finished");
        n.put("taskId", task.id());
        n.put("status", task.status().name());
        n.put("durationMs", task.durationMillis());
        n.put("result", task.result());
        n.put("error", task.error());
        return n;
    }

    public static ObjectNode encodePlanFinished(Plan plan, PlanResult result) {
        ObjectNode n = event("plan_finished");
        n.put("status", plan.status().name());
        n.put("succeeded", result.succeeded());
        n.put("failed", result.failed());
        n.put("skipped", result.skipped());
        n.put("replans", result.replans());
        n.put("durationMs", plan.durationMillis());
        return n;
    }

    /**
     * 这句话被自动改走规划了。
     *
     * <p>不发这条的话，界面会看到「我发的是 prompt，回来的却是 plan_created」——
     * 用户更是莫名其妙。带上理由，他才知道系统凭什么这么判，也才有机会说「不用，直接做」。
     */
    public static ObjectNode encodeModeSwitch(String reason) {
        ObjectNode n = event("mode_switched");
        n.put("mode", "plan");
        n.put("reason", reason);
        return n;
    }

    /**
     * 某一步失败之后补出了一批救场任务。
     *
     * <p>界面不知道这件事的话，用户看到的就是「某一步红了」，然后屏幕上莫名其妙
     * 冒出几个计划里根本没有的任务在跑。
     *
     * <p>和 {@code plan_tasks_added} 分成两条消息而不是共用一条：前者是计划<b>按原样长大</b>
     * （PLANNING 任务拆出下一层），后者是<b>原路走不通，换了条路</b>。
     * 对用户的意义完全不同，界面该画得不一样。
     */
    public static ObjectNode encodeReplanned(Task failed, List<Task> recovery) {
        ObjectNode n = event("plan_revised");
        n.put("failedTaskId", failed.id());
        ArrayNode tasks = n.putArray("tasks");
        recovery.forEach(task -> tasks.add(encodeTask(task)));
        return n;
    }

    private static ObjectNode encodeTask(Task task) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("id", task.id());
        item.put("type", task.type().name());
        item.put("description", task.description());
        // 新增字段不升协议版本：老界面照约定忽略不认识的字段，照跑不误
        item.put("acceptance", task.acceptance());
        item.put("status", task.status().name());
        item.put("parentId", task.parentId());
        ArrayNode deps = item.putArray("dependsOn");
        task.dependencies().forEach(deps::add);
        return item;
    }

    /** 取布尔字段。前端漏传或者传了别的类型，都退回默认值，不让服务端崩。 */
    public static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asBoolean(fallback);
    }

    /** 取字符串字段，缺了就返回默认值——前端传什么过来都不该让服务端崩。 */
    public static String str(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }

    public static int num(JsonNode node, String field, int fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asInt(fallback);
    }
}
