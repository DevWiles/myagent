package dev.wiles.myagent.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserSitesTest {

    @Test
    @DisplayName("http(s) 取主机名，端口不进站点名——同一个站换个端口不该重问一遍")
    void httpUrlsYieldTheHost() {
        assertEquals("example.com", BrowserSites.siteIn("https://example.com/a/b?q=1").orElseThrow());
        assertEquals("127.0.0.1", BrowserSites.siteIn("http://127.0.0.1:8901/form.html").orElseThrow());
        assertEquals("mail.google.com", BrowserSites.siteIn("https://MAIL.Google.COM/").orElseThrow());
    }

    @Test
    @DisplayName("file:// 归成一个站点，不是「没有站点」——从这条路能读到工作区外的整个磁盘")
    void fileUrlsAreASiteOfTheirOwn() {
        assertEquals("file://", BrowserSites.siteIn("file:///Users/me/.ssh/id_rsa").orElseThrow());
    }

    @Test
    @DisplayName("about:blank 上没东西可读，拦它只是纯摩擦")
    void blankPagesAreNotASite() {
        assertTrue(BrowserSites.siteIn("about:blank").isEmpty());
        assertTrue(BrowserSites.siteIn("").isEmpty());
        assertTrue(BrowserSites.siteIn(null).isEmpty());
    }

    @Test
    @DisplayName("从快照抬头认出这一页现在在哪儿——点击跳站全靠它发现")
    void snapshotHeaderUpdatesThePage() {
        BrowserSites sites = new BrowserSites();

        sites.observe("2", "## Latest page snapshot\n"
                + "uid=1_0 RootWebArea \"登记表\" url=\"https://example.com/form\"\n"
                + "  uid=1_1 heading \"登记表\" level=\"1\"");

        assertEquals("example.com", sites.hostOf("2").orElseThrow());
    }

    @Test
    @DisplayName("没带 pageId 的调用不能拿快照抬头往任何一页上安")
    void snapshotWithoutAPageIdIsIgnored() {
        BrowserSites sites = new BrowserSites();

        sites.observe(null, "uid=1_0 RootWebArea \"x\" url=\"https://example.com/\"");

        assertTrue(sites.hostOf("1").isEmpty(), "安错页的后果是拿 A 页的授权去批 B 页的动作");
    }

    @Test
    @DisplayName("list_pages 一次认全所有页面，它是「先认路」的那一步")
    void listPagesMapsEveryPage() {
        BrowserSites sites = new BrowserSites();

        sites.observe(null, "## Pages\n"
                + "1: 新标签页 (chrome://new-tab-page/)\n"
                + "2: 登记表 (http://127.0.0.1:8901/form.html) [selected]\n"
                + "3: Gmail (https://mail.google.com/mail/u/0)");

        assertEquals("chrome://", sites.hostOf("1").orElseThrow());
        assertEquals("127.0.0.1", sites.hostOf("2").orElseThrow());
        assertEquals("mail.google.com", sites.hostOf("3").orElseThrow());
    }
}
