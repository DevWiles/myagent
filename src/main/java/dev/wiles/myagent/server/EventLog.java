package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 广播事件的流水账：编号 + 留底，为断线重连补发（协议 1.2 节）。
 *
 * <p>手机端和终端不一样——终端和后端同生共死，连接断了进程也就没了；
 * 手机切个后台、过个电梯，连接必断而后端还活着。缺的那段事件不补，
 * 转录就永久豁一块口子，而且用户根本不知道豁了。
 *
 * <h2>为什么按字节封顶，不按条数</h2>
 * 第一版按条数留 4096 条。看着不小，实际上一轮长回答的 text_delta 就有几千条——
 * 手机重连（since=0）拿到的永远只有最新一轮，前面的全被冲掉了。
 * 「多少轮」由字节预算决定才诚实：一条 delta 几十字节，一条工具结果几 KB，
 * 按条数算的话这两种事件占的「名额」一样，而占的内存差一百倍。
 *
 * <h2>补发时合并 text_delta</h2>
 * 留底里存的是逐字的流式增量（现场直播要它），但补发的人不需要看打字机效果——
 * 他要的是内容。连续的 delta 在补发时合并成一条（带最后那个 seq），
 * 一轮回答从几千帧变成一帧：既省中继的流量，也让手机重连快得多。
 * 合并只发生在<b>读</b>的一侧，留底本身一个字没动。
 *
 * <p>缓冲有限，补不全时如实说有缺口，由客户端决定怎么提示。
 * <b>撒谎比缺口糟</b>：静默跳过的话，转录看起来完整、实际上少了一段。
 */
final class EventLog {

    /** 一次补发：缺口（如果有）+ 手上还留着的那些（delta 已合并）。 */
    record Replay(boolean gap, long gapFrom, long gapTo, List<String> events) {
    }

    /**
     * 一条留底。{@code deltaText} 非 null 表示这是条 text_delta，补发时可以合并；
     * {@code task} 是它属于哪条并行线（计划/流水线的子任务），不同线的不能合并。
     */
    private record Entry(long seq, String json, String deltaText, String task) {
    }

    private final ArrayDeque<Entry> buffer = new ArrayDeque<>();
    private final int maxBytes;
    private long bytes = 0;
    private long lastSeq = 0;

    EventLog(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * 编号、留底，返回该广播出去的那行 JSON。
     *
     * <p>调用方要在同一把锁里完成「stamp + broadcast」——编号顺序必须等于
     * 发送顺序，否则补发出去的序列和当时播出的对不上。
     */
    synchronized String stamp(ObjectNode node) {
        long seq = ++lastSeq;
        node.put("seq", seq);
        String json = node.toString();

        String deltaText = null;
        String task = null;
        if ("text_delta".equals(node.path("t").asText())) {
            deltaText = node.path("text").asText("");
            JsonNode taskNode = node.get("task");
            task = taskNode == null ? "" : taskNode.asText("");
        }
        buffer.addLast(new Entry(seq, json, deltaText, task));
        bytes += json.length();
        // 至少留最新一条：预算再小，「lastSeq 有货」这个不变式不能破
        while (bytes > maxBytes && buffer.size() > 1) {
            bytes -= buffer.removeFirst().json().length();
        }
        return json;
    }

    /** 到目前为止发出的最后一个号。一条都没发过是 0。 */
    synchronized long lastSeq() {
        return lastSeq;
    }

    /**
     * 客户端说「我看到 {@code since} 号了」，把之后的补给它。
     *
     * <p>连续的 text_delta（同一条并行线的）合并成一条，seq 取最后那个——
     * 客户端本来就是把 delta 逐段拼接的，一段长的和几百段短的拼出来一个样。
     */
    synchronized Replay replay(long since) {
        List<String> events = new ArrayList<>();
        long firstReplayed = -1;

        StringBuilder pendingText = null;
        long pendingSeq = 0;
        String pendingTask = null;

        for (Entry entry : buffer) {
            if (entry.seq() <= since) {
                continue;
            }
            if (firstReplayed < 0) {
                firstReplayed = entry.seq();
            }
            if (entry.deltaText() != null) {
                if (pendingText != null && !pendingTask.equals(entry.task())) {
                    events.add(mergedDelta(pendingText, pendingSeq, pendingTask));
                    pendingText = null;
                }
                if (pendingText == null) {
                    pendingText = new StringBuilder();
                    pendingTask = entry.task();
                }
                pendingText.append(entry.deltaText());
                pendingSeq = entry.seq();
                continue;
            }
            if (pendingText != null) {
                events.add(mergedDelta(pendingText, pendingSeq, pendingTask));
                pendingText = null;
            }
            events.add(entry.json());
        }
        if (pendingText != null) {
            events.add(mergedDelta(pendingText, pendingSeq, pendingTask));
        }

        boolean gap = firstReplayed > since + 1;
        return new Replay(gap, gap ? since + 1 : 0, gap ? firstReplayed - 1 : 0, events);
    }

    private static String mergedDelta(StringBuilder text, long seq, String task) {
        ObjectNode node = Protocol.event("text_delta");
        node.put("text", text.toString());
        node.put("seq", seq);
        if (!task.isEmpty()) {
            node.put("task", task);
        }
        return node.toString();
    }
}
