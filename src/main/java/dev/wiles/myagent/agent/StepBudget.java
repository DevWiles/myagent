package dev.wiles.myagent.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主 agent 和它派出的子 agent <b>共用</b>的步数预算（{@code MYAGENT_TOTAL_STEPS}）。
 *
 * <h3>为什么要有它</h3>
 * 主 agent 的 {@code --max-steps} 只管它自己；子 agent 各有各的步数，不算在里面。
 * 于是「单 agent 100 步」对比「主 agent 100 步 + 子 agent 又走了 137 步」，多 agent 一方
 * 变相多拿了一倍多的算力——分数高了也说不清是协作的功劳还是算力的功劳。
 * 共用一个计数器，两边就站在同一条起跑线上（算力对齐的那一组对照）。
 *
 * <p>每一步请求模型之前取一格，取不到就收尾。用完时谁正在跑谁收尾：
 * 子 agent 交出半截的结论，主 agent 用不带工具的最后一次请求交代进展——和撞上自己的步数上限一样。
 */
public final class StepBudget {

    private final int total;
    private final AtomicInteger used = new AtomicInteger();

    public StepBudget(int total) {
        if (total <= 0) {
            throw new IllegalArgumentException("总步数预算要大于 0，收到: " + total);
        }
        this.total = total;
    }

    /** 取一步。true 表示取到了。 */
    public boolean take() {
        while (true) {
            int now = used.get();
            if (now >= total) {
                return false;
            }
            if (used.compareAndSet(now, now + 1)) {
                return true;
            }
        }
    }

    public int used() {
        return used.get();
    }

    public int total() {
        return total;
    }
}
