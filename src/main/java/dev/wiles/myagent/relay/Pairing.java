package dev.wiles.myagent.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * 配对身份：房间号 + 配对密钥，**跨重启保持**（协议 1.3）。
 *
 * <p>为什么要持久化：不存的话每次重启后端都换一套，手机就得重新扫一次码——
 * 而这件事的全部目标是「任何时候都能连」。一次一换的安全收益也是假的：
 * 真正每次都换的是<b>会话盐</b>（见 {@link RelayCrypto}），
 * 那才是防 nonce 复用的那一层。
 *
 * <p>文件是 {@code ~/.myagent/relay.json}，权限 0600。里面那把密钥
 * <b>等于你电脑上这个 agent 的控制权</b>——谁拿到它就能把命令发进来。
 * 所以它和 SSH 私钥同一个待遇：只有本人可读，且永远不发给 relay。
 */
public final class Pairing {

    private static final Logger log = LoggerFactory.getLogger(Pairing.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String room;
    private final String key;

    private Pairing(String room, String key) {
        this.room = room;
        this.key = key;
    }

    public String room() {
        return room;
    }

    /** 配对密钥。**只用来算密钥，绝不能进任何发往 relay 的报文。** */
    public String key() {
        return key;
    }

    /** 默认位置：{@code ~/.myagent/relay.json}。 */
    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".myagent", "relay.json");
    }

    /**
     * 读出来；没有、读不动、或者 {@code reset} 时就新建一套并落盘。
     *
     * <p>读坏了不报错而是重新生成：一个坏掉的配对文件让后端起不来是过度反应，
     * 代价只是要重新扫一次码。但会记一条 warn——静默换密钥比换本身更可怕。
     */
    public static Pairing loadOrCreate(Path file, boolean reset) {
        if (!reset) {
            try {
                if (Files.exists(file)) {
                    JsonNode node = MAPPER.readTree(Files.readString(file));
                    String room = node.path("room").asText(null);
                    String key = node.path("key").asText(null);
                    if (valid(room, 16) && valid(key, 32)) {
                        return new Pairing(room, key);
                    }
                    log.warn("{} 里的配对信息不完整，重新生成（手机需要重新扫码）", file);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("读不动 {}，重新生成（手机需要重新扫码）: {}", file, e.toString());
            }
        }
        Pairing fresh = new Pairing(RelayCrypto.randomId(), RelayCrypto.randomKey());
        fresh.save(file);
        return fresh;
    }

    private static boolean valid(String value, int bytes) {
        try {
            return value != null && RelayCrypto.unb64(value).length == bytes;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode node = MAPPER.createObjectNode();
            node.put("room", room);
            node.put("key", key);
            Files.writeString(file, node.toString());
            // 先写后改权限有个很窄的窗口，但 ~/.myagent 本身就在家目录下；
            // 真正要紧的是**别让它带着默认 644 长期躺着**
            try {
                Files.setPosixFilePermissions(file,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException e) {
                // Windows 没有 POSIX 权限位，跳过
            }
        } catch (IOException e) {
            // 存不下就下次重新生成，不该因此拦住后端启动
            log.warn("配对信息存不下（下次启动要重新扫码）: {}", e.toString());
        }
    }

    /**
     * 给手机扫的那个 URL。
     *
     * <p>密钥在 fragment 里——URL 的 fragment **在协议层面永远不会被发到服务器**，
     * 这一条比任何「记得别发」的注释都可靠（协议 1.3）。
     *
     * @param relayUrl 形如 {@code wss://relay.example.com/ws}
     */
    public String pairingUrl(String relayUrl) {
        String base = relayUrl.endsWith("/") ? relayUrl.substring(0, relayUrl.length() - 1) : relayUrl;
        return base + "?room=" + URLEncoder.encode(room, StandardCharsets.UTF_8) + "#" + key;
    }

    /** host 侧真正去连的地址。**注意这里没有 key**——那是重点。 */
    public String hostUrl(String relayUrl) {
        String base = relayUrl.endsWith("/") ? relayUrl.substring(0, relayUrl.length() - 1) : relayUrl;
        return base + "?room=" + URLEncoder.encode(room, StandardCharsets.UTF_8) + "&role=host";
    }
}
