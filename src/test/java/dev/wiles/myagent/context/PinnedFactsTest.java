package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 钉住块：从记录里直接算，不经过模型。
 *
 * 这些断言的意义在于——它们是压缩「关键信息不丢」的<b>唯一硬保证</b>。
 * 摘要提示词里那四条是请模型自觉，没有代码检查；这里的每一条都是算出来的。
 */
class PinnedFactsTest {

    private static final Set<String> WRITERS = Set.of("write_file", "create_project");

    @Test
    @DisplayName("最初的要求就是记录里第一条 user 消息，不用模型转述")
    void goalIsFirstUserMessage() {
        var facts = PinnedFacts.of(List.of(
                ChatMessage.user("把 README 里的测试数改过来"),
                ChatMessage.assistant("好"),
                ChatMessage.user("顺便把封面也重生成一下")), WRITERS);

        assertEquals("把 README 里的测试数改过来", facts.goal());
    }

    @Test
    @DisplayName("write_file 的顶层 path 要收进来")
    void collectsTopLevelPath() {
        var facts = PinnedFacts.of(List.of(
                ChatMessage.user("改文件"),
                ChatMessage.assistant(null, List.of(ToolCall.of(
                        "c1", "write_file", "{\"path\":\"src/Main.java\",\"content\":\"x\"}")))),
                WRITERS);

        assertEquals(List.of("src/Main.java"), facts.files());
    }

    @Test
    @DisplayName("create_project 的 files[].path 嵌在数组里，照样要收到")
    void collectsNestedPaths() {
        var facts = PinnedFacts.of(List.of(
                ChatMessage.user("搭个骨架"),
                ChatMessage.assistant(null, List.of(ToolCall.of("c1", "create_project",
                        "{\"dirs\":[\"src\"],\"files\":[{\"path\":\"pom.xml\",\"content\":\"a\"},"
                                + "{\"path\":\"src/App.java\",\"content\":\"b\"}]}")))),
                WRITERS);

        assertEquals(List.of("pom.xml", "src/App.java"), facts.files());
    }

    @Test
    @DisplayName("只认传进来的那几个工具——读文件不算改文件")
    void ignoresNonWritingTools() {
        var facts = PinnedFacts.of(List.of(
                ChatMessage.user("看看"),
                ChatMessage.assistant(null, List.of(
                        ToolCall.of("c1", "read_file", "{\"path\":\"pom.xml\"}"),
                        ToolCall.of("c2", "list_dir", "{\"path\":\"src\"}")))),
                WRITERS);

        assertTrue(facts.files().isEmpty(), "读过的文件不该出现在「动过的文件」里");
    }

    @Test
    @DisplayName("同一个文件改了三遍只算一个，且保持首次出现的顺序")
    void dedupesKeepingOrder() {
        var facts = PinnedFacts.of(List.of(
                ChatMessage.user("改"),
                ChatMessage.assistant(null, List.of(
                        ToolCall.of("c1", "write_file", "{\"path\":\"b.txt\"}"),
                        ToolCall.of("c2", "write_file", "{\"path\":\"a.txt\"}"),
                        ToolCall.of("c3", "write_file", "{\"path\":\"b.txt\"}")))),
                WRITERS);

        assertEquals(List.of("b.txt", "a.txt"), facts.files());
    }

    @Test
    @DisplayName("参数是半截 JSON 时跳过这一条，绝不把窗口组装带崩")
    void survivesMalformedArguments() {
        var facts = PinnedFacts.of(List.of(
                ChatMessage.user("改"),
                ChatMessage.assistant(null, List.of(
                        ToolCall.of("c1", "write_file", "{\"path\":\"good.txt\"}"),
                        ToolCall.of("c2", "write_file", "{\"path\":\"trunc"),
                        ToolCall.of("c3", "write_file", "")))),
                WRITERS);

        assertEquals(List.of("good.txt"), facts.files(), "坏的跳过，好的照收");
    }

    @Test
    @DisplayName("什么都没有时 render 返回 null——一句废话不值得每轮都付钱")
    void emptyRendersNothing() {
        assertNull(PinnedFacts.of(List.of(), WRITERS).render());
        assertNull(new PinnedFacts(null, List.of()).render());
    }

    @Test
    @DisplayName("文件太多时截断并说明还有多少，别让清单本身变成负担")
    void capsFileList() {
        var many = new java.util.ArrayList<String>();
        for (int i = 0; i < PinnedFacts.MAX_FILES + 7; i++) {
            many.add("f" + i + ".txt");
        }
        String rendered = new PinnedFacts("目标", many).render().content();

        assertTrue(rendered.contains("另有 7 个"), "要说清楚省了多少");
        assertFalse(rendered.contains("f" + (PinnedFacts.MAX_FILES + 1) + ".txt"));
    }

    @Test
    @DisplayName("渲染出来是 user 消息，且写明它不随压缩丢失")
    void rendersAsUserMessage() {
        var message = new PinnedFacts("修 bug", List.of("a.java")).render();

        assertEquals(ChatMessage.ROLE_USER, message.role());
        assertTrue(message.content().contains("修 bug"));
        assertTrue(message.content().contains("a.java"));
    }

    @Test
    @DisplayName("合并时老的目标优先——它才是这段会话真正的起点")
    void mergeKeepsOriginalGoal() {
        var merged = new PinnedFacts("最初的", List.of("a")).merge(
                new PinnedFacts("后来的", List.of("a", "b")));

        assertEquals("最初的", merged.goal());
        assertEquals(List.of("a", "b"), merged.files());
    }
}
