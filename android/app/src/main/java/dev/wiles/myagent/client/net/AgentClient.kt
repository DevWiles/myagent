package dev.wiles.myagent.client.net

import dev.wiles.myagent.client.protocol.ClientCommand
import dev.wiles.myagent.client.protocol.PROTOCOL_VERSION
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
import org.json.JSONObject
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 和后端的连接，两种走法（协议 1.1 / 1.3）：
 *
 * - **局域网直连**：直接连后端的 WebSocket，明文，只在同一个网段内可用
 * - **中继**：连一台公网上的 relay，两端都是出站连接，**内容端到端加密**，
 *   任何网络都能用——这是常用的那条
 *
 * <p>两种走法之上是同一套东西：同一个 {@link SessionStore}、同一份协议解析、
 * 同一套断线重连和 seq 去重。传输的差别被关在这个类里，
 * 界面层根本不知道这一帧是从哪条路来的。
 *
 * <p>单例（Application 持有）：连接的生命周期比任何一个 Activity 都长，
 * 转屏、切后台都不该断连。
 */
class AgentClient {

    val store = SessionStore()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        // 事件之间隔几分钟是常态（模型在想、工具在跑），读超时必须关掉
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var pairing: Pairing? = null

    /** 见过的最大广播 seq。补发和实时可能重叠，靠它去重（协议 1.2）。 */
    @Volatile
    private var lastSeq = 0L

    @Volatile
    private var wanted = false

    // ---- 中继模式下的加密状态。局域网模式下全是 null/0 ----

    @Volatile
    private var sendKey: ByteArray? = null

    @Volatile
    private var recvKey: ByteArray? = null
    private var sendCounter = 0L

    /** 收到过的最大计数器。**不严格递增的一律丢**——这就是防重放。 */
    private var recvHighWater = -1L

    /** 断线窗口里敲的命令不丢，连上补发（同 TUI 的 outbox）。 */
    private val outbox = ArrayDeque<String>()

    val connected: Boolean get() = store.state.value.link == Link.CONNECTED

    /**
     * 连接层的日志。
     *
     * <p>加它的由来：第一次真机联调时手机卡在「连接中」，而 app 一行日志都没有——
     * relay 好的、后端好的、网络也通，就是不知道 app 卡在哪一步。
     * **一个排查不了的连接层，比一个有 bug 的连接层更糟**。
     */
    private fun log(message: String) = Log.i("myagent-net", message)

    /** 扫码/手填拿到配对信息后调它。已连着别的后端时先断干净。 */
    fun connect(pairing: Pairing) {
        disconnect(silent = true)
        wanted = true
        this.pairing = pairing
        lastSeq = 0
        resetSession()
        store.setLink(Link.CONNECTING)
        log("connect: ${if (pairing is Pairing.Relay) "relay" else "lan"}")
        open(pairing)
    }

    fun disconnect(reason: String = "已断开连接", silent: Boolean = false) {
        wanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "bye")
        socket = null
        if (!silent && pairing != null) store.setLink(Link.CLOSED, reason)
        pairing = null
        resetSession()
        synchronized(outbox) { outbox.clear() }
    }

    private fun resetSession() {
        sendKey = null
        recvKey = null
        sendCounter = 0
        recvHighWater = -1
    }

    fun send(command: String) {
        val ws = socket
        if (ws == null || !connected) {
            synchronized(outbox) { outbox.addLast(command) }
            return
        }
        transmit(ws, command)
    }

    /** 按当前走法把一条命令送出去：中继要封装加密，局域网原样发。 */
    private fun transmit(ws: WebSocket, command: String) {
        val key = sendKey
        if (pairing is Pairing.Relay) {
            if (key == null) {
                // 还没拿到会话盐，攒着——发出去对面也解不开
                synchronized(outbox) { outbox.addLast(command) }
                return
            }
            val counter = sendCounter++
            val frame = JSONObject()
                .put("t", "d")
                .put("n", counter)
                .put("c", RelayCrypto.seal(key, counter, command))
            ws.send(frame.toString())
        } else {
            ws.send(command)
        }
    }

    private fun open(pairing: Pairing) {
        val url = when (pairing) {
            is Pairing.Lan ->
                // 局域网模式下 since 塞在 URL 里（后端握手时就能看见）
                "${pairing.url}&since=$lastSeq"

            is Pairing.Relay -> pairing.clientUrl()
        }
        log("open $url")
        val request = Request.Builder().url(url.toHttpish()).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) {
                    log("忽略旧 socket 的帧")
                    return
                }
                when (val p = this@AgentClient.pairing) {
                    is Pairing.Relay -> handleRelayFrame(webSocket, p, text)
                    else -> handleProtocol(text)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== socket) return
                scheduleReconnect("连接已关闭${if (reason.isNotBlank()) "：$reason" else ""}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                log("onFailure: ${t.javaClass.simpleName} ${t.message} resp=${response?.code}")
                scheduleReconnect(t.message ?: "连接失败")
            }
        })
    }

    /** OkHttp 认 http(s)，ws(s) 要换一下头。 */
    private fun String.toHttpish(): String = when {
        startsWith("ws://") -> "http://" + substring(5)
        startsWith("wss://") -> "https://" + substring(6)
        else -> this
    }

    /**
     * 中继转来的一帧：可能是 relay 自己的 peer 通告、host 的 hello、或者密文。
     *
     * <p>**hello 是整条链路的起点**：它带着这次会话的盐，两端据此各算两把密钥。
     * host 只在「手机到了」那一下发它，所以我们连上之后总会收到一帧。
     */
    private fun handleRelayFrame(ws: WebSocket, p: Pairing.Relay, raw: String) {
        val node = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return          // 脏报文当噪声丢掉
        }
        when (node.optString("t")) {
            "peer" -> {
                log("peer present=${node.optBoolean("present", false)}")
                // 对面在不在。false 时不断连——电脑可能只是暂时睡着了，
                // 等它回来 relay 会再通知一次
                if (!node.optBoolean("present", false)) {
                    store.setLink(Link.RECONNECTING, "电脑不在线")
                }
            }

            "hello" -> {
                log("收到 hello，派生会话密钥")
                val sid = node.optString("sid")
                if (sid.isEmpty()) return
                recvKey = RelayCrypto.deriveKey(p.key, p.room, sid, RelayCrypto.INFO_HOST_TO_CLIENT)
                sendKey = RelayCrypto.deriveKey(p.key, p.room, sid, RelayCrypto.INFO_CLIENT_TO_HOST)
                sendCounter = 0
                recvHighWater = -1
                // 中继模式下 since 塞不进 URL（我们连的是 relay 不是后端），
                // 所以用一条内层命令要首帧 + 补发（协议 1.3）
                transmit(ws, ClientCommand.hello(lastSeq))
                flushOutbox(ws)
            }

            "d" -> {
                val counter = node.optLong("n", -1)
                if (counter <= recvHighWater) return      // 重放/乱序，丢
                val key = recvKey ?: return
                val plain = try {
                    RelayCrypto.open(key, counter, node.optString("c"))
                } catch (e: Exception) {
                    // 解不开通常意味着配对密钥不对（换过 --relay-reset），
                    // 或者我们还拿着上一次会话的密钥
                    store.notice(Entry.Notice.Level.WARN, "有一帧解不开——密钥可能已经换过，重新扫码试试")
                    return
                }
                recvHighWater = counter
                handleProtocol(plain)
            }
        }
    }

    /** 拆封之后就是普通协议 JSON，两种走法在这里汇合。 */
    private fun handleProtocol(text: String) {
        val event = parseServerEvent(text) ?: return
        // 补发和实时可能重叠：广播事件按 seq 去重。定向回执（seq=null）不参与
        val seq = event.seq
        if (seq != null) {
            if (seq <= lastSeq) return
            lastSeq = seq
        }
        store.apply(event)
        if (event is ServerEvent.Ready) {
            if (event.protocol != 0 && event.protocol != PROTOCOL_VERSION) {
                store.notice(
                    Entry.Notice.Level.ERROR,
                    "协议版本对不上：app 是 $PROTOCOL_VERSION，后端是 ${event.protocol}。更新其中一边",
                )
            }
            socket?.let { flushOutbox(it) }
        }
    }

    private fun flushOutbox(ws: WebSocket) {
        val pending = synchronized(outbox) {
            val copy = outbox.toList()
            outbox.clear()
            copy
        }
        for (command in pending) transmit(ws, command)
    }

    /**
     * 退避重连。挑不出「这次该不该重连」的聪明判断——密钥错、后端关了、网断了
     * 在这儿长得一模一样；诚实的做法是一直试并把状态摆在界面上。
     */
    private fun scheduleReconnect(reason: String) {
        if (!wanted) return
        val target = pairing ?: return
        socket = null
        resetSession()      // 下次连上会收到新的 hello，旧密钥一律作废
        store.setLink(Link.RECONNECTING, reason)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoff = 1000L
            while (wanted && store.state.value.link != Link.CONNECTED) {
                delay(backoff)
                if (!wanted) return@launch
                open(target)
                delay(3000)      // 给这次尝试留出握手时间
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }
}
