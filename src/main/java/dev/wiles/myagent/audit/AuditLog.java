package dev.wiles.myagent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.plan.Plan;
import dev.wiles.myagent.plan.PlanResult;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 审计轨迹：一行一条 JSON，只追加不修改。
 *
 * <p>和普通日志的分工很清楚：<b>slf4j 那套是给开发者排查程序问题的，这份是给用户
 * 复盘「agent 到底对我的代码做了什么」的</b>。后者要求完全不同——
 * 必须落盘、必须可机读（用 jq 过滤）、必须包含工具的实际参数和输出，
 * 而这些东西放进控制台日志只会把界面冲花。
 *
 * <p>三条硬性要求：
 * <ul>
 *   <li><b>永不抛异常</b>。审计写不进去是小事，任务跑一半崩掉是大事——
 *       和 {@code ToolRegistry} 对工具的态度一样，把失败咽下去，只在日志里提一次。</li>
 *   <li><b>同一次运行共享一个 runId</b>。串不起来的日志等于没有，并发之后更是。</li>
 *   <li><b>超长内容截断</b>。一次 {@code cat} 大文件就能写进来几百 KB，
 *       不截断的话审计文件会比被审计的代码还大。</li>
 * </ul>
 */
public final class AuditLog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单个字段最多留这么多字符。够看清做了什么，又不至于把文件撑爆。 */
    public static final int MAX_FIELD_LENGTH = 4000;

    private final String runId;
    /**
     * 日志落在哪。**打不开时也保留这个值**——界面要拿它告诉用户「记录在哪儿」，
     * 而「本该写在这儿但没写成」也是有用的信息，比返回 null 让人猜强。
     */
    private final Path file;
    private BufferedWriter writer;
    /** 写失败只报一次，不然一次坏盘能刷满屏幕。 */
    private boolean reportedFailure;

    /** 默认位置：{@code ~/.myagent/logs/run-<时间>.jsonl}。 */
    public static AuditLog forNewRun() {
        String stamp = Instant.now().toString().replace(':', '-');
        return new AuditLog(Path.of(System.getProperty("user.home"), ".myagent", "logs",
                "run-" + stamp + ".jsonl"));
    }

    public AuditLog(Path file) {
        this.runId = Long.toHexString(System.nanoTime());
        this.file = file;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // 打不开就整份作废，后面每次写都是空操作。不让它把 agent 带下水
            log.warn("审计日志打不开，本次运行不留审计轨迹: {}", e.getMessage());
            this.writer = null;
        }
    }

    public String runId() {
        return runId;
    }

    /** 日志文件的位置。界面退出时要把它显示给用户，好让人知道去哪儿查这趟做了什么。 */
    public Path file() {
        return file;
    }

    /** 规划产出了一张图。整张图都存下来——事后复盘得知道当时是怎么拆的。 */
    public void planCreated(Plan plan) {
        ObjectNode node = event("plan_created");
        node.put("goal", plan.goal());
        ArrayNode tasks = node.putArray("tasks");
        for (Task task : plan.graph().tasks()) {
            ObjectNode item = tasks.addObject();
            item.put("id", task.id());
            item.put("type", task.type().name());
            // 派了谁要记：角色决定了这一步手上有哪些工具，
            // 事后查「它怎么会改到这个文件」时，第一个要看的就是这里
            item.put("role", task.role().id());
            item.put("description", clip(task.description()));
            ArrayNode deps = item.putArray("dependsOn");
            task.dependencies().forEach(deps::add);
            if (task.parentId() != null) {
                item.put("parentId", task.parentId());
            }
        }
        write(node);
    }

    public void taskStarted(Task task) {
        ObjectNode node = event("task_started");
        node.put("taskId", task.id());
        node.put("taskType", task.type().name());
        node.put("role", task.role().id());
        node.put("description", clip(task.description()));
        write(node);
    }

    public void taskFinished(Task task) {
        ObjectNode node = event("task_finished");
        node.put("taskId", task.id());
        node.put("status", task.status().name());
        node.put("durationMs", task.durationMillis());
        if (task.result() != null) {
            node.put("result", clip(task.result()));
        }
        if (task.error() != null) {
            node.put("error", clip(task.error()));
        }
        write(node);
    }

    public void planFinished(Plan plan, PlanResult result) {
        ObjectNode node = event("plan_finished");
        node.put("status", plan.status().name());
        node.put("succeeded", result.succeeded());
        node.put("failed", result.failed());
        node.put("skipped", result.skipped());
        node.put("durationMs", plan.durationMillis());
        write(node);
    }

    /**
     * 一次工具调用。<b>参数和输出都要留底</b>——
     * 「它到底改了哪个文件、跑了什么命令」全靠这一条，只记工具名等于没记。
     */
    public void toolCall(String taskId, String tool, String arguments, boolean ok, String output) {
        ObjectNode node = event("tool_call");
        if (taskId != null) {
            node.put("taskId", taskId);
        }
        node.put("tool", tool);
        node.put("arguments", clip(arguments));
        node.put("ok", ok);
        node.put("output", clip(output));
        write(node);
    }

    /**
     * 一次审批决策。批准和拒绝都要记——这是审计第一关心的事。
     *
     * <p>{@code remembered} 尤其要记：一条规则存下去之后，后面所有被它放行的动作
     * 都<b>不会再产生审批记录</b>。事后查账的人看到一段时间内危险动作突然没人批了，
     * 得能找到那一刻——否则那段日志看起来就像审批被绕过了。
     */
    public void approval(ApprovalRequest request, ApprovalVerdict verdict) {
        ObjectNode node = event("approval");
        node.put("tool", request.kind().id());
        node.put("action", request.action());
        node.put("detail", clip(request.subject()));
        node.put("risk", request.reason());
        node.put("approved", verdict.approved());
        if (verdict.hasNote()) {
            node.put("note", clip(verdict.note()));
        }
        if (!verdict.remember().isEmpty()) {
            node.put("remembered", String.join(" + ", verdict.remember()));
        }
        if (request.origin() != null && !request.origin().isBlank()) {
            node.put("origin", request.origin().label());
        }
        write(node);
    }

    /**
     * 把一个审批策略包成会留底的版本。
     *
     * <p>用装饰器而不是去三个工具里各埋一行：审批点以后还会增加，
     * 埋点式的做法总有一天会漏掉一个，而漏掉的那个恰好就是出事的那个。
     * 包在这里，任何经过这条策略的决策都跑不掉。
     *
     * <p>包的位置在 {@code PolicyGate} <b>里面</b>：被规则直接放行的那些不产生记录，
     * 只有真正问到人的才记。规则本身在它被存下来的那一刻已经记过一次了。
     */
    public ApprovalPolicy audited(ApprovalPolicy delegate) {
        return request -> {
            ApprovalVerdict verdict = delegate.decide(request);
            approval(request, verdict);
            return verdict;
        };
    }

    /** 兜底：记一条带一个自定义字段的事件。 */
    public void note(String type, String field, Object value) {
        ObjectNode node = event(type);
        node.put(field, String.valueOf(value));
        write(node);
    }

    @Override
    public void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            log.debug("关闭审计日志失败: {}", e.getMessage());
        } finally {
            writer = null;
        }
    }

    private ObjectNode event(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ts", Instant.now().toString());
        node.put("runId", runId);
        node.put("type", type);
        return node;
    }

    // synchronized：并行的工具调用和子任务会同时来记账，
    // BufferedWriter 不是线程安全的，两条记录会绞在同一行里
    private synchronized void write(ObjectNode node) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(MAPPER.writeValueAsString(node));
            writer.newLine();
            // 每条都 flush：agent 跑崩或者被 Ctrl-C 掐掉时，
            // 缓冲区里没写出去的恰恰是最想看的那几条
            writer.flush();
        } catch (IOException | RuntimeException e) {
            if (!reportedFailure) {
                reportedFailure = true;
                log.warn("审计日志写入失败，后续记录将被丢弃: {}", e.getMessage());
            }
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_FIELD_LENGTH
                ? text
                : text.substring(0, MAX_FIELD_LENGTH) + "…(截断，共 " + text.length() + " 字)";
    }
}
