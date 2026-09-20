package dev.wiles.myagent.plan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内置角色名册。
 *
 * <pre>
 *   scout     探路   PLANNING      只读        看不清，先摸再拆
 *   explorer  调研   FILE_READ     只读        把事实查清楚
 *   reviewer  审查   FILE_READ     只读        对着现成的东西挑毛病
 *   coder     编码   FILE_WRITE    读+写       动手改
 *   operator  执行   COMMAND       读+bash     跑命令
 *   analyst   分析   ANALYSIS      无工具      只基于上游材料想
 *   verifier  验证   VERIFICATION  读+bash     确认真的成了
 * </pre>
 *
 * <p>{@code explorer} 和 {@code reviewer} 挨在一起是有意的：<b>它俩的工具白名单完全一样</b>。
 * 这正是角色存在的理由——如果角色只是工具白名单的别名，那它就只是 {@link TaskType}
 * 换了个名字。区别全在提示词：一个被要求「用事实回答，不要猜」，
 * 另一个被要求「假设这段代码有问题，去找出来」。
 *
 * <h3>名册在代码里，正文在资源文件里</h3>
 * 这个划分是刻意的。名册（有哪些角色、各自能用什么工具、算不算改动世界）是<b>安全边界</b>，
 * 归代码管，能被单测钉死；正文是<b>措辞</b>，归资源文件管，改完重启就生效。
 * 反过来把名册也做成可配置，等于让一个配置文件决定「谁能调 bash」。
 *
 * <h3>类加载时就把不变量查完</h3>
 * id 重不重、六种类型的默认角色齐不齐、每个角色的提示词文件在不在——三样都在静态块里查。
 * 这几种错的共同点是<b>不查就不会暴露</b>：少一个默认角色，只有老存档恢复时才炸；
 * 提示词文件漏打包，只是那个角色变得和别人一个样。宁可启动就失败。
 */
public final class AgentRoles {

    /**
     * 只读工具。单独拎出来是因为 {@code scout}/{@code explorer}/{@code reviewer}
     * 共用它，而「审查用的工具应该和调研一样」这件事，写成共享常量比抄三遍可靠。
     */
    private static final List<String> READ_ONLY =
            List.of("read_file", "list_dir", "grep", "glob");

    public static final AgentRole SCOUT = new AgentRole(
            "scout", "探路",
            "这块现在看不清，得先摸一摸才能拆。它跑完会再拆出一层子任务",
            TaskType.PLANNING, READ_ONLY, 8, false, false);

    public static final AgentRole EXPLORER = new AgentRole(
            "explorer", "调研",
            "查清某件事：读文件、列目录，用事实回答「现在是什么样」",
            TaskType.FILE_READ, READ_ONLY, 8, false, false);

    public static final AgentRole REVIEWER = new AgentRole(
            "reviewer", "审查",
            "对着已经存在的代码挑毛病：边界情况、和既有约定不一致的地方、漏掉的错误处理。"
                    + "它不改任何东西，产出是一份问题清单",
            TaskType.FILE_READ, READ_ONLY, 10, false, false);

    public static final AgentRole CODER = new AgentRole(
            "coder", "编码",
            "新建或修改文件",
            TaskType.FILE_WRITE,
            List.of("read_file", "list_dir", "grep", "glob", "write_file", "create_project"),
            12, true, false);

    public static final AgentRole OPERATOR = new AgentRole(
            "operator", "执行",
            "跑命令：构建、装依赖、执行脚本",
            TaskType.COMMAND, List.of("read_file", "list_dir", "grep", "glob", "bash"),
            12, true, false);

    public static final AgentRole ANALYST = new AgentRole(
            "analyst", "分析",
            "不碰文件系统，把上游任务交过来的材料整理成结论",
            // 一个工具都不给：它该基于上游交来的材料想，而不是自己再去翻一遍。
            // 没有工具的循环第一轮就收口，所以步数上限给三步——留两步的余量是
            // 怕模型在没有工具的情况下先吐一句「让我看看」再答，那会白占一轮
            TaskType.ANALYSIS, List.of(), 3, false, false);

    public static final AgentRole VERIFIER = new AgentRole(
            "verifier", "验证",
            "跑测试或构建，确认前面做的事真的成了",
            TaskType.VERIFICATION, List.of("read_file", "list_dir", "grep", "glob", "bash"),
            12, true, true);

    /**
     * 名册。顺序就是它出现在规划提示词里的顺序，所以<b>按「从看不清到收尾」排</b>，
     * 而不是按字母——模型对列表顺序是有感觉的，乱序等于白白丢掉一点先验。
     */
    private static final List<AgentRole> ALL =
            List.of(SCOUT, EXPLORER, REVIEWER, CODER, OPERATOR, ANALYST, VERIFIER);

    /**
     * 每种类型的默认角色。
     *
     * <p>为什么要显式写一张表，而不是「名册里第一个是这个类型的角色」：
     * {@code FILE_READ} 底下有两个角色，靠顺序决定默认值的话，
     * 哪天有人为了调提示词顺序把 reviewer 挪到前面，所有老存档就会
     * <b>悄悄变成用审查角色去做调研</b>——一次纯粹的排版改动，改掉了运行时行为。
     */
    private static final Map<TaskType, AgentRole> DEFAULTS = Map.of(
            TaskType.PLANNING, SCOUT,
            TaskType.FILE_READ, EXPLORER,
            TaskType.FILE_WRITE, CODER,
            TaskType.COMMAND, OPERATOR,
            TaskType.ANALYSIS, ANALYST,
            TaskType.VERIFICATION, VERIFIER);

    private static final Map<String, AgentRole> BY_ID = new LinkedHashMap<>();

    static {
        for (AgentRole role : ALL) {
            if (BY_ID.put(role.id(), role) != null) {
                throw new IllegalStateException("角色 id 重复: " + role.id());
            }
            // 提示词文件缺了不会有任何症状——那个角色只是变得和别人一样，
            // 而这件事在日志里看不见。在这儿读一次，缺了就起不来
            role.rules();
        }
        for (TaskType type : TaskType.values()) {
            AgentRole fallback = DEFAULTS.get(type);
            if (fallback == null) {
                throw new IllegalStateException("类型 " + type + " 没有默认角色"
                        + "（老存档和只填了 type 的计划恢复时会落到这里，缺一个就炸在那时候）");
            }
            if (fallback.taskType() != type) {
                throw new IllegalStateException("类型 " + type + " 的默认角色是 "
                        + fallback.id() + "，但那个角色声明自己是 " + fallback.taskType());
            }
        }
    }

    private AgentRoles() {
    }

    /** 全部角色，顺序稳定。 */
    public static List<AgentRole> all() {
        return ALL;
    }

    public static Optional<AgentRole> find(String id) {
        return Optional.ofNullable(id == null ? null : BY_ID.get(id.strip()));
    }

    /**
     * 按 id 取角色，没有就抛。
     *
     * <p>错误信息里要带上<b>全部可用角色</b>：这条消息的第一读者是模型——
     * 解析失败会被回灌回去让它改（见 {@code Planner#attempt}），
     * 只说「没有 xxx 这个角色」它只能再猜一次。
     */
    public static AgentRole require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException(
                "没有名为 " + id + " 的角色。可用的角色: " + String.join(", ", BY_ID.keySet())));
    }

    /**
     * 这个类型的默认角色。
     *
     * <p>两条路会走到这儿：老存档里的任务只有 {@code type} 没有 {@code role}，
     * 以及模型偷懒只填了 {@code type}。两种都不该让计划跑不起来。
     */
    public static AgentRole defaultFor(TaskType type) {
        if (type == null) {
            // 走到这儿的都是「只说了类型」的入口，而类型是 null。
            // 抛 IAE 而不是让 Map.get(null) 蹦个 NPE 出来：这是调用方给错了参数，
            // 而 Task 的构造校验全是 IAE，一条路上两种异常没有道理
            throw new IllegalArgumentException("没说类型，也就没法决定派谁去");
        }
        AgentRole role = DEFAULTS.get(type);
        if (role == null) {
            throw new IllegalStateException("类型 " + type + " 没有默认角色");
        }
        return role;
    }

    /** 当前模式下真正派得出去的角色。只读模式里，会动手的那几个规划出来也是白搭。 */
    public static List<AgentRole> runnable(boolean readOnly) {
        return readOnly ? ALL.stream().filter(role -> !role.mutates()).toList() : ALL;
    }

    /** 只读模式下可用的角色 id，拼在打回重写的错误信息里。 */
    public static Set<String> runnableIds(boolean readOnly) {
        Set<String> ids = new LinkedHashSet<>();
        runnable(readOnly).forEach(role -> ids.add(role.id()));
        return ids;
    }
}
