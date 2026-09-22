package dev.wiles.myagent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.safety.ApprovalKind;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;

import java.util.Optional;

/**
 * 给浏览器工具套一层<b>按站点</b>的审批。
 *
 * <h3>为什么这层非有不可</h3>
 * MCP 工具原来完全不走审批——以前接的都是查询类 server，代价有限。
 * 浏览器把这件事变尖锐了：{@code evaluate_script} 能在页面里跑任意 JS，
 * 而一旦接的是<b>你日常那个浏览器</b>，页面里带着你全部的登录态。
 *
 * <h3>问什么：站点，不是动作</h3>
 * 见 {@link ApprovalKind#BROWSE}。人能判断的是「要不要让它碰这个站」，
 * 判断不了「要不要让它 click uid=1_12」。
 *
 * <h3>三条路，其中第三条是这层的关键</h3>
 * <ol>
 *   <li>参数里有 {@code url}（导航）→ 拿那个 URL 的主机名去问；</li>
 *   <li>参数里有 {@code pageId} 且这一页的站点记过 → 拿记下的主机名去问
 *       （批过一次之后规则会静默放行，所以不吵）；</li>
 *   <li>参数里有 {@code pageId} 但<b>不知道这一页是哪个站</b> →
 *       <b>不问，直接退回</b>，让模型先去 {@code list_pages}。</li>
 * </ol>
 * 第三条是刻意的：这时候唯一能问出口的问题是「要不要允许操作第 3 号页面」，
 * 而用户根本没法判断第 3 号页面是什么。<b>问一个人答不了的问题，
 * 比不问更糟</b>——他只会一路按同意，那时候这层就成了纯粹的摩擦。
 * 退回去让模型先认路，一次多余的调用换一个答得出来的问题。
 *
 * <h3>没有 pageId 也没有 url 的工具</h3>
 * {@code list_pages} 这类不针对具体页面的直接放行：它是上面第三条的出路，
 * 拦住它就没人认得了路。代价是<b>标签页清单本身会被看到</b>
 * （标题和地址），接日常浏览器时这是一笔要认的账，README 里写明了。
 */
public final class GuardedBrowserTool implements Tool {

    private final Tool delegate;
    private final ApprovalPolicy approval;
    private final BrowserSites sites;

    public GuardedBrowserTool(Tool delegate, ApprovalPolicy approval, BrowserSites sites) {
        this.delegate = delegate;
        this.approval = approval;
        this.sites = sites;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public String parametersSchema() {
        return delegate.parametersSchema();
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String pageId = text(args, "pageId");
        String url = text(args, "url");

        Optional<String> host = url != null
                ? BrowserSites.siteIn(url)
                : sites.hostOf(pageId);

        if (host.isEmpty() && pageId != null && url == null) {
            return ToolResult.error("不知道 " + pageId + " 号页面现在停在哪个站，"
                    + "没法就它征求用户同意。先调 list_pages 认一下有哪些页面、"
                    + "各自是什么地址，再回来操作这一页。");
        }

        if (host.isPresent()) {
            ApprovalVerdict verdict = approval.decide(new ApprovalRequest(
                    ApprovalKind.BROWSE, "在这个站点上操作浏览器", host.get(),
                    reasonFor(url, host.get()), "", null));
            if (!verdict.approved()) {
                return ToolResult.error(verdict.deniedMessage(
                        "用户不允许在 " + host.get() + " 上操作浏览器。"
                                + "别换个工具再试同一个站，先问清楚该去哪儿。"));
            }
            if (url != null) {
                // 导航批下来了，记上——后面在这一页上的动作才认得出是哪个站
                sites.remember(pageId, host.get());
            }
        }

        ToolResult result = delegate.execute(args);
        // 不管成没成都看一眼：失败的导航也可能已经把页面挪走了
        sites.observe(pageId, result.content());
        return result;
    }

    private static String reasonFor(String url, String host) {
        return url != null
                ? "要打开 " + url + "，这会让 agent 能读写 " + host + " 上的页面内容"
                : "要在 " + host + " 已经打开的页面上操作。页面里有什么、"
                        + "你在这个站的登录态能做什么，它就能做什么";
    }

    private static String text(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").strip();
        return value.isEmpty() ? null : value;
    }
}
