package dev.wiles.myagent.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wiles.myagent.client.protocol.SessionRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会话选择器（/resume 的手机版）。列表不缓存，每次都是刚问来的（同 TUI）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    currentId: String,
    sessions: List<SessionRow>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(16.dp)) {
            Text("挑一段接着聊", style = MaterialTheme.typography.titleMedium)
            if (sessions.isEmpty()) {
                Text(
                    "这个工作区还没有存下来的会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentColors.skipped,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            LazyColumn {
                items(sessions, key = { it.id }) { row ->
                    val current = row.id == currentId
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !current) { onPick(row.id) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            row.title + if (current) "（当前）" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (current) AgentColors.skipped
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        val when_ = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                            .format(Date(row.savedAt))
                        Text(
                            listOfNotNull(
                                "${row.turns} 轮", row.model, row.branch?.let { "git:$it" }, when_,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = AgentColors.skipped,
                        )
                    }
                }
            }
        }
    }
}
