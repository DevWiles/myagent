package dev.wiles.myagent.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 主题照抄 Claude：暖灰底 + 陶土橙，浅色米白、深色暖棕黑，跟随系统。
 *
 * 色值取自 claude.ai 线上 CSS 的 token（bg-000~400 / text-000~500 / brand / danger…），
 * 调研报告在 Obsidian「myagent Android 端仿 Claude Code 移动端 UI 优化」。
 *
 * 为什么要把 M3 的**每个**角色都填上：没填的会退回 M3 基线的紫色——
 * 弹层、chip、菜单、对话框、描边一律偏紫灰，那就是「像模板」的来源。
 */

private val Light = lightColorScheme(
    primary = Color(0xFFC6613F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E3DA),
    onPrimaryContainer = Color(0xFF5A2A18),
    secondary = Color(0xFF3D3D3A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E6DC),
    onSecondaryContainer = Color(0xFF141413),
    tertiary = Color(0xFF2C84DB),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCEBFA),
    onTertiaryContainer = Color(0xFF0B3A66),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF141413),
    surface = Color(0xFFFAF9F5),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFF0EEE6),
    onSurfaceVariant = Color(0xFF73726C),
    surfaceTint = Color(0xFFC6613F),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF0EEE6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F4ED),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE8E6DC),
    inverseSurface = Color(0xFF30302E),
    inverseOnSurface = Color(0xFFFAF9F5),
    inversePrimary = Color(0xFFD97757),
    outline = Color(0x401F1E1D),
    outlineVariant = Color(0x1A1F1E1D),
    error = Color(0xFFB53333),
    onError = Color.White,
    errorContainer = Color(0xFFF9ECEC),
    onErrorContainer = Color(0xFF682626),
    scrim = Color(0x66000000),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2C20),
    onPrimaryContainer = Color(0xFFF5D0C0),
    secondary = Color(0xFFC2C0B6),
    onSecondary = Color(0xFF141413),
    secondaryContainer = Color(0xFF3A3A37),
    onSecondaryContainer = Color(0xFFFAF9F5),
    tertiary = Color(0xFF5AA2E8),
    onTertiary = Color(0xFF0B2239),
    tertiaryContainer = Color(0xFF173A5C),
    onTertiaryContainer = Color(0xFFDCEBFA),
    background = Color(0xFF262624),
    onBackground = Color(0xFFFAF9F5),
    surface = Color(0xFF262624),
    onSurface = Color(0xFFFAF9F5),
    surfaceVariant = Color(0xFF30302E),
    onSurfaceVariant = Color(0xFF9C9A92),
    surfaceTint = Color(0xFFD97757),
    surfaceBright = Color(0xFF3B3B38),
    surfaceDim = Color(0xFF1F1E1D),
    surfaceContainerLowest = Color(0xFF1F1E1D),
    surfaceContainerLow = Color(0xFF2B2B29),
    surfaceContainer = Color(0xFF30302E),
    surfaceContainerHigh = Color(0xFF30302E),
    surfaceContainerHighest = Color(0xFF3B3B38),
    inverseSurface = Color(0xFFFAF9F5),
    inverseOnSurface = Color(0xFF141413),
    inversePrimary = Color(0xFFC6613F),
    outline = Color(0x40DEDCD1),
    outlineVariant = Color(0x1FDEDCD1),
    error = Color(0xFFDD5353),
    onError = Color.White,
    errorContainer = Color(0xFF682626),
    onErrorContainer = Color(0xFFF9ECEC),
    scrim = Color(0x99000000),
)

/**
 * M3 角色装不下的语义色：运行/成功/失败、diff、气泡、卡片。
 * 深浅两套，走 CompositionLocal——调用处照旧写 `AgentColors.ok`。
 */
@Immutable
data class AgentPalette(
    val running: Color,
    val ok: Color,
    val fail: Color,
    val skipped: Color,
    val diffAdd: Color,
    val diffDel: Color,
    val diffAddText: Color,
    val diffDelText: Color,
    /** 用户消息气泡（Claude 的 bg-300） */
    val userBubble: Color,
    /** 工具/计划/记忆卡片 */
    val card: Color,
    /** 代码、命令、工具输出的底 */
    val codeBg: Color,
    /** 输入框卡片：比页面亮一层 */
    val composer: Color,
)

private val LightPalette = AgentPalette(
    running = Color(0xFFC6613F),
    ok = Color(0xFF2F7613),
    fail = Color(0xFFB53333),
    skipped = Color(0xFF73726C),
    diffAdd = Color(0xFFE6FFEC),
    diffDel = Color(0xFFFFEBE9),
    diffAddText = Color(0xFF1A7F37),
    diffDelText = Color(0xFFCF222E),
    userBubble = Color(0xFFF0EEE6),
    card = Color(0xFFFFFFFF),
    codeBg = Color(0xFFF5F4ED),
    composer = Color(0xFFFFFFFF),
)

private val DarkPalette = AgentPalette(
    running = Color(0xFFD97757),
    ok = Color(0xFF6DB33F),
    fail = Color(0xFFDD5353),
    skipped = Color(0xFF9C9A92),
    diffAdd = Color(0xFF203A26),
    diffDel = Color(0xFF4A2626),
    diffAddText = Color(0xFFA3D9A5),
    diffDelText = Color(0xFFF0A8A8),
    userBubble = Color(0xFF141413),
    card = Color(0xFF30302E),
    codeBg = Color(0xFF1F1E1D),
    composer = Color(0xFF30302E),
)

private val LocalAgentPalette = staticCompositionLocalOf { DarkPalette }

object AgentColors {
    val running: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.running
    val ok: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.ok
    val fail: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.fail
    val skipped: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.skipped
    val diffAdd: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.diffAdd
    val diffDel: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.diffDel
    val diffAddText: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.diffAddText
    val diffDelText: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.diffDelText
    val userBubble: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.userBubble
    val card: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.card
    val codeBg: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.codeBg
    val composer: Color @Composable @ReadOnlyComposable get() = LocalAgentPalette.current.composer
}

/** 圆角一处定死（参考 Happy 的 theme.ts）：小 8 / 中 12 / 大 16 / 特大 24。 */
private val AgentShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AgentTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) Dark else Light
    CompositionLocalProvider(LocalAgentPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AgentTypography,
            shapes = AgentShapes,
            content = content,
        )
    }
}
