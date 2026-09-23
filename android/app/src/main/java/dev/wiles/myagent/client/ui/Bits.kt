package dev.wiles.myagent.client.ui

import android.content.ClipData
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 界面里反复用的小零件：状态图标、状态圆点、复制按钮。 */

/**
 * 计划/流水线/工具共用的状态图标。取代原来的 ● ✓ ✗ 字符——
 * 跑着的是一个真在转的圈，而不是一个静止的点。
 */
@Composable
fun StatusIcon(status: String, size: Dp = 16.dp) {
    when (status) {
        "RUNNING" -> CircularProgressIndicator(
            modifier = Modifier.size(size - 2.dp),
            strokeWidth = 2.dp,
            color = AgentColors.running,
            trackColor = Color.Transparent,
        )

        "SUCCEEDED", "COMPLETED" -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(size), tint = AgentColors.ok)
        "FAILED" -> Icon(Icons.Rounded.Cancel, null, Modifier.size(size), tint = AgentColors.fail)
        "SKIPPED", "CANCELLED" ->
            Icon(Icons.Rounded.RemoveCircleOutline, null, Modifier.size(size), tint = AgentColors.skipped)

        "PARTIAL" -> Icon(Icons.Rounded.Error, null, Modifier.size(size), tint = AgentColors.running)
        else -> Icon(Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(size), tint = AgentColors.skipped)
    }
}

/** 连接状态灯。pulse = 正在干活，透明度呼吸 */
@Composable
fun StatusDot(color: Color, pulse: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (pulse) {
        val t = rememberInfiniteTransition(label = "dot")
        val a by t.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "dot-alpha",
        )
        a
    } else 1f
    Box(
        modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

/** 一键复制。系统（Android 13+）自己会弹「已复制」，这里只补一下触感 */
@Composable
fun CopyButton(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val buzz = rememberHaptics()
    IconButton(
        onClick = {
            buzz(Buzz.TICK)
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("myagent", text))) }
        },
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = "复制",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
