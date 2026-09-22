package dev.wiles.myagent.client

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wiles.myagent.client.service.AgentService
import dev.wiles.myagent.client.state.Link
import dev.wiles.myagent.client.store.Prefs
import dev.wiles.myagent.client.ui.AgentTheme
import dev.wiles.myagent.client.ui.ChatScreen
import dev.wiles.myagent.client.ui.ConnectScreen

class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒了就不弹通知，功能照常 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = (application as AgentApp).client
        val prefs = Prefs(applicationContext)

        // Android 13+ 通知要跑权限。审批推送是这个 app 的核心场景，进门就要
        if (Build.VERSION.SDK_INT >= 33) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AgentTheme {
                Root(client = client, prefs = prefs, onConnected = {
                    // 连接归前台服务保着，Activity 只是它的一块显示屏
                    startForegroundService(Intent(this, AgentService::class.java))
                })
            }
        }
    }
}

@Composable
private fun Root(
    client: dev.wiles.myagent.client.net.AgentClient,
    prefs: Prefs,
    onConnected: () -> Unit,
) {
    val state by client.store.state.collectAsStateWithLifecycle()

    // 连上了才起前台服务；断开（CLOSED）时 ChatScreen 会自己给出重连/换后端入口
    val connectedNow = state.link == Link.CONNECTED
    LaunchedEffect(connectedNow) {
        if (connectedNow) onConnected()
    }

    // 收到过 ready 才进会话页；重连中（ready 还在）留在会话页，转录不该被踢没。
    // 用户主动断开（CLOSED）回连接页
    if (state.ready != null && state.link != Link.CLOSED) {
        ChatScreen(client = client, state = state)
    } else {
        ConnectScreen(
            client = client,
            prefs = prefs,
            connecting = state.link == Link.CONNECTING || state.link == Link.RECONNECTING,
            closedReason = state.closedReason,
        )
    }
}
