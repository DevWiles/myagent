package dev.wiles.myagent.server;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 拉起后端的那个界面进程没了，后端跟着退。
 *
 * <h3>为什么靠 stdin 而不是信号或进程号</h3>
 * 界面正常退出时会 {@code kill} 掉后端，这条不用管。要管的是界面被硬杀、崩了：
 * macOS/Linux 不会连带杀子进程，后端就成了孤儿——手上那一轮没人看着照跑，
 * 计划模式又不限步数不限时，能一直烧 token 烧下去。
 * （Windows 上 Bun 用的 libuv 会把子进程放进「句柄一关就杀全组」的 Job Object，
 * 实测硬杀界面后端 1 秒内就没了；这条在那儿是第二道保险。）
 *
 * <p>界面 spawn 时把后端的 stdin 接成一根什么都不写的管道。<b>进程不管怎么死，
 * 操作系统都会关掉它持有的管道</b>，这边就读到 EOF。和 MCP、LSP 的 stdio 传输
 * 「stdin 关了就退」是同一个约定，跨平台成立，也不用轮询。
 * 轮询父进程号在 Windows 上还会撞上进程号复用。
 *
 * <p>只在界面本机拉起后端时开（{@code --exit-with-parent}）。手动 {@code --serve}
 * 起、让界面 {@code --connect} 过去的后端本来就该比界面活得久，stdin 也可能是个终端。
 *
 * <p>后端别处<b>不能再读 {@code System.in}</b>，否则会和这里抢。
 */
public final class ParentWatch {

    private static final Logger log = LoggerFactory.getLogger(ParentWatch.class);

    private ParentWatch() {
    }

    /** 起一个守护线程读到 EOF（或读出错），然后调 {@code onGone}。 */
    public static Thread start(InputStream in, Runnable onGone) {
        Thread t = new Thread(() -> {
            byte[] sink = new byte[256];
            try {
                // 界面从来不往里写；真写了什么也照单丢掉，只等它关
                while (in.read(sink) >= 0) {
                    // 丢弃
                }
            } catch (IOException e) {
                log.debug("读 stdin 出错，按界面已退出处理", e);
            }
            log.info("界面进程已退出（stdin 关闭），后端跟着退出");
            onGone.run();
        }, "parent-watch");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
