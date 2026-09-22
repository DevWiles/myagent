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
    @DisplayName("别的角色收到的是完成行，不是结论行——两句话问的不是一件事")
    void otherRolesGetTheCompletionContract() {
        Task task = new Task("n1", "填个表单", TaskType.COMMAND, List.of());

        String prompt = TaskPrompt.system(task, ROOT);

        assertTrue(prompt.contains(TaskPrompt.DONE_YES));
        assertTrue(prompt.contains(TaskPrompt.DONE_NO));
        assertFalse(prompt.contains(TaskPrompt.VERDICT_PASS),
                "结论行是「被验的东西过了吗」，别的角色收到它只会跑去评价东西好不好");
    }

    @Test
    @DisplayName("验证角色反过来：只收结论行，不收完成行")
    void verifierDoesNotGetTheCompletionContract() {
        Task task = new Task("n1", "跑测试", TaskType.VERIFICATION, List.of());

        assertFalse(TaskPrompt.system(task, ROOT).contains(TaskPrompt.DONE_YES));
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

    // ── 验收标准与数据流占位符 ──────────────────────────────────

    @Test
    @DisplayName("验收标准要交到子模型手上——它靠这个决定什么时候收口")
    void userPromptCarriesAcceptance() {
        Task task = new Task("n1", "改 README", TaskType.FILE_WRITE, List.of(),
                "README 里写上了构建命令");

        String prompt = TaskPrompt.user(task, List.of());

        assertTrue(prompt.contains("README 里写上了构建命令"), prompt);
        assertTrue(prompt.contains("算完成"), prompt);
    }

    @Test
    @DisplayName("没有验收标准时不留一个空标题")
    void omitsAcceptanceWhenAbsent() {
        Task task = new Task("n1", "随便做点什么", TaskType.ANALYSIS, List.of());

        assertFalse(TaskPrompt.user(task, List.of()).contains("算完成"));
    }

    @Test
    @DisplayName("系统提示词里点明「达到完成标准就收口」")
    void systemPromptMentionsStopCondition() {
        Task task = new Task("n1", "改代码", TaskType.FILE_WRITE, List.of());

        assertTrue(TaskPrompt.system(task, ROOT).contains("完成标准"));
    }

    @Test
    @DisplayName("{{n1}} 被换成上游真正的产出——这才是数据流，不只是先后顺序")
    void resolvesPlaceholders() {
        Task task = new Task("n2", "把 {{n1}} 里的版本号写进 README",
                TaskType.FILE_WRITE, List.of("n1"), "README 写上了版本号");

        String prompt = TaskPrompt.user(task, List.of(done("n1", "pom 里用的是 Java 17")));

        assertFalse(prompt.contains("{{n1}}"), "占位符没被替换: " + prompt);
        assertTrue(prompt.contains("pom 里用的是 Java 17"), prompt);
    }

    @Test
    @DisplayName("acceptance 里的占位符一样要替换")
    void resolvesPlaceholdersInAcceptance() {
        Task task = new Task("n2", "补测试", TaskType.FILE_WRITE, List.of("n1"),
                "覆盖了 {{n1}} 列出的每一种状态");

        String prompt = TaskPrompt.user(task, List.of(done("n1", "一共三种状态")));

        assertFalse(prompt.contains("{{n1}}"), prompt);
        assertTrue(prompt.contains("一共三种状态"), prompt);
    }

    @Test
    @DisplayName("被占位符引用过的上游不再进产出区块——同一段文字塞两遍纯烧 token")
    void inlinedUpstreamIsNotRepeated() {
        Task task = new Task("n3", "按 {{n1}} 改代码", TaskType.FILE_WRITE,
                List.of("n1", "n2"), "改完了");

        String prompt = TaskPrompt.user(task, List.of(
                done("n1", "版本是 Java 17"),
                done("n2", "入口在 Main.java")));

        assertEquals(1, prompt.split("版本是 Java 17", -1).length - 1,
                "n1 的产出出现了不止一次: " + prompt);
        assertTrue(prompt.contains("入口在 Main.java"), "没被引用的上游还是要给: " + prompt);
    }

    @Test
    @DisplayName("上游是 null 列表也不炸")
    void toleratesNullUpstream() {
        Task task = new Task("n1", "干活", TaskType.ANALYSIS, List.of());

        assertDoesNotThrow(() -> TaskPrompt.user(task, null));
    }
}
