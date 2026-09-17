package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskRefsTest {

    /** 造一个已经跑完、带产出的上游。 */
    private static Task finished(String id, String result) {
        Task task = new Task(id, "上游 " + id, TaskType.FILE_READ, List.of());
        task.start();
        task.succeed(result);
        return task;
    }

    @Test
    @DisplayName("认出引用的任务 id，去重并保持出现顺序")
    void findsReferences() {
        assertEquals(List.of("t1", "t2"),
                TaskRefs.referencedIn("先看 {{t1}}，再对照 {{t2}}，最后回头看 {{t1}}"));
    }

    @Test
    @DisplayName("子任务的 id 带点号，一样要认出来")
    void findsDottedIds() {
        assertEquals(List.of("t1.2"), TaskRefs.referencedIn("按 {{t1.2}} 的结论办"));
    }

    @Test
    @DisplayName("花括号里带空格也认——为这个打回重来不划算")
    void toleratesInnerSpaces() {
        assertEquals(List.of("t1"), TaskRefs.referencedIn("看 {{ t1 }}"));
    }

    @Test
    @DisplayName("没有引用、空文本、null 都返回空列表")
    void emptyWhenNoRefs() {
        assertTrue(TaskRefs.referencedIn("就是一句普通的话").isEmpty());
        assertTrue(TaskRefs.referencedIn("").isEmpty());
        assertTrue(TaskRefs.referencedIn(null).isEmpty());
    }

    @Test
    @DisplayName("替换成上游真正的产出，并且界定清楚哪段是材料")
    void resolvesToUpstreamResult() {
        String text = TaskRefs.resolve("按 {{t1}} 改文件",
                List.of(finished("t1", "Java 版本是 17")));

        assertTrue(text.contains("Java 版本是 17"), text);
        assertFalse(text.contains("{{t1}}"), "占位符没被换掉: " + text);
        assertTrue(text.contains("t1 的产出"), text);
    }

    @Test
    @DisplayName("多个占位符各换各的")
    void resolvesEachReference() {
        String text = TaskRefs.resolve("{{t1}} 和 {{t2}}",
                List.of(finished("t1", "甲"), finished("t2", "乙")));

        assertTrue(text.contains("甲") && text.contains("乙"), text);
    }

    @Test
    @DisplayName("上游没产出、或压根找不到时，明说，不留空")
    void neverSilentlyDropsAnInstruction() {
        assertTrue(TaskRefs.resolve("按 {{t1}} 办", List.of(finished("t1", "")))
                .contains("没有给出产出"));
        assertTrue(TaskRefs.resolve("按 {{t9}} 办", List.of(finished("t1", "甲")))
                .contains("不可用"));
        assertTrue(TaskRefs.resolve("按 {{t1}} 办", List.of()).contains("不可用"));
    }

    @Test
    @DisplayName("产出里带 $ 和反斜杠也不会把正则替换搞炸")
    void survivesRegexSpecialsInResult() {
        String text = TaskRefs.resolve("看 {{t1}}",
                List.of(finished("t1", "价格 $10 和路径 C:\\tmp")));

        assertTrue(text.contains("$10"), text);
        assertTrue(text.contains("C:\\tmp"), text);
    }

    @Test
    @DisplayName("没有占位符的文本原样返回")
    void leavesPlainTextAlone() {
        assertEquals("就这样", TaskRefs.resolve("就这样", List.of()));
        assertNull(TaskRefs.resolve(null, List.of()));
    }
}
