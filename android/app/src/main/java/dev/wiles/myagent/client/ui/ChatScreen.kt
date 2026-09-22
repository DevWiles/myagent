package dev.wiles.myagent.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wiles.myagent.client.net.AgentClient
import dev.wiles.myagent.client.protocol.ClientCommand
import dev.wiles.myagent.client.state.Entry
import dev.wiles.myagent.client.state.Link
import dev.wiles.myagent.client.state.Mode
import dev.wiles.myagent.client.state.SessionState

/**
 * 会话页：终端的另一块屏。
 *
 * 布局对齐 TUI：顶部状态行（工作区/分支/模型/上下文占用）、中间转录、
 * 底部模式切换 + 输入框。TUI 的 Shift+Tab 在手机上是三枚 chip——
 * 模式必须是**看得见的状态**，这条理由在两块屏上同样成立。
 */
@Composable
fun ChatScreen(client: AgentClient, state: SessionState) {
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // 新内容来了跟到底部；用户往上翻的时候别抢
    LaunchedEffect(state.entries.size, (state.entries.lastOrNull() as? Entry.Assistant)?.text?.length) {
        val last = state.entries.size - 1
        if (last >= 0 && !listState.isScrollInProgress) {
            val visible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: last
            if (visible >= last - 1) listState.animateScrollToItem(last)
        }
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty() || state.busy) return
        client.store.submitPrompt(text)
        val cid = client.store.clientId
        client.send(
            when (state.mode) {
                Mode.NORMAL -> ClientCommand.prompt(text, cid)
                Mode.PLAN -> ClientCommand.plan(text, cid)
                Mode.TEAM -> ClientCommand.team(text, cid)
            },
        )
        draft = ""
    }

    Scaffold(
        topBar = { StatusBar(state) },
        bottomBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                ModeChips(state, onMode = { client.store.setMode(it) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "命令")
                        }
                        CommandMenu(menuOpen, state, client, onDismiss = { menuOpen = false })
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                when (state.mode) {
                                    Mode.NORMAL -> "说点什么…"
                                    Mode.PLAN -> "要规划的目标…"
                                    Mode.TEAM -> "交给流水线的目标…"
                                },
                            )
                        },
                        maxLines = 4,
                    )
                    if (state.busy) {
                        IconButton(onClick = { client.send(ClientCommand.cancel()) }) {
                            Icon(Icons.Default.Stop, contentDescription = "中断", tint = AgentColors.fail)
                        }
                    } else {
                        IconButton(onClick = { submit() }, enabled = draft.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            items(state.entries, key = { it.key }) { entry -> EntryView(entry) }
            // 首连没有历史时给一句引导，别让人对着全空的屏幕猜
            if (state.entries.isEmpty() && state.link == Link.CONNECTED) {
                item {
                    Text(
                        "已连上 ${state.ready?.workspace ?: ""}。这里发的每句话都跑在电脑上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AgentColors.skipped,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }

    // ---- 弹层们 ----

    state.approval?.let { approval ->
        ApprovalSheet(approval) { decision, rules, feedback ->
            // 先收框再发：工具跑得久，别让人对着答过的框发呆（同 TUI）
            client.store.clearApproval()
            client.send(ClientCommand.approvalReply(approval.id, decision, rules, feedback))
        }
    }

    state.planConfirm?.let { id ->
        AlertDialog(
            onDismissRequest = { /* 后端在等，别让手滑关掉 */ },
            title = { Text("按这个计划跑吗？") },
            text = { Text("任务拆解在上面的计划卡片里。900 秒不回后端按否决处理。") },
            confirmButton = {
                TextButton(onClick = {
                    client.store.clearPlanConfirm()
                    client.send(ClientCommand.planConfirmReply(id, true))
                }) { Text("执行") }
            },
            dismissButton = {
                TextButton(onClick = {
                    client.store.clearPlanConfirm()
                    client.send(ClientCommand.planConfirmReply(id, false))
                }) { Text("放弃", color = AgentColors.fail) }
            },
        )
    }

    state.sessionPicker?.let { (currentId, sessions) ->
        SessionSheet(
            currentId = currentId,
            sessions = sessions,
            onPick = { id ->
                client.store.closeSessionPicker()
                client.send(ClientCommand.sessionResume(id))
            },
            onClose = { client.store.closeSessionPicker() },
        )
    }
}

@Composable
private fun StatusBar(state: SessionState) {
    val ready = state.ready
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ready?.workspace?.substringAfterLast('/') ?: "myagent",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            ready?.branch?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    "git:($it)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            val (label, color) = when (state.link) {
                Link.CONNECTED ->
                    if (state.busy) "跑第 ${state.step} 轮…" to AgentColors.running
                    else "空闲" to AgentColors.ok

                Link.CONNECTING -> "连接中…" to AgentColors.running
                Link.RECONNECTING -> "重连中…" to AgentColors.fail
                Link.CLOSED -> "已断开" to AgentColors.fail
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
        Row {
            val model = ready?.model ?: ""
            val context = if (state.contextLimit > 0) {
                " · 上下文 ${state.tokens * 100 / state.contextLimit}%"
            } else ""
            val sent = if (state.sentTokens > 0) " · 累计发出 ${state.sentTokens}" else ""
            Text(
                model + context + sent,
                style = MaterialTheme.typography.labelSmall,
                color = AgentColors.skipped,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModeChips(state: SessionState, onMode: (Mode) -> Unit) {
    val features = state.ready?.features ?: emptyList()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.mode == Mode.NORMAL,
            onClick = { onMode(Mode.NORMAL) },
            label = { Text("对话") },
        )
        // 没宣告的模式不画入口：一个进得去、一用就报错的模式比切不进去更困惑（同 TUI）
        if ("plan" in features) {
            FilterChip(
                selected = state.mode == Mode.PLAN,
                onClick = { onMode(Mode.PLAN) },
                label = { Text("计划") },
            )
        }
        if ("team" in features) {
            FilterChip(
                selected = state.mode == Mode.TEAM,
                onClick = { onMode(Mode.TEAM) },
                label = { Text("流水线") },
            )
        }
    }
}

/** 命令菜单：TUI 斜杠命令的手机版。用不了的灰着而不是消失（同 TUI 的理由）。 */
@Composable
private fun CommandMenu(
    open: Boolean,
    state: SessionState,
    client: AgentClient,
    onDismiss: () -> Unit,
) {
    val ready = state.ready
    val features = ready?.features ?: emptyList()
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        if ("session" in features) {
            DropdownMenuItem(
                text = { Text("接最近那段会话") },
                enabled = ready?.continuable != null && !state.busy,
                onClick = {
                    onDismiss()
                    client.store.submitPrompt("/continue")
                    client.send(ClientCommand.continueSession())
                },
            )
        }
        if ("sessions" in features) {
            DropdownMenuItem(
                text = { Text("从历史里挑一段…") },
                onClick = {
                    onDismiss()
                    client.send(ClientCommand.sessions())
                },
            )
        }
        if ("resume_plan" in features || "resume" in features) {
            DropdownMenuItem(
                text = { Text("接着跑上次的计划") },
                enabled = ready?.resumable != null && !state.busy,
                onClick = {
                    onDismiss()
                    client.store.submitPrompt("/resume-plan")
                    client.send(ClientCommand.resumePlan())
                },
            )
        }
        if ("compact" in features) {
            DropdownMenuItem(
                text = { Text("现在压缩上下文") },
                enabled = !state.busy,
                onClick = {
                    onDismiss()
                    client.store.submitPrompt("/compact")
                    client.send(ClientCommand.compact())
                },
            )
        }
        if ("memory" in features) {
            DropdownMenuItem(
                text = { Text("看长期记忆") },
                onClick = {
                    onDismiss()
                    client.store.submitPrompt("/memory")
                    client.send(ClientCommand.memory("list"))
                },
            )
        }
        DropdownMenuItem(
            text = { Text("清空对话（旧的还留着）") },
            enabled = !state.busy,
            onClick = {
                onDismiss()
                client.send(ClientCommand.reset())
            },
        )
        DropdownMenuItem(
            text = { Text("断开连接", color = AgentColors.fail) },
            onClick = {
                onDismiss()
                client.disconnect()
            },
        )
    }
}
