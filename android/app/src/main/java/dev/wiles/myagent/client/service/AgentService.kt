package dev.wiles.myagent.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.wiles.myagent.client.AgentApp
import dev.wiles.myagent.client.MainActivity
import dev.wiles.myagent.client.R
import dev.wiles.myagent.client.state.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 前台服务：把 WebSocket 连接从「界面活着才有」升格成「用户说断才断」。
 *
 * <p>Android 会回收后台 app 的进程，而这个 app 的核心场景恰恰发生在后台——
 * agent 跑了十分钟，突然要授权，人早就切去干别的了。前台服务 + 常驻通知
 * 是系统认可的「我在干活别杀我」的说法；审批到了再单独弹一条高优通知。
 *
 * <p>连接本体在 {@link dev.wiles.myagent.client.net.AgentClient}（Application 级单例），
 * 服务只是给它续命 + 盯着状态发通知，不拥有它。
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ONGOING_ID, ongoing("已连接"))
        watch()
        return START_STICKY
    }

    private fun watch() {
        val client = (application as AgentApp).client
        val nm = getSystemService(NotificationManager::class.java)

        // 连接状态 → 常驻通知的那行字
        scope.launch {
            client.store.state
                .map { it.link to it.busy }
                .distinctUntilChanged()
                .collect { (link, busy) ->
                    when (link) {
                        Link.CONNECTED ->
                            nm.notify(ONGOING_ID, ongoing(if (busy) "agent 正在干活…" else "已连接，空闲"))

                        Link.RECONNECTING -> nm.notify(ONGOING_ID, ongoing("断线，重连中…"))
                        Link.CONNECTING -> nm.notify(ONGOING_ID, ongoing("连接中…"))
                        Link.CLOSED -> {
                            // 用户主动断开：使命结束，通知和服务一起收
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
        }

        // 审批来了弹高优通知；答完（approval 变 null）自动收
        scope.launch {
            client.store.state
                .map { it.approval }
                .distinctUntilChanged()
                .collect { approval ->
                    if (approval == null) {
                        nm.cancel(APPROVAL_ID)
                    } else {
                        nm.notify(
                            APPROVAL_ID,
                            NotificationCompat.Builder(this@AgentService, CHANNEL_APPROVAL)
                                .setSmallIcon(R.drawable.ic_stat_agent)
                                .setColor(BRAND)
                                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                                .setContentTitle("myagent 在等你批准")
                                .setContentText(approval.action)
                                .setStyle(NotificationCompat.BigTextStyle().bigText(
                                    "${approval.action}\n${approval.detail}",
                                ))
                                .setPriority(NotificationCompat.PRIORITY_HIGH)
                                .setAutoCancel(true)
                                .setContentIntent(openApp())
                                .addAction(0, "同意", decide(approval.id, "yes"))
                                .addAction(0, "拒绝", decide(approval.id, "no"))
                                .build(),
                        )
                    }
                }
        }
    }

    private fun ongoing(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setColor(BRAND)
            .setContentTitle("myagent")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 通知按钮 → ApprovalActionReceiver。request code 按决定区分，否则两个按钮会共用一个 PendingIntent */
    private fun decide(id: Int, decision: String): PendingIntent = PendingIntent.getBroadcast(
        this,
        if (decision == "yes") 1 else 2,
        Intent(this, ApprovalActionReceiver::class.java)
            .putExtra(ApprovalActionReceiver.EXTRA_ID, id)
            .putExtra(ApprovalActionReceiver.EXTRA_DECISION, decision),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "连接状态", NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVAL, "等待批准", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ONGOING = "ongoing"
        const val CHANNEL_APPROVAL = "approval"
        const val ONGOING_ID = 1
        const val APPROVAL_ID = 2
        const val BRAND = 0xFFD97757.toInt()
    }
}
