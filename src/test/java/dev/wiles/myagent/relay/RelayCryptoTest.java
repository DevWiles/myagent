package dev.wiles.myagent.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 这组测试有一半是**跨语言契约**：带 VECTOR 字样的用例，Android 侧
 * （{@code RelayCryptoTest.kt}）用一模一样的输入输出跑一遍。
 * 两边有一边改了常量，就会有一边红——这正是要的。
 */
class RelayCryptoTest {

    // 固定的测试料。真实使用时全是随机的，这里钉死是为了两种语言能对结果
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";  // 0x00..0x1f
    private static final String ROOM = "ICEiIyQlJicoKSorLC0uLw";                      // 0x20..0x2f
    private static final String SID = "MDEyMzQ1Njc4OTo7PD0-Pw";                       // 0x30..0x3f

    @Test
    @DisplayName("VECTOR：HKDF 派生出来的密钥必须是这个值——两种语言对着它")
    void vectorDerive() {
        byte[] h2c = RelayCrypto.deriveKey(KEY, ROOM, SID, RelayCrypto.INFO_HOST_TO_CLIENT);
        byte[] c2h = RelayCrypto.deriveKey(KEY, ROOM, SID, RelayCrypto.INFO_CLIENT_TO_HOST);

        assertEquals(32, h2c.length);
        // 两个方向必须是两把不同的密钥——这条是 nonce 安全的地基
        assertNotEquals(HexFormat.of().formatHex(h2c), HexFormat.of().formatHex(c2h));

        // 钉死的期望值（由本实现产出，Kotlin 侧断言同一串）
        assertEquals(VECTOR_H2C_KEY, HexFormat.of().formatHex(h2c));
        assertEquals(VECTOR_C2H_KEY, HexFormat.of().formatHex(c2h));
    }

    @Test
    @DisplayName("VECTOR：同一把密钥、同一个计数器，密文必须一字不差")
    void vectorSeal() {
        byte[] key = RelayCrypto.deriveKey(KEY, ROOM, SID, RelayCrypto.INFO_HOST_TO_CLIENT);
        assertEquals(VECTOR_SEALED, RelayCrypto.seal(key, 7, "{\"t\":\"pong\"}"));
    }

    @Test
    @DisplayName("VECTOR：nonce 是 4 字节 0 + 8 字节大端计数器")
    void vectorNonce() {
        assertEquals("000000000000000000000000", HexFormat.of().formatHex(RelayCrypto.nonce(0)));
        assertEquals("000000000000000000000001", HexFormat.of().formatHex(RelayCrypto.nonce(1)));
        assertEquals("00000000000000000000ffff", HexFormat.of().formatHex(RelayCrypto.nonce(65535)));
    }

    @Test
    @DisplayName("封了能拆开，拆出来和原文一样")
    void roundTrip() throws Exception {
        byte[] key = RelayCrypto.deriveKey(KEY, ROOM, SID, RelayCrypto.INFO_CLIENT_TO_HOST);
        String plain = "{\"t\":\"prompt\",\"text\":\"中文也要过一遍 UTF-8\"}";
        assertEquals(plain, RelayCrypto.open(key, 3, RelayCrypto.seal(key, 3, plain)));
    }

    @Test
    @DisplayName("计数器对不上就解不开——防重放靠的就是这一条")
    void wrongCounterFails() {
        byte[] key = RelayCrypto.deriveKey(KEY, ROOM, SID, RelayCrypto.INFO_HOST_TO_CLIENT);
        String sealed = RelayCrypto.seal(key, 5, "hello");
        assertThrows(AEADBadTagException.class, () -> RelayCrypto.open(key, 6, sealed));
    }

    @Test
    @DisplayName("拿另一个方向的密钥解不开——两把密钥是真的分开的")
    void wrongDirectionFails() {
        byte[] h2c = RelayCrypto.deriveKey(KEY, ROOM, SID, RelayCrypto.INFO_HOST_TO_CLIENT);
        byte[] c2h = RelayCrypto.deriveKey(KEY, ROOM, SID, RelayCrypto.INFO_CLIENT_TO_HOST);
        String sealed = RelayCrypto.seal(h2c, 0, "hello");
        assertThrows(AEADBadTagException.class, () -> RelayCrypto.open(c2h, 0, sealed));
    }

    @Test
    @DisplayName("换一个会话盐就是另一把密钥——重启之后老密文再也解不开")
    void sessionSaltIsolates() {
        byte[] a = RelayCrypto.deriveKey(KEY, ROOM, SID, RelayCrypto.INFO_HOST_TO_CLIENT);
        byte[] b = RelayCrypto.deriveKey(KEY, ROOM, RelayCrypto.randomId(),
                RelayCrypto.INFO_HOST_TO_CLIENT);
        String sealed = RelayCrypto.seal(a, 0, "hello");
        assertThrows(AEADBadTagException.class, () -> RelayCrypto.open(b, 0, sealed));
    }

    @Test
    @DisplayName("改一个 bit 就认不出来——AEAD 的完整性")
    void tamperFails() {
        byte[] key = RelayCrypto.deriveKey(KEY, ROOM, SID, RelayCrypto.INFO_HOST_TO_CLIENT);
        String sealed = RelayCrypto.seal(key, 0, "hello world");
        char[] chars = sealed.toCharArray();
        chars[0] = chars[0] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);
        assertThrows(GeneralSecurityException.class, () -> RelayCrypto.open(key, 0, tampered));
    }

    @Test
    @DisplayName("随机料的长度和编码：房间号 16 字节、配对密钥 32 字节")
    void randomShapes() {
        assertEquals(16, RelayCrypto.unb64(RelayCrypto.randomId()).length);
        assertEquals(32, RelayCrypto.unb64(RelayCrypto.randomKey()).length);
        // base64url 不能带 + / =，否则塞进 URL 要再转义一次
        String id = RelayCrypto.randomId();
        assertFalse(id.contains("+") || id.contains("/") || id.contains("="), id);
    }

    @Test
    @DisplayName("HKDF 对着 RFC 5869 的 Test Case 1 校准")
    void rfc5869Case1() {
        byte[] ikm = HexFormat.of().parseHex("0b".repeat(22));
        byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
        byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = RelayCrypto.hkdf(ikm, salt, info, 42);
        // RFC 5869 A.1 的 OKM。走吃 byte[] 的那个重载——这些 info 字节是非 ASCII，
        // 经 String 会被 UTF-8 改写。自己写的 KDF 必须有一次外部校准，
        // 否则「两种语言算得一样」只能证明两边错得一样
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                + "34007208d5b887185865", HexFormat.of().formatHex(okm));
    }

    // 下面三串由本实现产出，Android 侧的同名用例断言同样的值
    private static final String VECTOR_H2C_KEY = "4b434b0e28cff9d851937b11320af59aa44ee0db232095e951f3a8419bfe8ff6";
    private static final String VECTOR_C2H_KEY = "103cd9f1cac41eaf4d176616e3575fef90f5c07560a1bef8d95b256cd9d10277";
    private static final String VECTOR_SEALED = "fw_66Ai9bYRTy2HnYruDgH_W3IWgdQfjeK1nmQ";
}
