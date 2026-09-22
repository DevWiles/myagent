package dev.wiles.myagent.browser;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 常驻的调试用 Chrome：让浏览器活得比 agent 长。
 *
 * <h3>解决的是什么</h3>
 * chrome-devtools-mcp 默认自己拉一个 Chrome，进程一死浏览器跟着死。
 * 代价是<b>每次重启 agent，页面、登录态、Cookie 全部归零</b>——
 * 而「登录一次，之后一直用」恰恰是浏览器相对 curl 最大的那点价值。
 *
 * <p>换成 CDP 复用之后，浏览器是独立的一摊：myagent 起来先确认它在，
 * 不在就拉起来，在就直接连。MCP server 进程反复生灭，浏览器不动。
 * 实测过：杀掉 server 进程再起一个新的，上一次打开的页面原样还在。
 *
 * <h3>端口从哪来：不新增配置</h3>
 * 端口写在 {@code mcp.json} 里 chrome-devtools-mcp 自己的 {@code --browserUrl}
 * 参数上，这里<b>从那串参数里读出来</b>，而不是让用户再配一遍。
 * 同一个事实写两处，迟早对不上——这条教训在 {@code layers}、角色名册、
 * {@code PlanLimits} 上已经付过三次学费了。
 *
 * <h3>想用自己那个浏览器怎么办</h3>
 * 不用配任何东西：自己带 {@code --remote-debugging-port} 起一个 Chrome，
 * 这里探到有人在就直接连，不会去抢。默认起的那个用的是<b>专用 profile</b>
 * （{@value #PROFILE_DIR}），和你日常那个浏览器的登录态互不相干——
 * 默认值该是安全的那一边，想把全部身份交给模型是一个需要你动手的决定。
 *
 * <h3>一个必须说清楚的代价</h3>
 * 开着调试端口的 Chrome，<b>本机任何进程都能控制它</b>（CDP 没有鉴权）。
 * 端口只听 127.0.0.1，但这仍然比默认状态宽。不想留就把那个 Chrome 关掉。
 */
public final class ChromeSession {

    private static final Logger log = LoggerFactory.getLogger(ChromeSession.class);

    /** 专用 profile 的位置。和用户日常那个浏览器彻底分开。 */
    static final String PROFILE_DIR = "~/.myagent/chrome-profile";

    /**
     * 自己拉起来之后，等它把调试端口支起来的预算。
     *
     * <p>实测全新 profile 冷启动到端口就绪约 1.4 秒，五秒是给「这台机器今天特别慢」
     * 留的余量。<b>这个数必须明显小于 {@code McpServers.STARTUP_TIMEOUT_MILLIS}</b>：
     * 备浏览器发生在那家 server 的启动预算<b>里面</b>，不另开一份——
     * 再往外加一层预算，最后是 TUI 那头等握手的 15 秒兜不住。
     * 两个数的关系由 {@code McpServersTest} 钉着。
     */
    public static final long LAUNCH_TIMEOUT_MILLIS = 5_000;

    /** 探活的间隔。Chrome 冷启动大约两三秒，200ms 一次既不慢也不吵。 */
    private static final long POLL_INTERVAL_MILLIS = 200;

    /** 找不到 Chrome 时的退路。Linux 上发行版把它装在哪儿都有可能。 */
    static final String EXECUTABLE_ENV = "MYAGENT_CHROME";

    private static final List<String> CANDIDATES = List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/google-chrome",
            "/usr/bin/chromium-browser",
            "/usr/bin/chromium");

    private final OkHttpClient http;

    public ChromeSession() {
        // 探的是本机回环，超时给到秒级已经很宽松了。这里卡住会顶在 agent 的启动路径上
        this(new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build());
    }

    ChromeSession(OkHttpClient http) {
        this.http = http;
    }

    /**
     * 从一串 MCP server 的启动参数里认出调试端点。
     *
     * <p>三种写法都认：{@code --browserUrl <url>}、{@code --browserUrl=<url>}、
     * 以及短参 {@code -u}。认不出就是空——那说明这家 server 自己管浏览器，
     * <b>不该替它做主</b>。
     */
    public static Optional<URI> debugTargetIn(List<String> args) {
        if (args == null) {
            return Optional.empty();
        }
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            String value = null;
            if ("--browserUrl".equals(arg) || "-u".equals(arg)) {
                value = i + 1 < args.size() ? args.get(i + 1) : null;
            } else if (arg.startsWith("--browserUrl=")) {
                value = arg.substring("--browserUrl=".length());
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                URI uri = URI.create(value.strip());
                if (uri.getHost() != null && uri.getPort() > 0) {
                    return Optional.of(uri);
                }
            } catch (IllegalArgumentException ignored) {
                // 参数是给别人的，写坏了也轮不到我们报错——那家 server 自己会抱怨
            }
        }
        return Optional.empty();
    }

    /**
     * 这家 server 是不是自己会去找你日常那个浏览器（{@code --autoConnect}）。
     *
     * <h3>认出来是为了「什么都不做」</h3>
     * 这条参数的意思是：chrome-devtools-mcp 自己去本机默认 profile 里找一个
     * 开了远程调试的 Chrome。那正是<b>你日常那个浏览器</b>，带着全部登录态。
     * 这时候我们绝不能再拉一个专用 profile 的 Chrome——那会抢走它的位置，
     * 用户明明想用自己的浏览器，结果 agent 在一个空白 profile 里干活，
     * 而两边都没有报错。
     *
     * <p>认出来的第二个用处是<b>失败时能说人话</b>：远程调试开关没打开时，
     * 这条路会直接连不上，而通用报错只会说「server 起不来」。
     * 那个开关在 {@code chrome://inspect/#remote-debugging}，
     * 而且它<b>不落地成任何文件</b>（实测 Chrome 153 不写 DevToolsActivePort），
     * 所以只能在失败之后提示，猜不出来。
     */
    public static boolean usesAutoConnect(List<String> args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--autoConnect".equals(arg) || "--auto-connect".equals(arg)
                    || arg.startsWith("--autoConnect=") || arg.startsWith("--auto-connect=")) {
                return !arg.endsWith("=false");
            }
        }
        return false;
    }

    /** 远程调试开关没开时该说的那句话。失败之后才发得出来，见 {@link #usesAutoConnect}。 */
    public static final String AUTO_CONNECT_HINT =
            "这家 server 配的是 --autoConnect（用你日常那个浏览器）。连不上最常见的原因是"
                    + "浏览器里的远程调试开关没打开：在 Chrome 里打开 "
                    + "chrome://inspect/#remote-debugging，勾上 Enable remote debugging，"
                    + "然后重启一次 Chrome。需要 Chrome 144 以上。";

    /** 只有回环地址才谈得上「拉起来」。指到别的机器上，我们只能连，连不上也没辙。 */
    static boolean isLoopback(URI target) {
        String host = target.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || "127.0.0.1".equals(lower) || "[::1]".equals(lower)
                || "::1".equals(lower);
    }

    /**
     * 保证这个端点上有一个能连的浏览器。
     *
     * <p>先探后拉，<b>探到了就绝不插手</b>：那可能是用户自己开的、带着全部登录态的
     * 浏览器，我们再拉一个只会抢 profile 锁。拉不起来也不抛——
     * 浏览器起不来的代价该是「这家 MCP server 接不上」，
     * 而不是整个 agent 起不来（和 {@code McpConfig} 的解析纪律同一条）。
     *
     * @return 现在这个端点上有没有浏览器
     */
    public boolean ensure(URI target) {
        if (alive(target)) {
            log.info("浏览器已经在 {} 上，直接复用（页面和登录态都还在）", target);
            return true;
        }
        if (!isLoopback(target)) {
            log.warn("{} 不是本机地址，起不了浏览器，只能等那头自己就绪", target);
            return false;
        }
        Path executable = findExecutable().orElse(null);
        if (executable == null) {
            log.warn("找不到 Chrome，本次不接浏览器。装了但不在常见位置的话，"
                    + "用环境变量 {} 指给我", EXECUTABLE_ENV);
            return false;
        }
        return launch(executable, target);
    }

    /** 端点上是不是真有个浏览器。认 {@code webSocketDebuggerUrl}——别的东西占着这个端口也会答话。 */
    boolean alive(URI target) {
        Request request = new Request.Builder()
                .url(base(target) + "/json/version")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }
            return response.body().string().contains("webSocketDebuggerUrl");
        } catch (Exception e) {
            return false;                    // 连不上就是没起来，这是最常见的一条路
        }
    }

    private boolean launch(Path executable, URI target) {
        Path profile = profileDir();
        List<String> command = new ArrayList<>(List.of(
                executable.toString(),
                "--remote-debugging-port=" + target.getPort(),
                "--user-data-dir=" + profile,
                // 新 profile 第一次起会弹欢迎页和「设为默认浏览器」，两个都会挡住自动化
                "--no-first-run",
                "--no-default-browser-check"));
        try {
            Files.createDirectories(profile);
            ProcessBuilder builder = new ProcessBuilder(command);
            // 必须丢掉输出。没人读的管道填满 64K 之后 Chrome 的下一次写入就阻塞，
            // 症状是「浏览器用着用着整个卡死」，和我们的代码看不出任何关系——
            // 这个坑在 McpClient 的 stderr 上已经踩过一次了
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            // 故意不留 Process 引用、也不注册关闭钩子：**浏览器就该活得比我们长**，
            // 那正是这个类存在的理由。JVM 退出不会带走子进程
        } catch (Exception e) {
            log.warn("拉不起 Chrome（{}）: {}", executable, e.getMessage());
            return false;
        }
        if (!awaitAlive(target)) {
            log.warn("Chrome 起来了但 {} 秒内没把调试端口支起来，本次不接浏览器。"
                    + "它多半还在起，下次启动就赶得上", LAUNCH_TIMEOUT_MILLIS / 1000);
            return false;
        }
        log.info("拉起了浏览器，调试端口 {}，profile 在 {}（和你日常那个浏览器互不相干）",
                target.getPort(), profile);
        return true;
    }

    private boolean awaitAlive(URI target) {
        long deadline = System.nanoTime() + LAUNCH_TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (alive(target)) {
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 端点的 scheme://host:port，末尾不带斜杠。 */
    static String base(URI target) {
        String scheme = target.getScheme() == null ? "http" : target.getScheme();
        return scheme + "://" + target.getHost() + ":" + target.getPort();
    }

    static Path profileDir() {
        return Path.of(System.getProperty("user.home"), ".myagent", "chrome-profile");
    }

    /** 环境变量优先，然后按常见安装位置找。找不到返回空，由调用方决定怎么说。 */
    static Optional<Path> findExecutable() {
        String configured = System.getenv(EXECUTABLE_ENV);
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.strip());
            return Files.isExecutable(path) ? Optional.of(path) : Optional.empty();
        }
        return firstExecutableAmong(CANDIDATES);
    }

    /** 拆出来是为了能单测「按顺序挑第一个能跑的」，不用依赖这台机器上装了什么。 */
    static Optional<Path> firstExecutableAmong(List<String> candidates) {
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.canExecute()) {
                return Optional.of(file.toPath());
            }
        }
        return Optional.empty();
    }
}
