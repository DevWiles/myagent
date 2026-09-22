package dev.wiles.myagent.relay;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 中继层的加密封套（协议 1.3）：HKDF-SHA256 派生 + AES-256-GCM 封装。
 *
 * <h2>为什么自己写 HKDF</h2>
 * JDK 17 没有 HKDF（JEP 478 是 24 才进来的），而这个项目的第三方库只有
 * Jackson/OkHttp/slf4j——为一个二十行的 KDF 引 BouncyCastle 不划算。
 * HKDF 本身就是「HMAC 两下」：extract 把熵拍匀，expand 按 info 拉出任意长度。
 *
 * <h2>三条不能动的规矩</h2>
 * <ol>
 *   <li><b>两个方向两把密钥</b>。共用一把的话，两端各自从 0 计数，
 *       第一帧就撞上同一个 (key, nonce)</li>
 *   <li><b>每次会话换盐</b>。配对密钥是跨重启保持的，计数器却每次从 0 起——
 *       没有会话盐，重启一次就是一次 nonce 复用。GCM 在 nonce 复用下
 *       <b>会泄露认证密钥</b>，那不是「弱一点」，是彻底失效</li>
 *   <li><b>收方必须查计数器严格递增</b>。不查就等于没有防重放：
 *       录下一帧 approval_reply 反复重放，等于替用户一直点同意</li>
 * </ol>
 *
 * <p>这个类是**跨语言契约**：Android 侧有一份等价实现
 * （{@code android/.../net/RelayCrypto.kt}），两边用同一组测试向量互证。
 * 改这里的任何常量，两边的测试会同时红。
 */
public final class RelayCrypto {

    /** 方向标签。进 HKDF 的 info，两个方向必须不同。 */
    public static final String INFO_HOST_TO_CLIENT = "myagent-relay v1 host->client";
    public static final String INFO_CLIENT_TO_HOST = "myagent-relay v1 client->host";

    private static final int KEY_BYTES = 32;      // AES-256
    private static final int NONCE_BYTES = 12;    // GCM 标准长度
    private static final int TAG_BITS = 128;

    private RelayCrypto() {
    }

    // ------------------------------------------------------------ 随机料

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 16 字节房间号 / 会话盐，base64url 无填充。 */
    public static String randomId() {
        return b64(random(16));
    }

    /** 32 字节配对密钥，base64url 无填充。**这个值永远不发给 relay。** */
    public static String randomKey() {
        return b64(random(KEY_BYTES));
    }

    private static byte[] random(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static byte[] unb64(String text) {
        return Base64.getUrlDecoder().decode(text);
    }

    // ------------------------------------------------------------ HKDF

    /**
     * HKDF-SHA256（RFC 5869）。
     *
     * <p>{@code extract} 先用 salt 当 HMAC 密钥把 ikm 拍成一个定长 PRK，
     * {@code expand} 再按 info 一段段拉长。我们只要 32 字节，所以 expand 只跑一轮。
     */
    static byte[] hkdf(byte[] ikm, byte[] salt, String info, int length) {
        return hkdf(ikm, salt, info.getBytes(StandardCharsets.UTF_8), length);
    }

    /**
     * 同上，但 info 直接给字节。
     *
     * <p>存在的唯一理由是**对着 RFC 5869 的标准向量校准**——那些向量的 info
     * 是 {@code f0f1…f9} 这种非 ASCII 字节，塞不进 String 再原样取回来。
     * 自己手写的 KDF 必须有一次外部校准，否则「两边一致」只能证明两边错得一样。
     */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] infoBytes, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);

            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[length];
            byte[] block = new byte[0];
            int done = 0;
            for (int counter = 1; done < length; counter++) {
                mac.update(block);
                mac.update(infoBytes);
                mac.update((byte) counter);
                block = mac.doFinal();
                int take = Math.min(block.length, length - done);
                System.arraycopy(block, 0, out, done, take);
                done += take;
            }
            return out;
        } catch (GeneralSecurityException e) {
            // HmacSHA256 是 JDK 必备算法，取不到说明运行环境坏了，救不回来
            throw new IllegalStateException("HKDF 算不出来", e);
        }
    }

    /**
     * 这次会话、这个方向的密钥。
     *
     * @param pairingKey 配对密钥（base64url），扫码得来
     * @param room       房间号（base64url）
     * @param sessionSalt host 每次连上新生成的会话盐（base64url）
     * @param info       方向标签，{@link #INFO_HOST_TO_CLIENT} 或 {@link #INFO_CLIENT_TO_HOST}
     */
    public static byte[] deriveKey(String pairingKey, String room, String sessionSalt, String info) {
        byte[] roomBytes = unb64(room);
        byte[] saltBytes = unb64(sessionSalt);
        byte[] salt = ByteBuffer.allocate(roomBytes.length + saltBytes.length)
                .put(roomBytes).put(saltBytes).array();
        return hkdf(unb64(pairingKey), salt, info, KEY_BYTES);
    }

    // ------------------------------------------------------------ AEAD

    /** nonce = 4 字节 0 + 8 字节大端计数器。每方向各自从 0 起。 */
    static byte[] nonce(long counter) {
        return ByteBuffer.allocate(NONCE_BYTES).putInt(0).putLong(counter).array();
    }

    /** 加密一帧明文，返回 base64url 的密文+tag。 */
    public static String seal(byte[] key, long counter, String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce(counter)));
            return b64(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解开一帧。
     *
     * @throws GeneralSecurityException 密钥不对、被篡改、计数器对不上——
     *         <b>三种都长一个样，这是 AEAD 的本意</b>：区分开来就是给攻击者送预言机
     */
    public static String open(byte[] key, long counter, String ciphertext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, nonce(counter)));
        return new String(cipher.doFinal(unb64(ciphertext)), StandardCharsets.UTF_8);
    }
}
