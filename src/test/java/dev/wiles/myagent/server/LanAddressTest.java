package dev.wiles.myagent.server;

import dev.wiles.myagent.server.LanAddress.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只测「挑」的规则。「读网卡」那一行在测试里没法伪造（NetworkInterface 造不出来），
 * 所以规则全部收在 {@code pick(List)} 上——这正是把两件事拆开的理由。
 */
class LanAddressTest {

    @Test
    @DisplayName("真实网卡的内网地址赢过一切——en0 上的 192.168 就是二维码该写的那个")
    void realNicSiteLocalWins() {
        Optional<String> picked = LanAddress.pick(List.of(
                new Candidate("utun3", "100.101.1.2", false),      // Tailscale
                new Candidate("en0", "192.168.1.7", true),
                new Candidate("bridge100", "192.168.64.1", true)));  // 虚拟机网桥

        assertEquals(Optional.of("192.168.1.7"), picked);
    }

    @Test
    @DisplayName("虚拟口（utun/docker/bridge…）要跳过，哪怕它挂着内网地址")
    void virtualInterfacesAreSkipped() {
        Optional<String> picked = LanAddress.pick(List.of(
                new Candidate("docker0", "172.17.0.1", true),
                new Candidate("vmnet8", "192.168.128.1", true),
                new Candidate("en5", "10.0.0.3", true)));

        assertEquals(Optional.of("10.0.0.3"), picked);
    }

    @Test
    @DisplayName("真实网卡没有内网地址时给它的第一个地址——直连公网也该能配对")
    void fallsBackToFirstRealAddress() {
        Optional<String> picked = LanAddress.pick(List.of(
                new Candidate("en0", "203.0.113.5", false)));

        assertEquals(Optional.of("203.0.113.5"), picked);
    }

    @Test
    @DisplayName("只剩虚拟口时也别空手而归——Tailscale 组网下 utun 恰恰是通的")
    void virtualOnlyStillPicksSomething() {
        Optional<String> picked = LanAddress.pick(List.of(
                new Candidate("utun3", "100.101.1.2", false)));

        assertEquals(Optional.of("100.101.1.2"), picked);
    }

    @Test
    @DisplayName("什么都没有就是 empty，不编造")
    void emptyWhenNothing() {
        assertTrue(LanAddress.pick(List.of()).isEmpty());
    }

    @Test
    @DisplayName("同优先级取先见者——系统给的顺序里 en0 通常排在前面")
    void firstSeenWinsWithinSamePriority() {
        Optional<String> picked = LanAddress.pick(List.of(
                new Candidate("en0", "192.168.1.7", true),
                new Candidate("en5", "192.168.2.9", true)));

        assertEquals(Optional.of("192.168.1.7"), picked);
    }
}
