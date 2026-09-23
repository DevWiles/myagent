package dev.wiles.myagent.client.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 触感只有三种（抄 Happy 的 haptics.ts 那份克制）：
 * 轻点 = 发送/切模式/展开；确认 = 批准、一轮跑完；拒绝 = 拒绝、出错、断线。
 * 多了就成噪音，手机在兜里一直嗡嗡响比不响还糟。
 */
enum class Buzz { TICK, CONFIRM, REJECT }

@Composable
fun rememberHaptics(): (Buzz) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { buzz ->
            val constant = when (buzz) {
                Buzz.TICK -> HapticFeedbackConstants.CLOCK_TICK
                Buzz.CONFIRM ->
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.VIRTUAL_KEY
                Buzz.REJECT ->
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
                    else HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(constant)
        }
    }
}
