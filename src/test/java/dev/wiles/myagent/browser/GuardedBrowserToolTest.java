package dev.wiles.myagent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.safety.ApprovalKind;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这层是安全控制，所以测的重点不是「批准的时候能跑」，而是
 * <b>没批准的时候跑不了，以及绕不过去</b>。
 */
class GuardedBrowserToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 记下被问过哪些站，好断言「问的是站点」而不只是「问了」。 */
    private static final class Recorder implements ApprovalPolicy {
        final List<String> asked = new ArrayList<>();
        boolean answer = true;

        @Override
        public ApprovalVerdict decide(ApprovalRequest request) {
            assertEquals(ApprovalKind.BROWSE, request.kind(), "浏览器动作必须按 browse 问");
            asked.add(request.subject());
            return answer ? ApprovalVerdict.yes() : ApprovalVerdict.no();
        }
    }

    /** 假的浏览器工具：记下自己有没有真被执行，输出可控。 */
    private static final class FakeTool implements Tool {
        int runs;
        String output = "";

        @Override
        public String name() {
            return "mcp__chrome__click";
        }

        @Override
        public String description() {
            return "点一下";
        }

        @Override
        public String parametersSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public ToolResult execute(JsonNode args) {
            runs++;
            return ToolResult.ok(output);
        }
    }

    private static JsonNode args(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("导航按目标站点问——批下来才真的打开")
    void navigationAsksAboutTheTargetSite() {
        Recorder policy = new Recorder();
        FakeTool tool = new FakeTool();
        GuardedBrowserTool guarded = new GuardedBrowserTool(tool, policy, new BrowserSites());

        guarded.execute(args("{\"url\":\"https://mail.google.com/mail/u/0\"}"));

        assertEquals(List.of("mail.google.com"), policy.asked, "问的是主机名，不是整条 URL");
        assertEquals(1, tool.runs);
    }

    @Test
    @DisplayName("用户说不行，工具一次都不许跑——拒绝要挡在执行之前")
    void denialStopsTheToolFromRunning() {
        Recorder policy = new Recorder();
        policy.answer = false;
        FakeTool tool = new FakeTool();
        GuardedBrowserTool guarded = new GuardedBrowserTool(tool, policy, new BrowserSites());

        ToolResult result = guarded.execute(args("{\"url\":\"https://example.com\"}"));

        assertFalse(result.ok());
        assertEquals(0, tool.runs, "拒绝之后还执行，这层就只是个装饰");
    }

    @Test
    @DisplayName("导航批下来之后，同一页上的后续动作按同一个站问——click 参数里根本没有 URL")
    void laterActionsOnThatPageAskAboutTheSameSite() {
        Recorder policy = new Recorder();
        BrowserSites sites = new BrowserSites();
        GuardedBrowserTool guarded = new GuardedBrowserTool(new FakeTool(), policy, sites);

        guarded.execute(args("{\"pageId\":\"2\",\"url\":\"https://example.com/form\"}"));
        guarded.execute(args("{\"pageId\":\"2\",\"uid\":\"1_12\"}"));

        assertEquals(List.of("example.com", "example.com"), policy.asked);
    }

    @Test
    @DisplayName("不知道这一页是哪个站：不问，退回去让模型先认路")
    void unknownPageIsRefusedNotAsked() {
        Recorder policy = new Recorder();
        FakeTool tool = new FakeTool();
        GuardedBrowserTool guarded = new GuardedBrowserTool(tool, policy, new BrowserSites());

        ToolResult result = guarded.execute(args("{\"pageId\":\"7\",\"uid\":\"1_3\"}"));

        assertFalse(result.ok());
        assertEquals(0, tool.runs);
        assertTrue(policy.asked.isEmpty(),
                "「要不要允许操作 7 号页面」是个人答不了的问题，问了只会被一路按同意");
        assertTrue(result.content().contains("list_pages"), "得告诉模型怎么把路认出来");
    }

    @Test
    @DisplayName("点链接跳到别的站：下一个动作按新站点问，不能还挂在老站名下")
    void followingALinkOffSiteIsCaughtBeforeTheNextAction() {
        Recorder policy = new Recorder();
        BrowserSites sites = new BrowserSites();
        FakeTool tool = new FakeTool();
        GuardedBrowserTool guarded = new GuardedBrowserTool(tool, policy, sites);

        guarded.execute(args("{\"pageId\":\"2\",\"url\":\"https://example.com\"}"));
        // 这一下点出去了，输出里的快照抬头已经是别的站
        tool.output = "uid=1_0 RootWebArea \"别处\" url=\"https://evil.test/landing\"";
        guarded.execute(args("{\"pageId\":\"2\",\"uid\":\"1_5\"}"));
        tool.output = "";
        guarded.execute(args("{\"pageId\":\"2\",\"uid\":\"1_6\"}"));

        assertEquals(List.of("example.com", "example.com", "evil.test"), policy.asked,
                "只认导航参数的话，跳站之后所有动作都会挂在被批准的那个站名下");
    }

    @Test
    @DisplayName("list_pages 这类不针对具体页面的直接过——它是「先认路」的唯一出路")
    void pagelessToolsPassThrough() {
        Recorder policy = new Recorder();
        BrowserSites sites = new BrowserSites();
        FakeTool tool = new FakeTool();
        tool.output = "## Pages\n1: 新标签页 (chrome://new-tab-page/)\n2: 登记表 (https://example.com/form) [selected]";
        GuardedBrowserTool guarded = new GuardedBrowserTool(tool, policy, sites);

        assertTrue(guarded.execute(args("{}")).ok());
        assertTrue(policy.asked.isEmpty());
        assertEquals("example.com", sites.hostOf("2").orElseThrow(), "认完路要记住");
        assertEquals("chrome://", sites.hostOf("1").orElseThrow(),
                "chrome:// 的 getHost() 是 new-tab-page，拿它当站点名去问用户纯属胡说");
    }
}
