package dev.wiles.myagent.client.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wiles.myagent.client.protocol.SessionRow

/** 会话选择器（/resume 的手机版）。列表不缓存，每次都是刚问来的（同 TUI）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    currentId: String,
    sessions: List<SessionRow>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "挑一段接着聊",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (sessions.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(32.dp), tint = AgentColors.skipped)
                    Text(
                        "这个工作区还没有存下来的会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AgentColors.skipped,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            LazyColumn {
                itemsIndexed(sessions, key = { _, row -> row.id }) { index, row ->
                    val current = row.id == currentId
                    if (index > 0) {
                        HorizontalDivider(
                            Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !current) { onPick(row.id) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.ChatBubbleOutline,
                            null,
                            Modifier.size(20.dp),
                            tint = if (current) MaterialTheme.colorScheme.primary else AgentColors.skipped,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (current) AgentColors.skipped else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // 相对时间（「3 小时前」）比 MM-dd HH:mm 好扫：挑会话时要的是「最近那段」
                            val ago = DateUtils.getRelativeTimeSpanString(
                                row.savedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                            )
                            Text(
                                listOfNotNull(
                                    ago.toString(), "${row.turns} 轮", row.model, row.branch?.let { "git:$it" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = AgentColors.skipped,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
