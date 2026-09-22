package dev.wiles.myagent.client.net

import dev.wiles.myagent.client.protocol.ServerEvent
import dev.wiles.myagent.client.protocol.parseServerEvent
import dev.wiles.myagent.client.state.Entry
import dev.wiles.myagent.client.state.Link
import dev.wiles.myagent.client.state.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 和后端的那条 WebSocket，外加手机特有的两件事：**自动重连**和**seq 去重**。
 *
 * <p>终端的连接和后端同生共死，断了就是结束；手机切个后台、过个电梯连接必断，
 * 而后端还活着。所以这里断线不报丧，退避重连（1s 起，翻倍到 30s 封顶），
 * 重连时带上 `since=<见过的最大 seq>`，后端补发缺的那段（协议 1.2）。
 *
 * <p>首连带 `since=0`：把后端留底的近况全要过来——手机常常是**中途加入**的，
 * 不带的话屏幕上只有从连上那一刻起的事件，正在跑的那一轮前半截全是空白。
 *
 * <p>单例（Application 持有）：连接的生命周期比任何一个 Activity 都长，
 * 转屏、切后台都不该断连。
 */
class AgentClient {

    val store = SessionStore()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        // 后端 30 秒发一次 WebSocket ping（setConnectionLostTimeout），这边不用自己发；
        // 但读超时必须关掉——事件之间隔几分钟是常态（模型在想、工具在跑）
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null

    /** 配对 URL（ws://host:port/?token=…），连接期间不变。 */
    @Volatile
    private var pairedUrl: String? = null

    /** 见过的最大广播 seq。补发和实时可能重叠，靠它去重（协议 1.2）。 */
    @Volatile
    private var lastSeq = 0L

    /** 用户主动断开后不再重连。 */
    @Volatile
    private var wanted = false

    /**
     * 后端起来之前/断线期间攒下的命令，连上按序补发。
     * 和 TUI 的 outbox 同一个理由：静默扔掉的话，界面记了、后端永远收不到。
     */
    private val outbox = ArrayDeque<String>()

    val connected: Boolean get() = store.state.value.link == Link.CONNECTED

    /** 扫码/手填拿到 URL 后调它。已连着别的后端时先断干净。 */
    fun connect(url: String) {
        disconnect()
        wanted = true
        pairedUrl = url
        lastSeq = 0
        store.setLink(Link.CONNECTING)
        open(url, sinceParam = 0)
    }

    /** 用户主动断开：不再重连，状态记为 CLOSED。 */
    fun disconnect(reason: String = "已断开连接") {
        wanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "bye")
        socket = null
        if (pairedUrl != null) store.setLink(Link.CLOSED, reason)
        pairedUrl = null
        synchronized(outbox) { outbox.clear() }
    }

    fun send(command: String) {
        val ws = socket
        if (ws == null || !connected) {
            // 断线窗口里敲的话不丢，重连上补发
            synchronized(outbox) { outbox.addLast(command) }
            return
        }
        ws.send(command)
    }

    private fun open(url: String, sinceParam: Long) {
        val full = "$url&since=$sinceParam"
        val request = Request.Builder().url(full).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = parseServerEvent(text) ?: return
                // 补发和实时可能重叠：广播事件按 seq 去重，小于等于见过的直接丢。
                // 定向回执（seq=null）不参与——它们不会被补发，也就不会重复
                val seq = event.seq
                if (seq != null) {
                    if (seq <= lastSeq) return
                    lastSeq = seq
                }
                store.apply(event)
                if (event is ServerEvent.Ready) {
                    // 到 ready 才算真连上（token 错的话到不了这儿就被 close 了）
                    if (event.protocol != PROTOCOL_MISMATCH_GUARD && event.protocol != dev.wiles.myagent.client.protocol.PROTOCOL_VERSION) {
                        store.notice(
                            Entry.Notice.Level.ERROR,
                            "协议版本对不上：app 是 ${dev.wiles.myagent.client.protocol.PROTOCOL_VERSION}，" +
                                "后端是 ${event.protocol}。更新其中一边",
                        )
                    }
                    flushOutbox(webSocket)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== socket) return
                scheduleReconnect("连接已关闭${if (reason.isNotBlank()) "：$reason" else ""}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                scheduleReconnect(t.message ?: "连接失败")
            }
        })
    }

    private fun flushOutbox(ws: WebSocket) {
        val pending = synchronized(outbox) {
            val copy = outbox.toList()
            outbox.clear()
            copy
        }
        for (command in pending) ws.send(command)
    }

    /**
     * 退避重连。挑不出「这次该不该重连」的聪明判断——token 错、后端关了、网断了
     * 在这儿长得一模一样；诚实的做法是一直试并把状态摆在界面上，用户自己会判断。
     */
    private fun scheduleReconnect(reason: String) {
        if (!wanted) return
        val url = pairedUrl ?: return
        socket = null
        store.setLink(Link.RECONNECTING, reason)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoff = 1000L
            while (wanted && store.state.value.link != Link.CONNECTED) {
                delay(backoff)
                if (!wanted) return@launch
                open(url, sinceParam = lastSeq)
                // 给这次尝试留出握手时间；连上了 ready 会把 link 掰回 CONNECTED
                delay(3000)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    private companion object {
        // 只是为了让上面那行判断在 protocol=0（老后端根本没这字段）时不误报
        const val PROTOCOL_MISMATCH_GUARD = 0
    }
}
