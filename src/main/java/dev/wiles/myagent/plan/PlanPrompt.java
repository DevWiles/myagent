package dev.wiles.myagent.plan;

/**
 * 规划阶段的提示词。
 *
 * <p>数量上限、层级上限这些数字全部从 {@link PlanLimits} 插值进来，
 * 绝不在这里手写——提示词说「最多 10 个」而校验按 12 放行（或者反过来）这种不同步，
 * 症状是「模型明明照做了却被程序打回」，很难联想到是两处常量对不上。
 */
public final class PlanPrompt {

    private PlanPrompt() {
    }

    public static String system() {
        return system(false);
    }

    /**
     * @param readOnly 当前是只读模式（{@code --readonly}）。必须告诉模型，
     *                 否则它会规划出一堆改文件、跑命令的任务，跑到那儿才发现没工具可用——
     *                 这是第一次真机跑 /plan 时实际踩到的。
     */
    public static String system(boolean readOnly) {
        return """
                你是一个任务规划器。用户会给你一个目标，你的工作是把它拆成一张**任务依赖图**，
                交给别的执行器去跑。你自己不动手做任何事。

                **只输出一个 JSON 对象，不要写任何别的话**，格式如下:

                {
                  "goal": "用一句话复述你对目标的理解",
                  "tasks": [
                    {"id": "t1", "type": "FILE_READ", "description": "读 pom.xml 弄清 Java 版本", "dependsOn": []},
                    {"id": "t2", "type": "FILE_WRITE", "description": "在 X 处加上 Y", "dependsOn": ["t1"]}
                  ]
                }

                任务类型只有这六种:
                - PLANNING     这块现在看不清，得先摸一摸才能拆。它跑完会再拆出一层子任务
                - FILE_READ    读文件、列目录，查清某件事
                - FILE_WRITE   新建或修改文件
                - COMMAND      执行命令
                - ANALYSIS     不碰文件，把上游拿到的材料整理成结论
                - VERIFICATION 跑测试或构建，确认前面做的事真的成了

                规则:
                - 拆成 %d 到 %d 个任务。少于 %d 个说明你没在规划，多于 %d 个是把事情拆碎了。
                - **dependsOn 只写真正的前置**。B 需要 A 的产出才能做，才连边。
                  仅仅是「先做 A 顺手做 B」不算依赖——把所有任务串成一条链，
                  等于放弃了这张图的全部意义。
                - 依赖的 id 必须在 tasks 里出现过，且不能形成环。
                - description 会被原样交给执行这一步的模型，它看不到你这张图的其余部分，
                  所以每一条都要能独立读懂。
                - 每个任务只做一件事。「改代码并跑测试」应该拆成 FILE_WRITE + VERIFICATION 两个。
                - 看不清的地方用 PLANNING 占位，别硬猜。但 PLANNING 只能再拆一层，
                  它拆出的子任务里不能再有 PLANNING。
                %s
                """.formatted(PlanLimits.MIN_TASKS, PlanLimits.MAX_TASKS,
                PlanLimits.MIN_TASKS, PlanLimits.MAX_TASKS, readOnlyRule(readOnly));
    }

    /** 只读模式下，一半的任务类型根本跑不了，规划时就得知道。 */
    private static String readOnlyRule(boolean readOnly) {
        return readOnly ? """
                - **当前是只读模式**: 只能用 FILE_READ / ANALYSIS / PLANNING 三种类型。
                  改文件、跑命令、跑测试统统做不了，别规划那样的任务——
                  目标要是非改不可，就在 goal 里说清楚「只读模式下做不了」。""" : "";
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
                    {"id": "t1.1", "type": "FILE_WRITE", "description": "具体做什么", "dependsOn": []}
                  ]
                }

                任务类型: FILE_READ / FILE_WRITE / COMMAND / ANALYSIS / VERIFICATION。
                **不能用 PLANNING**——你已经在第二层了，不许再往下拆。

                规则:
                - **最多 %d 个**，够用就行，拆出一两步是正常的。你负责的只是一小块，
                  不要在这里把整件事重新规划一遍。
                - dependsOn 只写真正的前置，id 要在这批任务里出现过，不能成环。
                - 每条描述都要能被一个看不到上下文的模型独立读懂。
                """.formatted(PlanLimits.MAX_SUB_TASKS);
    }

    public static String subPlanUser(Task parent, String findings) {
        return """
                你负责的这一步是: %s

                你刚才摸到的情况:
                %s

                把接下来要做的事拆成具体步骤。""".formatted(parent.description(), findings);
    }
}
