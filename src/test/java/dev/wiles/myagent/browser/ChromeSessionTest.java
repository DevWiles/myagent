package dev.wiles.myagent.browser;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDP 复用这件事能测的只有两头：<b>端口是不是从参数里认对了</b>，
 * 以及<b>端点上有没有人在是不是判对了</b>。
 *
 * <p>中间「拉起 Chrome」那一段不测——真拉一个浏览器起来的测试跑在 CI 上必挂，
 * 而 mock 掉 ProcessBuilder 之后剩下的只是「我调了我自己写的那行」。
 * 那一段的正确性由真机冒烟保证，不由单测假装保证。
 */
class ChromeSessionTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private ChromeSession session() {
        return new ChromeSession(new OkHttpClient());
    }

    @Test
    @DisplayName("从 --browserUrl 认出端点——端口写在 server 自己的参数里，不让用户配第二遍")
    void readsTheEndpointFromBrowserUrl() {
        Optional<URI> target = ChromeSession.debugTargetIn(
                List.of("-y", "chrome-devtools-mcp@latest", "--browserUrl", "http://127.0.0.1:9333"));

        assertTrue(target.isPresent());
        assertEquals(9333, target.get().getPort());
    }

    @Test
    @DisplayName("等号写法和短参 -u 也要认——同一件事三种写法，认漏一种就是「怎么不给我起浏览器」")
    void readsTheOtherTwoSpellings() {
        assertEquals(9222, ChromeSession.debugTargetIn(
                List.of("--browserUrl=http://127.0.0.1:9222")).orElseThrow().getPort());
        assertEquals(9111, ChromeSession.debugTargetIn(
                List.of("-u", "http://localhost:9111")).orElseThrow().getPort());
    }

    @Test
    @DisplayName("没写这个参数就一步都不做——那家 server 自己管浏览器，别替它做主")
    void withoutTheFlagWeStayOut() {
        assertTrue(ChromeSession.debugTargetIn(
                List.of("-y", "chrome-devtools-mcp@latest", "--isolated")).isEmpty());
        assertTrue(ChromeSession.debugTargetIn(List.of()).isEmpty());
        assertTrue(ChromeSession.debugTargetIn(null).isEmpty());
    }

    @Test
    @DisplayName("参数写坏了也不炸——那串参数是给别人的，轮不到我们报错")
    void brokenValuesAreIgnored() {
        assertTrue(ChromeSession.debugTargetIn(List.of("--browserUrl")).isEmpty());
        assertTrue(ChromeSession.debugTargetIn(List.of("--browserUrl", "")).isEmpty());
        assertTrue(ChromeSession.debugTargetIn(List.of("--browserUrl", "没有端口")).isEmpty());
    }

    @Test
    @DisplayName("探活认的是 webSocketDebuggerUrl——别的东西占着这个端口也会答话")
    void aliveRequiresARealBrowserOnTheOtherEnd() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"Browser\":\"Chrome/153\",\"webSocketDebuggerUrl\":\"ws://127.0.0.1/devtools/browser/x\"}"));

        assertTrue(session().alive(server.url("/").uri()));

        RecordedRequest request = server.takeRequest();
        assertEquals("/json/version", request.getPath(), "探的就该是这个端点");
    }

    @Test
    @DisplayName("端口上是别的服务：答了 200 也不算浏览器在——认错了后面每一步都在对着空气说话")
    void somethingElseOnThePortIsNotABrowser() {
        server.enqueue(new MockResponse().setBody("{\"hello\":\"我是别的服务\"}"));

        assertFalse(session().alive(server.url("/").uri()));
    }

    @Test
    @DisplayName("端口上没人：探活返回假，而不是抛出去")
    void nobodyHomeIsFalseNotAnException() throws IOException {
        URI dead = server.url("/").uri();
        server.shutdown();                   // 关掉之后这个端口就没人应答了

        assertFalse(session().alive(dead));
    }

    @Test
    @DisplayName("非本机地址不拉浏览器——远端那台机器上的 Chrome 我们起不了，只能连")
    void remoteTargetsAreNeverLaunched() {
        assertTrue(ChromeSession.isLoopback(URI.create("http://127.0.0.1:9333")));
        assertTrue(ChromeSession.isLoopback(URI.create("http://localhost:9333")));
        assertFalse(ChromeSession.isLoopback(URI.create("http://192.168.1.7:9333")));
        assertFalse(ChromeSession.isLoopback(URI.create("http://example.com:9333")));
    }

    @Test
    @DisplayName("找 Chrome 时按顺序挑第一个真能跑的，找不到就是空——不能返回一个跑不起来的路径")
    void executableLookupTakesTheFirstRunnableOne(@org.junit.jupiter.api.io.TempDir Path dir)
            throws IOException {
        Path missing = dir.resolve("不存在的浏览器");
        Path real = Files.createFile(dir.resolve("chrome"));
        assertTrue(real.toFile().setExecutable(true));

        assertEquals(real, ChromeSession.firstExecutableAmong(
                List.of(missing.toString(), real.toString())).orElseThrow());
        assertTrue(ChromeSession.firstExecutableAmong(List.of(missing.toString())).isEmpty());
    }

    @Test
    @DisplayName("专用 profile 不在用户日常那个浏览器的目录里——默认值该是安全的那一边")
    void theProfileIsOursNotYours() {
        String profile = ChromeSession.profileDir().toString();

        assertTrue(profile.endsWith("/.myagent/chrome-profile"), profile);
    }

    @Test
    @DisplayName("配了 --autoConnect 就绝不拉浏览器——那是要用你日常那个，抢位置会静默出错")
    void autoConnectMeansHandsOff() {
        assertTrue(ChromeSession.usesAutoConnect(
                List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect")));
        assertTrue(ChromeSession.usesAutoConnect(List.of("--auto-connect")));
        assertFalse(ChromeSession.usesAutoConnect(List.of("--autoConnect=false")),
                "显式关掉就不算");
        assertFalse(ChromeSession.usesAutoConnect(List.of("--isolated")));
        assertFalse(ChromeSession.usesAutoConnect(null));
    }

    @Test
    @DisplayName("autoConnect 和 --browserUrl 是两条路，认 autoConnect 时不该再去探端口")
    void autoConnectCarriesNoPortToProbe() {
        List<String> args = List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect");

        assertTrue(ChromeSession.usesAutoConnect(args));
        assertTrue(ChromeSession.debugTargetIn(args).isEmpty(),
                "没有端口可探——发现不了就该老实什么都不做，而不是猜一个 9222");
    }

    @Test
    @DisplayName("远程调试开关没开时那句提示，得说清去哪儿点")
    void theAutoConnectHintIsActionable() {
        assertTrue(ChromeSession.AUTO_CONNECT_HINT.contains("chrome://inspect"),
                "只说「连不上」等于没说——这个开关不落地成文件，探不出来，只能告诉他去哪儿开");
    }
}
