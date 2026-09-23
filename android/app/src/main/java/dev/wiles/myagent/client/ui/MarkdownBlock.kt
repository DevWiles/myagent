package dev.wiles.myagent.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes

/**
 * assistant 回答的正文渲染：markdown + 代码块高亮。
 *
 * <p>高亮选型和 TUI 一致的结论：不是 tree-sitter 那一挂（devlog 2026-09-21 那次
 * 调研已经把这个前提推翻了）。这里用 markdown-renderer 自带的 code 模块
 * （底下是 dev.snipme/highlights，纯 Kotlin），一处 wrapper，将来要换只动这个文件。
 *
 * <p>字：正文衬线（claude.ai 的做法），标题也衬线但压小——库默认拿 display 字阶当 h1，
 * 放在聊天里像海报。代码块带顶条（语言名 + 复制），高亮主题**跟 app 的深浅走**，
 * 库默认跟系统，两者不一致时会深底配浅色高亮。
 */
@Composable
fun MarkdownBlock(text: String, modifier: Modifier = Modifier, style: TextStyle = ReplyText) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val highlights = remember(dark) { Highlights.Builder().theme(SyntaxThemes.atom(darkMode = dark)) }
    val heading = style.copy(fontWeight = FontWeight.SemiBold)
    val code = CodeText.copy(color = scheme.onSurface)

    Markdown(
        content = text,
        modifier = modifier,
        colors = markdownColor(
            text = scheme.onBackground,
            codeBackground = AgentColors.codeBg,
            inlineCodeBackground = AgentColors.codeBg,
            dividerColor = scheme.outlineVariant,
        ),
        typography = markdownTypography(
            h1 = heading.copy(fontSize = 22.sp, lineHeight = 30.sp),
            h2 = heading.copy(fontSize = 20.sp, lineHeight = 28.sp),
            h3 = heading.copy(fontSize = 18.sp, lineHeight = 26.sp),
            h4 = heading.copy(fontSize = 17.sp),
            h5 = heading,
            h6 = heading,
            text = style,
            code = code,
            // 行内代码压小一号：等宽字比衬线宽，同号会显得又大又散
            inlineCode = style.copy(fontFamily = Mono, fontSize = style.fontSize * 0.82f),
            quote = style.copy(color = scheme.onSurfaceVariant),
            paragraph = style,
            ordered = style,
            bullet = style,
            list = style,
            textLink = TextLinkStyles(
                SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline),
            ),
        ),
        components = markdownComponents(
            codeBlock = {
                MarkdownHighlightedCodeBlock(it.content, it.node, it.typography.code, highlights, showHeader = true)
            },
            codeFence = {
                MarkdownHighlightedCodeFence(it.content, it.node, it.typography.code, highlights, showHeader = true)
            },
        ),
    )
}
