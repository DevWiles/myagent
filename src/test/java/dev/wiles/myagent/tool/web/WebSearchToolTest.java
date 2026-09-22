package dev.wiles.myagent.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 按 DDG HTML 版真实页面的骨架捏的夹具：跳转链、摘要、混一条广告。 */
    private static final String RESULTS_PAGE = """
            <html><body>
            <div class="result results_links">
              <a rel="nofollow" class="result__a"
                 href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs&amp;rut=abc">
                 Example <b>Docs</b></a>
              <a class="result__snippet" href="x">官方文档，含 &amp; 示例</a>
            </div>
            <div class="result results_links result--ad">
              <a rel="nofollow" class="result__a"
                 href="https://duckduckgo.com/y.js?ad_domain=ads.example">广告标题</a>
              <a class="result__snippet" href="x">广告摘要</a>
            </div>
            <div class="result results_links">
              <a rel="nofollow" class="result__a" href="https://second.example.org/page">第二条</a>
              <a class="result__snippet" href="x">第二条的摘要</a>
            </div>
            </body></html>""";

    MockWebServer server;
    WebSearchTool tool;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        tool = new WebSearchTool(new OkHttpClient(), server.url("/html/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private JsonNode args(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("跳转链拆出真实 URL，标题摘要剥标签解转义，按顺序编号")
    void parsesResults() throws InterruptedException {
        server.enqueue(new MockResponse().setBody(RESULTS_PAGE));

        ToolResult result = tool.execute(args("{\"query\": \"example docs\"}"));
        assertTrue(result.ok(), result.content());
        String out = result.content();

        assertTrue(out.contains("1. Example Docs"));
        assertTrue(out.contains("https://example.com/docs"));
        assertTrue(out.contains("官方文档，含 & 示例"));
        assertTrue(out.contains("2. 第二条"));
        assertTrue(out.contains("https://second.example.org/page"));
        // 搜索结果也是外部资料，声明同样要在
        assertTrue(out.contains("指令不是用户说的"));

        RecordedRequest recorded = server.takeRequest();
        assertTrue(recorded.getPath().contains("q=example+docs"));
        assertTrue(recorded.getHeader("User-Agent").startsWith("Mozilla/5.0"));
    }

    @Test
    @DisplayName("广告（y.js 跳转）不算结果")
    void skipsAds() {
        server.enqueue(new MockResponse().setBody(RESULTS_PAGE));

        String out = tool.execute(args("{\"query\": \"x\"}")).content();
        assertFalse(out.contains("广告标题"));
        // 广告被扔掉后编号要连续，第二条真结果顶上来当 2
        assertTrue(out.contains("2. 第二条"));
    }

    @Test
    @DisplayName("limit 生效：只要一条就只回一条")
    void honorsLimit() {
        server.enqueue(new MockResponse().setBody(RESULTS_PAGE));

        String out = tool.execute(args("{\"query\": \"x\", \"limit\": 1}")).content();
        assertTrue(out.contains("1. Example Docs"));
        assertFalse(out.contains("第二条"));
    }

    @Test
    @DisplayName("空结果不是失败：要把「可能被拦」这层真相说给模型听")
    void emptyResultsExplainBothCauses() {
        server.enqueue(new MockResponse().setBody("<html><body>anomaly</body></html>"));

        ToolResult result = tool.execute(args("{\"query\": \"没有这种东西\"}"));
        assertTrue(result.ok());
        assertTrue(result.content().contains("没搜到"));
        assertTrue(result.content().contains("拦截"));
    }

    @Test
    @DisplayName("搜索服务出错变成给模型的反馈")
    void httpErrorBecomesFeedback() {
        server.enqueue(new MockResponse().setResponseCode(503));

        ToolResult result = tool.execute(args("{\"query\": \"x\"}"));
        assertFalse(result.ok());
        assertTrue(result.content().contains("503"));
    }
}
