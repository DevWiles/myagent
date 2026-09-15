package dev.wiles.myagent.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @BeforeEach
    void setUp() {
        Ansi.setEnabled(false);   // 关掉颜色，就能直接断言「标记有没有被吃掉」
    }

    @AfterEach
    void tearDown() {
        Ansi.setEnabled(true);
    }

    @Test
    @DisplayName("粗体标记被吃掉，只留文字")
    void rendersBold() {
        assertEquals("技术栈: Maven", renderer.renderLine("**技术栈**: Maven"));
        assertEquals("前 中 后", renderer.renderLine("前 **中** 后"));
    }

    @Test
    @DisplayName("行内代码的反引号被吃掉")
    void rendersInlineCode() {
        assertEquals("入口在 Main.java", renderer.renderLine("入口在 `Main.java`"));
        assertEquals("a 和 b", renderer.renderLine("`a` 和 `b`"));
    }

    @Test
    @DisplayName("代码里的星号不能被当成粗体标记")
    void doesNotTouchStarsInsideCode() {
        assertEquals("看 a**b 这个写法", renderer.renderLine("看 `a**b` 这个写法"));
    }

    @Test
    @DisplayName("标题去掉井号")
    void rendersHeadings() {
        assertEquals("总览", renderer.renderLine("# 总览"));
        assertEquals("二级", renderer.renderLine("## 二级"));
        assertEquals("三级", renderer.renderLine("### 三级"));
    }

    @Test
    @DisplayName("无序列表换成圆点，缩进保留")
    void rendersBullets() {
        assertEquals("• 第一项", renderer.renderLine("- 第一项"));
        assertEquals("• 第二项", renderer.renderLine("* 第二项"));
        assertEquals("  ◦ 子项", renderer.renderLine("  - 子项"));
    }

    @Test
    @DisplayName("列表里的行内标记照样渲染")
    void rendersInlineInsideBullets() {
        assertEquals("• Main.java 是入口", renderer.renderLine("- `Main.java` 是入口"));
        assertEquals("• 重点 内容", renderer.renderLine("- **重点** 内容"));
    }

    @Test
    @DisplayName("有序列表保留序号")
    void rendersNumberedList() {
        assertEquals("1. 第一步", renderer.renderLine("1. 第一步"));
        assertEquals("  2) 第二步", renderer.renderLine("  2) 第二步"));
    }

    @Test
    @DisplayName("引用变成竖线")
    void rendersQuote() {
        assertEquals("│ 注意事项", renderer.renderLine("> 注意事项"));
    }

    @Test
    @DisplayName("分隔线变成横线")
    void rendersRule() {
        assertTrue(renderer.renderLine("---").startsWith("─"));
        assertTrue(renderer.renderLine("***").startsWith("─"));
    }

    @Test
    @DisplayName("代码块跨多行，所以渲染器得记住自己在不在块里")
    void tracksCodeBlockState() {
        assertFalse(renderer.inCodeBlock());
        renderer.renderLine("```java");
        assertTrue(renderer.inCodeBlock());
        renderer.renderLine("public class X {}");
        assertTrue(renderer.inCodeBlock());
        renderer.renderLine("```");
        assertFalse(renderer.inCodeBlock());
    }

    @Test
    @DisplayName("代码块内原样输出——代码里的星号和反引号是代码，不是标记")
    void keepsCodeBlockVerbatim() {
        renderer.renderLine("```java");
        assertEquals("int a = b ** c;", renderer.renderLine("int a = b ** c;"));
        assertEquals("  // - 这不是列表", renderer.renderLine("  // - 这不是列表"));
        assertEquals("# 这不是标题", renderer.renderLine("# 这不是标题"));
    }

    @Test
    @DisplayName("reset 清掉未闭合的代码块状态，免得影响下一轮对话")
    void resetClearsState() {
        renderer.renderLine("```");
        assertTrue(renderer.inCodeBlock());
        renderer.reset();
        assertFalse(renderer.inCodeBlock());
    }

    @Test
    @DisplayName("普通文字原样通过")
    void passesPlainText() {
        assertEquals("这是一段普通的话。", renderer.renderLine("这是一段普通的话。"));
        assertEquals("", renderer.renderLine(""));
    }

    @Test
    @DisplayName("开了颜色时确实加了样式，而不是只删标记")
    void appliesStylesWhenColorEnabled() {
        Ansi.setEnabled(true);
        String bold = renderer.renderLine("**重要**");
        assertTrue(bold.indexOf(Ansi.ESC) >= 0);
        assertTrue(bold.contains("重要"));
        assertFalse(bold.contains("**"));
    }
}
