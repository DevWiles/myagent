package dev.wiles.myagent.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.server.Peer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 后端这一侧的中继连接：**往外拨**，在 relay 上等手机来会合（协议 1.3）。
 *
 * <p>这是「任何网络都能连」的全部机密：从不监听、只往外连。
 * NAT 和防火墙对出站连接一律放行，所以家里、4G、公司网、咖啡馆都一样。
 *
 * <p>它对 {@link dev.wiles.myagent.server.AgentServer} 伪装成一个普通的
 * {@link Peer}：服务器照常广播、照常定向回执，不知道对面隔着一层加密和一台公网机器。
 *
 * <h2>会话盐为什么由 host 出</h2>
 * 配对密钥是长期的，计数器每次连上从 0 起。没有一个每次都换的盐，
 * 第二次连接就会重复使用 (key, nonce) ——GCM 在那种情况下直接失效。
 * 谁先到不一定，但**发 hello 的一定是 host**：手机可能先连上等着，
 * host 一上来就宣布「这次用这个盐」，双方据此重算密钥。
 */
public final class RelayLink implements Peer {

    private static final Logger log = LoggerFactory.getLogger(RelayLink.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String relayUrl;
    private final Pairing pairing;
    /** 收到一帧解开的明文（就是普通协议 JSON）后交给谁。 */
    private final Consumer<String> inbound;
    /** 手机来了/走了。界面可以据此提示，也可以不理。 */
    private final Consumer<Boolean> peerPresence;

    private final ScheduledExecutorService reconnector =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "relay-reconnect");
                t.setDaemon(true);
                return t;
            });

    private volatile WebSocketClient socket;
    private volatile byte[] sendKey;
    private volatile byte[] recvKey;
    private final AtomicLong sendCounter = new AtomicLong(0);
    /** 收到过的最大计数器。**不严格递增的一律丢**——这就是防重放。 */
    private final AtomicLong recvHighWater = new AtomicLong(-1);
    private final AtomicBoolean wanted = new AtomicBoolean(true);
    /**
     * 对面此刻在不在。**只在 false→true 那一下开新会话**。
     *
     * <p>relay 那边已经改成边沿触发了，这里再挡一道是因为：重复 announce 的症状
     * 不是「多发一帧」而是「手机从此一个字都解不开」——手机用的是第一个盐，
     * 我们留的是最后一个。这种代价的错，值得两端各防一次。
     */
    private volatile boolean peerPresent;
    private volatile long backoffMs = 1000;

    public RelayLink(String relayUrl, Pairing pairing,
                     Consumer<String> inbound, Consumer<Boolean> peerPresence) {
        this.relayUrl = relayUrl;
        this.pairing = pairing;
        this.inbound = inbound;
        this.peerPresence = peerPresence;
    }

    /** 拨出去。失败不抛——中继连不上不该拦住本机界面照常干活。 */
    public void start() {
        connect();
    }

    private void connect() {
        if (!wanted.get()) {
            return;
        }
        try {
            WebSocketClient client = new WebSocketClient(URI.create(pairing.hostUrl(relayUrl))) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    backoffMs = 1000;
                    log.info("已挂到中继上，等手机来连");
                }

                @Override
                public void onMessage(String message) {
                    handle(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.info("中继连接断了（{} {}），稍后重连", code, reason);
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    log.debug("中继连接出错", ex);
                }
            };
            client.setConnectionLostTimeout(30);
            socket = client;
            client.connect();
        } catch (RuntimeException e) {
            log.warn("中继拨号失败: {}", e.toString());
            scheduleReconnect();
        }
    }

    /**
     * 开一次新会话：换盐、重算两把密钥、计数器归零，然后把盐明文告诉对面。
     *
     * <p>盐明文发没关系——relay 看得见它，但没有配对密钥的话，盐推不出任何东西。
     * 而**每次手机来都换一次**，是计数器可以从 0 起的前提：配对密钥是长期的，
     * 没有新盐就等于用同一把密钥重复同一串 nonce（协议 1.3 里那段警告）。
     */
    private void announceSession() {
        String sid = RelayCrypto.randomId();
        sendKey = RelayCrypto.deriveKey(pairing.key(), pairing.room(), sid,
                RelayCrypto.INFO_HOST_TO_CLIENT);
        recvKey = RelayCrypto.deriveKey(pairing.key(), pairing.room(), sid,
                RelayCrypto.INFO_CLIENT_TO_HOST);
        sendCounter.set(0);
        recvHighWater.set(-1);
        ObjectNode hello = MAPPER.createObjectNode();
        hello.put("t", "hello");
        hello.put("sid", sid);
        WebSocketClient client = socket;
        if (client != null && client.isOpen()) {
            client.send(hello.toString());      // 明文，不走 send()——那个会加密
        }
    }

    private void scheduleReconnect() {
        if (!wanted.get()) {
            return;
        }
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 30_000);
        reconnector.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    /** relay 转来的一帧。可能是它自己的 peer 通告，也可能是手机的密文。 */
    private void handle(String raw) {
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            return;              // 脏报文当噪声丢掉，不断连
        }
        String type = node.path("t").asText("");
        if ("peer".equals(type)) {
            boolean present = node.path("present").asBoolean(false);
            if (present && !peerPresent) {
                // **手机到了才开一次会话**，不是我们连上 relay 那一刻。
                // 早先在 onOpen 里就发 hello，而手机通常是几小时后才来的——
                // 它永远收不到那一帧，于是拿不到会话盐、解不开任何东西。
                // 一条真跑的冒烟测试当场就把这个抓出来了
                announceSession();
            }
            peerPresent = present;
            peerPresence.accept(present);
            return;
        }
        if (!"d".equals(type)) {
            return;              // 认不出的一律忽略（协议第 2 节那条规矩，这一层同样适用）
        }
        long counter = node.path("n").asLong(-1);
        // 防重放：同一帧重放进来，counter 不比见过的大，直接丢。
        // 少了这一条，录下一帧 approval_reply 就能反复「替用户点同意」
        if (counter <= recvHighWater.get()) {
            log.debug("丢掉一帧重放/乱序的密文 n={}", counter);
            return;
        }
        byte[] key = recvKey;
        if (key == null) {
            return;              // 还没握手就来数据，说明对面用的是上一次会话的密钥
        }
        String plain;
        try {
            plain = RelayCrypto.open(key, counter, node.path("c").asText(""));
        } catch (Exception e) {
            // 解不开的三种原因（密钥不对/被篡改/计数器对不上）长一个样，这是 AEAD 的本意
            log.warn("中继来的帧解不开，丢弃");
            return;
        }
        recvHighWater.set(counter);
        inbound.accept(plain);
    }

    // ------------------------------------------------------------ Peer

    @Override
    public void send(String json) {
        WebSocketClient client = socket;
        byte[] key = sendKey;
        if (client == null || !client.isOpen() || key == null) {
            return;              // 中继断着的时候丢弃：补发归 1.2 那套管，不在这一层排队
        }
        long counter = sendCounter.getAndIncrement();
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("t", "d");
        frame.put("n", counter);
        frame.put("c", RelayCrypto.seal(key, counter, json));
        try {
            client.send(frame.toString());
        } catch (RuntimeException e) {
            log.debug("中继发送失败", e);
        }
    }

    @Override
    public boolean isOpen() {
        WebSocketClient client = socket;
        return client != null && client.isOpen() && sendKey != null;
    }

    /** 停掉，不再重连。 */
    public void close() {
        wanted.set(false);
        reconnector.shutdownNow();
        WebSocketClient client = socket;
        if (client != null) {
            client.close();
        }
    }
}
