package dev.wiles.myagent.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真起一个 relay、真挂一条 {@link RelayLink} 上去、再真扮一次手机。
 *
 * <p>这组测试的由来值得记一笔：最早 host 是**在自己连上 relay 那一刻**发 hello 的，
 * 单测全绿，因为单测里两端总是同时出现。而真实场景是电脑先挂上去、
 * 手机几小时后才来——它永远收不到那一帧，于是拿不到会话盐、一个字都解不开。
 * 下面第一个用例就是照着那个现场写的。
 */
class RelayLinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RelayServer relay;
    private int port;
    private RelayLink link;
    private final List<String> inbound = new CopyOnWriteArrayList<>();
    private final List<Boolean> presence = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startRelay() throws Exception {
        relay = new RelayServer(0);
        new Thread(relay::run, "relay-link-test").start();
        for (int i = 0; i < 100 && relay.getPort() <= 0; i++) {
            Thread.sleep(20);
        }
        port = relay.getPort();
        assertTrue(port > 0);
    }

    @AfterEach
    void stopAll() throws Exception {
        if (link != null) {
            link.close();
        }
        relay.stop(500);
    }

    /** 扮手机：连上、等 hello、派生密钥、加解密。和真手机走的是同一套算法。 */
    private final class FakePhone extends WebSocketClient {
        private final String pairingKey;
        private final String room;
        private byte[] sendKey;
        private byte[] recvKey;
        private long sendCounter = 0;
        final List<String> plaintexts = new CopyOnWriteArrayList<>();
        final CountDownLatch keysReady = new CountDownLatch(1);

        FakePhone(Pairing pairing) {
            super(URI.create("ws://127.0.0.1:" + port + "/ws?room=" + pairing.room() + "&role=client"));
            this.pairingKey = pairing.key();
            this.room = pairing.room();
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonNode node = MAPPER.readTree(message);
                if ("hello".equals(node.path("t").asText())) {
                    String sid = node.path("sid").asText();
                    recvKey = RelayCrypto.deriveKey(pairingKey, room, sid,
                            RelayCrypto.INFO_HOST_TO_CLIENT);
                    sendKey = RelayCrypto.deriveKey(pairingKey, room, sid,
                            RelayCrypto.INFO_CLIENT_TO_HOST);
                    keysReady.countDown();
                } else if ("d".equals(node.path("t").asText())) {
                    plaintexts.add(RelayCrypto.open(recvKey, node.path("n").asLong(),
                            node.path("c").asText()));
                }
            } catch (Exception e) {
                plaintexts.add("<解不开: " + e + ">");
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
        }

        void sendEncrypted(String plaintext) {
            long counter = sendCounter++;
            ObjectNode frame = MAPPER.createObjectNode();
            frame.put("t", "d");
            frame.put("n", counter);
            frame.put("c", RelayCrypto.seal(sendKey, counter, plaintext));
            send(frame.toString());
        }

        /** 原样发一帧（用来重放）。 */
        void sendRaw(String raw) {
            send(raw);
        }

        String lastFrameToHost(String plaintext, long counter) {
            ObjectNode frame = MAPPER.createObjectNode();
            frame.put("t", "d");
            frame.put("n", counter);
            frame.put("c", RelayCrypto.seal(sendKey, counter, plaintext));
            return frame.toString();
        }
    }

    private Pairing startLink(Path dir) {
        Pairing pairing = Pairing.loadOrCreate(dir.resolve("relay.json"), false);
        link = new RelayLink("ws://127.0.0.1:" + port + "/ws", pairing,
                inbound::add, presence::add);
        link.start();
        return pairing;
    }

    private static void await(CountDownLatch latch) throws Exception {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "等超时了");
    }

    private void awaitSize(List<?> list, int n) throws Exception {
        for (int i = 0; i < 300 && list.size() < n; i++) {
            Thread.sleep(10);
        }
        assertTrue(list.size() >= n, "只等到 " + list.size() + " 条: " + list);
    }

    /**
     * 等到某个条件成立。
     *
     * <p>**别拿「收到几条」当「状态到了哪」**——这两件事在异步里不是一回事。
     * presence 的序列是 false（host 独自在线）→ true（手机来了）→ false（手机走了），
     * 按条数等的话，第二条刚到就以为收齐了，断言打在一个中间态上，
     * 于是测试**时快时慢地红**。这个坑是本测试自己踩出来的。
     */
    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition)
            throws Exception {
        for (int i = 0; i < 300 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }

    @Test
    @DisplayName("手机是后来才到的，也必须拿得到会话盐——这条是照着真实现场写的")
    void phoneJoiningLaterStillGetsSession(@TempDir Path dir) throws Exception {
        Pairing pairing = startLink(dir);
        Thread.sleep(500);                      // host 先挂上去，手机还没影

        FakePhone phone = new FakePhone(pairing);
        assertTrue(phone.connectBlocking(3, TimeUnit.SECONDS));
        await(phone.keysReady);                 // 手机到了才发 hello，这里才等得到
        phone.closeBlocking();
    }

    @Test
    @DisplayName("手机发的命令，后端拿到的是原样明文")
    void clientToHostRoundTrip(@TempDir Path dir) throws Exception {
        Pairing pairing = startLink(dir);
        FakePhone phone = new FakePhone(pairing);
        assertTrue(phone.connectBlocking(3, TimeUnit.SECONDS));
        await(phone.keysReady);

        phone.sendEncrypted("{\"t\":\"prompt\",\"text\":\"中文也要过一遍\"}");
        awaitSize(inbound, 1);
        assertEquals("{\"t\":\"prompt\",\"text\":\"中文也要过一遍\"}", inbound.get(0));
        phone.closeBlocking();
    }

    @Test
    @DisplayName("后端广播的事件，手机解得开")
    void hostToClientRoundTrip(@TempDir Path dir) throws Exception {
        Pairing pairing = startLink(dir);
        FakePhone phone = new FakePhone(pairing);
        assertTrue(phone.connectBlocking(3, TimeUnit.SECONDS));
        await(phone.keysReady);
        Thread.sleep(200);                       // 让 host 侧也把密钥装好

        link.send("{\"t\":\"busy\",\"busy\":true}");
        awaitSize(phone.plaintexts, 1);
        assertEquals("{\"t\":\"busy\",\"busy\":true}", phone.plaintexts.get(0));
        phone.closeBlocking();
    }

    @Test
    @DisplayName("重放同一帧不会被处理第二次——不然录一帧「同意」就能反复替用户点头")
    void replayIsDropped(@TempDir Path dir) throws Exception {
        Pairing pairing = startLink(dir);
        FakePhone phone = new FakePhone(pairing);
        assertTrue(phone.connectBlocking(3, TimeUnit.SECONDS));
        await(phone.keysReady);

        String frame = phone.lastFrameToHost("{\"t\":\"approval_reply\",\"id\":1,"
                + "\"decision\":\"yes\"}", 0);
        phone.sendRaw(frame);
        awaitSize(inbound, 1);
        phone.sendRaw(frame);                    // 一模一样再来一次
        Thread.sleep(400);

        assertEquals(1, inbound.size(), "重放帧被处理了第二次: " + inbound);
        phone.closeBlocking();
    }

    @Test
    @DisplayName("拿错配对密钥的人解不开，也发不进来")
    void wrongKeyGetsNothing(@TempDir Path dir) throws Exception {
        Pairing pairing = startLink(dir);
        FakePhone phone = new FakePhone(pairing);
        assertTrue(phone.connectBlocking(3, TimeUnit.SECONDS));
        await(phone.keysReady);

        // 用另一把密钥伪造一帧
        byte[] wrongKey = RelayCrypto.deriveKey(RelayCrypto.randomKey(), pairing.room(),
                RelayCrypto.randomId(), RelayCrypto.INFO_CLIENT_TO_HOST);
        ObjectNode forged = MAPPER.createObjectNode();
        forged.put("t", "d");
        forged.put("n", 99);
        forged.put("c", RelayCrypto.seal(wrongKey, 99, "{\"t\":\"reset\"}"));
        phone.sendRaw(forged.toString());
        Thread.sleep(400);

        assertTrue(inbound.isEmpty(), "伪造的帧被当成命令处理了: " + inbound);
        phone.closeBlocking();
    }

    @Test
    @DisplayName("手机来了/走了都会通知到——界面据此能说清「电脑在不在」")
    void reportsPresence(@TempDir Path dir) throws Exception {
        Pairing pairing = startLink(dir);
        FakePhone phone = new FakePhone(pairing);
        assertTrue(phone.connectBlocking(3, TimeUnit.SECONDS));
        awaitUntil("手机上线的通告", () -> presence.contains(true));

        phone.closeBlocking();
        awaitUntil("手机下线的通告",
                () -> !presence.isEmpty() && Boolean.FALSE.equals(presence.get(presence.size() - 1)));
    }

    @Test
    @DisplayName("中继还没连上时发东西不抛异常——本机界面不该被中继拖垮")
    void sendBeforeConnectedIsSafe(@TempDir Path dir) {
        Pairing pairing = Pairing.loadOrCreate(dir.resolve("relay.json"), false);
        link = new RelayLink("ws://127.0.0.1:1/ws", pairing, inbound::add, presence::add);
        link.start();
        assertDoesNotThrow(() -> link.send("{\"t\":\"busy\"}"));
        assertFalse(link.isOpen());
    }

}
