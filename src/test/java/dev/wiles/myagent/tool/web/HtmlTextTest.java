package dev.wiles.myagent.tool.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlTextTest {

    @Test
    @DisplayName("script/style/注释整块剥掉，正文的字一个不少")
    void dropsNonContent() {
        String html = """
                <html><head><title>t</title><style>.a{color:red}</style></head>
                <body><!-- 注释 --><script>alert("x")</script><p>正文在这里</p></body></html>""";
        String text = HtmlText.text(html);
        assertTrue(text.contains("正文在这里"));
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("color:red"));
        assertFalse(text.contains("注释"));
    }

    @Test
    @DisplayName("块级标签变换行，行内标签直接抹掉")
    void blockTagsBecomeNewlines() {
        String text = HtmlText.text("<p>第一段有<b>加粗</b></p><p>第二段</p>");
        assertEquals("第一段有加粗\n\n第二段", text);
    }

    @Test
    @DisplayName("列表项带上「- 」前缀，条目边界看得见")
    void listItemsKeepBullets() {
        String text = HtmlText.text("<ul><li>甲</li><li>乙</li></ul>");
        assertTrue(text.contains("- 甲"));
        assertTrue(text.contains("- 乙"));
    }

    @Test
    @DisplayName("常见实体和数字实体都能解，认不出的原样保留")
    void decodesEntities() {
        assertEquals("a & b < c", HtmlText.decodeEntities("a &amp; b &lt; c"));
        assertEquals("中", HtmlText.decodeEntities("&#20013;"));
        assertEquals("A", HtmlText.decodeEntities("&#x41;"));
        assertEquals("&foobar;", HtmlText.decodeEntities("&foobar;"));
    }

    @Test
    @DisplayName("解转义在剥标签之后：&lt;script&gt; 变出来的只是文字，不是结构")
    void entityDecodedAfterStripping() {
        String text = HtmlText.text("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>");
        // 字还在（它是正文），但没有被当成 script 块整段剥掉
        assertTrue(text.contains("<script>alert(1)</script>"));
    }

    @Test
    @DisplayName("title() 取页面标题并压成一行")
    void extractsTitle() {
        assertEquals("首页 — 文档", HtmlText.title(
                "<html><head><title>  首页 &mdash;\n 文档 </title></head></html>"));
        assertEquals("", HtmlText.title("<html><body>没有标题</body></html>"));
    }

    @Test
    @DisplayName("连续空行最多留一个，行内空白并成一个空格")
    void collapsesWhitespace() {
        String text = HtmlText.text("<div>a</div>\n\n\n\n<div>b   c</div>");
        assertEquals("a\n\nb c", text);
    }

    @Test
    @DisplayName("flatten() 把 HTML 片段压成一行字")
    void flattensFragments() {
        assertEquals("加粗 和 链接", HtmlText.flatten("<b>加粗</b>\n 和 <a href=\"x\">链接</a>"));
    }
}
