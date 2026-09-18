package dev.wiles.myagent.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 一段原文的摘要，覆盖会话记录里 {@code [from, to)} 这个左闭右开区间。
 *
 * <h3>下标为什么能当缓存键</h3>
 * 因为<b>记录只追加</b>。会话记录（{@link SessionSnapshot#messages}）一旦写进去就不再改动，
 * 所以「第 12 到第 47 条的摘要」这句话的含义永远不会变——今天算出来的段，
 * 明天、下次开机、恢复会话之后，指的还是同一批消息。
 *
 * <p>这是整个压缩设计的地基。旧实现把压缩结果写回历史本身，下标随时会错位，
 * 于是任何缓存都不成立，每次只能从头再压一遍，而「再压一遍」压的又是上次的摘要，
 * 于是摘要的摘要的摘要，压几轮就退化成没有信息量的套话。
 *
 * <h3>map 的产物，不是 reduce 的</h3>
 * 段是 map 阶段一块一块算出来的，输入<b>永远是原文</b>。
 * reduce 把若干段合成一段连贯摘要，那个结果是一次性的，用完即弃，
 * <b>绝不回流</b>成下一次的输入——递归退化因此在结构上不可能发生。
 *
 * @param from 起点下标，闭
 * @param to   终点下标，开
 * @param text 这一段的摘要正文
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SummarySegment(int from, int to, String text) {

    /**
     * 这个段对一份 {@code size} 条的记录还成立吗。
     *
     * <p>存档可能被手工改过、或者写到一半断电，读回来的段完全可能指向不存在的下标。
     * 越界的段不是「修一修还能用」，是<b>指错了原文</b>，只能丢——
     * 留着它等于让一段不相干的摘要冒充这批消息的内容。
     */
    @JsonIgnore
    public boolean fitsIn(int size) {
        return from >= 0 && to > from && to <= size && text != null && !text.isBlank();
    }

    /** 覆盖了多少条消息。 */
    @JsonIgnore
    public int count() {
        return to - from;
    }

    /** 一串段里最靠后的那个终点——也就是「原文已经被摘要覆盖到哪了」。 */
    public static int coveredUpTo(List<SummarySegment> segments) {
        int max = 0;
        if (segments != null) {
            for (SummarySegment s : segments) {
                max = Math.max(max, s.to());
            }
        }
        return max;
    }
}
