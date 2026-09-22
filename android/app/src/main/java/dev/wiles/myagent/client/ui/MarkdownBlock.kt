package dev.wiles.myagent.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown

/**
 * assistant 回答的正文渲染：markdown + 代码块高亮。
 *
 * <p>高亮选型和 TUI 一致的结论：不是 tree-sitter 那一挂（devlog 2026-09-21 那次
 * 调研已经把这个前提推翻了）。这里用 markdown-renderer 自带的 code 模块
 * （底下是 dev.snipme/highlights，纯 Kotlin），一处 wrapper，将来要换只动这个文件。
 */
@Composable
fun MarkdownBlock(text: String, modifier: Modifier = Modifier) {
    Markdown(
        content = text,
        modifier = modifier,
        components = markdownComponents(
            codeBlock = highlightedCodeBlock,
            codeFence = highlightedCodeFence,
        ),
    )
}
