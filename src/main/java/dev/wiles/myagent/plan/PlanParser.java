package dev.wiles.myagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
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
 *   <li>类型写成小写、写成自己编的名字</li>
 *   <li>字段缺失、tasks 不是数组</li>
 *   <li>任务数量离谱</li>
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

    private static List<Task> parseTasks(JsonNode root, Task parent) {
        JsonNode array = root.get("tasks");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("缺少 tasks 字段，或者它不是数组。");
        }

        List<Task> tasks = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            tasks.add(parseTask(array.get(i), i, parent));
        }
        return tasks;
    }

    private static Task parseTask(JsonNode node, int index, Task parent) {
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

        TaskType type = parseType(text(node, "type"), where, id);
        // 子任务不许再是 PLANNING：它深度已经是 1，再拆就是孙子任务，模型层直接不给入口。
        // 与其让它跑起来之后拆不动再报错，不如在规划阶段就打回去重写。
        if (parent != null && type == TaskType.PLANNING) {
            throw new IllegalArgumentException(where + "（" + id + "）的类型是 PLANNING。"
                    + "子任务不能再是规划任务，请直接给出具体要做的事。");
        }

        List<String> dependsOn = parseDependsOn(node, where, id);
        return parent == null
                ? new Task(id, description, type, dependsOn)
                : parent.subTask(id, description, type, dependsOn);
    }

    private static TaskType parseType(String raw, String where, String id) {
        if (raw == null) {
            throw new IllegalArgumentException(where + "（" + id + "）缺少 type。" + legalTypes());
        }
        try {
            // 大小写不敏感：模型写小写是常事，为这个打回重来不划算
            return TaskType.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "（" + id + "）的类型 " + raw
                    + " 不存在。" + legalTypes());
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

    private static String legalTypes() {
        return "合法的类型只有: " + Arrays.stream(TaskType.values())
                .map(Enum::name).collect(Collectors.joining(", "));
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
