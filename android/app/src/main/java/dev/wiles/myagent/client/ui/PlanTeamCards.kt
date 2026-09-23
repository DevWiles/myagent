package dev.wiles.myagent.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wiles.myagent.client.state.PlanData
import dev.wiles.myagent.client.state.TeamData

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

/**
 * 流水线面板。`reason`（分工说明）要显眼——花钱之前用户唯一一眼；
 * PARTIAL 必须分两种画：verdict 非空是「打回没修完」，为空是「验收没跑成」（协议 3.14）。
 */
@Composable
fun TeamCard(team: TeamData) {
    AgentCard {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(team.status, 18.dp)
                Spacer(Modifier.width(10.dp))
                Text("流水线：${team.goal}", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                team.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            for (seat in team.seats) {
                var expanded by remember(seat.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { expanded = !expanded }) {
                    Box(Modifier.padding(top = 2.dp)) { StatusIcon(seat.status, 16.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${seat.roleTitle} · ${seat.summary}", style = MaterialTheme.typography.bodyMedium)
                        seat.error?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = AgentColors.fail)
                        }
                        if (expanded && !seat.opinion.isNullOrBlank()) {
                            MarkdownBlock(seat.opinion, Modifier.padding(top = 4.dp), ReplyText.copy(fontSize = 14.sp, lineHeight = 22.sp))
                        }
                    }
                }
            }
            when {
                team.status == "PARTIAL" && !team.verdict.isNullOrBlank() -> Column {
                    Text(
                        "验收打回，问题清单：",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = AgentColors.running,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    MarkdownBlock(team.verdict, style = ReplyText.copy(fontSize = 14.sp, lineHeight = 22.sp))
                }

                team.status == "PARTIAL" -> Text(
                    "活干了但没人担保它对——验收没跑成",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentColors.running,
                    modifier = Modifier.padding(top = 8.dp),
                )

                team.status == "COMPLETED" && !team.verdict.isNullOrBlank() -> Column {
                    Text(
                        "验收放行：",
                        style = MaterialTheme.typography.bodySmall,
                        color = AgentColors.ok,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    MarkdownBlock(team.verdict, style = ReplyText.copy(fontSize = 14.sp, lineHeight = 22.sp))
                }
            }
        }
    }
}
