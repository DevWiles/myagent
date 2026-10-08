package dev.wiles.myagent.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wiles.myagent.client.net.AgentClient
import dev.wiles.myagent.client.protocol.ClientCommand
import dev.wiles.myagent.client.state.Entry
import dev.wiles.myagent.client.state.Link
import dev.wiles.myagent.client.state.Mode
import dev.wiles.myagent.client.state.SessionState
import kotlinx.coroutines.launch

/**
 * 会话页：终端的另一块屏。
 *
 * 布局对齐 TUI：顶部状态行（工作区/分支/模型/上下文占用）、中间转录、
 * 底部输入框 + 模式切换。皮照抄 Claude 移动端：输入框是一张圆角大卡片，
 * 模式收进卡片底行的胶囊里——模式仍然是**看得见的状态**（TUI 的 Shift+Tab 那条理由），
 * 只是不再单独占一排。
 */
@Composable
fun ChatScreen(client: AgentClient, state: SessionState) {
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val buzz = rememberHaptics()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /**
     * 自动跟随：流式输出时钉在底部。**用户一上滑就让位**——
     * 他正在读上面的东西，这时候抢滚动条比不滚动糟得多。
     * 判据分两半：用户手势（drag）关掉跟随，自己滚回底部又打开。
     */
    var autoFollow by remember { mutableStateOf(true) }
    // 只有用户的手指会发 DragInteraction；程序滚动不发——
    // 所以这里天然分得清「用户在翻」和「我们在跟」，不用猜
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) autoFollow = false
        }
    }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(atBottom) {
        if (atBottom) autoFollow = true
    }
    // 跟随本体。滚到「底部锚点」而不是最后一条内容——最后一条正在长个儿，
    // 滚到它的开头看不见新字，锚点在它后面，滚到锚点就是滚到底
    LaunchedEffect(
        state.entries.size,
        (state.entries.lastOrNull() as? Entry.Assistant)?.text?.length,
        state.busy,
        autoFollow,
    ) {
        if (autoFollow) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }

    // 一轮跑完震一下（确认）、掉线震一下（拒绝）——人没盯着屏幕时手里能感觉到
    var wasBusy by remember { mutableStateOf(state.busy) }
    LaunchedEffect(state.busy) {
        if (wasBusy && !state.busy) buzz(Buzz.CONFIRM)
        wasBusy = state.busy
    }
    LaunchedEffect(state.link) {
        if (state.link == Link.RECONNECTING) buzz(Buzz.REJECT)
    }

    /**
     * 「回到刚才」：发新指令会强制跳到底部，但用户可能正读到一半——
     * 给一个能跳回去的按钮，3 秒不点自动消失。存的是发送那一刻的滚动位置。
     */
    var returnSpot by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(returnSpot) {
        if (returnSpot != null) {
            kotlinx.coroutines.delay(3000)
            returnSpot = null
        }
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty() || state.busy) return
        buzz(Buzz.TICK)
        // 用户正在上面读东西？记下位置再跳底，给他一条回去的路
        returnSpot = if (listState.canScrollForward) {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        } else null
        autoFollow = true
        scope.launch {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { StatusBar(state) },
        bottomBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            ) {
                AnimatedVisibility(
                    visible = returnSpot != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = AgentColors.composer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.padding(bottom = 8.dp),
                        onClick = {
                            val (index, offset) = returnSpot ?: return@Surface
                            returnSpot = null
                            autoFollow = false      // 回去是为了继续读，别立刻又被拽下来
                            scope.launch { listState.scrollToItem(index, offset) }
                        },
                    ) {
                        Text(
                            "↑ 回到刚才的位置",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Composer(
                    draft = draft,
                    onDraft = { draft = it },
                    state = state,
                    onSubmit = ::submit,
                    onCancel = {
                        buzz(Buzz.TICK)
                        client.send(ClientCommand.cancel())
                    },
                    onMode = {
                        buzz(Buzz.TICK)
                        client.store.setMode(it)
                    },
                    menu = {
                        Box {
                            RoundIcon(Icons.Rounded.MoreHoriz, "命令") { menuOpen = true }
                            CommandMenu(menuOpen, state, client, onDismiss = { menuOpen = false })
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                item(key = "top-gap") { Spacer(Modifier.height(8.dp)) }
                items(state.entries, key = { it.key }) { entry -> EntryView(entry) }
                if (state.busy) item(key = "working") { WorkingRow(state.step) }
                // 底部锚点：0 高度，只为让「滚到底」有一个确定的目标——
                // 最后一条内容在流式期间不断长个儿，滚到它的开头看不见新字
                item(key = "bottom-anchor") { Spacer(Modifier.height(1.dp)) }
            }
            // 首连没有历史时给一个像样的开场，别让人对着全空的屏幕猜
            if (state.entries.isEmpty() && !state.busy && state.link == Link.CONNECTED) {
                EmptyState(workspace = state.ready?.workspace ?: "", onPick = { draft = it })
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
            text = {
                Text(
                    "任务拆解在上面的计划卡片里。900 秒不回后端按否决处理。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(onClick = {
                    buzz(Buzz.CONFIRM)
                    client.store.clearPlanConfirm()
                    client.send(ClientCommand.planConfirmReply(id, true))
                }) { Text("执行") }
            },
            dismissButton = {
                TextButton(onClick = {
                    buzz(Buzz.REJECT)
                    client.store.clearPlanConfirm()
                    client.send(ClientCommand.planConfirmReply(id, false))
                }) { Text("放弃", color = MaterialTheme.colorScheme.error) }
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
    val (label, color) = when (state.link) {
        Link.CONNECTED ->
            if (state.busy) "跑第 ${state.step} 轮" to AgentColors.running
            else "空闲" to AgentColors.ok

        Link.CONNECTING -> "连接中" to AgentColors.running
        Link.RECONNECTING -> "重连中" to AgentColors.fail
        Link.CLOSED -> "已断开" to AgentColors.fail
    }
    val dotColor by animateColorAsState(color, label = "dot-color")
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ready?.workspace?.substringAfterLast('/') ?: "myagent",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ready?.branch?.let {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                it,
                                style = CodeText.copy(fontSize = 11.sp, lineHeight = 14.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
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
            Spacer(Modifier.width(12.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(dotColor, pulse = state.busy || state.link != Link.CONNECTED)
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 输入卡片：上面是字，下面一行是「命令 · 模式 ……… 发送」（Claude 移动端的格局） */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    state: SessionState,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onMode: (Mode) -> Unit,
    menu: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = AgentColors.composer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                when {
                                    // 手机屏窄，长提示已经在转录里了，这里只留动作本身
                                    state.interrupted != null && !state.busy -> "说「继续」接着做…"
                                    else -> when (state.mode) {
                                    Mode.NORMAL -> "说点什么…"
                                    Mode.PLAN -> "要规划的目标…"
                                    Mode.TEAM -> "交给流水线的目标…"
                                    }
                                },
                                style = textStyle,
                                color = AgentColors.skipped,
                            )
                        }
                        inner()
                    }
                },
            )
            Row(
                Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                menu()
                Spacer(Modifier.width(6.dp))
                ModePill(state, onMode)
                Spacer(Modifier.weight(1f))
                SendButton(busy = state.busy, enabled = draft.isNotBlank(), onSubmit = onSubmit, onCancel = onCancel)
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = onClick,
        modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 发送/中断：圆形。能发 = 陶土橙，空着 = 灰，跑着 = 深色方块（Claude 的中断键） */
@Composable
private fun SendButton(busy: Boolean, enabled: Boolean, onSubmit: () -> Unit, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        when {
            busy -> scheme.onSurface
            enabled -> scheme.primary
            else -> scheme.outlineVariant
        },
        label = "send-bg",
    )
    Surface(
        shape = CircleShape,
        color = bg,
        onClick = if (busy) onCancel else onSubmit,
        enabled = busy || enabled,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                Icon(Icons.Rounded.Stop, "中断", Modifier.size(18.dp), tint = scheme.surface)
            } else {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    "发送",
                    Modifier.size(20.dp),
                    tint = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun modeLabel(mode: Mode) = when (mode) {
    Mode.NORMAL -> "对话"
    Mode.PLAN -> "计划"
    Mode.TEAM -> "流水线"
}

@Composable
private fun modeColor(mode: Mode): Color = when (mode) {
    Mode.NORMAL -> AgentColors.skipped
    Mode.PLAN -> AgentColors.ok
    Mode.TEAM -> MaterialTheme.colorScheme.tertiary
}

/** 模式胶囊：带色点，点开挑。没宣告的模式不进菜单（同 TUI：进得去一用就报错比切不进去更困惑） */
@Composable
private fun ModePill(state: SessionState, onMode: (Mode) -> Unit) {
    val features = state.ready?.features ?: emptyList()
    val modes = buildList {
        add(Mode.NORMAL)
        if ("plan" in features) add(Mode.PLAN)
        if ("team" in features) add(Mode.TEAM)
    }
    var open by remember { mutableStateOf(false) }
    val color = modeColor(state.mode)
    Box {
        Surface(
            shape = CircleShape,
            color = if (state.mode == Mode.NORMAL) Color.Transparent else color.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, if (state.mode == Mode.NORMAL) MaterialTheme.colorScheme.outline else color.copy(alpha = 0.4f)),
            onClick = { open = true },
            enabled = modes.size > 1,
        ) {
            Row(
                Modifier.height(34.dp).padding(start = 12.dp, end = if (modes.size > 1) 6.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color, pulse = false)
                Spacer(Modifier.width(6.dp))
                Text(modeLabel(state.mode), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                if (modes.size > 1) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.large,
            containerColor = AgentColors.composer,
        ) {
            for (mode in modes) {
                DropdownMenuItem(
                    text = { Text(modeLabel(mode)) },
                    leadingIcon = { StatusDot(modeColor(mode), pulse = false) },
                    onClick = {
                        open = false
                        onMode(mode)
                    },
                )
            }
        }
    }
}

private val suggestions = listOf(
    "这个仓库是做什么的？",
    "跑一遍测试，看看有没有挂的",
    "看看最近一次提交改了什么",
)

@Composable
private fun EmptyState(workspace: String, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✻", fontSize = 40.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("今天做点什么？", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "已连上 ${workspace.substringAfterLast('/')}。这里发的每句话都跑在电脑上。",
            style = MaterialTheme.typography.bodyMedium,
            color = AgentColors.skipped,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        for (s in suggestions) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = AgentColors.card,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = { onPick(s) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    s,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
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

    @Composable
    fun item(icon: ImageVector, text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
        val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        DropdownMenuItem(
            text = { Text(text, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
            leadingIcon = { Icon(icon, null, Modifier.size(20.dp), tint = tint) },
            enabled = enabled,
            onClick = {
                onDismiss()
                onClick()
            },
        )
    }

    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = AgentColors.composer,
    ) {
        if ("session" in features) {
            item(Icons.Rounded.Replay, "接最近那段会话", enabled = ready?.continuable != null && !state.busy) {
                client.store.submitPrompt("/continue")
                client.send(ClientCommand.continueSession())
            }
        }
        if ("sessions" in features) {
            item(Icons.Rounded.History, "从历史里挑一段…") { client.send(ClientCommand.sessions()) }
        }
        if ("resume_plan" in features || "resume" in features) {
            item(Icons.Rounded.PlayArrow, "接着跑上次的计划", enabled = ready?.resumable != null && !state.busy) {
                client.store.submitPrompt("/resume-plan")
                client.send(ClientCommand.resumePlan())
            }
        }
        if ("compact" in features) {
            item(Icons.Rounded.Compress, "现在压缩上下文", enabled = !state.busy) {
                client.store.submitPrompt("/compact")
                client.send(ClientCommand.compact())
            }
        }
        if ("memory" in features) {
            item(Icons.Rounded.Psychology, "看长期记忆") {
                client.store.submitPrompt("/memory")
                client.send(ClientCommand.memory("list"))
            }
        }
        item(Icons.Rounded.AddCircleOutline, "清空对话（旧的还留着）", enabled = !state.busy) {
            client.send(ClientCommand.reset())
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        item(Icons.Rounded.LinkOff, "断开连接", danger = true) { client.disconnect() }
    }
}
