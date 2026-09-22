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
import dev.wiles.myagent.client.state.ToolSummary

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

        is Entry.Tools -> ToolsCard(entry.key, entry.calls)
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

/**
 * 连着调用的工具收成**一行摘要**：「▸ 读取 3 个文件 · 执行 1 条命令」——和 TUI 同一个折叠语义。
 *
 * 为什么不默认列每一条：工具名和参数是 agent 的内部动作，不是用户要读的东西，
 * 七八条会把真正有信息量的回复挤出屏幕。点摘要行展开明细，再点一次收起。
 *
 * 两件事收着也不能藏（否则摘要行会骗人）：还在跑（末尾省略号）、有失败（单独标红）。
 * 还有一个例外和 TUI 一致：**改了文件的 diff 一直摊开**——那是 agent 替用户改了
 * 他的代码，只给个「写入 1 个文件」的计数等于什么都没说。
 */
@Composable
private fun ToolsCard(entryKey: String, calls: List<ToolCall>) {
    var expanded by remember(entryKey) { mutableStateOf(false) }
    val running = calls.any { it.running }
    val failed = calls.count { it.ok == false }
    val changes = calls.filter { !it.diff.isNullOrBlank() }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Text(
                    (if (expanded) "▾ " else "▸ ") +
                        ToolSummary.summarize(calls) + (if (running) "…" else ""),
                    color = AgentColors.skipped,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                if (failed > 0) {
                    Text(
                        " · $failed 个失败",
                        color = AgentColors.fail,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
            if (expanded) {
                Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
                    for (call in calls) ToolRow(call)
                }
            }
            // diff 不折叠：收着的时候它也得在（同 TUI 的唯一例外）。
            // 展开时明细行里自己会画，别重复
            if (!expanded) {
                for (call in changes) {
                    DiffText(call.diff!!, Modifier.padding(top = 4.dp))
                }
            }
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
