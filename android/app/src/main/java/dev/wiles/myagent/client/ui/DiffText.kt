package dev.wiles.myagent.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 统一 diff 的着色显示：+ 行绿底、- 行红底、@@ 行弱化。
 * 行级就够了（TUI 也是行级；词级是 Claude Code 的下一层，记在 devlog 里）。
 */
@Composable
fun DiffText(diff: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        for (line in diff.lines()) {
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
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                softWrap = false,
                modifier = Modifier
                    .background(bg)
                    .fillMaxWidth(),
            )
        }
    }
}
