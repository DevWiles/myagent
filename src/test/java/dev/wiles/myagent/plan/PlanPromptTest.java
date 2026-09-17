package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 提示词的测试只能断言两件事：<b>该说的话说了</b>，以及<b>示例本身站得住</b>。
 *
 * <p>后者是这里最值钱的一个用例——把范例从提示词里抠出来，原样喂给
 * {@link PlanParser}。模型对示例的模仿强于对文字规则的服从，示例一旦和规则打架，
 * 输的永远是规则。这条测试让那种打架当场变红，而不是等到线上多烧一轮重试才发现。
 */
class PlanPromptTest {

    /**
     * 从提示词里抠出第一个配平的 JSON 块。
     *
     * <p>不能复用 {@code PlanParser} 那套「第一个 &#123; 到最后一个 &#125;」——
     * 提示词正文里还有 <code>{{t1}}</code> 这样的占位符说明，最后一个右花括号属于它们。
     */
    private static String firstJsonBlock(String prompt) {
        int start = prompt.indexOf('{');
        assertTrue(start >= 0, "提示词里根本没有 JSON 示例");
        int depth = 0;
        for (int i = start; i < prompt.length(); i++) {
            char c = prompt.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return prompt.substring(start, i + 1);
            }
        }
        throw new AssertionError("提示词里的 JSON 示例括号不配平");
    }

    @Test
    @DisplayName("根计划的示例，自己就是一份能通过解析的合法计划")
    void rootExampleIsItselfValid() {
        PlanParser.ParsedPlan parsed = PlanParser.parse(firstJsonBlock(PlanPrompt.system()));

        assertTrue(parsed.tasks().size() >= PlanLimits.MIN_TASKS,
                "示例任务数低于规则要求的下限，模型会照着它违规: " + parsed.tasks().size());
        assertTrue(parsed.tasks().size() <= PlanLimits.MAX_TASKS, "示例任务数超过上限");
        assertFalse(parsed.goal().isBlank());
    }

    @Test
    @DisplayName("示例里每个任务都带 acceptance——规则要求的东西，示例必须做到")
    void everyExampleTaskHasAcceptance() {
        PlanParser.ParsedPlan parsed = PlanParser.parse(firstJsonBlock(PlanPrompt.system()));

        for (Task task : parsed.tasks()) {
            assertNotNull(task.acceptance(), "示例任务 " + task.id() + " 没写 acceptance");
        }
    }

    @Test
    @DisplayName("示例里有真正并列的一层——DAG 的全部价值就在这儿")
    void exampleShowsParallelism() {
        PlanParser.ParsedPlan parsed = PlanParser.parse(firstJsonBlock(PlanPrompt.system()));
        List<List<Task>> layers = TaskGraph.of(parsed.tasks()).layers();

        assertTrue(layers.stream().anyMatch(layer -> layer.size() > 1),
                "示例被拆成了一条链，那模型也会照着拆成一条链");
    }

    @Test
    @DisplayName("示例里演示了占位符，而且引用的任务确实写进了 dependsOn")
    void exampleDemonstratesPlaceholders() {
        String json = firstJsonBlock(PlanPrompt.system());
        assertTrue(json.contains("{{"), "示例里没演示占位符，模型就不会用");

        // 引用与依赖对不上的话，这一句会直接抛——解析器管着这条一致性
        assertDoesNotThrow(() -> PlanParser.parse(json));
    }

    @Test
    @DisplayName("子规划的示例同样是一份能通过解析的子任务列表")
    void subPlanExampleIsValid() {
        Task parent = new Task("t1", "探路", TaskType.PLANNING, List.of());

        List<Task> subTasks = PlanParser.parseSubTasks(
                firstJsonBlock(PlanPrompt.subPlanSystem()), parent);

        assertFalse(subTasks.isEmpty());
        assertTrue(subTasks.size() <= PlanLimits.MAX_SUB_TASKS);
        subTasks.forEach(t -> assertNotNull(t.acceptance(), t.id() + " 没写 acceptance"));
    }

    @Test
    @DisplayName("数量上下限是从 PlanLimits 插进来的，不是手写的数字")
    void limitsComeFromPlanLimits() {
        String prompt = PlanPrompt.system();

        assertTrue(prompt.contains(String.valueOf(PlanLimits.MIN_TASKS)), prompt);
        assertTrue(prompt.contains(String.valueOf(PlanLimits.MAX_TASKS)), prompt);
        assertTrue(PlanPrompt.subPlanSystem().contains(String.valueOf(PlanLimits.MAX_SUB_TASKS)));
    }

    @Test
    @DisplayName("几条关键纪律都在：不许编造、goal 不许照抄、占位符要声明依赖")
    void statesTheRulesThatCostUsRetries() {
        String prompt = PlanPrompt.system();

        assertTrue(prompt.contains("不许编造"), "缺「不许编造」，模型会猜文件名");
        assertTrue(prompt.contains("不要把用户那句原话抄一遍"), "缺 goal 复述要求");
        assertTrue(prompt.contains("dependsOn"), prompt);
    }

    // ── 现场信息 ────────────────────────────────────────────────

    private static PlanContext sampleContext() {
        return new PlanContext(
                "/tmp/proj",
                "src/\n  Main.java\npom.xml",
                List.of(new PlanContext.ToolBrief("read_file", "读文件。")),
                List.of("sudo", "mkfs"));
    }

    @Test
    @DisplayName("现场信息拼进了提示词：工作区、目录树、工具清单")
    void injectsSituation() {
        String prompt = PlanPrompt.system(false, sampleContext());

        assertTrue(prompt.contains("/tmp/proj"), prompt);
        assertTrue(prompt.contains("Main.java"), "目录树没进提示词，模型只能靠编");
        assertTrue(prompt.contains("read_file"), "工具清单没进提示词");
        assertTrue(prompt.contains("读文件。"), prompt);
    }

    @Test
    @DisplayName("禁止命令前置到规划阶段，省掉一条注定跑不通的依赖链")
    void injectsForbiddenCommands() {
        String prompt = PlanPrompt.system(false, sampleContext());

        assertTrue(prompt.contains("sudo"), prompt);
        assertTrue(prompt.contains("mkfs"), prompt);
    }

    @Test
    @DisplayName("没有现场信息时，不留下空标题——空标题比没标题更让模型困惑")
    void omitsEmptySections() {
        String prompt = PlanPrompt.system(false, PlanContext.none());

        assertFalse(prompt.contains("当前工作区:"), prompt);
        assertFalse(prompt.contains("工作区的结构"), prompt);
        assertFalse(prompt.contains("执行器手上有这些工具"), prompt);
    }

    @Test
    @DisplayName("只读模式要明写，否则它会规划一堆跑不了的写任务")
    void mentionsReadOnly() {
        assertTrue(PlanPrompt.system(true).contains("只读模式"));
        assertFalse(PlanPrompt.system(false).contains("只读模式"));
    }
}
