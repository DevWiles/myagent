package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.plan.AgentRole;
import dev.wiles.myagent.plan.AgentRoles;

import java.util.ArrayList;
import java.util.List;
import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.context.Interruption;

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
 *                     mode / plan_confirm_request / plan_confirm_resolved
 *   客户端 → 服务端   prompt / cancel / approval_reply / reset / ping
 *                     set_mode / plan_confirm_reply</pre>
 *
 * <p>plan 模式（照 Claude Code，是一种权限模式，不是另一条执行链路）的一轮长这样:
 * <pre>
 *   客户端 {"t":"set_mode","mode":"plan"}              (或者模型调 enter_plan_mode、用户同意)
 *   服务端 {"t":"mode","mode":"plan"}
 *   客户端 {"t":"prompt","text":"给 Task 加重试"}
 *   服务端 ...只读调研，照常发 thinking / tool_started 等事件...
 *          {"t":"plan_confirm_request","id":1,"kind":"exit","plan":"## 计划
..."}
 *   客户端 {"t":"plan_confirm_reply","id":1,"approved":true}
 *   服务端 {"t":"plan_confirm_resolved","id":1}
 *          {"t":"mode","mode":"normal"}
 *          ...同一个 agent 接着照计划改文件、跑测试...
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
            List.of("plan_mode", "skills",
                    "session", "sessions", "compact", "memory", "roles",
                    "events_resume", "user_echo", "undo", "rewind", "images");

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
     * <p>{@code taskId} 是计划里发这个事件的任务号，主对话传 null。
     * 并行的任务各自在发事件，不标出处的话，界面上「正在读 pom.xml」和
     * 「正在读 README」会被当成同一条线上的先后两步。老界面不认识这个字段，
     * 忽略即可（见 docs/protocol.md 第 2 节），所以不升协议版本。
     */
    public static ObjectNode encode(AgentEvent event, String taskId) {
        ObjectNode node = encode(event);
        if (taskId != null && !taskId.isBlank()) {
            node.put("task", taskId);
            // 子任务说「继续」没有对象：计划有自己的续跑（3.7），这里的断点只属于主对话
            node.remove("interrupted");
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
            if (e.interrupted() != null) {
                encodeInterruption(n.putObject("interrupted"), e.interrupted());
            }
            return n;
        }
        // sealed interface 保证走不到这里，但 Java 17 的 instanceof 链没法让编译器确信
        throw new IllegalStateException("未知事件类型: " + event.getClass());
    }

    /**
     * 断点：界面拿它拼「说一句继续就能接上」那行提示。
     * {@code failed} 和 {@code ready} 用同一个形状，界面只写一份解析。
     */
    public static void encodeInterruption(ObjectNode n, Interruption cut) {
        n.put("reason", cut.reason());
        n.put("prompt", cut.userInput());
        n.put("steps", cut.steps());
        n.put("at", cut.at());
    }

    /** 接回来的对话里，一条工具输出最多带这么多字。全量还在存档里，模型那边不受影响。 */
    static final int TRANSCRIPT_OUTPUT_LIMIT = 4000;

    private static final String INTERRUPT_OPEN = "<turn_interrupted>";
    private static final String INTERRUPT_CLOSE = "</turn_interrupted>";

    /**
     * 把一段存档摊成界面能直接画的条目，{@code session_restored} 带着它，
     * 接回来的对话才能像 Claude Code 那样原样摆回屏幕上——光说「接上了 4 轮」，
     * 用户看不见自己上次说到哪。
     *
     * <p>三种条目，顺序就是对话顺序：
     * <ul>
     *   <li>{@code user}：人说的话。系统塞进去的（子 agent 通知、委派计划、验证提醒，
     *       见 {@code SubAgents.isNotice}）不算；断点后第一句前面拼着的
     *       {@code <turn_interrupted>} 说明剥掉，只留原话</li>
     *   <li>{@code assistant}：模型的文字。调工具前顺口说的那句也是它——实时界面上就是这么流出来的</li>
     *   <li>{@code tool}：一次调用和它的结果按 id 配上。结果里不存成败，按
     *       {@code ToolResult.error} 的 {@code 错误: } 前缀推；没有结果（断在半路）就不带 ok</li>
     * </ul>
     */
    public static void encodeTranscript(ArrayNode out, List<dev.wiles.myagent.llm.ChatMessage> messages) {
        java.util.Map<String, String> results = new java.util.HashMap<>();
        for (var m : messages) {
            if ("tool".equals(m.role()) && m.toolCallId() != null) {
                results.put(m.toolCallId(), m.content() == null ? "" : m.content());
            }
        }
        for (var m : messages) {
            String content = m.content() == null ? "" : m.content();
            switch (m.role()) {
                case "user" -> {
                    if (dev.wiles.myagent.agent.SubAgents.isNotice(content)) {
                        continue;
                    }
                    if (content.startsWith(INTERRUPT_OPEN)) {
                        int end = content.indexOf(INTERRUPT_CLOSE);
                        content = end < 0 ? content : content.substring(end + INTERRUPT_CLOSE.length());
                    }
                    // plan 模式规矩、hook 补的上下文、/技能 的正文：给模型看的，不是用户说的
                    content = dev.wiles.myagent.context.SystemReminder.strip(content.stripLeading());
                    if (!content.isBlank()) {
                        out.addObject().put("kind", "user").put("text", content.strip());
                    }
                }
                case "assistant" -> {
                    if (!content.isBlank()) {
                        out.addObject().put("kind", "assistant").put("text", content.strip());
                    }
                    for (var call : m.toolCallsOrEmpty()) {
                        ObjectNode tool = out.addObject().put("kind", "tool")
                                .put("tool", call.function().name())
                                .put("arguments", call.function().arguments());
                        String result = results.get(call.id());
                        if (result != null) {
                            tool.put("ok", !result.startsWith("错误: "));
                            tool.put("output", result.length() <= TRANSCRIPT_OUTPUT_LIMIT ? result
                                    : result.substring(0, TRANSCRIPT_OUTPUT_LIMIT) + "\n…（界面只显示前 "
                                            + TRANSCRIPT_OUTPUT_LIMIT + " 字，完整结果在存档里）");
                        }
                    }
                }
                default -> {
                    // system 和 tool 不单独成条：前者不该给人看，后者已经配到调用上了
                }
            }
        }
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
