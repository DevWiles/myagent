package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 「这份计划要不要执行」——发给远端界面，然后阻塞等回答。
 *
 * <p>和 {@link RemoteApproval} 是一个路数，区别只在问的是什么。没有合并成一个类，
 * 因为两者的语义差别是实打实的：审批可以「本会话都同意」，计划确认不行——
 * 每份计划都长得不一样，一次性授权掉后面所有计划是很危险的事。
 *
 * <p>没人连着就按拒绝：跑一份没人看过的计划，正是这个特性想避免的事。
 */
public final class PlanConfirmation {

    /** 等人看计划的上限。计划要读一会儿，给得比审批宽松些。 */
    private static final long TIMEOUT_SECONDS = 900;

    private final Consumer<ObjectNode> publish;
    private final BooleanSupplier hasClients;

    private final AtomicInteger nextId = new AtomicInteger(1);
    /** 容量 1 就够：同一时刻只可能有一份待确认的计划。 */
    private final BlockingQueue<Boolean> answers = new ArrayBlockingQueue<>(1);

    private volatile int pendingId = 0;

    public PlanConfirmation(Consumer<ObjectNode> publish, BooleanSupplier hasClients) {
        this.publish = publish;
        this.hasClients = hasClients;
    }

    /**
     * 问一次。阻塞直到界面回答、超时、或者发现没人连着。
     *
     * @return true 表示放行执行
     */
    public boolean confirm() {
        if (!hasClients.getAsBoolean()) {
            return false;
        }
        int id = nextId.getAndIncrement();
        answers.clear();                    // 丢掉上一轮的过期回答
        pendingId = id;

        publish.accept(Protocol.event("plan_confirm_request").put("id", id));
        try {
            Boolean answer = answers.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return answer != null && answer;        // 超时按拒绝
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            pendingId = 0;
        }
    }

    /**
     * 界面回了答案。由 WebSocket 线程调用。
     *
     * @return 是否被接受（id 对不上就是过期回答，丢弃）
     */
    public boolean reply(int id, boolean approved) {
        if (id != pendingId || pendingId == 0) {
            return false;
        }
        return answers.offer(approved);
    }

    /** 用户中断了。把等在那儿的 confirm() 按拒绝放行，否则执行线程醒不过来。 */
    public void cancelPending() {
        if (pendingId != 0) {
            answers.offer(false);
        }
    }

    public boolean isPending() {
        return pendingId != 0;
    }
}
