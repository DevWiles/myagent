package dev.wiles.myagent.plan;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 规划阶段的提示词。
 *
 * <p>数量上限、层级上限这些数字全部从 {@link PlanLimits} 插值进来，
 * 绝不在这里手写——提示词说「最多 10 个」而校验按 12 放行（或者反过来）这种不同步，
 * 症状是「模型明明照做了却被程序打回」，很难联想到是两处常量对不上。
 *
 * <h3>示例必须自洽于规则</h3>
 * 这里的 JSON 范例<b>本身就得是一份合格的计划</b>：任务数落在 {@link PlanLimits} 的区间里，
 * 有真正并行的一层，有占位符，每条都带验收标准。理由是模型对示例的模仿远强于对文字规则的服从——
 * 曾经这里给的是一个两任务的骨架，而规则要求至少 {@value PlanLimits#MIN_TASKS} 个，
 * 于是每次规划都要先被打回一轮，白烧一次调用。<b>示例和规则打架时，输的总是规则。</b>
 *
 * <h3>现场信息放 system、目标放 user</h3>
 * 工具清单和目录树对同一个工作区是稳定的，跟在系统提示词里能吃到服务端的前缀缓存；
 * 目标每次都不一样，放 user 才不会把前缀打断。
 */
public final class PlanPrompt {

    private PlanPrompt() {
    }

    public static String system() {
        return system(false, PlanContext.none());
    }

    public static String system(boolean readOnly) {
        return system(readOnly, PlanContext.none());
    }

    /**
     * @param readOnly 当前是只读模式（{@code --readonly}）。必须告诉模型，
     *                 否则它会规划出一堆改文件、跑命令的任务，跑到那儿才发现没工具可用——
     *                 这是第一次真机跑 /plan 时实际踩到的。
     * @param context  现场信息：仓库长什么样、有哪些工具、哪些命令绝对不许用。
     *                 缺了它模型只能靠编和靠探路任务凑，见 {@link PlanContext}。
     */
    public static String system(boolean readOnly, PlanContext context) {
        return """
                你是一个任务规划器。用户会给你一个目标，你的工作是把它拆成一张**任务依赖图**，
                交给别的执行器去跑。你自己不动手做任何事。

                **只输出一个 JSON 对象，不要写任何别的话**，格式如下:

                {
                  "goal": "用一句话复述你对目标的理解",
                  "tasks": [
                    {"id": "t1", "type": "FILE_READ",
                     "description": "读 src/plan/Task.java，弄清它现在的状态机和字段",
                     "acceptance": "说清 Task 有哪几种状态、哪个方法负责标记失败",
                     "dependsOn": []},
                    {"id": "t2", "type": "FILE_READ",
                     "description": "读 src/plan/PlanExecutor.java，弄清任务失败后调度器做了什么",
                     "acceptance": "说清失败之后调度器怎么处理下游任务",
                     "dependsOn": []},
                    {"id": "t3", "type": "FILE_WRITE",
                     "description": "按 {{t1}} 说明的状态机，给 Task 加上重试次数字段和 retry() 方法",
                     "acceptance": "Task 里有重试次数字段和 retry() 方法，状态迁移仍然合法",
                     "dependsOn": ["t1"]},
                    {"id": "t4", "type": "FILE_WRITE",
                     "description": "按 {{t2}} 说明的调度逻辑，让任务失败时先重试，用尽了才往下游传播失败",
                     "acceptance": "失败分支里会先调 retry()，重试用尽才走原来的传播逻辑",
                     "dependsOn": ["t2", "t3"]},
                    {"id": "t5", "type": "VERIFICATION",
                     "description": "跑 mvn -q test，确认改动没弄坏现有测试",
                     "acceptance": "测试全部通过；有失败就指明是哪个用例、报什么错",
                     "dependsOn": ["t4"]}
                  ]
                }

                注意上面这个例子里，t1 和 t2 谁都不依赖谁——**它们本来就该是并列的**。
                能并列的事情别串成一条链。

                任务类型只有这六种:
                - PLANNING     这块现在看不清，得先摸一摸才能拆。它跑完会再拆出一层子任务
                - FILE_READ    读文件、列目录，查清某件事
                - FILE_WRITE   新建或修改文件
                - COMMAND      执行命令
                - ANALYSIS     不碰文件，把上游拿到的材料整理成结论
                - VERIFICATION 跑测试或构建，确认前面做的事真的成了

                规则:
                - 拆成 %d 到 %d 个任务。少于 %d 个说明你没在规划，多于 %d 个是把事情拆碎了。
                - **goal 要用你自己的话复述，不要把用户那句原话抄一遍**。
                  用户就是靠这句话判断你有没有理解偏——照抄等于什么都没说。
                - **dependsOn 只写真正的前置**。B 需要 A 的产出才能做，才连边。
                  仅仅是「先做 A 顺手做 B」不算依赖——把所有任务串成一条链，
                  等于放弃了这张图的全部意义。
                - 依赖的 id 必须在 tasks 里出现过，且不能形成环。
                - **要用上游的产出，就在描述里写 {{任务id}}**，比如 {{t1}}。
                  执行前它会被换成那个任务真正的产出。写了 {{t1}} 就必须把 t1 写进 dependsOn，
                  否则执行时 t1 可能还没跑，那儿换进来的会是一片空白。
                  别写「根据上游的结果」——有三个上游时，执行的人根本不知道你指的是哪个。
                - **每个任务都要有 acceptance**，一句话说清「做到什么算这一步完成」。
                  执行这一步的模型靠它决定什么时候收口：少了它，它要么没做完就宣布成功，
                  要么做完了还在原地打转。
                - description 会被原样交给执行这一步的模型，它看不到你这张图的其余部分，
                  所以每一条都要能独立读懂。
                - 每个任务只做一件事。「改代码并跑测试」应该拆成 FILE_WRITE + VERIFICATION 两个。
                - **不许编造**。没见过的文件路径、类名、命令，不要凭空写进计划。
                  拿不准就先安排一个 FILE_READ 去查，或者用 PLANNING 占位。
                - 看不清的地方用 PLANNING 占位，别硬猜。但 PLANNING 只能再拆一层，
                  它拆出的子任务里不能再有 PLANNING。
                %s%s%s
                """.formatted(PlanLimits.MIN_TASKS, PlanLimits.MAX_TASKS,
                PlanLimits.MIN_TASKS, PlanLimits.MAX_TASKS,
                readOnlyRule(readOnly), forbiddenRule(context), situation(context));
    }

    /** 只读模式下，一半的任务类型根本跑不了，规划时就得知道。 */
    private static String readOnlyRule(boolean readOnly) {
        return readOnly ? """
                - **当前是只读模式**: 只能用 FILE_READ / ANALYSIS / PLANNING 三种类型。
                  改文件、跑命令、跑测试统统做不了，别规划那样的任务——
                  目标要是非改不可，就在 goal 里说清楚「只读模式下做不了」。
                """ : "";
    }

    /**
     * 把「怎么都不会放行的命令」前置到规划阶段。
     *
     * <p>不写这条的代价是具体的：模型规划出一个 {@code sudo apt install} 任务，
     * 跑到那儿被 {@code CommandGuard} 拦死，这一步失败，下游全被连累成 SKIPPED。
     * 一次白跑的规划加一条废掉的依赖链，只为了省这几十个 token。
     */
    private static String forbiddenRule(PlanContext context) {
        return context.hasForbidden() ? """
                - **下面这些命令无论如何都不会被执行**，别规划用到它们的任务，
                  需要提权或改动系统的事情一律绕开或交回给用户去做:
                  %s
                """.formatted(String.join(" ", context.forbiddenCommands())) : "";
    }

    /** 现场信息。没有就整段不出现——空标题比没标题更让模型困惑。 */
    private static String situation(PlanContext context) {
        StringBuilder sb = new StringBuilder();
        if (context.workspaceRoot() != null) {
            sb.append("\n当前工作区: ").append(context.workspaceRoot()).append('\n');
        }
        if (context.hasTools()) {
            sb.append("\n执行器手上有这些工具（只有这些，别规划用不到的能力）:\n")
                    .append(context.tools().stream()
                            .map(t -> "- " + t.name() + ": " + t.summary())
                            .collect(Collectors.joining("\n")))
                    .append('\n');
        }
        if (context.hasTree()) {
            sb.append("""

                    工作区的结构（只列了前几层，看不到的地方安排 FILE_READ 去查，别猜）:

                    ```
                    %s
                    ```
                    """.formatted(context.tree()));
        }
        return sb.toString();
    }

    public static String user(String goal) {
        return "目标: " + goal;
    }

    /** 子规划的系统提示词：同一套格式，但不要 goal、不许再出 PLANNING。 */
    public static String subPlanSystem() {
        return """
                你刚刚摸清了一小块情况，现在要把它拆成几个具体可执行的步骤。

                **只输出一个 JSON 对象，不要写任何别的话**:

                {
                  "tasks": [
                    {"id": "t1.1", "type": "FILE_WRITE",
                     "description": "具体做什么",
                     "acceptance": "做到什么算完成",
                     "dependsOn": []},
                    {"id": "t1.2", "type": "VERIFICATION",
                     "description": "按 {{t1.1}} 改动的范围，跑对应的测试",
                     "acceptance": "相关测试通过",
                     "dependsOn": ["t1.1"]}
                  ]
                }

                任务类型: FILE_READ / FILE_WRITE / COMMAND / ANALYSIS / VERIFICATION。
                **不能用 PLANNING**——你已经在第二层了，不许再往下拆。

                规则:
                - **最多 %d 个**，够用就行，拆出一两步是正常的。你负责的只是一小块，
                  不要在这里把整件事重新规划一遍。
                - dependsOn 只写真正的前置，id 要在这批任务里出现过，不能成环。
                - 要用同批某一步的产出，写 {{那一步的id}}，并把它写进 dependsOn。
                - 每个任务都要有 acceptance，一句话说清做到什么算完成。
                - 每条描述都要能被一个看不到上下文的模型独立读懂。
                - 不许编造没见过的文件路径和类名——你刚摸过的情况里有什么就写什么。
                """.formatted(PlanLimits.MAX_SUB_TASKS);
    }

    // ── 重规划 ──────────────────────────────────────────────────

    /**
     * 计划跑到一半出岔子时的补救提示词。
     *
     * <p><b>为什么重规划是「续」而不是「重来」</b>：失败之前那些成功的步骤是真金白银换来的，
     * 推倒重来等于把它们全部作废，再花一遍钱买同样的东西。所以这里给模型的姿势是
     * 「在已有成果之上接着走」——原计划的产出全部摊给它看，只让它补接下来该做的几步。
     *
     * <p><b>为什么允许它认输</b>：缺权限、缺依赖、需求本身矛盾这类失败，
     * 换几个步骤是绕不过去的。不给认输的出口，模型就会硬凑一批注定跑不通的任务，
     * 白烧一轮调用，还让用户以为系统在努力。明确告诉它可以交白卷，
     * 这时候把「跑到哪儿、为什么停」如实报给用户，比假装还有救有用得多。
     */
    public static String replanSystem() {
        return replanSystem(false);
    }

    public static String replanSystem(boolean readOnly) {
        return """
                你在给一份跑到一半出岔子的计划做补救。

                原计划有一步失败了，依赖它的步骤全被跳过——**那些活还没干**。
                你的工作是：在**已经做成的成果之上**，给出接下来该做的几步，把目标补上。

                **只输出一个 JSON 对象，不要写任何别的话**:

                {
                  "tasks": [
                    {"id": "r1", "type": "FILE_READ",
                     "description": "查清 X 为什么不存在",
                     "acceptance": "说清它是没建还是路径写错了",
                     "dependsOn": []},
                    {"id": "r2", "type": "FILE_WRITE",
                     "description": "按 {{r1}} 查到的实际路径，改 Y",
                     "acceptance": "Y 里引用的路径和实际一致",
                     "dependsOn": ["r1"]}
                  ]
                }

                任务类型: FILE_READ / FILE_WRITE / COMMAND / ANALYSIS / VERIFICATION。
                **不能用 PLANNING**——现在是补救，不是重新摸底。

                规则:
                - **最多 %d 个**。补救是在已有成果上接着走，不是把整件事重做一遍。
                - **id 一律用 r 开头**（r1、r2…），不能和原计划里已有的 id 撞车。
                - **已经做成的事不要重做**。下面会把成功步骤的产出原样给你，直接用。
                - **dependsOn 只能写这批新任务里的 id**。原计划那些任务要么已经跑完
                  （产出都给你了），要么已经废掉——连过去的话，这一步会永远等不到就绪。
                - **失败的那一步不要原样再试一遍**。同样的做法大概率是同样的结果，
                  要么换条路，要么先安排一步去查清它到底为什么失败。
                - 每个任务都要有 acceptance，一句话说清做到什么算完成。
                - 要用这批里某一步的产出，写 {{那一步的id}}，并把它写进 dependsOn。
                - 不许编造没见过的文件路径和类名。
                - **补不了就直说**：如果失败的原因说明这件事在当前条件下根本做不成
                  （缺权限、缺依赖、需求本身矛盾、失败的是环境而不是代码），
                  就输出 {"tasks": []}。硬凑一批注定跑不通的步骤，比交白卷更糟——
                  用户会以为还有救，而其实只是在烧钱。
                %s
                """.formatted(PlanLimits.MAX_REPLAN_TASKS, readOnlyRule(readOnly));
    }

    /**
     * 把现场原样摊给模型：原目标、做成了什么、哪一步倒了、还有哪些活没干。
     *
     * <p>四样缺一不可。缺了「做成了什么」它会重做；缺了「为什么倒的」它会原样再试一遍；
     * 缺了「还有哪些活没干」它只会修那一步，忘掉被连累跳过的那一串。
     */
    public static String replanUser(Plan plan, Task failed, String error) {
        StringBuilder sb = new StringBuilder("原目标: ").append(plan.goal()).append('\n');

        List<Task> succeeded = plan.graph().tasks().stream()
                .filter(t -> t.status() == TaskStatus.SUCCEEDED)
                .toList();
        if (succeeded.isEmpty()) {
            sb.append("\n还没有任何一步跑成功。\n");
        } else {
            sb.append("\n已经做成的步骤（别重做，产出可以直接用）:\n");
            for (Task task : succeeded) {
                sb.append("\n【").append(task.id()).append(' ').append(task.description())
                        .append("】\n")
                        .append(task.result() == null ? "（没有留下产出）" : task.result())
                        .append('\n');
            }
        }

        sb.append("\n失败的步骤: ").append(failed.id()).append(' ')
                .append(failed.description()).append('\n')
                .append("失败原因: ").append(error == null ? "（没有留下原因）" : error).append('\n');

        List<Task> skipped = plan.graph().tasks().stream()
                .filter(t -> t.status() == TaskStatus.SKIPPED)
                .toList();
        if (!skipped.isEmpty()) {
            sb.append("\n被连累跳过、还没干的活:\n");
            for (Task task : skipped) {
                sb.append("- ").append(task.id()).append(' ').append(task.description())
                        .append(task.acceptance() == null ? "" : "（要求: " + task.acceptance() + "）")
                        .append('\n');
            }
        }

        sb.append("\n给出接下来该做的几步。做不成就输出 {\"tasks\": []}。");
        return sb.toString();
    }

    public static String subPlanUser(Task parent, String findings) {
        return """
                你负责的这一步是: %s

                你刚才摸到的情况:
                %s

                把接下来要做的事拆成具体步骤。""".formatted(parent.description(), findings);
    }
}
