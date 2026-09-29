package dev.wiles.myagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把模型吐出来的一坨文本，解析成一批 {@link Task}。
 *
 * <p>这个类的每一句报错都是<b>提示词</b>：解析失败时，错误原文会被追加成一条 user 消息
 * 发回给模型让它改（见 {@link Planner}）。所以报错必须写成人话，并且说清楚
 * 「哪儿不对 + 该是什么样」——写成 {@code NullPointerException} 那种，模型只会一脸茫然。
 *
 * <p>模型输出 JSON 有多少种翻法，这里就得挡多少种：
 * <ul>
 *   <li>整段没有 JSON，只有一段商量的话</li>
 *   <li>JSON 裹在 ```json 围栏里，前后还有寒暄</li>
 *   <li>角色写成大写、写成自己编的名字，或者退回去填了 type</li>
 *   <li>字段缺失、tasks 不是数组</li>
 *   <li>任务数量离谱</li>
 *   <li>引用了 {@code {{t1}}} 却忘了把 t1 写进 dependsOn（见 {@link TaskRefs}）</li>
 * </ul>
 */
public final class PlanParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 根计划的解析结果：模型复述的目标 + 一批根任务。 */
    public record ParsedPlan(String goal, List<Task> tasks) {
    }

    private PlanParser() {
    }

    /**
     * 解析一份完整计划。任务数必须落在 {@link PlanLimits#MIN_TASKS}..{@link PlanLimits#MAX_TASKS}。
     */
    public static ParsedPlan parse(String text) {
        JsonNode root = extractJson(text);

        JsonNode goal = root.get("goal");
        if (goal == null || goal.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "缺少 goal 字段。goal 要用一句话复述你对目标的理解，让用户确认你没理解偏。");
        }

        List<Task> tasks = parseTasks(root, null);
        if (tasks.size() < PlanLimits.MIN_TASKS || tasks.size() > PlanLimits.MAX_TASKS) {
            throw new IllegalArgumentException("任务数是 " + tasks.size() + " 个，要求 "
                    + PlanLimits.MIN_TASKS + " 到 " + PlanLimits.MAX_TASKS + " 个。"
                    + (tasks.size() < PlanLimits.MIN_TASKS
                    ? "拆得太粗了，把大步骤再分开。"
                    : "拆得太碎了，把关系紧密的步骤合并。"));
        }
        return new ParsedPlan(goal.asText().strip(), tasks);
    }

    /**
     * 解析 {@link TaskType#PLANNING} 任务拆出来的子任务。
     *
     * <p>和根计划的三点不同：不要 goal（父任务的描述就是目标）、没有数量下限
     * （拆出一两步是正常的）、每个任务都从 {@code parent} 身上长出来，深度恒为 1。
     */
    public static List<Task> parseSubTasks(String text, Task parent) {
        List<Task> tasks = parseTasks(extractJson(text), parent);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("一个子任务都没拆出来。至少给出一步具体要做的事。");
        }
        if (tasks.size() > PlanLimits.MAX_SUB_TASKS) {
            throw new IllegalArgumentException("子任务有 " + tasks.size() + " 个，最多 "
                    + PlanLimits.MAX_SUB_TASKS + " 个。只列出接下来必须做的几步，"
                    + "不要在这里重新规划整件事。");
        }
        return tasks;
    }

    /**
     * 解析一批<b>补救任务</b>——原计划某一步倒了之后重规划出来的那些。
     *
     * <p>和根计划的三点不同：
     * <ul>
     *   <li>不要 goal（目标还是原来那个，没变）</li>
     *   <li><b>允许空</b>。{@code {"tasks": []}} 是模型在说「这事儿在当前条件下做不成」，
     *       那是一个有用的答案，不是错误——见 {@link PlanPrompt#replanSystem()}。</li>
     *   <li>依赖只能指向这批新任务内部，理由见 {@link #requireSelfContainedDeps}</li>
     * </ul>
     */
    public static List<Task> parseReplan(String text) {
        JsonNode root = extractJson(text);
        JsonNode array = root.get("tasks");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("缺少 tasks 字段，或者它不是数组。"
                    + "补不了的话就输出 {\"tasks\": []}。");
        }
        if (array.isEmpty()) {
            return List.of();       // 模型明说补不了，这是合法答案
        }

        List<Task> tasks = parseTasks(root, null);
        if (tasks.size() > PlanLimits.MAX_REPLAN_TASKS) {
            throw new IllegalArgumentException("补救任务有 " + tasks.size() + " 个，最多 "
                    + PlanLimits.MAX_REPLAN_TASKS + " 个。补救是在已有成果上接着走，"
                    + "不是把整件事重做一遍。");
        }
        requireSelfContainedDeps(tasks);
        return tasks;
    }

    /**
     * 补救任务的依赖只能指向这批新任务里的其它任务。
     *
     * <p>为什么不能连回原计划：原来那些任务此刻只有两种状态——已经跑完（产出已经
     * 摊进提示词里了，直接用就行），或者已经失败/被跳过。<b>连到后者会让新任务永远等不到就绪</b>，
     * 而调度器看不出「死等」和「还没轮到」的区别，整张图就静默挂在那儿。
     * 与其等它挂住再去猜，不如在解析这一步就打回去重写。
     */
    private static void requireSelfContainedDeps(List<Task> tasks) {
        List<String> ids = tasks.stream().map(Task::id).toList();
        for (Task task : tasks) {
            for (String dep : task.dependencies()) {
                if (!ids.contains(dep)) {
                    throw new IllegalArgumentException("补救任务 " + task.id() + " 依赖了 " + dep
                            + "，但它不在这批新任务里。dependsOn 只能写这批任务内部的 id——"
                            + "原计划那些步骤要么已经跑完（产出都给你了），要么已经废掉，"
                            + "连过去的话这一步永远等不到就绪。");
                }
            }
        }
    }

    private static List<Task> parseTasks(JsonNode root, Task parent) {
        JsonNode array = root.get("tasks");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("缺少 tasks 字段，或者它不是数组。");
        }

        // 先收齐这张图里的 id：{{x}} 只有在 x 是图里某个任务时才算引用。
        // 模型描述的活本身可能含花括号（模板引擎的 {{name}}、Jinja、Handlebars……），
        // 那是任务的正文，不是占位符——按引用去拦，这类目标一个方案都拟不出来
        java.util.Set<String> knownIds = new java.util.HashSet<>();
        for (JsonNode n : array) {
            if (n != null && n.hasNonNull("id")) {
                knownIds.add(n.get("id").asText().strip());
            }
        }
        List<Task> tasks = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            tasks.add(parseTask(array.get(i), i, parent, knownIds));
        }
        return tasks;
    }

    private static Task parseTask(JsonNode node, int index, Task parent,
                                  java.util.Set<String> knownIds) {
        String where = "第 " + (index + 1) + " 个任务";
        if (!node.isObject()) {
            throw new IllegalArgumentException(where + "不是一个 JSON 对象。");
        }

        String id = text(node, "id");
        if (id == null) {
            throw new IllegalArgumentException(where + "缺少 id。id 是连依赖用的，必须有且唯一。");
        }
        String description = text(node, "description");
        if (description == null) {
            throw new IllegalArgumentException(where + "（" + id + "）缺少描述。"
                    + "描述要写清这一步具体做什么，它会被原样交给执行这一步的模型。");
        }

        String acceptance = text(node, "acceptance");
        if (acceptance == null) {
            throw new IllegalArgumentException(where + "（" + id + "）缺少 acceptance。"
                    + "acceptance 要用一句话写清「做到什么算这一步完成」，"
                    + "执行这一步的模型靠它判断能不能收口——少了它，它只能凭感觉决定什么时候停。");
        }

        AgentRole role = parseRole(node, where, id);
        // 子任务不许再是 PLANNING：它深度已经是 1，再拆就是孙子任务，模型层直接不给入口。
        // 与其让它跑起来之后拆不动再报错，不如在规划阶段就打回去重写。
        if (parent != null && role.taskType() == TaskType.PLANNING) {
            throw new IllegalArgumentException(where + "（" + id + "）派的是 " + role.id()
                    + "，那是个规划角色。子任务不能再规划，请直接给出具体要做的事。");
        }

        List<String> dependsOn = parseDependsOn(node, where, id);
        requireRefsDeclared(where, id, description, acceptance, dependsOn, knownIds);
        return parent == null
                ? new Task(id, description, role, dependsOn, acceptance)
                : parent.subTask(id, description, role, dependsOn, acceptance);
    }

    /**
     * 占位符引用的任务，必须同时写进 {@code dependsOn}。
     *
     * <p>这是整个数据流设计里唯一需要强制的一致性：引用说的是「我要用你的产出」，
     * 依赖说的是「我得等你跑完」。只写前者不写后者，调度器完全可能先跑引用方——
     * 那时占位符能换进来的只有一片空白，而且不会报任何错，是最难查的一种静默错误。
     */
    private static void requireRefsDeclared(String where, String id, String description,
                                            String acceptance, List<String> dependsOn,
                                            java.util.Set<String> knownIds) {
        for (String ref : TaskRefs.referencedIn(description + "\n" + acceptance)) {
            if (!knownIds.contains(ref)) {
                continue;       // 不是图里的任务，是正文里的花括号，见 parseTasks
            }
            if (ref.equals(id)) {
                throw new IllegalArgumentException(where + "（" + id + "）引用了 {{" + id
                        + "}}，也就是它自己。占位符只能用来引用上游任务的产出。");
            }
            if (!dependsOn.contains(ref)) {
                throw new IllegalArgumentException(where + "（" + id + "）的文字里写了 {{" + ref
                        + "}}，但它的 dependsOn 里没有 " + ref + "。"
                        + "要用某个任务的产出，就必须把它写进 dependsOn，"
                        + "否则执行时那一步可能还没跑，这里拿到的会是一片空白。");
            }
        }
    }

    /**
     * 派谁干这一步。
     *
     * <h3>为什么还认 type</h3>
     * 提示词只让模型填 {@code role}，但它偶尔还是会填回 {@code type}——
     * 尤其是被回灌打回重写的那几轮，模型很容易退回到更常见的写法。
     * 这种输出的<b>意图是清楚的</b>（「这一步是读文件」），
     * 只是没说派谁去；为一个意图清楚的答案再烧一轮调用不划算，
     * 落到那个类型的默认角色上就是了。
     *
     * <p>反过来两个都填且对不上时，<b>以 role 为准</b>：role 是提示词要的那个，
     * 而且它更具体（{@code reviewer} 和 {@code explorer} 同属 FILE_READ，
     * 类型表达不出这个区别）。
     */
    private static AgentRole parseRole(JsonNode node, String where, String id) {
        String raw = text(node, "role");
        if (raw != null) {
            try {
                // 大小写不敏感：模型写 Coder、CODER 都是常事，为这个打回重来不划算
                return AgentRoles.require(raw.toLowerCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + "（" + id + "）" + e.getMessage());
            }
        }

        String type = text(node, "type");
        if (type == null) {
            throw new IllegalArgumentException(where + "（" + id + "）缺少 role。" + legalRoles());
        }
        try {
            return AgentRoles.defaultFor(TaskType.valueOf(type.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "（" + id + "）的 role 没填，"
                    + "type 填的 " + type + " 也不是合法类型。" + legalRoles());
        }
    }

    private static List<String> parseDependsOn(JsonNode node, String where, String id) {
        JsonNode deps = node.get("dependsOn");
        if (deps == null || deps.isNull()) {
            return List.of();     // 没写就是没依赖，不值得报错
        }
        if (!deps.isArray()) {
            throw new IllegalArgumentException(where + "（" + id
                    + "）的 dependsOn 必须是数组，里面放它依赖的任务 id。");
        }
        List<String> result = new ArrayList<>(deps.size());
        for (JsonNode dep : deps) {
            String value = dep.asText().strip();
            if (!value.isEmpty() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static String legalRoles() {
        return "可以派的角色只有: " + AgentRoles.all().stream()
                .map(AgentRole::id).collect(Collectors.joining(", "));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().strip();
    }

    /**
     * 从一坨文本里把 JSON 抠出来。
     *
     * <p>取第一个 {@code &#123;} 到最后一个 {@code &#125;} 之间的部分——比正则匹配围栏可靠：
     * 模型可能用 ```json、可能用 ```、可能干脆不用，但只要它写了 JSON 对象，
     * 这个区间就一定包住它。前后的寒暄自然被切掉。
     */
    private static JsonNode extractJson(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("没有输出任何内容。请按要求输出 JSON。");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException(
                    "回答里没有找到 JSON 对象。请只输出一个 JSON 对象，不要写别的话。");
        }
        try {
            JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("最外层必须是一个 JSON 对象。");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage()
                    + "。请重新输出一段格式正确的 JSON。");
        }
    }
}
