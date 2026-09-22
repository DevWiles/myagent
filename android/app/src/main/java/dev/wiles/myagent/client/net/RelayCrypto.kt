package dev.wiles.myagent.client.net

import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 中继层的加密封套，**和 Java 侧 `relay/RelayCrypto.java` 是同一份契约**（协议 1.3）。
 *
 * <p>两边各写一遍而不是共用一份代码：手机和后端本来就是两个独立实现
 * （这是这个项目「前后端真的分开」那条约束的延续）。代价是必须有一组
 * **共享测试向量**互证——`RelayCryptoTest.kt` 里带 VECTOR 字样的用例，
 * 和 Java 侧那几条断言的是同一串十六进制。改任一边的常量，两边会同时红。
 *
 * <p>HKDF 手写的理由同 Java 侧：Android 的 JCA 没有 HKDF，而为二十行代码
 * 引一个加密库不划算。这里用的 `javax.crypto` 在 Android 上是系统实现（Conscrypt）。
 */
object RelayCrypto {

    const val INFO_HOST_TO_CLIENT = "myagent-relay v1 host->client"
    const val INFO_CLIENT_TO_HOST = "myagent-relay v1 client->host"

    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    // 用 java.util.Base64 而不是 android.util.Base64：minSdk 是 26，它从 API 26 就有，
    // 而且**在 JVM 单测里就能跑**——那组跨语言测试向量因此不需要设备或 Robolectric
    fun b64(raw: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    /** HKDF-SHA256（RFC 5869）：extract 把熵拍匀，expand 按 info 拉长。 */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val take = minOf(block.size, length - done)
            block.copyInto(out, done, 0, take)
            done += take
            counter++
        }
        return out
    }

    /**
     * 这次会话、这个方向的密钥。
     *
     * @param pairingKey 配对密钥（base64url），扫码得来，**从不发给 relay**
     * @param room       房间号（base64url）
     * @param sessionSalt host 每次开新会话生成的盐（base64url）
     */
    fun deriveKey(pairingKey: String, room: String, sessionSalt: String, info: String): ByteArray {
        val salt = unb64(room) + unb64(sessionSalt)
        return hkdf(unb64(pairingKey), salt, info.toByteArray(Charsets.UTF_8), KEY_BYTES)
    }

    /** nonce = 4 字节 0 + 8 字节大端计数器。每方向各自从 0 起。 */
    fun nonce(counter: Long): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).putInt(0).putLong(counter).array()

    fun seal(key: ByteArray, counter: Long, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce(counter)))
        return b64(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /**
     * 解开一帧。密钥不对、被篡改、计数器对不上——三种都抛同一个异常，
     * 这是 AEAD 的本意：区分开来就是给攻击者送预言机。
     */
    fun open(key: ByteArray, counter: Long, ciphertext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce(counter)))
        return String(cipher.doFinal(unb64(ciphertext)), Charsets.UTF_8)
    }
}
