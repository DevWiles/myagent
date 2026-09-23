package dev.wiles.myagent.client.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wiles.myagent.client.state.Approval

/**
 * 审批：这个 app 存在的头号理由——人不在电脑前，agent 卡在等授权。
 *
 * 三幕和 TUI 一致：表态（同意/以后都同意/拒绝）→ 挑授权范围（从紧到松）→ 补一句话。
 * 「以后都同意」只在后端提议了规则时出现（suggestions 空 = 这次记不成规则，同 TUI）。
 *
 * <p>比官方 Claude App 多做的两件（它的用户在 issue 里骂过）：命令**全文**可选中可复制，
 * 不截断；按 kind 给出风险色——跑命令、改文件、上网三件事一眼分得开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(
    approval: Approval,
    onReply: (decision: String, rules: List<String>?, feedback: String?) -> Unit,
) {
    var stage by remember(approval.id) { mutableStateOf("ask") }
    var rulePick by remember(approval.id) { mutableIntStateOf(approval.preferred) }
    var feedback by remember(approval.id) { mutableStateOf("") }
    val buzz = rememberHaptics()

    fun reply(decision: String, rules: List<String>?, text: String?) {
        buzz(if (decision == "no") Buzz.REJECT else Buzz.CONFIRM)
        onReply(decision, rules, text)
    }

    // 不可扫掉：后端阻塞在等答案上，手滑关掉框等于把后端晾在那儿。
    // 想拒绝就明着按拒绝
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden },
    )
    val (icon, accent, kindLabel) = kindLook(approval.kind)
    ModalBottomSheet(
        onDismissRequest = { /* 刻意不关 */ },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, Modifier.size(22.dp), tint = accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("需要你批准", style = MaterialTheme.typography.titleLarge)
                    // 屏幕上七八个任务跑过时，一个没有出处的「执行命令」只会被盲点同意
                    Text(
                        if (approval.origin.isNotBlank()) "$kindLabel · 来自 ${approval.origin}" else kindLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(approval.action, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (approval.detail.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(AgentColors.codeBg, MaterialTheme.shapes.small),
                ) {
                    SelectionContainer {
                        Text(
                            approval.detail,
                            style = CodeText,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(start = 12.dp, end = 40.dp, top = 10.dp, bottom = 10.dp),
                        )
                    }
                    CopyButton(approval.detail, Modifier.align(Alignment.TopEnd).padding(2.dp))
                }
            }
            if (approval.reason.isNotBlank()) {
                Text(
                    approval.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentColors.skipped,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (approval.preview.isNotBlank()) {
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DiffText(approval.preview)
                }
            }
            Spacer(Modifier.height(20.dp))

            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    // 往里走（ask → rule/feedback）从右进，返回从左进
                    val forward = targetState != "ask"
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                label = "approval-stage",
            ) { current ->
                Column {
                    when (current) {
                        "ask" -> {
                            Button(
                                onClick = { reply("yes", null, null) },
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("同意这一次", style = MaterialTheme.typography.titleSmall) }
                            if (approval.suggestions.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { stage = "rule" },
                                    shape = MaterialTheme.shapes.large,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp),
                                ) { Text("以后都同意…", color = MaterialTheme.colorScheme.onSurface) }
                            }
                            TextButton(
                                onClick = { stage = "feedback" },
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(48.dp),
                            ) { Text("拒绝…", color = MaterialTheme.colorScheme.error) }
                        }

                        "rule" -> {
                            Text(
                                "记成哪条规则？（从紧到松）",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            approval.suggestions.forEachIndexed { index, suggestion ->
                                val selected = rulePick == index
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                ) {
                                    Row(
                                        Modifier
                                            .selectable(selected = selected, role = Role.RadioButton) { rulePick = index }
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // 整行可点，RadioButton 只是显示，onClick 交给外层
                                        RadioButton(selected = selected, onClick = null, Modifier.padding(12.dp))
                                        Text(suggestion.label, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    // patterns 原样回传——后端只接受它自己提议过的（协议 3.3）
                                    reply("always", approval.suggestions[rulePick].patterns, null)
                                },
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
                            ) { Text("同意并记住", style = MaterialTheme.typography.titleSmall) }
                            TextButton(onClick = { stage = "ask" }, modifier = Modifier.fillMaxWidth()) { Text("返回") }
                        }

                        "feedback" -> {
                            OutlinedTextField(
                                value = feedback,
                                onValueChange = { feedback = it },
                                label = { Text("补一句话（可空）") },
                                supportingText = { Text("它会原样交给模型") },
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { reply("no", null, feedback.ifBlank { null }) },
                                shape = MaterialTheme.shapes.large,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                            ) { Text("拒绝", style = MaterialTheme.typography.titleSmall) }
                            TextButton(onClick = { stage = "ask" }, modifier = Modifier.fillMaxWidth()) { Text("返回") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private data class KindLook(val icon: ImageVector, val accent: Color, val label: String)

/** 协议 kind：bash | write | browse。新 kind 不升协议版本，所以要有兜底 */
@Composable
private fun kindLook(kind: String): KindLook = when (kind) {
    "bash" -> KindLook(Icons.Rounded.Terminal, AgentColors.running, "执行命令")
    "write" -> KindLook(Icons.Rounded.EditNote, MaterialTheme.colorScheme.tertiary, "修改文件")
    "browse" -> KindLook(Icons.Rounded.Language, MaterialTheme.colorScheme.tertiary, "访问网站")
    else -> KindLook(Icons.Rounded.Shield, MaterialTheme.colorScheme.onSurfaceVariant, "操作")
}
