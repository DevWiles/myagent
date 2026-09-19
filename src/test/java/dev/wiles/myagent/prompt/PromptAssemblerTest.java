package dev.wiles.myagent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 提示词装配。
 *
 * <p>这里大部分测试盯的是<b>装配失败</b>而不是装配成功——因为这套东西的价值
 * 就在那两道不变量上：缺段和漏替换都是「不查就永远不会暴露」的问题，
 * 模型只是行为微妙地变差，日志里一切正常。
 */
class PromptAssemblerTest {

    private static PromptAssembler assembler() {
        return PromptAssembler.createDefault();
    }

    private static PromptContext ctx() {
        return PromptContext.builder().variable("workspace", "/tmp/proj").build();
    }

    @Test
    @DisplayName("装出来的是 base + 模式段，关键小标题一个不少")
    void assemblesBasePlusMode() {
        String prompt = assembler().assemble(PromptMode.AGENT, ctx());

        for (String section : PromptAssembler.REQUIRED_SECTIONS) {
            assertTrue(prompt.contains(section), "缺了 " + section);
        }
        assertTrue(prompt.contains("ReAct"), "模式段没拼进来");
        assertTrue(prompt.contains("/tmp/proj"), "工作区没填进去");
    }

    @Test
    @DisplayName("占位符打错名字要当场抛，不能把字面的 {{名字}} 发给模型")
    void unresolvedPlaceholderIsFatal() {
        // 模板里有 {{workspace}}，这里故意只给一个拼错的名字
        PromptContext bad = PromptContext.builder().variable("workspac", "/tmp/proj").build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> assembler().assemble(PromptMode.AGENT, bad));

        assertTrue(e.getMessage().contains("workspace"), "报错要点出是哪个占位符");
    }

    @Test
    @DisplayName("一个变量都不给也要抛——空上下文不等于「这份提示词没有变量」")
    void emptyContextStillChecked() {
        assertThrows(IllegalStateException.class,
                () -> assembler().assemble(PromptMode.AGENT, PromptContext.empty()));
    }

    @Test
    @DisplayName("缺了关键段要抛。少了「语言」那段模型会改说英文，而这件事不会报错")
    void missingRequiredSectionIsFatal() {
        // 用一个假仓库：base.md 里把「语言」那段拿掉
        PromptRepository real = PromptRepository.createDefault();
        PromptSource broken = path -> path.equals("base.md")
                ? real.loadRequired(path).replace("## 语言", "## 随便什么别的标题")
                : real.loadRequired(path);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PromptAssembler(broken).assemble(PromptMode.AGENT, ctx()));

        assertTrue(e.getMessage().contains("## 语言"));
    }

    @Test
    @DisplayName("资源缺失直接抛，不静默退化成空串")
    void missingResourceIsFatal() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PromptRepository.createDefault().loadRequired("modes/根本没有这个.md"));

        assertTrue(e.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("只读模式要明说，不能让模型从「菜单上没有写工具」自己推断")
    void readOnlyIsStated() {
        String normal = assembler().assemble(PromptMode.AGENT, ctx());
        String readOnly = assembler().assemble(PromptMode.AGENT,
                PromptContext.builder().variable("workspace", "/tmp/proj").readOnly(true).build());

        assertFalse(normal.contains("只读模式"));
        assertTrue(readOnly.contains("只读模式"));
        assertTrue(readOnly.contains("不是故障"),
                "得说清这是用户的选择——不然模型会当成工具坏了，转头去 bash 里找重定向");
    }

    @Test
    @DisplayName("记忆索引拼在最后：前面几段恒定，前缀才命中得了缓存")
    void memoryIndexGoesLast() {
        String prompt = assembler().assemble(PromptMode.AGENT, PromptContext.builder()
                .variable("workspace", "/tmp/proj")
                .memoryIndex("## 你记住的事\n\n- x — y")
                .build());

        assertTrue(prompt.indexOf("## 你记住的事") > prompt.indexOf("## 回答风格"),
                "索引每次启动都不一样，放前面等于每轮全价重发整份提示词");
    }

    @Test
    @DisplayName("记忆正文里正好有 {{...}} 也不能把 agent 卡在启动阶段")
    void placeholderInsideMemoryIsNotSubstituted() {
        String prompt = assertDoesNotThrow(() -> assembler().assemble(PromptMode.AGENT,
                PromptContext.builder()
                        .variable("workspace", "/tmp/proj")
                        .memoryIndex("- tpl — 模板语法是 {{name}}")
                        .build()));

        assertTrue(prompt.contains("{{name}}"), "记忆是数据，原样留着");
    }

    @Test
    @DisplayName("两个模式装出来的东西不一样，而且不会互相串")
    void modesAreDistinct() {
        String agent = assembler().assemble(PromptMode.AGENT, ctx());
        String task = assembler().assemble(PromptMode.TASK, PromptContext.builder()
                .variable("workspace", "/tmp/proj")
                .variable("taskType", "写文件")
                .variable("taskRules", "你有写工具。")
                .build());

        assertTrue(agent.contains("ReAct"));
        assertFalse(task.contains("ReAct"),
                "模式是用来选的不是用来叠的——两段同时在场，模型会收到相反的指令");
        assertTrue(task.contains("写文件"));
    }
}
