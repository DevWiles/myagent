package dev.wiles.myagent.browser;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每个标签页现在停在哪个站上。
 *
 * <h3>为什么非得自己记</h3>
 * 按站点审批要回答的问题是「这次动作发生在哪个站」，而绝大多数浏览器工具
 * （{@code click}、{@code fill}、{@code evaluate_script}）参数里<b>只有 uid 和 pageId，
 * 没有 URL</b>——站点这件事只在导航的那一刻出现过一次。不记下来的话，
 * 后面每一次操作都问不出一个人能回答的问题。
 *
 * <p>Claude in Chrome 那套扩展不需要这一层：扩展本来就知道每个 tab 的真实 URL。
 * 走 CDP 就得自己维护这份对照表，<b>这是两种管子的真实差价</b>。
 *
 * <h3>为什么要从工具输出里认，而不只认参数</h3>
 * 点一个链接就能跳到别的站。只认导航参数的话，审批就成了摆设：
 * 批准了 a.com，agent 点一下链接到了 b.com，之后所有动作都还挂在 a.com 名下。
 * 所以每次调用之后都要看一眼输出里这一页现在的 URL——
 * <b>发现得晚不要紧，下一个动作之前拦住就行</b>。
 */
public final class BrowserSites {

    /** 快照抬头里那一页自己的地址：{@code uid=1_0 RootWebArea "标题" url="https://…"}。 */
    private static final Pattern ROOT_URL =
            Pattern.compile("RootWebArea[^\\n]*?url=\"([^\"]+)\"");

    /** {@code list_pages} 的一行：{@code 2: 登记表 (http://127.0.0.1:8901/form.html) [selected]}。 */
    private static final Pattern PAGE_LINE =
            Pattern.compile("(?m)^\\s*(\\d+):[^(\\n]*\\((\\S+?)\\)");

    private final Map<String, String> hostByPage = new ConcurrentHashMap<>();

    /** 这一页现在在哪个站。没记过就是空——空的时候<b>不能猜</b>，见 GuardedBrowserTool。 */
    public Optional<String> hostOf(String pageId) {
        return pageId == null ? Optional.empty() : Optional.ofNullable(hostByPage.get(pageId));
    }

    public void remember(String pageId, String host) {
        if (pageId != null && host != null && !host.isBlank()) {
            hostByPage.put(pageId, host);
        }
    }

    /**
     * 从一次调用的输出里更新对照表。
     *
     * @param pageId 这次调用针对的页面，可能为 null（{@code list_pages} 这种）
     */
    public void observe(String pageId, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        // list_pages 一次给全所有页面，是最权威的一份，先认它
        Matcher lines = PAGE_LINE.matcher(output);
        while (lines.find()) {
            String pageNumber = lines.group(1);
            siteIn(lines.group(2)).ifPresent(host -> remember(pageNumber, host));
        }
        // 快照抬头只说得清「这一页」，所以必须知道这次问的是哪一页
        if (pageId != null) {
            Matcher root = ROOT_URL.matcher(output);
            if (root.find()) {
                siteIn(root.group(1)).ifPresent(host -> remember(pageId, host));
            }
        }
    }

    /**
     * 从一个 URL 里取出「这是哪个站」——审批要问的就是这个字符串。
     *
     * <h3>为什么不能直接用 URI.getHost()</h3>
     * {@code chrome://new-tab-page/} 的 getHost() 是 {@code new-tab-page}，
     * 拿它去问用户「要不要允许在 new-tab-page 上操作」纯属胡说，
     * 还会在 policy.json 里留下一条名字像域名的垃圾规则。
     *
     * <h3>非 http(s) 的页面按 scheme 归一类</h3>
     * {@code file://} 尤其要管：浏览器打开本地文件是一条<b>绕过工作区边界</b>的路，
     * 文件工具那边拦得死死的，从这边却能读整个磁盘。所以它不是「没有站点」，
     * 而是「站点叫 file://」——一个用户答得出来的问题（「要不要允许它操作本地文件页面」），
     * 也是一条记得下来的规则。Chrome 自己对扩展也是这么分的
     * （file:// 要单独开一个「允许访问文件网址」）。
     *
     * <p>{@code about:blank} 这种连 scheme 之外什么都没有的，返回空：
     * 空白页上没有任何东西可读，拦它只是纯摩擦。
     */
    public static Optional<String> siteIn(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = uri.getHost();
            return host == null || host.isBlank()
                    ? Optional.empty()
                    : Optional.of(host.toLowerCase(Locale.ROOT));
        }
        if ("about".equals(scheme) || scheme.isEmpty()) {
            return Optional.empty();         // about:blank 上没东西可读，拦它只是摩擦
        }
        return Optional.of(scheme + "://");
    }
}
