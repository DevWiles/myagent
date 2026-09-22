package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 广播事件的流水账：编号 + 环形缓冲，为断线重连补发（协议 1.2 节）。
 *
 * <p>手机端和终端不一样——终端和后端同生共死，连接断了进程也就没了；
 * 手机切个后台、过个电梯，连接必断而后端还活着。缺的那段事件不补，
 * 转录就永久豁一块口子，而且用户根本不知道豁了。
 *
 * <p>所以：每条<b>广播</b>事件盖一个单调递增的 {@code seq}，最近的若干条留底；
 * 客户端重连时报上「我看到几号了」，之后的照序补发。定向回执（error、
 * session_list 这些）不编号也不留底——它们答的是某一次具体的问，
 * 问的人都断线了，答案没有别人可给。
 *
 * <p>缓冲有限（text_delta 一轮就是上千条），补不全时如实说有缺口，
 * 由客户端决定怎么提示。<b>撒谎比缺口糟</b>：静默跳过的话，
 * 转录看起来完整、实际上少了一段，这种错没人查得出来。
 */
final class EventLog {

    /** 一次补发：缺口（如果有）+ 手上还留着的那些。 */
    record Replay(boolean gap, long gapFrom, long gapTo, List<String> events) {
    }

    private record Entry(long seq, String json) {
    }

    private final ArrayDeque<Entry> buffer = new ArrayDeque<>();
    private final int capacity;
    private long lastSeq = 0;

    EventLog(int capacity) {
        this.capacity = capacity;
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
        buffer.addLast(new Entry(seq, json));
        if (buffer.size() > capacity) {
            buffer.removeFirst();
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
     * <p>缓冲最旧的一条已经比 {@code since + 1} 新，说明中间有一段永远补不回来了——
     * {@code gap} 如实报出缺口范围。{@code since} 比 {@code lastSeq} 还大
     * （连错了后端？）当成没有可补的，不猜。
     */
    synchronized Replay replay(long since) {
        List<String> events = new ArrayList<>();
        long firstReplayed = -1;
        for (Entry entry : buffer) {
            if (entry.seq() > since) {
                if (firstReplayed < 0) {
                    firstReplayed = entry.seq();
                }
                events.add(entry.json());
            }
        }
        // 缓冲至少留着最后一条，所以「lastSeq > since 却什么都补不出」不会发生；
        // 缺口只有一种：补出来的第一条不紧挨着 since
        boolean gap = firstReplayed > since + 1;
        return new Replay(gap, gap ? since + 1 : 0, gap ? firstReplayed - 1 : 0, events);
    }
}
