package dev.wiles.myagent.client.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wiles.myagent.client.state.PlanConfirm

/**
 * plan 模式的两道确认（同 TUI）：
 * - enter：模型觉得这件事该先规划，提议进 plan 模式——同意 / 不用，直接做
 * - exit：模型调研完交了计划——批准（退出 plan 模式、照计划动手）/ 不批准（可附一句意见，原话交还给模型）
 *
 * 和审批框一样不可扫掉：后端阻塞在等答案上，手滑关掉等于把它晾在那儿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanConfirmSheet(
    confirm: PlanConfirm,
    onReply: (approved: Boolean, feedback: String?) -> Unit,
) {
    var rejecting by remember(confirm.id) { mutableStateOf(false) }
    var feedback by remember(confirm.id) { mutableStateOf("") }
    val buzz = rememberHaptics()
    val exit = confirm.kind != "enter"

    fun reply(approved: Boolean, text: String?) {
        buzz(if (approved) Buzz.CONFIRM else Buzz.REJECT)
        onReply(approved, text)
    }

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden },
    )
    val accent = AgentColors.ok
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
                    Icon(Icons.Rounded.Map, null, Modifier.size(22.dp), tint = accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (exit) "按这个计划动手吗？" else "先出个计划再动手？",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (exit) "批准后退出 plan 模式，照计划改" else "进 plan 模式：只读调研，写好计划再交给你批",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                    )
                }
            }
            if (exit && confirm.plan.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .background(AgentColors.codeBg, MaterialTheme.shapes.small)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    MarkdownBlock(confirm.plan)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (!rejecting) {
                Button(
                    onClick = { reply(true, null) },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (exit) "批准，开始做" else "好，先规划", style = MaterialTheme.typography.titleSmall) }
                OutlinedButton(
                    onClick = { if (exit) rejecting = true else reply(false, null) },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                ) { Text(if (exit) "不批准，提意见…" else "不用，直接做", style = MaterialTheme.typography.titleSmall) }
            } else {
                OutlinedTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    label = { Text("哪里要改（可空）") },
                    supportingText = { Text("它会原样交给模型，模型改好计划再交") },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { reply(false, feedback.ifBlank { null }) },
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                ) { Text("不批准", style = MaterialTheme.typography.titleSmall) }
                TextButton(onClick = { rejecting = false }, modifier = Modifier.fillMaxWidth()) { Text("返回") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
