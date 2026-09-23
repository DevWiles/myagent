package dev.wiles.myagent.client.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.wiles.myagent.client.R

/**
 * 三套字，分工照抄 claude.ai：**回复用衬线、界面和用户的话用无衬线**、代码等宽。
 *
 * Anthropic 自己的字（Styrene / Tiempos 定制）是商业授权，这里用 Google Fonts 的开源替身：
 * Inter / Source Serif 4 / JetBrains Mono。res/font 里是可变字体，
 * 已用 fontTools 裁到拉丁 + 常用符号（❯ ✓ ✗ ✻ 框线都在）——中文本来就回退系统黑体，
 * 和 claude.ai 在中文环境下的表现一致。
 */

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: FontWeight) = FontFamily(
    weights.map {
        Font(res, weight = it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
    },
)

val Sans = variable(R.font.inter, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
val Serif = variable(R.font.source_serif4, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
val Mono = variable(R.font.jetbrains_mono, FontWeight.Normal, FontWeight.Medium, FontWeight.Bold)

private val base = Typography()

val AgentTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Serif),
    displayMedium = base.displayMedium.copy(fontFamily = Serif),
    displaySmall = base.displaySmall.copy(fontFamily = Serif),
    headlineLarge = base.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    headlineMedium = base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    headlineSmall = base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = Sans, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Sans, lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(fontFamily = Sans, lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
)

/** 助手正文：衬线 16/26，claude.ai 的回复行高约 1.65 */
val ReplyText = TextStyle(fontFamily = Serif, fontSize = 16.sp, lineHeight = 26.sp)

/** 代码、diff、工具行 */
val CodeText = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp)
