package dev.wiles.myagent.relay;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 中继：按房间号把两条出站连接对上，只转发，不解密（协议 1.3）。
 *
 * <h2>为什么它可以这么笨</h2>
 * 这是整套设计里**权限最小的一环**，也是唯一一台放在公网上的机器。
 * 它没有密钥、看不懂内容、不落盘、不记正文——所以即使这台机器整个被拿下，
 * 攻击者能做的也只有「断掉服务」这一档，拿不到一个字的代码。
 *
 * <p>安全性全靠两件事，都不在这个类里：房间号是 128 位随机数（猜不到），
 * 内容是 AES-GCM 密文（挤进来也解不开、伪造不了）。
 *
 * <h2>为什么和 agent 打在同一个 jar 里</h2>
 * 省一套构建和一套部署。这个 jar 在服务器上只会以 {@code --relay-serve} 起，
 * 那条路径上不碰 API key、不注册任何工具、不起 agent——
 * 多一个 Maven 模块换来的是每次改协议都要发两个包。
 *
 * <h2>放在 TLS 后面</h2>
 * 它自己只说明文 ws，由 nginx 做 wss 终结。理由和「不在局域网模式做 TLS」一样：
 * 证书该由已经在管证书的那一层管，不该让应用自己维护一份。
 */
public final class RelayServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(RelayServer.class);

    /** 单帧上限。正常一帧是几百字节到几十 KB，1MB 已经宽得离谱 */
    private static final int MAX_FRAME = 1024 * 1024;

    /** 一个房间：一个 host + 一个 client，谁都可能暂时缺席。 */
    private static final class Room {
        volatile WebSocket host;
        volatile WebSocket client;
        /** 上一次**通告出去**的配对状态。用来把「变化」和「没变化」分开。 */
        boolean announced;
    }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);

    public RelayServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    /** 当前有多少个房间。给测试和运维看一眼用，不含任何内容。 */
    public int roomCount() {
        return rooms.size();
    }

    @Override
    public void onStart() {
        log.info("relay 起来了，端口 {}", getPort());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String resource = handshake.getResourceDescriptor();
        String room = param(resource, "room");
        String role = param(resource, "role");

        if (room == null || room.length() < 16 || !isBase64Url(room)) {
            // 房间号的随机性是这套安全模型的地基，短的一律不收
            conn.close(1008, "bad room");
            return;
        }
        if (!"host".equals(role) && !"client".equals(role)) {
            conn.close(1008, "bad role");
            return;
        }

        Room r = rooms.computeIfAbsent(room, key -> new Room());
        conn.setAttachment(new Seat(room, role));

        if ("host".equals(role)) {
            WebSocket old = r.host;
            r.host = conn;
            // 后到的 host 顶掉先到的：笔记本睡醒重连时，旧连接往往还没被判死
            if (old != null && old != conn && old.isOpen()) {
                old.close(1000, "replaced by a newer host");
            }
        } else {
            WebSocket old = r.client;
            r.client = conn;
            if (old != null && old != conn && old.isOpen()) {
                old.close(1000, "replaced by a newer client");
            }
        }
        log.debug("房间 {} 有人进来了（{}）", shortId(room), role);
        // 对面在不在，直接告诉刚进来的这位——省得它连上之后对着空气等
        notifyPeerState(r, conn);
    }

    /**
     * 告诉「对面在不在」。这是 relay 唯一自己生成的消息——没有它的话，
     * 手机连上之后在「电脑没开机」和「正在等第一帧」之间分不出区别，只能干等。
     *
     * <h3>为什么要分「新来的」和「状态变了」两种</h3>
     * 两端几乎同时连上时，两个 {@code onOpen} 会<b>并发</b>跑，各自都看到
     * 「两边都在」，于是同一条 present=true 发两遍。host 收到两遍就换两次会话盐，
     * 而手机用的是第一个——<b>之后每一帧都解不开</b>。
     *
     * <p>所以：**变化才广播**（边沿，而不是电平），另外单独把当前状态发给刚进来的那位
     * ——它需要知道现状，哪怕现状和刚才一样。
     *
     * @param justArrived 刚连上的那条连接；{@code null} 表示这次是有人走了
     */
    private void notifyPeerState(Room r, WebSocket justArrived) {
        synchronized (r) {
            boolean both = r.host != null && r.host.isOpen()
                    && r.client != null && r.client.isOpen();
            String frame = "{\"t\":\"peer\",\"present\":" + both + "}";
            if (justArrived != null) {
                send(justArrived, frame);
            }
            if (both != r.announced) {
                r.announced = both;
                // 只通知另一边：新来的那位上面已经收过了，再发一遍就又是重复
                WebSocket other = justArrived == r.host ? r.client : r.host;
                send(other, frame);
            }
        }
    }

    private static void send(WebSocket conn, String text) {
        if (conn != null && conn.isOpen()) {
            conn.send(text);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message.length() > MAX_FRAME) {
            conn.close(1009, "frame too large");
            return;
        }
        Seat seat = conn.getAttachment();
        if (seat == null) {
            return;
        }
        Room r = rooms.get(seat.room());
        if (r == null) {
            return;
        }
        // 转发，一个字节都不看。日志里也**只记长度不记内容**——
        // 端到端加密的前提是这台机器上不留下正文，哪怕是密文也没必要落
        WebSocket target = "host".equals(seat.role()) ? r.client : r.host;
        if (target != null && target.isOpen()) {
            target.send(message);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Seat seat = conn.getAttachment();
        if (seat == null) {
            return;
        }
        Room r = rooms.get(seat.room());
        if (r == null) {
            return;
        }
        // 只清掉自己那把椅子：被顶替的旧连接关闭时，不能把新连接一起抹掉
        if ("host".equals(seat.role()) && r.host == conn) {
            r.host = null;
        } else if ("client".equals(seat.role()) && r.client == conn) {
            r.client = null;
        }
        if (r.host == null && r.client == null) {
            rooms.remove(seat.room(), r);      // 空房间就地回收，不留痕
        } else {
            notifyPeerState(r, null);      // 有人走了，不存在「新来的」
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            log.error("relay 服务级错误", ex);
            stopped.countDown();
        } else {
            log.debug("连接出错", ex);
        }
    }

    /** 阻塞到服务停止。前台跑的时候用它挂住主线程。 */
    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    /** 这条连接坐在哪个房间的哪把椅子上。 */
    private record Seat(String room, String role) {
    }

    /** 日志里只出现房间号前 6 位——够排查，又不至于把完整房间号写进日志文件。 */
    private static String shortId(String room) {
        return room.length() <= 6 ? room : room.substring(0, 6) + "…";
    }

    private static boolean isBase64Url(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /** 从 "/ws?room=abc&role=host" 里抠一个查询参数。够用就行，不引 URL 解析库。 */
    static String param(String resource, String key) {
        if (resource == null) {
            return null;
        }
        int q = resource.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : resource.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
