package dev.wiles.myagent.plan;

import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * 按任务类型收窄工具菜单。
 *
 * <p>这是 {@link TaskType} 三个用途里最硬的一个：<b>模型看不到菜单上没有的工具，
 * 就根本不会去调</b>。比「注册了但执行时拦住」可靠得多——后者要靠审批逻辑不出漏，
 * 前者压根没有那扇门。这个思路现成就有，{@code BuiltinTools.createReadOnly} 用的是同一套道理。
 *
 * <p>用<b>白名单</b>而不是黑名单：以后新增工具（比如发邮件、调 API），
 * 默认对所有任务类型都不可见，得显式想清楚该给谁才加进来。
 * 黑名单反过来——新工具默认全放行，忘了加禁令就是一个静默的安全缺口。
 */
public final class TaskTools {

    private static final List<String> READ_ONLY = List.of("read_file", "list_dir");

    private static final Map<TaskType, List<String>> ALLOWED = Map.of(
            // 规划任务要摸情况才能拆得准，但它的产出是任务列表，不是代码
            TaskType.PLANNING, READ_ONLY,
            TaskType.FILE_READ, READ_ONLY,
            // 写任务必须带上读工具：系统提示词里写死了「修改文件前必须先读取它」
            TaskType.FILE_WRITE, List.of("read_file", "list_dir", "write_file", "create_project"),
            TaskType.COMMAND, List.of("read_file", "list_dir", "bash"),
            TaskType.VERIFICATION, List.of("read_file", "list_dir", "bash"),
            // 分析任务一个工具都不给：它该基于上游交过来的材料想，而不是自己再去翻一遍
            TaskType.ANALYSIS, List.of());

    private TaskTools() {
    }

    /**
     * 从全量注册表里挑出这个类型能用的工具，组成一个新的注册表。
     *
     * <p>注册表里没有的名字直接跳过——全量注册表长什么样由上层决定
     * （比如审批策略不同，工具实例就不同），这里不该假设它一定齐全。
     */
    public static ToolRegistry forType(ToolRegistry all, TaskType type) {
        List<String> allowed = ALLOWED.getOrDefault(type, List.of());
        ToolRegistry narrowed = new ToolRegistry();
        for (String name : allowed) {
            for (Tool tool : all.all()) {
                if (tool.name().equals(name)) {
                    narrowed.register(tool);
                    break;
                }
            }
        }
        return narrowed;
    }
}
