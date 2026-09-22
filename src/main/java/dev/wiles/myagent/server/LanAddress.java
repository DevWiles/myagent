package dev.wiles.myagent.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 挑一个能打在配对二维码上的局域网 IPv4。
 *
 * <p>难点不是「找一个非环回地址」，而是 Mac 上的网卡动辄十几块：
 * Wi-Fi（en0）、雷雳网桥、Docker、Tailscale/VPN 的 utun、AirDrop 的 awdl……
 * 全都有地址，但手机能连上的通常只有真实网卡那一个。挑错了，二维码扫了也白扫。
 *
 * <p>所以过滤分两刀：先按<b>接口名前缀</b>剔掉已知的虚拟口，再在剩下的里
 * 优先挑 {@code site-local}（10/8、172.16/12、192.168/16——家用路由器发的就是这些）。
 * 都没有就退而求其次给第一个非环回 IPv4，总比什么都不给强。
 *
 * <p>真正读网卡的只有 {@link #pick()} 一行，其余逻辑全在
 * {@link #pick(List)} 上——{@link NetworkInterface} 没法在测试里伪造，
 * 把「读」和「挑」拆开，挑的规则才测得动。
 */
final class LanAddress {

    /** 已知的虚拟接口名前缀。撞上就跳过，不管它上面挂着什么地址。 */
    private static final List<String> VIRTUAL_PREFIXES = List.of(
            "utun", "tun", "tap",            // VPN / Tailscale
            "awdl", "llw",                   // AirDrop / 低延迟 WLAN
            "docker", "veth", "br-",         // Docker
            "vmnet", "vboxnet", "virbr",     // 虚拟机
            "bridge", "ap");                 // 网桥 / 热点

    /** 一块网卡上的一个 IPv4，连同挑选要用的判据。挑选逻辑只看这个，不碰系统 API。 */
    record Candidate(String interfaceName, String address, boolean siteLocal) {
    }

    private LanAddress() {
    }

    /** 从系统网卡里挑。读不到（无网/权限）就 empty，调用方自己决定怎么提示。 */
    static Optional<String> pick() {
        List<Candidate> candidates = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof Inet4Address v4 && !v4.isLoopbackAddress()) {
                        candidates.add(new Candidate(nic.getName(), v4.getHostAddress(),
                                v4.isSiteLocalAddress()));
                    }
                }
            }
        } catch (SocketException e) {
            return Optional.empty();
        }
        return pick(candidates);
    }

    /** 挑选规则本体。列表顺序就是系统给的顺序（en0 通常排在前面），同优先级取先见者。 */
    static Optional<String> pick(List<Candidate> candidates) {
        List<Candidate> real = candidates.stream()
                .filter(c -> !isVirtual(c.interfaceName()))
                .toList();
        // 真实网卡上的内网地址是首选；没有内网地址（比如直连公网）就给真实网卡的第一个
        return real.stream().filter(Candidate::siteLocal).findFirst()
                .or(() -> real.stream().findFirst())
                // 全是虚拟口时也别空手而归——Tailscale 组网下 utun 恰恰是能通的那个
                .or(() -> candidates.stream().filter(Candidate::siteLocal).findFirst())
                .or(() -> candidates.stream().findFirst())
                .map(Candidate::address);
    }

    private static boolean isVirtual(String name) {
        String lower = name.toLowerCase();
        return VIRTUAL_PREFIXES.stream().anyMatch(lower::startsWith);
    }
}
