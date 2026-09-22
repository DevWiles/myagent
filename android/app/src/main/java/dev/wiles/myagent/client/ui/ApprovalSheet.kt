package dev.wiles.myagent.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.wiles.myagent.client.state.Approval

/**
 * 审批：这个 app 存在的头号理由——人不在电脑前，agent 卡在等授权。
 *
 * 三幕和 TUI 一致：表态（同意/以后都同意/拒绝）→ 挑授权范围（从紧到松）→ 补一句话。
 * 「以后都同意」只在后端提议了规则时出现（suggestions 空 = 这次记不成规则，同 TUI）。
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

    // 不可扫掉：后端阻塞在等答案上，手滑关掉框等于把后端晾在那儿。
    // 想拒绝就明着按拒绝
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { /* 刻意不关 */ },
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("需要你批准", style = MaterialTheme.typography.titleMedium)
            if (approval.origin.isNotBlank()) {
                // 屏幕上七八个任务跑过时，一个没有出处的「执行命令」只会被盲点同意
                Text(
                    "来自：${approval.origin}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(approval.action, style = MaterialTheme.typography.bodyMedium)
            Text(
                approval.detail,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (approval.reason.isNotBlank()) {
                Text(
                    approval.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentColors.skipped,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (approval.preview.isNotBlank()) {
                DiffText(
                    approval.preview,
                    Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = 300.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            when (stage) {
                "ask" -> {
                    Button(
                        onClick = { onReply("yes", null, null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("同意这一次") }
                    if (approval.suggestions.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { stage = "rule" },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("以后都同意…") }
                    }
                    OutlinedButton(
                        onClick = { stage = "feedback" },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("拒绝…", color = AgentColors.fail) }
                }

                "rule" -> {
                    Text("记成哪条规则？（从紧到松）", style = MaterialTheme.typography.titleSmall)
                    approval.suggestions.forEachIndexed { index, suggestion ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = rulePick == index) { rulePick = index }
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(selected = rulePick == index, onClick = { rulePick = index })
                            Spacer(Modifier.width(4.dp))
                            Text(suggestion.label, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    Button(
                        onClick = {
                            // patterns 原样回传——后端只接受它自己提议过的（协议 3.3）
                            onReply("always", approval.suggestions[rulePick].patterns, null)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("同意并记住") }
                    TextButton(onClick = { stage = "ask" }) { Text("返回") }
                }

                "feedback" -> {
                    OutlinedTextField(
                        value = feedback,
                        onValueChange = { feedback = it },
                        label = { Text("补一句话（可空）——它会原样交给模型") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onReply("no", null, feedback.ifBlank { null }) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("拒绝") }
                    TextButton(onClick = { stage = "ask" }) { Text("返回") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
