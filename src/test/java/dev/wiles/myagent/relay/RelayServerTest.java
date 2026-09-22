package dev.wiles.myagent.relay;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真起一个 relay、真连两条 WebSocket 上去。
 *
 * <p>不 mock：这一层的全部价值就是「两条真实连接能不能对上」，
 * mock 掉传输之后剩下的逻辑还不到二十行，测了也证明不了什么。
 */
class RelayServerTest {

    private RelayServer relay;
    private int port;
    private final List<WebSocketClient> opened = new ArrayList<>();

    @BeforeEach
    void startRelay() throws Exception {
        relay = new RelayServer(0);
        CountDownLatch up = new CountDownLatch(1);
        // onStart 在绑定完成后才跑，端口要等到那时候才知道
        new Thread(() -> relay.run(), "relay-test").start();
        for (int i = 0; i < 100 && relay.getPort() <= 0; i++) {
            Thread.sleep(20);
        }
        port = relay.getPort();
        assertTrue(port > 0, "relay 没起来");
        up.countDown();
    }

    @AfterEach
    void stopRelay() throws Exception {
        for (WebSocketClient c : opened) {
            c.closeBlocking();
        }
        opened.clear();
        relay.stop(500);
    }

    /** 一条测试用连接，收到的帧全攒下来。 */
    private final class Peer extends WebSocketClient {
        final List<String> received = new ArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile int closeCode;

        Peer(String room, String role) throws Exception {
            super(new URI("ws://127.0.0.1:" + port + "/ws?room=" + room + "&role=" + role));
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
        }

        @Override
        public void onMessage(String message) {
            synchronized (received) {
                received.add(message);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closeCode = code;
            closed.countDown();
        }

        @Override
        public void onError(Exception ex) {
        }

        /** 等到收够 n 帧（或超时）。轮询比 sleep 固定时长稳。 */
        List<String> awaitFrames(int n) throws Exception {
            for (int i = 0; i < 200; i++) {
                synchronized (received) {
                    if (received.size() >= n) {
                        return List.copyOf(received);
                    }
                }
                Thread.sleep(10);
            }
            synchronized (received) {
                return List.copyOf(received);
            }
        }
    }

    private Peer connect(String room, String role) throws Exception {
        Peer peer = new Peer(room, role);
        assertTrue(peer.connectBlocking(3, TimeUnit.SECONDS), "连不上 relay");
        opened.add(peer);
        return peer;
    }

    private static final String ROOM = "0123456789abcdef0123";

    @Test
    @DisplayName("host 发的原样到 client，client 发的原样到 host")
    void routesBothWays() throws Exception {
        Peer host = connect(ROOM, "host");
        Peer client = connect(ROOM, "client");
        client.awaitFrames(1);              // 先等 peer 通告落地

        host.send("{\"t\":\"d\",\"n\":1,\"c\":\"AAAA\"}");
        List<String> gotByClient = client.awaitFrames(2);
        assertTrue(gotByClient.contains("{\"t\":\"d\",\"n\":1,\"c\":\"AAAA\"}"),
                "client 没收到 host 的帧: " + gotByClient);

        client.send("{\"t\":\"d\",\"n\":2,\"c\":\"BBBB\"}");
        List<String> gotByHost = host.awaitFrames(3);
        assertTrue(gotByHost.contains("{\"t\":\"d\",\"n\":2,\"c\":\"BBBB\"}"),
                "host 没收到 client 的帧: " + gotByHost);
    }

    @Test
    @DisplayName("两边都在时通告 peer:true——手机才分得清「电脑没开机」和「正在等」")
    void announcesPeerPresence() throws Exception {
        Peer host = connect(ROOM, "host");
        // 只有自己时不该说对面在
        List<String> alone = host.awaitFrames(1);
        assertTrue(alone.stream().anyMatch(f -> f.contains("\"present\":false")), alone.toString());

        connect(ROOM, "client");
        List<String> paired = host.awaitFrames(2);
        assertTrue(paired.stream().anyMatch(f -> f.contains("\"present\":true")), paired.toString());
    }

    @Test
    @DisplayName("不同房间的帧串不了台")
    void roomsAreIsolated() throws Exception {
        Peer host = connect(ROOM, "host");
        Peer otherClient = connect("ffffffffffffffffffff", "client");
        host.send("{\"t\":\"d\",\"n\":1,\"c\":\"SECRET\"}");
        Thread.sleep(200);
        synchronized (otherClient.received) {
            assertTrue(otherClient.received.stream().noneMatch(f -> f.contains("SECRET")),
                    "帧串到别的房间去了: " + otherClient.received);
        }
    }

    @Test
    @DisplayName("后到的 host 顶掉先到的——笔记本睡醒重连时旧连接往往还没判死")
    void newerHostReplacesOlder() throws Exception {
        Peer first = connect(ROOM, "host");
        Peer second = connect(ROOM, "host");
        assertTrue(first.closed.await(3, TimeUnit.SECONDS), "旧 host 没被顶掉");
        assertTrue(second.isOpen());
    }

    @Test
    @DisplayName("房间号太短或不是 base64url 一律拒——随机性是这套安全模型的地基")
    void rejectsWeakRoom() throws Exception {
        Peer tooShort = new Peer("abc", "host");
        tooShort.connectBlocking(3, TimeUnit.SECONDS);
        opened.add(tooShort);
        assertTrue(tooShort.closed.await(3, TimeUnit.SECONDS));
        assertEquals(1008, tooShort.closeCode);
    }

    @Test
    @DisplayName("认不出的 role 拒掉，不当成 host 也不当成 client")
    void rejectsUnknownRole() throws Exception {
        Peer weird = new Peer(ROOM, "admin");
        weird.connectBlocking(3, TimeUnit.SECONDS);
        opened.add(weird);
        assertTrue(weird.closed.await(3, TimeUnit.SECONDS));
        assertEquals(1008, weird.closeCode);
    }

    @Test
    @DisplayName("人走光了房间就地回收，不在内存里留痕")
    void emptyRoomIsReclaimed() throws Exception {
        Peer host = connect(ROOM, "host");
        assertEquals(1, relay.roomCount());
        host.closeBlocking();
        for (int i = 0; i < 100 && relay.roomCount() > 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(0, relay.roomCount());
    }
}
