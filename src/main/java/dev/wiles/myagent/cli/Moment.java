package dev.wiles.myagent.cli;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 把一个时刻说成人话：{@code 刚刚}、{@code 12 分钟前}、{@code 昨天 14:32}、{@code 9-16 09:15}。
 *
 * <h3>为什么不直接打时间戳</h3>
 * 这些时间是给人做<b>选择</b>用的（挑哪段会话接着聊、那份计划是不是还值得续）。
 * {@code 2026-09-18 14:32:10} 需要用户自己去和「现在」做减法，
 * 而他真正想知道的只有「这是刚才的事还是上周的事」。
 *
 * <h3>为什么近的说相对、远的说绝对</h3>
 * 相对时间在近处精确、在远处失真：「237 小时前」谁也换算不出来是哪天。
 * 所以 6 小时内说相对（那是「同一段工作」的尺度），
 * 跨过去就报日期时间，让用户自己认那一天在干什么。
 *
 * <p>纯函数，{@code now} 由调用方传——不然这个类没法测，
 * 而它每一条分支都是「差一点就错」的边界。
 */
public final class Moment {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MD_HHMM = DateTimeFormatter.ofPattern("M-d HH:mm");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-M-d");

    /** 6 小时内算「同一段工作」，说相对时间。 */
    private static final long RELATIVE_HOURS = 6;

    private Moment() {
    }

    public static String describe(long when, long now) {
        if (when <= 0) {
            return "时间不明";
        }
        long seconds = (now - when) / 1000;
        if (seconds < 60) {
            // 负数也走这条：时钟回拨、存档从别的机器拷过来，都会让「现在」比存档还早。
            // 说「刚刚」不准，但「-3 小时前」是纯粹的乱码
            return "刚刚";
        }
        if (seconds < 3600) {
            return seconds / 60 + " 分钟前";
        }
        if (seconds < RELATIVE_HOURS * 3600) {
            return seconds / 3600 + " 小时前";
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime then = Instant.ofEpochMilli(when).atZone(zone).toLocalDateTime();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate thatDay = then.toLocalDate();

        if (thatDay.equals(today)) {
            return "今天 " + HHMM.format(then);
        }
        if (thatDay.equals(today.minusDays(1))) {
            return "昨天 " + HHMM.format(then);
        }
        if (thatDay.getYear() == today.getYear()) {
            return MD_HHMM.format(then);
        }
        // 跨年就不报时刻了：隔了这么久，具体几点几分帮不上任何决定
        return YMD.format(then);
    }
}
