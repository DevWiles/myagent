package dev.wiles.myagent.client.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 竖屏的扫码页。
 *
 * <p>zxing-android-embedded 自带的 CaptureActivity **默认锁横屏**——
 * 用户举着手机竖着扫，界面却横过来，这是库的历史默认，不是我们的选择。
 * 库认的定制方式就是「给个子类 + Manifest 里声明竖屏」，类体是空的，
 * 方向锁写在 Manifest 的 screenOrientation 上。
 */
class PortraitCaptureActivity : CaptureActivity()
