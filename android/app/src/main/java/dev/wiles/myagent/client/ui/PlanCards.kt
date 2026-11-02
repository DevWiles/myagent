package dev.wiles.myagent.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wiles.myagent.client.state.PlanData

/** 计划面板：任务逐个点亮，救场任务标「改道」。原地更新，滚上去随时能看进度（同 TUI）。 */
@Composable
fun PlanCard(plan: PlanData) {
    AgentCard {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(plan.status, 18.dp)
                Spacer(Modifier.width(10.dp))
                Text("计划：${plan.goal}", style = MaterialTheme.typography.titleSmall)
            }
            for (task in plan.tasks) {
                Row(Modifier.padding(top = 8.dp, start = if (task.parentId != null) 20.dp else 0.dp)) {
                    Box(Modifier.padding(top = 2.dp)) { StatusIcon(task.status, 16.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            buildString {
                                if (task.recovery) append("[改道] ")
                                append(task.description)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (task.recovery) AgentColors.running
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        val who = task.roleTitle ?: task.type
                        Text(
                            who + (task.error?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (task.error != null) AgentColors.fail else AgentColors.skipped,
                        )
                    }
                }
            }
            plan.summary?.let {
                Text(
                    "完成 ${it.succeeded} · 失败 ${it.failed} · 跳过 ${it.skipped} · ${it.durationMs / 1000}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = AgentColors.skipped,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

