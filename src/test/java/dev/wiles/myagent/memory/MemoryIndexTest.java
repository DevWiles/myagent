package dev.wiles.myagent.memory;

import dev.wiles.myagent.agent.SystemPrompt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryIndexTest {

    private static MemoryEntry entry(String name, String description) {
        return new MemoryEntry(name, description, "正文", MemoryScope.PROJECT,
                MemorySource.OBSERVATION, LocalDate.now());
    }

    @Test
    @DisplayName("空索引渲染成空串——没记过东西时一个 token 都不该多花")
    void emptyRendersToNothing() {
        assertEquals("", MemoryIndex.empty().render());
        assertTrue(MemoryIndex.empty().isEmpty());
    }

    @Test
    @DisplayName("没有记忆时，系统提示词和加记忆功能之前一字不差")
    void promptUnchangedWithoutMemories() {
        Path ws = Path.of("/tmp/proj");
        assertEquals(SystemPrompt.forWorkspace(ws),
                SystemPrompt.forWorkspace(ws, MemoryIndex.empty()));
        assertEquals(SystemPrompt.forWorkspace(ws), SystemPrompt.forWorkspace(ws, null));
    }

    @Test
    @DisplayName("每条记忆在索引里占一行，name 和 description 都在")
    void oneLinePerEntry() {
        String rendered = MemoryIndex.of(List.of(
                entry("mvn-offline", "跑测试要加 -o"),
                entry("prefers-cn", "始终用中文回复"))).render();

        assertTrue(rendered.contains("- mvn-offline — 跑测试要加 -o"));
        assertTrue(rendered.contains("- prefers-cn — 始终用中文回复"));
    }

    @Test
    @DisplayName("索引必须告诉模型「这只是摘要，要 recall 读正文」")
    void tellsModelToRecallBeforeActing() {
        String rendered = MemoryIndex.of(List.of(entry("x", "某件事"))).render();
        assertTrue(rendered.contains("recall"), "不提 recall 的话，模型不知道正文能读");
        assertTrue(rendered.contains("摘要"), "不说清是摘要，模型会拿这一行当完整事实用");
    }

    @Test
    @DisplayName("索引拼在系统提示词尾部，原有内容一个字不动")
    void appendsToPromptWithoutDisturbingIt() {
        Path ws = Path.of("/tmp/proj");
        String bare = SystemPrompt.forWorkspace(ws);
        String withMemory = SystemPrompt.forWorkspace(ws,
                MemoryIndex.of(List.of(entry("mvn-offline", "跑测试要加 -o"))));

        assertTrue(withMemory.startsWith(bare), "记忆索引必须是追加，不能改动原提示词");
        assertTrue(withMemory.contains("mvn-offline"));
    }

    @Test
    @DisplayName("description 带换行会被压成一行——索引是一行一条")
    void descriptionIsForcedToOneLine() {
        MemoryEntry e = entry("x", "第一行\n第二行\n第三行");
        assertFalse(e.description().contains("\n"));
        assertFalse(MemoryIndex.of(List.of(e)).render().contains("第一行\n第二行"));
    }

    @Test
    @DisplayName("description 过长会截断——它要乘以对话轮数变成账单")
    void descriptionIsCapped() {
        MemoryEntry e = entry("x", "长".repeat(MemoryEntry.MAX_DESCRIPTION * 2));
        assertEquals(MemoryEntry.MAX_DESCRIPTION, e.description().length());
        assertTrue(e.description().endsWith("…"));
    }

    @Test
    @DisplayName("name 被归一化：大小写、空格、下划线都统一，否则写进去的和查出来的对不上")
    void nameIsNormalized() {
        assertEquals("mvn-offline", MemoryEntry.normalizeName("Mvn Offline"));
        assertEquals("mvn-offline", MemoryEntry.normalizeName("  MVN_OFFLINE  "));
        assertEquals("mvn-offline", MemoryEntry.normalizeName("mvn--offline"));
        assertEquals("build-用-bun", MemoryEntry.normalizeName("build 用 bun"));
    }

    @Test
    @DisplayName("name 或 description 为空时当场拒绝，不写一条没法用的记忆进去")
    void rejectsUnusableEntries() {
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.normalizeName(""));
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.normalizeName("!!!"));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryEntry("x", "  ", "正文", null, null, null));
    }
}
