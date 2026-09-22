package dev.wiles.myagent.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PairingTest {

    @Test
    @DisplayName("第一次生成并落盘，第二次读出来必须是同一套——不然手机每次都要重新扫码")
    void survivesRestart(@TempDir Path dir) {
        Path file = dir.resolve("relay.json");
        Pairing first = Pairing.loadOrCreate(file, false);
        Pairing again = Pairing.loadOrCreate(file, false);

        assertEquals(first.room(), again.room());
        assertEquals(first.key(), again.key());
    }

    @Test
    @DisplayName("--relay-reset 换一套")
    void resetRotates(@TempDir Path dir) {
        Path file = dir.resolve("relay.json");
        Pairing first = Pairing.loadOrCreate(file, false);
        Pairing rotated = Pairing.loadOrCreate(file, true);

        assertNotEquals(first.key(), rotated.key());
        // 换完要落盘，否则下次启动又变回去了
        assertEquals(rotated.key(), Pairing.loadOrCreate(file, false).key());
    }

    @Test
    @DisplayName("文件权限是 0600——这把密钥等于这台机器上 agent 的控制权")
    void fileIsPrivate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.json");
        Pairing.loadOrCreate(file, false);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
    }

    @Test
    @DisplayName("文件坏了就重新生成，不该因此拦住后端启动")
    void corruptFileRegenerates(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.json");
        Files.writeString(file, "{ 这不是 json");

        Pairing fresh = Pairing.loadOrCreate(file, false);
        assertEquals(16, RelayCrypto.unb64(fresh.room()).length);
        assertEquals(32, RelayCrypto.unb64(fresh.key()).length);
    }

    @Test
    @DisplayName("配对 URL 把密钥放在 fragment 里——fragment 永远不会被发到服务器")
    void keyLivesInFragment(@TempDir Path dir) {
        Pairing pairing = Pairing.loadOrCreate(dir.resolve("relay.json"), false);
        String url = pairing.pairingUrl("wss://relay.example.com/ws");

        assertTrue(url.startsWith("wss://relay.example.com/ws?room="), url);
        assertTrue(url.contains("#" + pairing.key()), url);
        // 密钥只能出现在 # 之后，一次都不能出现在查询串里
        assertFalse(url.substring(0, url.indexOf('#')).contains(pairing.key()));
    }

    @Test
    @DisplayName("host 去连的那个地址里没有密钥——这是整套加密的地基")
    void hostUrlCarriesNoKey(@TempDir Path dir) {
        Pairing pairing = Pairing.loadOrCreate(dir.resolve("relay.json"), false);
        String url = pairing.hostUrl("wss://relay.example.com/ws");

        assertTrue(url.contains("role=host"), url);
        assertFalse(url.contains(pairing.key()), "配对密钥泄漏到了发往 relay 的 URL 里: " + url);
    }
}
