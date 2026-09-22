package dev.wiles.myagent.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题就一件事：像终端。深色为主，强调色沿用 TUI 的那一挂
 * （运行中橙、成功绿、失败红），代码/diff 一律等宽。
 */

private val Dark = darkColorScheme(
    primary = Color(0xFFE8A87C),          // TUI 的主题橙
    secondary = Color(0xFF88C0A0),
    background = Color(0xFF16181D),
    surface = Color(0xFF1E2128),
    surfaceVariant = Color(0xFF262A33),
    onBackground = Color(0xFFD8DEE9),
    onSurface = Color(0xFFD8DEE9),
    error = Color(0xFFE06C75),
)

object AgentColors {
    val running = Color(0xFFE8A87C)
    val ok = Color(0xFF88C08F)
    val fail = Color(0xFFE06C75)
    val skipped = Color(0xFF7A8190)
    val diffAdd = Color(0xFF2E4436)
    val diffDel = Color(0xFF4A2F33)
    val diffAddText = Color(0xFFA3D9A5)
    val diffDelText = Color(0xFFE8A0A6)
}

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    // 刻意只有深色：这是终端的另一块屏，不是一个要讨好系统主题的 app
    MaterialTheme(colorScheme = Dark, content = content)
}
