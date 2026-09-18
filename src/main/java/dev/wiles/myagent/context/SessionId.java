package dev.wiles.myagent.context;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 一次会话的标识，形如 {@code 20260918-143210-3f9a}：本地时间 + 四位随机尾巴。
 *
 * <h3>为什么不用 UUID</h3>
 * 这个 id 同时是<b>文件名</b>，而文件名是这套存档唯一的排序依据。
 * 用 UUID 的话，{@code ~/.myagent/sessions/} 下一眼看过去全是乱码，
 * 想知道哪份最新只能把每个文件都打开读 {@code savedAt}。
 * 带时间前缀的话，按名字排序就是按时间排序，列表和人工排查都省事。
 *
 * <h3>为什么还要四位随机</h3>
 * 光有秒级时间不够：同一秒里开两个终端（或者测试里连着造两份）会撞名，
 * 后开的那个会把前一个直接覆盖掉——现象是「明明开了两个会话，历史里只剩一个」。
 *
 * <h3>为什么要 {@link #isValid}</h3>
 * id 是<b>从盘上的文件名读回来</b>的，也会从界面/协议传进来。
 * 一旦有 {@code ../../../.ssh/id_rsa} 这种东西混进来当 id，
 * {@code dir.resolve(id + ".json")} 就会跑到工作区外面去读写。
 * 和 {@link dev.wiles.myagent.tool.Workspace} 里那道防线是同一个道理：
 * 拼路径之前先认格式，不认就当没有。
 */
public final class SessionId {

    /** 只认这一种形状：8 位日期 - 6 位时间 - 4 位十六进制。 */
    private static final Pattern SHAPE = Pattern.compile("\\d{8}-\\d{6}-[0-9a-f]{4}");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SessionId() {
    }

    /** 给「现在开始的这次会话」造一个 id。 */
    public static String create(long epochMillis) {
        return stamp(epochMillis) + "-" + String.format("%04x",
                ThreadLocalRandom.current().nextInt(0x10000));
    }

    /**
     * 给一份没有 id 的老存档补一个，时间取它自己的 {@code savedAt}。
     *
     * <p>随机尾巴换成固定的 {@code 0000}：迁移得是<b>幂等</b>的——
     * 万一同一份老档被收两次（上次收到一半进程挂了），算出来的还是同一个文件名，
     * 覆盖掉就完了，不会在历史里留下两条一模一样的会话。
     */
    public static String forLegacy(long epochMillis) {
        return stamp(epochMillis) + "-0000";
    }

    /** 这个字符串能不能当 id 用（也就是能不能拿去拼文件名）。 */
    public static boolean isValid(String id) {
        return id != null && SHAPE.matcher(id).matches();
    }

    /** id 里的时间，给界面显示「这个会话是什么时候的」用。认不出来就返回 null。 */
    public static Instant instantOf(String id) {
        if (!isValid(id)) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(id.substring(0, 15), STAMP)
                    .atZone(ZoneId.systemDefault()).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stamp(long epochMillis) {
        return STAMP.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
