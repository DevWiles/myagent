package dev.wiles.myagent.tool.safety;

import java.util.Locale;

/**
 * 审批动作的<b>机器键</b>。
 *
 * <h3>为什么要和给人看的那句话分开</h3>
 * 以前审批请求里只有一个 {@code action} 字符串，取值是「执行命令」「写入文件」「覆盖文件」——
 * 给人看没问题，但它同时被当成了唯一的标识。于是「覆盖文件」和「写入文件」在机器眼里
 * 是两种东西，尽管它们该受同一条规则管；反过来，想加一句提示改改措辞，
 * 就会悄悄让已有的规则全部失配。
 *
 * <p>所以分成两个字段：{@code kind} 是这个枚举，规则匹配只认它；
 * {@link ApprovalRequest#action()} 是给人看的那句话，随便改。
 *
 * <h3>它也决定了规则怎么匹配</h3>
 * 两种 kind 的 {@code subject} 是完全不同的东西，匹配方式也就不一样：
 * <ul>
 *   <li>{@link #BASH} 的 subject 是命令原文，规则按 <b>token 前缀</b>匹配，
 *       而且必须<b>逐段</b>匹配（见 {@link ApprovalRules}）</li>
 *   <li>{@link #WRITE} 的 subject 是工作区相对路径，规则按<b>路径 glob</b> 匹配</li>
 *   <li>{@link #BROWSE} 的 subject 是<b>站点主机名</b>，规则按主机名匹配，
 *       {@code *.example.com} 连顶级域一起罩住</li>
 * </ul>
 */
public enum ApprovalKind {

    /** 执行 shell 命令。 */
    BASH("bash"),

    /** 写文件（新建、覆盖、脚手架里的覆盖，都算这一种）。 */
    WRITE("write"),

    /**
     * 在某个站点上操作浏览器。
     *
     * <h3>为什么按站点而不是按动作</h3>
     * 「点一下」「填个字」单独看都无害，有害的是<b>在哪个站上</b>点——
     * 同一个 click，在本地开发页上是测试，在网银上是转账。
     * 用户能判断的也只有站点：问他「要不要允许在 mail.google.com 上操作」
     * 他答得出来，问他「要不要允许 click uid=1_12」他只能瞎点。
     *
     * <p>这也是 Claude in Chrome 扩展的做法——权限按站点给、按站点撤。
     */
    BROWSE("browse");

    private final String id;

    ApprovalKind(String id) {
        this.id = id;
    }

    /** 落盘和上协议时用的名字。<b>别跟着枚举名走</b>——改枚举名不该让用户的策略文件失效。 */
    public String id() {
        return id;
    }

    /**
     * 认不出就返回 null。
     *
     * <p>策略文件是用户可以手改的，里面出现拼错的 kind 是迟早的事；
     * 让它抛异常会导致<b>一条写错的规则毁掉整个文件</b>，
     * 那时候用户丢掉的是全部授权，而不是一条。
     */
    public static ApprovalKind of(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (ApprovalKind kind : values()) {
            if (kind.id.equals(normalized)) {
                return kind;
            }
        }
        return null;
    }
}
