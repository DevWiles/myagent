package dev.wiles.myagent.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 统一 diff 的着色显示：+ 行绿底、- 行红底、@@ 行弱化。
 * 行级就够了（TUI 也是行级；词级是 Claude Code 的下一层，记在 devlog 里）。
 *
 * <p>行底色要铺满**最长那行**：横向滚动里 fillMaxWidth 不起作用（宽度约束是无穷），
 * 所以内层用 IntrinsicSize.Max 把列宽定成最长行，再让每行 fillMaxWidth；
 * 外面再垫一个「至少一屏宽」，短 diff 也不会只染半截。
 */
@Composable
fun DiffText(diff: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(AgentColors.codeBg),
    ) {
        val minWidth = maxWidth
        SelectionContainer {
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                Column(Modifier.widthIn(min = minWidth).width(IntrinsicSize.Max)) {
                    // 末尾换行别画成一行空白
                    for (line in diff.trimEnd('\n').lines()) {
                        val (bg, fg) = when {
                            line.startsWith("+++") || line.startsWith("---") ->
                                Color.Transparent to AgentColors.skipped

                            line.startsWith("@@") -> Color.Transparent to AgentColors.skipped
                            line.startsWith("+") -> AgentColors.diffAdd to AgentColors.diffAddText
                            line.startsWith("-") -> AgentColors.diffDel to AgentColors.diffDelText
                            else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            text = line.ifEmpty { " " },
                            color = fg,
                            style = CodeText,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
