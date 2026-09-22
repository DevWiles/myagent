package dev.wiles.myagent.plan;

import dev.wiles.myagent.prompt.PromptRepository;

import java.util.List;
import java.util.Objects;

/**
 * 一个角色：派谁去干这一步。
 *
 * <h3>为什么要在 {@link TaskType} 之外再有角色</h3>
 * 类型说的是「这一步会对世界做什么」——读、写、跑命令。它够用来排并发、上图标，
 * 但它<b>决定不了该用什么姿势去做</b>。最直接的证据是 {@code explorer} 和 {@code reviewer}：
 * 两个都只读文件，工具白名单一模一样，可一个的任务是「把事实查清楚」，
 * 另一个的任务是「挑毛病」。写进同一个 {@code FILE_READ} 里，就只能共用一段提示词，
 * 那段话必然是两头都不讨好的和稀泥。
 *
 * <p>所以分工是：<b>角色拿走提示词、工具、步数、收尾契约，类型只留调度语义和界面</b>。
 *
 * <h3>模型只选角色，类型是推导出来的</h3>
 * 规划提示词里只让模型填 {@code role}，{@link #taskType} 由角色自己声明。
 * 两个字段都让模型填的话，迟早会收到 {@code type: FILE_READ} 配 {@code role: coder}
 * 这种自相矛盾的计划——而那时候<b>没有哪个字段是可信的</b>：按类型派工具就没有写工具，
 * 按角色派就违背了它自己填的类型，两条路都得猜。让它只填一个，这种矛盾从根上不存在。
 *
 * <h3>正文为什么在资源文件里</h3>
 * 和 {@link PromptRepository} 是同一个理由：这几段话会被反复调，
 * 而「调一句措辞要重新编译打包」等于没人会去调。这里只留名册和参数——
 * 「内置」指的是<b>角色名册写死在代码里</b>，不是把文字也埋进 Java 字符串。
 *
 * @param id             角色 id，小写，模型在计划 JSON 里填的就是它
 * @param title          中文名，界面和提示词里给人看
 * @param summary        一句话说清<b>什么时候该派它</b>。这句会原样进规划提示词，
 *                       是模型选角色的唯一依据——写成「负责编码」这种废话，
 *                       模型就只能靠 id 猜
 * @param taskType       对应的任务类型。调度和界面继续用它，协议里也照发
 * @param tools          工具白名单。<b>白名单不是黑名单</b>：以后新增工具（发邮件、调 API）
 *                       默认对所有角色不可见，得显式想清楚该给谁。
 *                       写全名精确匹配；<b>只有 {@code mcp__} 开头的条目</b>许用尾部
 *                       {@code *} 通配，见 {@link #matches(String)}
 * @param maxSteps       这个角色的 ReAct 步数上限。分析型的给三步足够，
 *                       编码型的要留出「读—改—再读」的来回
 * @param mutates        会不会改变世界。只读模式靠它过滤掉一半角色，
 *                       将来并发调度也要靠它分流
 * @param requiresVerdict 收尾必须给出结论行（见 {@link TaskPrompt#VERDICT_PASS}）。
 *                       目前只有验证角色要，但它是角色的属性不是类型的属性——
 *                       将来多一个「验收」角色，它也要
 */
public record AgentRole(
        String id,
        String title,
        String summary,
        TaskType taskType,
        List<String> tools,
        int maxSteps,
        boolean mutates,
        boolean requiresVerdict) {

    private static final PromptRepository REPO = PromptRepository.createDefault();

    /** MCP 工具的名字前缀，和 {@code McpTool} 那边定的一致。通配只对它开放。 */
    private static final String MCP_PREFIX = "mcp__";

    public AgentRole {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(taskType, "taskType");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("角色 " + id + " 的步数上限必须大于 0");
        }
        tools = List.copyOf(tools == null ? List.of() : tools);
        for (String entry : tools) {
            if (entry.endsWith("*") && !entry.startsWith(MCP_PREFIX)) {
                // 通配只对外挂工具开放，理由见 matches()。写死在构造里而不是靠约定，
                // 是因为这条一旦破了，破的方式一定是某次「顺手加个 * 省事」
                throw new IllegalArgumentException("角色 " + id + " 的白名单里有非法通配 \""
                        + entry + "\"：只有 " + MCP_PREFIX + " 开头的条目可以用 *");
            }
        }
    }

    /**
     * 这个工具在不在本角色的菜单上。
     *
     * <h3>为什么要有通配，又为什么只给 MCP</h3>
     * MCP 工具的名字是 {@code mcp__<server>__<tool>}，而 {@code <server>} 是用户在
     * {@code ~/.myagent/mcp.json} 里自己起的 key，{@code <tool>} 由 server 决定——
     * <b>两截都不在我们手里</b>，没法像内置工具那样把全名写进名册。
     * 不给通配的结果实测过一次：规划器看得见 29 个浏览器工具、规划得头头是道，
     * 执行器按白名单精确匹配一个都没兑现，七步全报「我没这个工具」却全判成功。
     *
     * <p>但通配<b>只对 {@code mcp__} 开放</b>。名册是安全边界（见 {@link AgentRoles}），
     * 「谁能调 bash」必须是代码里一眼看得见的字面量；放开 {@code *} 等于让
     * {@code "b*"} 这种手滑把 bash 递给只读角色。
     */
    public boolean matches(String toolName) {
        if (toolName == null) {
            return false;
        }
        for (String entry : tools) {
            if (entry.endsWith("*")
                    ? toolName.startsWith(entry.substring(0, entry.length() - 1))
                    : entry.equals(toolName)) {
                return true;
            }
        }
        return false;
    }

    /** 这个角色的提示词正文在哪。一个角色一个文件，改哪个角色就只碰哪个文件。 */
    public String promptPath() {
        return "roles/" + id + ".md";
    }

    /**
     * 这个角色的行事规矩，会被填进 {@code prompts/modes/task.md}。
     *
     * <p>找不到就抛，不退化成空字符串——少一段规矩不会报错，
     * 只是这个角色变得和别的角色一个样，而这件事从日志里看不出来。
     */
    public String rules() {
        return REPO.loadRequired(promptPath());
    }
}
