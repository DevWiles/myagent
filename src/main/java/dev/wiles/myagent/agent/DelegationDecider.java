package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.prompt.PromptRepository;
import dev.wiles.myagent.tool.WorkspaceDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 开工前的委派决策：这个任务该一个人做完，还是值得派子 agent。
 *
 * <h3>为什么开局决定，而不是让主 agent 中途自己想起来</h3>
 * cookiecutter 上把「能并行就派」写进提示词之后，三次里一次认真派了、一次快用满步数才派
 * （刚派出就随收尾被叫停）、一次一个没派。派不派、什么时候派全看运气，结果没法评估。
 * 开局用一次不带工具的请求把这件事想清楚、写成结构化决策，有三个好处：
 * <ul>
 *   <li>决定不委派时，主 agent 的菜单上<b>根本没有</b>派遣工具——行为和纯单 agent 一样，
 *       单 agent 的能力在结构上不会被拖累</li>
 *   <li>决定委派时，计划（子任务、写入范围、完成标准、验证方式）在第一步就摆在主 agent 面前</li>
 *   <li>每个任务委派了没有、为什么，都留在评测记录里，能拿来做归因</li>
 * </ul>
 *
 * <h3>任何拿不准的情况都退回单 agent</h3>
 * 模型调用失败、输出不是 JSON、要委派却没给子任务、worker 没写写入范围、
 * 两个 worker 的写入范围重叠——一律按「不委派」处理，理由写进 {@link Decision#reason()}。
 * 决策器出错的代价只该是「少了一次委派」，不该是任务跑不起来。
 */
public final class DelegationDecider {

    private static final Logger log = LoggerFactory.getLogger(DelegationDecider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PromptRepository REPO = PromptRepository.createDefault();
    private static final String PROMPT = "subagents/decide.md";

    /** 决策只是一段短 JSON，给足余量但封顶：JSON 模式下截断时服务端可能一直吐空白。 */
    private static final int MAX_TOKENS = 2_000;
    private static final double TEMPERATURE = 0.2;

    static {
        REPO.loadRequired(PROMPT);
    }

    /** 一个子任务。{@code owns} 是相对工作区的路径，explorer 为空。 */
    public record Subtask(String agentType, String goal, List<String> owns, String acceptance, String verify) {
    }

    /**
     * 决策结果。
     *
     * @param delegate 最终是否委派（校验不过的已经被改成 false）
     * @param reason   为什么；被校验否决时是否决的理由
     * @param raw      模型原话，留给评测记录对账
     */
    public record Decision(boolean delegate, String reason, String expectedBenefit, List<String> risks,
                           String estimatedCost, List<Subtask> subtasks, String raw) {

        static Decision single(String reason, String raw) {
            return new Decision(false, reason, "", List.of(), "", List.of(), raw == null ? "" : raw);
        }

        /** 写进评测摘要的那份。 */
        public ObjectNode toJson() {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("delegate", delegate);
            out.put("reason", reason);
            out.put("expected_benefit", expectedBenefit);
            risks.forEach(out.putArray("risks")::add);
            out.put("estimated_cost", estimatedCost);
            ArrayNode subs = out.putArray("subtasks");
            for (Subtask s : subtasks) {
                ObjectNode n = subs.addObject();
                n.put("agent_type", s.agentType());
                n.put("goal", s.goal());
                s.owns().forEach(n.putArray("owns")::add);
                n.put("acceptance", s.acceptance());
                n.put("verify", s.verify());
            }
            return out;
        }

        /** 放进主 agent 对话里的那份计划。 */
        public String render() {
            StringBuilder b = new StringBuilder(SubAgents.PLAN_TAG).append('\n');
            b.append("开工前的委派决策：派子 agent 分担下面这几块，其余由你自己做。\n");
            b.append("理由：").append(reason).append('\n');
            if (!risks.isEmpty()) {
                b.append("要防的风险：").append(String.join("；", risks)).append('\n');
            }
            b.append('\n');
            for (int i = 0; i < subtasks.size(); i++) {
                Subtask s = subtasks.get(i);
                b.append(i + 1).append(". [").append(s.agentType()).append("] ").append(s.goal()).append('\n');
                if (!s.owns().isEmpty()) {
                    b.append("   owns: ").append(String.join(", ", s.owns())).append('\n');
                }
                if (!s.acceptance().isBlank()) {
                    b.append("   完成标准: ").append(s.acceptance()).append('\n');
                }
                if (!s.verify().isBlank()) {
                    b.append("   验证: ").append(s.verify()).append('\n');
                }
            }
            b.append("""

                    怎么做：
                    - 先用 spawn_agent 把这几块派出去（worker 照上面的 owns 填），再去做你自己那部分；计划之外的不要另派。
                    - 派出去的文件你写不了，也别去碰；子 agent 交回后它们才放开。
                    - 子 agent 说「做完了」不算数：交回后你自己看改动、跑相关测试，再决定接受、返工还是自己接手。
                    - 发现计划不对（比如两块其实缠在一起），可以不派、close_agent 收回，改成自己做。
                    """);
            b.append("</delegation_plan>");
            return b.toString();
        }
    }

    private final LlmClient client;
    private final Path workspaceRoot;
    private final int maxSubtasks;

    /**
     * @param client      专用客户端（JSON 模式、低温度会改它的状态，别和主 agent 共用）
     * @param maxSubtasks 子任务上限，和子 agent 并发上限对齐
     */
    public DelegationDecider(LlmClient client, Path workspaceRoot, int maxSubtasks) {
        this.client = client;
        this.workspaceRoot = workspaceRoot;
        this.maxSubtasks = maxSubtasks;
    }

    /**
     * @param task     用户原话
     * @param maxSteps 主 agent 的步数预算，{@code <= 0} 表示不限
     */
    public Decision decide(String task, int maxSteps) {
        String system = REPO.loadRequired(PROMPT).replace("{{max}}", String.valueOf(maxSubtasks));
        String user = "## 任务（用户原话）\n" + task
                + "\n\n## 预算\n主 agent 步数上限：" + (maxSteps <= 0 ? "不限" : maxSteps)
                + "；子 agent 同时最多 " + maxSubtasks + " 个。"
                + "\n\n## 工作区概览\n" + digest();
        String raw;
        try {
            raw = client.jsonMode(true).temperature(TEMPERATURE).maxTokens(MAX_TOKENS)
                    .chat(List.of(ChatMessage.system(system), ChatMessage.user(user))).text();
        } catch (RuntimeException e) {
            log.warn("委派决策调用失败，按单 agent 处理", e);
            return Decision.single("决策器调用失败，按单 agent 处理: " + e.getMessage(), "");
        }
        return parse(raw, maxSubtasks);
    }

    private String digest() {
        try {
            return WorkspaceDigest.of(workspaceRoot);
        } catch (RuntimeException e) {
            return "（读不到工作区目录）";
        }
    }

    /** 解析加校验。包内可见：测试直接喂模型输出。 */
    static Decision parse(String raw, int maxSubtasks) {
        JsonNode node;
        try {
            int start = raw == null ? -1 : raw.indexOf('{');
            int end = raw == null ? -1 : raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return Decision.single("决策器没有输出 JSON，按单 agent 处理", raw);
            }
            node = MAPPER.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return Decision.single("决策器输出的 JSON 解析失败，按单 agent 处理: " + e.getMessage(), raw);
        }
        String reason = text(node, "reason");
        String benefit = text(node, "expected_benefit");
        List<String> risks = strings(node.path("risks"));
        String cost = text(node, "estimated_cost");
        if (!node.path("delegate").asBoolean(false)) {
            return new Decision(false, reason.isBlank() ? "决策器判断单 agent 更合适" : reason,
                    benefit, risks, cost, List.of(), raw);
        }

        List<Subtask> subtasks = new ArrayList<>();
        for (JsonNode s : node.path("subtasks")) {
            String type = text(s, "agent_type").toLowerCase(Locale.ROOT);
            if (!Set.of("explorer", "worker", "default").contains(type)) {
                type = "default";
            }
            String goal = text(s, "goal");
            if (goal.isBlank()) {
                continue;
            }
            List<String> owns = type.equals("explorer") ? List.of()
                    : strings(s.path("owns")).stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
            subtasks.add(new Subtask(type, goal, owns, text(s, "acceptance"), text(s, "verify")));
        }
        String veto = veto(subtasks, maxSubtasks);
        if (veto != null) {
            return new Decision(false, veto + "（决策器原本的理由：" + reason + "）", benefit, risks, cost,
                    List.of(), raw);
        }
        return new Decision(true, reason, benefit, risks, cost, List.copyOf(subtasks), raw);
    }

    /** 要委派但计划站不住的情况。null 表示通过。 */
    private static String veto(List<Subtask> subtasks, int maxSubtasks) {
        if (subtasks.isEmpty()) {
            return "要委派却没给出有效的子任务，按单 agent 处理";
        }
        if (subtasks.size() > maxSubtasks) {
            return "子任务 " + subtasks.size() + " 个，超过上限 " + maxSubtasks + "，按单 agent 处理";
        }
        List<String> claimed = new ArrayList<>();
        for (Subtask s : subtasks) {
            if (s.agentType().equals("worker") && s.owns().isEmpty()) {
                return "worker 子任务没写写入范围（owns），按单 agent 处理";
            }
            for (String o : s.owns()) {
                String norm = normalize(o);
                for (String c : claimed) {
                    if (norm.equals(c) || norm.startsWith(c + "/") || c.startsWith(norm + "/")) {
                        return "子任务的写入范围重叠（" + o + "），按单 agent 处理";
                    }
                }
                claimed.add(norm);
            }
        }
        return null;
    }

    private static String normalize(String path) {
        String p = path.strip().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText().strip() : "";
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> {
            if (v.isTextual() && !v.asText().isBlank()) {
                out.add(v.asText().strip());
            }
        });
        return out;
    }
}
