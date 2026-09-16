package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskPromptTest {

    private static final Path ROOT = Path.of("/tmp/ws");

    private static Task done(String id, String result) {
        Task t = new Task(id, "描述 " + id, TaskType.FILE_READ, List.of());
        t.start();
        t.succeed(result);
        return t;
    }

    @Test
    @DisplayName("系统提示词交代清楚：这是大计划里的一步，只做自己那份")
    void systemPromptFramesTheSubtask() {
        Task task = new Task("n2", "改 UserService", TaskType.FILE_WRITE, List.of());

        String prompt = TaskPrompt.system(task, ROOT);

        assertTrue(prompt.contains("/tmp/ws"), "得带上工作区路径");
        assertTrue(prompt.contains("子任务"));
        assertTrue(prompt.contains("不要"), "得明确说别顺手干别的任务的活");
    }

    @Test
    @DisplayName("验证任务被要求给出结论行，否则没法判定通过与否")
    void verificationPromptDemandsVerdict() {
        Task task = new Task("n1", "跑测试", TaskType.VERIFICATION, List.of());

        String prompt = TaskPrompt.system(task, ROOT);

        assertTrue(prompt.contains(TaskPrompt.VERDICT_PASS));
        assertTrue(prompt.contains(TaskPrompt.VERDICT_FAIL));
    }

    @Test
    @DisplayName("分析任务被告知没有工具，别去尝试调用")
    void analysisPromptSaysNoTools() {
        Task task = new Task("n1", "总结一下", TaskType.ANALYSIS, List.of());

        assertTrue(TaskPrompt.system(task, ROOT).contains("没有任何工具"));
    }

    @Test
    @DisplayName("没有上游时，用户提示词就是任务本身")
    void userPromptWithoutUpstream() {
        Task task = new Task("n1", "读一下 pom.xml", TaskType.FILE_READ, List.of());

        String prompt = TaskPrompt.user(task, List.of());

        assertTrue(prompt.contains("读一下 pom.xml"));
        assertFalse(prompt.contains("上游"), "没有上游就不该凭空出现这一节");
    }

    @Test
    @DisplayName("上游产出要原样带上，并标明出自哪个任务")
    void userPromptCarriesUpstreamResults() {
        Task task = new Task("n3", "改代码", TaskType.FILE_WRITE, List.of("n1", "n2"));

        String prompt = TaskPrompt.user(task, List.of(
                done("n1", "pom 里用的是 Java 17"),
                done("n2", "入口在 Main.java")));

        assertTrue(prompt.contains("改代码"));
        assertTrue(prompt.contains("pom 里用的是 Java 17"));
        assertTrue(prompt.contains("入口在 Main.java"));
        assertTrue(prompt.indexOf("pom 里用的是 Java 17") < prompt.indexOf("入口在 Main.java"),
                "顺序要跟依赖声明的顺序一致，上下文才稳定");
        assertTrue(prompt.contains("n1") && prompt.contains("n2"));
    }

    @Test
    @DisplayName("上游没有产出也不会拼出个 null 字样来")
    void upstreamWithoutResultDoesNotLeakNull() {
        Task task = new Task("n2", "接着干", TaskType.ANALYSIS, List.of("n1"));
        Task upstream = new Task("n1", "前一步", TaskType.FILE_READ, List.of());

        String prompt = TaskPrompt.user(task, List.of(upstream));

        assertFalse(prompt.contains("null"), "拼出 null 会把模型带偏：" + prompt);
    }
}
