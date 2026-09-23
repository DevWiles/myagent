package dev.wiles.myagent.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wiles.myagent.client.state.Entry
import dev.wiles.myagent.client.state.ToolCall
import dev.wiles.myagent.client.state.ToolSummary
import kotlinx.coroutines.delay

/**
 * 转录条目的渲染，对齐 TUI 的 EntryView：同一串事件在两块屏上长同一个样子。
 * （「同一个样子」指语义——折叠规则、diff 常驻、失败标红；皮是各屏自己的。）
 */
@Composable
fun EntryView(entry: Entry) {
    when (entry) {
        is Entry.User -> UserBubble(entry.text)
        is Entry.Assistant -> SelectionContainer {
            MarkdownBlock(entry.text, Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }

        is Entry.Thought -> ThoughtRow(entry.key, entry.text)
        is Entry.Tools -> ToolsCard(entry.key, entry.calls)
        is Entry.Notice -> NoticeRow(entry)
        is Entry.Compacting -> CompactingRow(entry)
        is Entry.Compacted -> DividerLabel(
            "已压缩 ${entry.before} → ${entry.after} tokens" +
                if (entry.summarized) "（早期细节已折进摘要）" else "",
        )

        is Entry.Memory -> MemoryCard(entry)
        is Entry.Plan -> PlanCard(entry.plan)
        is Entry.Team -> TeamCard(entry.team)
    }
}

/** 卡片统一的皮：比页面亮一层 + 一圈细描边，不用阴影（Claude 的做法） */
@Composable
fun AgentCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = AgentColors.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        content = content,
    )
}

/** 用户的话：右对齐气泡，最多占八成宽（左边 48dp 让出来） */
@Composable
private fun UserBubble(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(shape = RoundedCornerShape(18.dp), color = AgentColors.userBubble) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 思考：左边一道细线 + 斜体弱化，默认只露三行，点开看全文 */
@Composable
private fun ThoughtRow(key: String, text: String) {
    var open by remember(key) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 4.dp)
            .clickable { open = !open }
            .animateContentSize(),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(1.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Psychology,
                    null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text("思考", style = MaterialTheme.typography.labelMedium, color = AgentColors.skipped)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = AgentColors.skipped,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoticeRow(notice: Entry.Notice) {
    val (icon, color) = when (notice.level) {
        Entry.Notice.Level.INFO -> Icons.Rounded.Info to AgentColors.skipped
        Entry.Notice.Level.WARN -> Icons.Rounded.WarningAmber to AgentColors.running
        Entry.Notice.Level.ERROR -> Icons.Rounded.ErrorOutline to AgentColors.fail
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(14.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(notice.text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CompactingRow(entry: Entry.Compacting) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            if (entry.phase == "map") "压缩中（分段摘要 ${entry.done}/${entry.total}）…"
            else "压缩中（合并摘要）… 进度为估算",
            style = MaterialTheme.typography.bodySmall,
            color = AgentColors.skipped,
        )
        LinearProgressIndicator(
            progress = { entry.percent / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            strokeCap = StrokeCap.Round,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** 居中的一行说明，两边细线——事件分隔用（压缩完成这类） */
@Composable
private fun DividerLabel(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = AgentColors.skipped,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 连着调用的工具收成**一行摘要**：「读取 3 个文件 · 执行 1 条命令」——和 TUI 同一个折叠语义。
 *
 * 为什么不默认列每一条：工具名和参数是 agent 的内部动作，不是用户要读的东西，
 * 七八条会把真正有信息量的回复挤出屏幕。点摘要行展开明细，再点一次收起。
 *
 * 两件事收着也不能藏（否则摘要行会骗人）：还在跑（转圈）、有失败（单独标红）。
 * 还有一个例外和 TUI 一致：**改了文件的 diff 一直摊开**——那是 agent 替用户改了
 * 他的代码，只给个「写入 1 个文件」的计数等于什么都没说。
 */
@Composable
private fun ToolsCard(entryKey: String, calls: List<ToolCall>) {
    var expanded by remember(entryKey) { mutableStateOf(false) }
    val buzz = rememberHaptics()
    val running = calls.any { it.running }
    val failed = calls.count { it.ok == false }
    val changes = calls.filter { !it.diff.isNullOrBlank() }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    AgentCard {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        buzz(Buzz.TICK)
                        expanded = !expanded
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIcon(
                    when {
                        running -> "RUNNING"
                        failed > 0 -> "FAILED"
                        else -> "SUCCEEDED"
                    },
                    size = 16.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    ToolSummary.summarize(calls),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (failed > 0) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            "$failed 个失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(chevron),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(top = 6.dp)) {
                    for (call in calls) ToolRow(call)
                }
            }
            // diff 不折叠：收着的时候它也得在（同 TUI 的唯一例外）。
            // 展开时明细行里自己会画，别重复
            if (!expanded) {
                for (call in changes) {
                    DiffText(call.diff!!, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolRow(call: ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(
                when {
                    call.running -> "RUNNING"
                    call.ok == true -> "SUCCEEDED"
                    else -> "FAILED"
                },
                size = 14.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                call.tool,
                style = CodeText,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                call.args.replace('\n', ' '),
                style = CodeText,
                color = AgentColors.skipped,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (call.running) ElapsedLabel(call.id)
        if (expanded) {
            val diff = call.diff
            if (diff != null && diff.isNotBlank()) {
                DiffText(diff, Modifier.padding(top = 6.dp))
            }
            val output = call.output
            if (!output.isNullOrBlank()) OutputBox(output)
        }
    }
}

/**
 * 跑着的工具右下角走秒（抄 Happy 的 ToolView）。只是「我看见它以来」的秒数——
 * 协议里没带开始时间，重连补发时会从 0 起算，所以只在 running 时画，不落进转录。
 */
@Composable
private fun ElapsedLabel(id: Int) {
    val start = remember(id) { System.currentTimeMillis() }
    var now by remember(id) { mutableLongStateOf(start) }
    LaunchedEffect(id) {
        while (true) {
            delay(100)
            now = System.currentTimeMillis()
        }
    }
    Text(
        "%.1fs".format((now - start) / 1000f),
        style = CodeText.copy(fontSize = 11.sp),
        color = AgentColors.skipped,
        modifier = Modifier.padding(start = 24.dp, top = 2.dp),
    )
}

/** 工具输出：限高滚动 + 可选中 + 一键复制。长输出不再把整屏撑没 */
@Composable
private fun OutputBox(output: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(AgentColors.codeBg, MaterialTheme.shapes.small),
    ) {
        SelectionContainer {
            Text(
                output,
                style = CodeText,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 36.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        CopyButton(output, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun MemoryCard(entry: Entry.Memory) {
    AgentCard {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("长期记忆（${entry.rows.size} 条）", style = MaterialTheme.typography.titleSmall)
            }
            for (row in entry.rows) {
                val superseded = row.supersededBy != null
                Text(
                    "${row.name} — ${row.description}" + if (superseded) "（已被新事实取代）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    // 被顶掉的画弱不隐藏：用户要能看见系统曾经记过什么（同 TUI）
                    color = if (superseded) AgentColors.skipped else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "${row.scope} · ${row.sourceLabel} · ${row.created}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AgentColors.skipped,
                )
            }
            if (entry.rows.isEmpty()) {
                Text(
                    "还没有记下任何东西",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentColors.skipped,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private val sparkFrames = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")
private val verbs = listOf("思考中", "琢磨中", "推敲中", "翻代码", "斟酌中", "捋一捋", "对一对", "拼起来")

/**
 * 「在干活」行：抄 Claude Code 的星芒循环 + 动词 + 走秒，钉在转录末尾。
 * 原来只有顶栏角落一行「跑第 N 轮」，眼睛在看正文的时候根本注意不到。
 */
@Composable
fun WorkingRow(step: Int) {
    val start = remember { System.currentTimeMillis() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(120)
            tick++
        }
    }
    val seconds = (System.currentTimeMillis() - start) / 1000
    // 动词每 ~3 秒换一个，起点随 step 变——每轮开头不总是同一个词
    val verb = verbs[(step + tick / 25) % verbs.size]
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            sparkFrames[tick % sparkFrames.size],
            color = MaterialTheme.colorScheme.primary,
            fontSize = 18.sp,
            modifier = Modifier.width(22.dp),
        )
        Text("$verb…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            "${seconds}s · 第 $step 轮",
            style = MaterialTheme.typography.labelSmall,
            color = AgentColors.skipped,
        )
    }
}
