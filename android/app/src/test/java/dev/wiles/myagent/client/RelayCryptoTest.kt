package dev.wiles.myagent.client

import dev.wiles.myagent.client.net.Pairing
import dev.wiles.myagent.client.net.RelayCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * **跨语言契约**：带 VECTOR 字样的用例，和 Java 侧
 * `src/test/java/dev/wiles/myagent/relay/RelayCryptoTest.java` 断言的是同一串值。
 *
 * <p>这不是重复劳动——两边是两份独立实现，只有对着同一组向量才能证明它们真的互通。
 * 任一边改了常量（方向标签、nonce 布局、HKDF 的盐怎么拼），两边会同时红。
 */
class RelayCryptoTest {

    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val room = "ICEiIyQlJicoKSorLC0uLw"
    private val sid = "MDEyMzQ1Njc4OTo7PD0-Pw"

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `VECTOR 派生出来的密钥必须和 Java 侧一模一样`() {
        val h2c = RelayCrypto.deriveKey(key, room, sid, RelayCrypto.INFO_HOST_TO_CLIENT)
        val c2h = RelayCrypto.deriveKey(key, room, sid, RelayCrypto.INFO_CLIENT_TO_HOST)

        assertEquals(32, h2c.size)
        assertNotEquals(hex(h2c), hex(c2h))
        assertEquals("4b434b0e28cff9d851937b11320af59aa44ee0db232095e951f3a8419bfe8ff6", hex(h2c))
        assertEquals("103cd9f1cac41eaf4d176616e3575fef90f5c07560a1bef8d95b256cd9d10277", hex(c2h))
    }

    @Test
    fun `VECTOR 同一把密钥同一个计数器，密文一字不差`() {
        val k = RelayCrypto.deriveKey(key, room, sid, RelayCrypto.INFO_HOST_TO_CLIENT)
        assertEquals("fw_66Ai9bYRTy2HnYruDgH_W3IWgdQfjeK1nmQ", RelayCrypto.seal(k, 7, """{"t":"pong"}"""))
    }

    @Test
    fun `VECTOR nonce 是 4 字节 0 加 8 字节大端计数器`() {
        assertEquals("000000000000000000000000", hex(RelayCrypto.nonce(0)))
        assertEquals("000000000000000000000001", hex(RelayCrypto.nonce(1)))
        assertEquals("00000000000000000000ffff", hex(RelayCrypto.nonce(65535)))
    }

    @Test
    fun `Java 侧封的，这边解得开——这条才是「互通」本身`() {
        // 这串密文由 Java 实现产出（见 Java 侧 vectorSeal）
        val k = RelayCrypto.deriveKey(key, room, sid, RelayCrypto.INFO_HOST_TO_CLIENT)
        assertEquals("""{"t":"pong"}""", RelayCrypto.open(k, 7, "fw_66Ai9bYRTy2HnYruDgH_W3IWgdQfjeK1nmQ"))
    }

    @Test
    fun `往返：中文也要过一遍 UTF-8`() {
        val k = RelayCrypto.deriveKey(key, room, sid, RelayCrypto.INFO_CLIENT_TO_HOST)
        val plain = """{"t":"prompt","text":"中文也要过一遍 UTF-8"}"""
        assertEquals(plain, RelayCrypto.open(k, 3, RelayCrypto.seal(k, 3, plain)))
    }

    @Test
    fun `计数器对不上就解不开——防重放靠这一条`() {
        val k = RelayCrypto.deriveKey(key, room, sid, RelayCrypto.INFO_HOST_TO_CLIENT)
        val sealed = RelayCrypto.seal(k, 5, "hello")
        assertThrows(AEADBadTagException::class.java) { RelayCrypto.open(k, 6, sealed) }
    }

    @Test
    fun `拿另一个方向的密钥解不开`() {
        val h2c = RelayCrypto.deriveKey(key, room, sid, RelayCrypto.INFO_HOST_TO_CLIENT)
        val c2h = RelayCrypto.deriveKey(key, room, sid, RelayCrypto.INFO_CLIENT_TO_HOST)
        val sealed = RelayCrypto.seal(h2c, 0, "hello")
        assertThrows(AEADBadTagException::class.java) { RelayCrypto.open(c2h, 0, sealed) }
    }

    @Test
    fun `HKDF 对着 RFC 5869 Test Case 1 校准`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(
            0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(),
            0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte(),
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(RelayCrypto.hkdf(ikm, salt, info, 42)),
        )
    }
}

/** 配对串的判别：扫码和手填走的是同一条路，值得单独测。 */
class PairingParseTest {

    @Test
    fun `带 fragment 的是中继，密钥在 # 后面`() {
        val p = Pairing.parse("wss://relay.example.com/ws?room=ROOM123#KEY456")
        assertTrue(p is Pairing.Relay)
        p as Pairing.Relay
        assertEquals("wss://relay.example.com/ws", p.relayUrl)
        assertEquals("ROOM123", p.room)
        assertEquals("KEY456", p.key)
    }

    @Test
    fun `连 relay 的地址里不能出现配对密钥——这是整套加密的地基`() {
        val p = Pairing.parse("wss://relay.test/ws?room=R#SECRETKEY") as Pairing.Relay
        val url = p.clientUrl()
        assertTrue(url.contains("role=client"))
        assertTrue("配对密钥泄漏进了发往 relay 的 URL: $url", !url.contains("SECRETKEY"))
    }

    @Test
    fun `带 token 的是局域网直连`() {
        val p = Pairing.parse("ws://192.168.1.7:61772/?token=abc123")
        assertTrue(p is Pairing.Lan)
    }

    @Test
    fun `残片一律拒绝——缺一半的配对连上去只会得到一堆解不开的密文`() {
        assertNull(Pairing.parse("wss://relay.test/ws?room=R"))        // 有房间没密钥
        assertNull(Pairing.parse("wss://relay.test/ws#KEY"))           // 有密钥没房间
        assertNull(Pairing.parse("ws://192.168.1.7:61772/"))           // 局域网但没 token
        assertNull(Pairing.parse("https://example.com"))               // 压根不是 ws
        assertNull(Pairing.parse("随便一句话"))
    }

    @Test
    fun `前后空白要容忍——扫码和复制粘贴都可能带上它`() {
        assertTrue(Pairing.parse("  ws://10.0.0.2:8080/?token=t  ") is Pairing.Lan)
    }
}
