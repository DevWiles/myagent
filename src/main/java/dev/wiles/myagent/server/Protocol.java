package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.plan.AgentRole;
import dev.wiles.myagent.plan.AgentRoles;
import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.PlanResult;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.team.TeamResult;

import java.util.ArrayList;
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
                    "session", "sessions", "compact", "memory", "roles", "team",
                    "events_resume", "user_echo", "undo", "rewind");

    /**
     * 角色名册，进首帧。
     *
     * <p><b>为什么服务端把名册整份发过去，而不是让界面自己写一张表</b>：
     * 和 {@code layers} 由服务端算好是同一个理由——同一份事实在两种语言里各写一遍，
     * 迟早有一边过期。这里过期的症状还特别隐蔽：后端加了个角色，
     * 界面不认得它的 id，于是那一步显示成一个空标签，而其它一切正常。
     */
    public static ArrayNode encodeRoles() {
        ArrayNode list = MAPPER.createArrayNode();
        for (AgentRole role : AgentRoles.all()) {
            ObjectNode item = list.addObject();
            item.put("id", role.id());
            item.put("title", role.title());
            item.put("summary", role.summary());
            item.put("type", role.taskType().name());
            item.put("mutates", role.mutates());
            ArrayNode tools = item.putArray("tools");
            role.tools().forEach(tools::add);
        }
        return list;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Protocol() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 用户说的那句话，广播给所有界面（协议 3.15）。
     *
     * <p>存在的理由：用户消息原来<b>从没进过协议</b>——每个界面自己把「我刚说的」
     * 写进转录再发命令。一块屏时看不出问题，两块屏时的症状是
     * 「手机上说的话，终端里只会凭空冒出一段回答」。
     *
     * <p>{@code cid} 是发起方的连接 id：它本地已经即时回显过了，收到自己这条要跳过。
     */
    public static ObjectNode encodeUserMessage(String text, String mode, String cid) {
        ObjectNode node = event("user_message");
        node.put("text", text);
        node.put("mode", mode);
        node.put("cid", cid == null ? "" : cid);
        return node;
    }

    public static ObjectNode event(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", type);
        return node;
    }

    /**
     * 把一个 agent 事件翻成线上报文，标上这是哪个任务的动静。
     *
     * <p>{@code taskId} 是计划/流水线里发这个事件的任务（工序）号，主对话传 null。
     * 并行的任务各自在发事件，不标出处的话，界面上「正在读 pom.xml」和
     * 「正在读 README」会被当成同一条线上的先后两步。老界面不认识这个字段，
     * 忽略即可（见 docs/protocol.md 第 2 节），所以不升协议版本。
     */
    public static ObjectNode encode(AgentEvent event, String taskId) {
        ObjectNode node = encode(event);
        if (taskId != null && !taskId.isBlank()) {
            node.put("task", taskId);
        }
        return node;
    }

    /**
     * 把一个 agent 事件翻成线上报文。
     *
     * <p>工具事件的 {@code id} 直接取事件自带的流水号（{@link AgentEvent.ToolStarted#id}），
     * 前端靠它把「开始」和「结束」配成同一行来原地更新。以前这个号是服务端看着
     * 事件顺序补的——那套编号法建立在「工具严格串行」上；并行安全的调用凑批同时执行之后，
     * 「开始」和「结束」在事件流里不再相邻，只有源头发的号才配得对。
     */
    public static ObjectNode encode(AgentEvent event) {
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
            n.put("id", e.id());
            n.put("tool", e.tool());
            n.put("arguments", e.arguments());
            return n;
        }
        if (event instanceof AgentEvent.ToolFinished e) {
            ObjectNode n = event("tool_finished");
            n.put("id", e.id());
            n.put("tool", e.tool());
            n.put("ok", e.ok());
            n.put("output", e.output());
            // 空的就不发。每条工具结果都挂一个空字段，只是让报文变长、让界面多判一次
            if (!e.diff().isEmpty()) {
                n.put("diff", e.diff());
            }
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
        n.put("role", task.role().id());
        n.put("roleTitle", task.role().title());
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

    // ── 流水线 ────────────────────────────────────────────────────

    /**
     * 流水线开场。<b>这一帧要在第一次子循环开跑之前发出去</b>——每道工序都是一次
     * 完整的子循环，用户得先看见这笔钱怎么花。此刻座位表上只有规划那一道工序——
     * worker 有几个、派谁，要等规划跑完才知道，届时走 {@link #encodeStagesAdded}。
     *
     * <p>帧名和字段沿用圆桌时期的 {@code seats}——换名就是破坏性改动，语义变了名字不动
     * （见 docs/protocol.md 3.14）。id 直接取任务自己的（plan/t1/review…），
     * 后续 {@code team_seat_started} 报的必须是同一批 id，界面按它对行。
     */
    public static ObjectNode encodeTeamStarted(String goal, String reason, List<Task> stages) {
        ObjectNode n = event("team_started");
        n.put("goal", goal);
        n.put("reason", reason);
        encodeSeats(n.putArray("seats"), stages);
        return n;
    }

    /**
     * 座位表长大了：规划拆出的 workers、每轮的验收、返工/重拟长出的新工序。
     * 界面按执行顺序追加到已有座位表末尾即可。
     */
    public static ObjectNode encodeStagesAdded(List<Task> stages) {
        ObjectNode n = event("team_seats_added");
        encodeSeats(n.putArray("seats"), stages);
        return n;
    }

    private static void encodeSeats(ArrayNode seats, List<Task> stages) {
        for (Task stage : stages) {
            seats.addObject()
                    .put("id", stage.id())
                    .put("role", stage.role().id())
                    .put("roleTitle", stage.role().title())
                    .put("summary", stage.role().summary())
                    .put("status", "PENDING");
        }
    }

    public static ObjectNode encodeStageStarted(Task stage) {
        return event("team_seat_started")
                .put("seatId", stage.id())
                .put("role", stage.role().id())
                .put("roleTitle", stage.role().title());
    }

    /**
     * 一道工序落定。<b>倒了的、被跳过的也走这条</b>——界面上那一道要是没有终态，
     * 它会一直转圈。
     *
     * <p>{@code opinion} 是这道工序的正文。界面其实已经通过流式文本收到过一遍了，
     * 但那是<b>混在一起的流</b>；这里给的是归到这道工序名下的定稿，
     * 界面要折叠、要回看，靠的都是它。
     */
    public static ObjectNode encodeStageFinished(Task stage) {
        return event("team_seat_finished")
                .put("seatId", stage.id())
                .put("role", stage.role().id())
                .put("status", stage.status().name())
                .put("durationMs", stage.durationMillis())
                .put("opinion", stage.result())
                .put("error", stage.error());
    }

    /**
     * 收工。
     *
     * <p>{@code status} 里 {@code PARTIAL} 那一档界面<b>必须画出来</b>，而且要分清两种：
     * {@code verdict} 非空是验收打回（问题清单就在里面），为空是验收没跑成——
     * 两种都意味着工作区里有一批没人担保的改动。
     */
    public static ObjectNode encodeTeamFinished(TeamResult result) {
        ObjectNode n = event("team_finished");
        n.put("status", result.status().name());
        n.put("spoke", result.spoke());
        n.put("silent", result.silent());
        n.put("verdict", result.verdict());
        // 新增字段不升协议版本：老界面照约定忽略不认识的字段
        n.put("rounds", result.rounds());
        return n;
    }

    private static ObjectNode encodeTask(Task task) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("id", task.id());
        item.put("type", task.type().name());
        // 派给谁。和 type 一起发而不是取代它：两者回答的不是同一个问题——
        // type 是「这一步会对世界做什么」（界面上色、将来排并发靠它），
        // role 是「派谁去、用什么姿势」。而且老界面只认 type，
        // 换掉它就是一次破坏性改动，得升协议版本；新增一个字段不用
        item.put("role", task.role().id());
        item.put("roleTitle", task.role().title());
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

    /** 取字符串数组。缺了、类型不对、里面混了别的，一律当没有——前端传什么都不该让服务端崩。 */
    public static List<String> strings(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(v.size());
        for (JsonNode item : v) {
            String text = item.asText("").strip();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    public static int num(JsonNode node, String field, int fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asInt(fallback);
    }
}
