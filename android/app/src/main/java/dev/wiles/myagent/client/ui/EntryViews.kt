package dev.wiles.myagent.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wiles.myagent.client.state.Entry
import dev.wiles.myagent.client.state.ToolCall

/**
 * 转录条目的渲染，对齐 TUI 的 EntryView：同一串事件在两块屏上长同一个样子。
 */
@Composable
fun EntryView(entry: Entry) {
    when (entry) {
        is Entry.User -> UserRow(entry.text)
        is Entry.Assistant -> MarkdownBlock(entry.text, Modifier.fillMaxWidth().padding(vertical = 4.dp))
        is Entry.Thought -> Text(
            entry.text,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = AgentColors.skipped,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        is Entry.Tools -> ToolsCard(entry.calls)
        is Entry.Notice -> NoticeRow(entry)
        is Entry.Compacting -> CompactingRow(entry)
        is Entry.Compacted -> Text(
            "已压缩 ${entry.before} → ${entry.after} tokens" +
                if (entry.summarized) "（早期细节已折进摘要）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = AgentColors.skipped,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        is Entry.Memory -> MemoryCard(entry)
        is Entry.Plan -> PlanCard(entry.plan)
        is Entry.Team -> TeamCard(entry.team)
    }
}

@Composable
private fun UserRow(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("❯ ", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
        Text(text, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun NoticeRow(notice: Entry.Notice) {
    val color = when (notice.level) {
        Entry.Notice.Level.INFO -> AgentColors.skipped
        Entry.Notice.Level.WARN -> AgentColors.running
        Entry.Notice.Level.ERROR -> AgentColors.fail
    }
    Text(
        notice.text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun CompactingRow(entry: Entry.Compacting) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            if (entry.phase == "map") "压缩中（分段摘要 ${entry.done}/${entry.total}）…"
            else "压缩中（合并摘要）… 进度为估算",
            style = MaterialTheme.typography.bodySmall,
            color = AgentColors.skipped,
        )
        LinearProgressIndicator(
            progress = { entry.percent / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** 连着调用的工具收成一张卡；点一行展开它的输出/diff（对齐 TUI 的折叠语义）。 */
@Composable
private fun ToolsCard(calls: List<ToolCall>) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            for (call in calls) ToolRow(call)
        }
    }
}

@Composable
private fun ToolRow(call: ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Row {
            val (mark, color) = when {
                call.running -> "●" to AgentColors.running
                call.ok == true -> "✓" to AgentColors.ok
                else -> "✗" to AgentColors.fail
            }
            Text(mark, color = color, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(call.tool, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(
                call.args.replace('\n', ' '),
                color = AgentColors.skipped,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            val diff = call.diff
            if (diff != null && diff.isNotBlank()) {
                DiffText(diff, Modifier.padding(top = 4.dp))
            }
            val output = call.output
            if (!output.isNullOrBlank()) {
                Text(
                    output,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun MemoryCard(entry: Entry.Memory) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("长期记忆（${entry.rows.size} 条）", style = MaterialTheme.typography.titleSmall)
            for (row in entry.rows) {
                val superseded = row.supersededBy != null
                Text(
                    "• ${row.name} — ${row.description}" + if (superseded) "（已被新事实取代）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    // 被顶掉的画弱不隐藏：用户要能看见系统曾经记过什么（同 TUI）
                    color = if (superseded) AgentColors.skipped else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "${row.scope} · ${row.sourceLabel} · ${row.created}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AgentColors.skipped,
                )
            }
            if (entry.rows.isEmpty()) {
                Text("还没有记下任何东西", style = MaterialTheme.typography.bodySmall, color = AgentColors.skipped)
            }
        }
    }
}

/** 状态 → 颜色/记号，计划和流水线共用。 */
fun statusMark(status: String): Pair<String, Color> = when (status) {
    "RUNNING" -> "●" to AgentColors.running
    "SUCCEEDED", "COMPLETED" -> "✓" to AgentColors.ok
    "FAILED" -> "✗" to AgentColors.fail
    "SKIPPED", "CANCELLED" -> "-" to AgentColors.skipped
    "PARTIAL" -> "!" to AgentColors.running
    else -> "○" to AgentColors.skipped
}
