package dev.wiles.myagent.plan;

import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.WorkspaceDigest;
import dev.wiles.myagent.tool.safety.CommandGuard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 规划时模型需要知道的<b>现场情况</b>：仓库长什么样、手上有哪些工具、哪些事绝对不许干。
 *
 * <h3>为什么非有不可</h3>
 * 在这之前，规划器收到的全部输入就是一句「目标: xxx」。它对这个仓库一无所知，
 * 于是必然做两件事：
 * <ul>
 *   <li><b>编</b>。凭空猜一个 {@code src/main/App.java} 出来，跑到那一步才发现没这文件。</li>
 *   <li><b>拿探路任务凑数</b>。一半的步骤在「先看看项目结构」——那些信息本来
 *       一次目录扫描就能白送给它，却花了一整个任务、一轮模型调用去换。</li>
 * </ul>
 *
 * <h3>为什么禁止命令也要给它</h3>
 * {@link CommandGuard} 有一份怎么都不放行的名单（{@code sudo}、{@code mkfs}、关机…）。
 * 规划器不知道这份名单，就会规划出「用 sudo 装依赖」这种<b>注定跑不通</b>的任务——
 * 跑到那儿被拦死，还顺带把整条下游连累成 SKIPPED。
 *
 * <p>这和只读模式的处理是同一个道理：<b>提示词里先说清楚，产出里再校验一遍</b>。
 * 光靠执行时拦截，代价是一次白跑的规划加一条废掉的依赖链。
 *
 * <h3>为什么是 record、为什么留一个 none()</h3>
 * 纯数据、不可变，{@link PlanPrompt} 只管把它排版成文字，两边都好单测——
 * 断言「工具清单确实拼进去了」不必真的发一次模型调用。
 * {@link #none()} 让「没有现场信息」成为一种合法状态：单测和无工作区的场景照常能规划。
 */
public record PlanContext(
        String workspaceRoot,
        String tree,
        List<ToolBrief> tools,
        List<String> forbiddenCommands) {

    /** 工具在规划器眼里只需要两件事：叫什么、大概能干嘛。 */
    public record ToolBrief(String name, String summary) {
    }

    public PlanContext {
        tools = List.copyOf(tools == null ? List.of() : tools);
        forbiddenCommands = List.copyOf(forbiddenCommands == null ? List.of() : forbiddenCommands);
    }

    /** 什么现场信息都没有。规划照常能跑，只是模型得自己去摸。 */
    public static PlanContext none() {
        return new PlanContext(null, null, List.of(), List.of());
    }

    /**
     * 从工作区和工具注册表现场采一份。
     *
     * <p>工具清单取自<b>实际注册的那一份</b>，不是写死的名字——
     * 只读模式下注册表里本来就没有写工具，规划器于是连菜单都看不到它们。
     * 这比在提示词里多写一句「现在不能写文件」更硬：{@link TaskTools} 用的是同一套道理。
     */
    public static PlanContext of(Path workspaceRoot, ToolRegistry tools) {
        List<ToolBrief> briefs = new ArrayList<>();
        if (tools != null) {
            for (Tool tool : tools.all()) {
                briefs.add(new ToolBrief(tool.name(), firstSentence(tool.description())));
            }
        }
        return new PlanContext(
                workspaceRoot == null ? null : workspaceRoot.toString(),
                workspaceRoot == null ? null : WorkspaceDigest.of(workspaceRoot),
                briefs,
                CommandGuard.forbiddenCommands());
    }

    public boolean hasTree() {
        return tree != null && !tree.isBlank();
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    public boolean hasForbidden() {
        return !forbiddenCommands.isEmpty();
    }

    /**
     * 工具描述只取第一句。
     *
     * <p>完整描述是写给<b>执行</b>阶段看的，里面全是参数怎么填、什么情况下会被拒这类细节；
     * 规划器只需要知道「有没有这个能力」。整段拼进去，五个工具就能占掉几百 token，
     * 而且把真正要紧的目录树挤到注意力的边缘。
     */
    private static String firstSentence(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        int stop = description.indexOf('。');
        return stop < 0 ? description.strip() : description.substring(0, stop + 1).strip();
    }
}
