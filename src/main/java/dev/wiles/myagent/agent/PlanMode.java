package dev.wiles.myagent.agent;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 会话当前是不是 plan 模式。照 Claude Code：plan 模式是一种<b>权限模式</b>，不是另一条执行链路。
 *
 * <h3>它管什么</h3>
 * 只管一件事：主 agent 这一刻手上有哪些工具（{@link #allows}）。plan 模式下只剩看的工具，
 * 外加 {@code exit_plan_mode} 用来把计划交给用户批；普通模式下反过来，
 * 多一个 {@code enter_plan_mode} 让模型提议先规划。批准之后还是同一个 agent、同一段对话接着做——
 * 调研时读过的东西全在上下文里，不用再转述给谁。
 *
 * <h3>谁能切</h3>
 * 用户随时能切（界面 Shift+Tab）；模型只能<b>提议</b>，切不切由用户在确认框里定
 * （见 {@link PlanModeTools}）。两条路最后都落到 {@link #set}，界面靠监听器同步。
 */
public final class PlanMode {

    public static final String ENTER_TOOL = "enter_plan_mode";
    public static final String EXIT_TOOL = "exit_plan_mode";

    /**
     * plan 模式下还能用的工具。只放<b>只看不改</b>的：读、搜、联网读、读记忆、读技能、找工具，
     * 外加派子 agent 那几个（{@code SubAgents} 自己会把类型限制在 explorer）。
     *
     * <p>bash 不在里面：它能做的事没法从名字上判断，{@code git log} 和 {@code rm} 走的是同一扇门。
     * MCP 工具也不在：它叫 query 也可能在写库。这和只读模式（{@code --readonly}）是同一个判断。
     */
    static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read_file", "list_dir", "grep", "glob", "web_search", "web_fetch", "recall", "skill",
            "tool_search", EXIT_TOOL,
            "spawn_agent", "send_input", "wait_agent", "close_agent", "resume_agent");

    private volatile boolean on;
    private final CopyOnWriteArrayList<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

    public boolean on() {
        return on;
    }

    /** 切换。和眼下一样时什么也不做，监听器不会收到重复的通知。 */
    public void set(boolean planMode) {
        if (on == planMode) {
            return;
        }
        on = planMode;
        listeners.forEach(l -> l.accept(planMode));
    }

    public PlanMode onChange(Consumer<Boolean> listener) {
        listeners.add(listener);
        return this;
    }

    /** 这个工具眼下能不能出现在主 agent 的菜单上、能不能被调用。 */
    public boolean allows(String tool) {
        if (on) {
            return READ_ONLY_TOOLS.contains(tool);
        }
        return !EXIT_TOOL.equals(tool);
    }
}
