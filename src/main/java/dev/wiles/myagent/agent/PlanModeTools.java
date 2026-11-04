package dev.wiles.myagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.prompt.PromptRepository;
import dev.wiles.myagent.tool.Args;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;

import java.util.List;

/**
 * 进出 plan 模式的两个工具，照 Claude Code 的 {@code EnterPlanMode} / {@code ExitPlanMode}。
 *
 * <h3>模型提议，用户拍板</h3>
 * 「这件事该不该先规划」不按字面猜（误判一次就是一道多余的确认），也不全交给用户记得按 Shift+Tab：
 * 模型看着任务觉得该先摸清楚再动手时，调 {@code enter_plan_mode} <b>提议</b>，用户同意才进。
 * 计划写好了调 {@code exit_plan_mode} 交上来，用户批了才出——出了就是同一个 agent 照着做，
 * 不另起一套执行器。
 *
 * <h3>否决不是失败</h3>
 * 用户说「不用规划」或者「计划不行，改成……」都是正常答复，作为工具结果原话交还给模型：
 * 前者它就直接做，后者它就留在 plan 模式里改计划再交。
 */
public final class PlanModeTools {

    private static final PromptRepository REPO = PromptRepository.createDefault();
    /** plan 模式的规矩。进模式时作为工具结果交给模型，用户手动切进来时拼在那句话前面。 */
    public static final String RULES = "policy/plan-mode.md";

    static {
        REPO.loadRequired(RULES);
    }

    /** 问用户的是哪件事。 */
    public enum Ask { ENTER, EXIT }

    /** 用户的答复。{@code feedback} 是否决时附的话，可能为空。 */
    public record Answer(boolean approved, String feedback) {
        public Answer {
            feedback = feedback == null ? "" : feedback.strip();
        }
    }

    /** 去问用户。阻塞到有答复为止；没人能问（界面全断了）时按否决。 */
    @FunctionalInterface
    public interface Reviewer {
        Answer review(Ask ask, String plan);
    }

    private PlanModeTools() {
    }

    /** plan 模式的规矩全文。 */
    public static String rules() {
        return REPO.loadRequired(RULES).strip();
    }

    public static List<Tool> create(PlanMode mode, Reviewer reviewer) {
        return List.of(new Enter(mode, reviewer), new Exit(mode, reviewer));
    }

    private record Enter(PlanMode mode, Reviewer reviewer) implements Tool {
        @Override
        public String name() {
            return PlanMode.ENTER_TOOL;
        }

        @Override
        public String description() {
            return """
                    提议先进 plan 模式：只读调研、写出计划、用户批准后再动手。要用户同意才会进。

                    什么时候用（动手之前就该想到）：
                    - 有好几种做得通的做法，选哪种会明显影响结果，而用户没说
                    - 改动面大：要动很多文件、改接口或数据结构、牵涉架构上的取舍
                    - 需求不清楚，得先读代码摸清现状才知道该怎么做
                    - 用户的要求里有「先看看」「出个方案」「别急着改」这类意思

                    什么时候别用：
                    - 简单任务：改一两处、修一个定位清楚的 bug、加一个小函数
                    - 用户已经把怎么做说得很具体了
                    - 纯问答、查资料、解释代码——这些本来就不改东西
                    """;
        }

        @Override
        public String parametersSchema() {
            return """
                    {"type": "object", "properties": {}}""";
        }

        @Override
        public ToolResult execute(JsonNode args) {
            if (mode.on()) {
                return ToolResult.error("已经在 plan 模式里了。调研完把计划交给 " + PlanMode.EXIT_TOOL);
            }
            Answer answer = reviewer.review(Ask.ENTER, "");
            if (!answer.approved()) {
                return ToolResult.ok("用户不想进 plan 模式，直接做。"
                        + (answer.feedback().isEmpty() ? "" : "\n用户的话：" + answer.feedback()));
            }
            mode.set(true);
            return ToolResult.ok("用户同意了，现在是 plan 模式。\n\n" + rules());
        }
    }

    private record Exit(PlanMode mode, Reviewer reviewer) implements Tool {
        @Override
        public String name() {
            return PlanMode.EXIT_TOOL;
        }

        @Override
        public String description() {
            return """
                    plan 模式下调研完了，把计划交给用户批准。批准后退出 plan 模式，你接着照计划动手。

                    只在计划已经能落地时调：要改哪些文件、各改什么、按什么顺序、怎么验证。
                    还有拿不准、需要用户选的地方，先在回复里问清楚，别把疑问塞进计划里交上来。
                    纯调研、不打算改东西的任务不用调它——直接回答就行。""";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "plan": {"type": "string", "description": "计划全文，Markdown。用户会逐字读它来决定批不批，写清楚、写短"}
                      },
                      "required": ["plan"]
                    }
                    """;
        }

        @Override
        public ToolResult execute(JsonNode args) {
            String plan = Args.requireString(args, "plan").strip();
            if (plan.isEmpty()) {
                throw new IllegalArgumentException("plan 不能是空的");
            }
            if (!mode.on()) {
                return ToolResult.error("现在不在 plan 模式，不需要批准，直接做");
            }
            Answer answer = reviewer.review(Ask.EXIT, plan);
            if (!answer.approved()) {
                return ToolResult.ok("用户没有批准这份计划，还在 plan 模式。"
                        + (answer.feedback().isEmpty()
                                ? "问问用户哪里不行，或者重新想一下再交。"
                                : "\n用户的意见：" + answer.feedback() + "\n按意见改好计划再调 " + PlanMode.EXIT_TOOL + "。"));
            }
            mode.set(false);
            return ToolResult.ok("用户批准了计划，已退出 plan 模式，写文件和跑命令的工具都回来了。"
                    + "现在照计划动手，做完验证一遍。");
        }
    }
}
