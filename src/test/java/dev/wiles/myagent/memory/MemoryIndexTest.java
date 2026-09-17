package dev.wiles.myagent.memory;

import dev.wiles.myagent.agent.SystemPrompt;
import dev.wiles.myagent.context.TokenEstimator;
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
    @DisplayName("一条记忆都没有时必须明说，不能沉默——沉默会让模型去猜记忆的名字")
    void emptyIndexSaysSoOutLoud() {
        String rendered = MemoryIndex.empty().render();

        // 这条是 2026-09-17 第一次真跑抓到的：提示词里不提记忆现状，
        // 而 recall 又挂在菜单上，模型就拿 RememberTool 说明里的举例现编了一个
        // build-uses-bun 去 recall，盘上根本没有这条
        assertFalse(rendered.isBlank(), "空索引保持沉默 = 模型只能猜记忆的名字");
        assertTrue(rendered.contains("recall"), "得点名是哪个工具不能瞎调: " + rendered);
        assertTrue(rendered.contains("举例") || rendered.contains("例子"),
                "得说清工具说明里的名字只是举例: " + rendered);
        assertTrue(MemoryIndex.empty().isEmpty());
    }

    @Test
    @DisplayName("压根不接记忆功能时一个字都不提——那条路（计划任务）连 recall 工具都没有")
    void promptSaysNothingWhenMemoryIsNotWiredAtAll() {
        Path ws = Path.of("/tmp/proj");

        assertEquals(SystemPrompt.forWorkspace(ws), SystemPrompt.forWorkspace(ws, null));
        assertFalse(SystemPrompt.forWorkspace(ws).contains("recall"),
                "TaskTools 白名单里没有 recall，提它只会让模型去找一个调不到的工具");
    }

    @Test
    @DisplayName("接了记忆但还没记过东西，提示词必须比不接的时候多出那句话")
    void promptDiffersWhenMemoryIsWiredButEmpty() {
        Path ws = Path.of("/tmp/proj");

        assertNotEquals(SystemPrompt.forWorkspace(ws),
                SystemPrompt.forWorkspace(ws, MemoryIndex.empty()),
                "接了记忆功能却和没接时一字不差，等于没告诉模型它有记忆这回事");
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

    // ── token 预算 ──────────────────────────────────────────────

    /** 一条写满 {@link MemoryEntry#MAX_DESCRIPTION} 的中文记忆——最坏情况长这样。 */
    private static MemoryEntry fatEntry(int i) {
        return entry("mem-" + i, "长".repeat(MemoryEntry.MAX_DESCRIPTION));
    }

    @Test
    @DisplayName("索引报出的 token 数，必须就是它渲染出来那段文字的 token 数")
    void tokensMatchWhatIsActuallyRendered() {
        MemoryIndex index = MemoryIndex.of(List.of(entry("a", "甲"), entry("b", "乙")));

        // 算的和发的是不是同一个东西——漏算表头或那段「先 recall 再下结论」的尾巴，
        // 预算就会系统性偏小，而这种错不会有任何症状，只会悄悄超支
        assertEquals(TokenEstimator.estimate(index.render()), index.estimatedTokens());
    }

    @Test
    @DisplayName("空索引那句话要小——它换的是一次瞎猜的 recall，不能反过来变成负担")
    void emptyNoticeStaysCheap() {
        int cost = MemoryIndex.empty().estimatedTokens();

        assertTrue(cost > 0, "现在它有内容了");
        assertTrue(cost < 100, "一句话而已，超过 100 token 说明写跑题了: " + cost);
    }

    @Test
    @DisplayName("预算够用时原样不动，不能白裁")
    void keepsEverythingWhenItFits() {
        MemoryIndex index = MemoryIndex.of(List.of(entry("a", "甲"), entry("b", "乙")));

        assertEquals(2, index.withinBudget(MemoryIndex.MAX_TOKENS).size());
    }

    @Test
    @DisplayName("条数没超上限，但字数超了预算，照样得裁——这是原来漏掉的那种超支")
    void cutsByTokensNotByCount() {
        // 100 条只有 MemoryStore.MAX_ENTRIES(200) 的一半，靠数条数永远发现不了
        List<MemoryEntry> fat = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fat.add(fatEntry(i));
        }
        MemoryIndex full = MemoryIndex.of(fat);
        assertTrue(full.estimatedTokens() > MemoryIndex.MAX_TOKENS,
                "100 条写满的记忆本来就该超预算，否则这个测试没测到东西");

        MemoryIndex fitted = full.withinBudget(MemoryIndex.MAX_TOKENS);

        assertTrue(fitted.size() < full.size(), "超预算了就必须裁");
        assertTrue(fitted.estimatedTokens() <= MemoryIndex.MAX_TOKENS,
                "裁完还超预算 = 白裁了: " + fitted.estimatedTokens());
    }

    @Test
    @DisplayName("裁的是尾巴，留下的是前缀——顺序稳定，提示词才有缓存命中的可能")
    void keepsThePrefix() {
        List<MemoryEntry> fat = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fat.add(fatEntry(i));
        }

        MemoryIndex fitted = MemoryIndex.of(fat).withinBudget(MemoryIndex.MAX_TOKENS);

        assertEquals("mem-0", fitted.entries().get(0).name());
        assertEquals("mem-" + (fitted.size() - 1),
                fitted.entries().get(fitted.size() - 1).name());
    }

    @Test
    @DisplayName("预算小到一条都放不下时不炸，也不硬塞——退化成「没有可用的记忆」")
    void survivesAnAbsurdBudget() {
        MemoryIndex fitted = MemoryIndex.of(List.of(entry("a", "甲"))).withinBudget(5);

        assertTrue(fitted.isEmpty());
        // 措辞是「没有可用的」而不是「没记过」，这里正是那个措辞要站住的场合：
        // 记忆是存在的，只是放不进预算
        assertFalse(fitted.render().contains("- a —"), "放不下就不该出现在索引里");
    }
}
