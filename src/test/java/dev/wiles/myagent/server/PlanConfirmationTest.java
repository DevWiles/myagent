package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlanConfirmationTest {

    private final List<ObjectNode> published = new ArrayList<>();

    private PlanConfirmation withClients() {
        return new PlanConfirmation(published::add, () -> true);
    }

    @Test
    @DisplayName("没人连着就按拒绝——跑一份没人看过的计划正是要避免的事")
    void rejectsWhenNobodyIsWatching() {
        PlanConfirmation confirm = new PlanConfirmation(published::add, () -> false);

        assertFalse(confirm.confirm());
        assertTrue(published.isEmpty(), "没人能答就别发问了");
    }

    @Test
    @DisplayName("界面回同意，执行线程就放行")
    void approves() throws InterruptedException {
        PlanConfirmation confirm = withClients();
        boolean[] result = new boolean[1];
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            result[0] = confirm.confirm();
            done.countDown();
        });
        worker.start();

        waitUntilPending(confirm);
        assertEquals("plan_confirm_request", published.get(0).get("t").asText());
        assertTrue(confirm.reply(published.get(0).get("id").asInt(), true));

        assertTrue(done.await(2, TimeUnit.SECONDS), "confirm() 没醒过来");
        assertTrue(result[0]);
        assertFalse(confirm.isPending());
    }

    @Test
    @DisplayName("id 对不上的回答是过期回答，丢弃")
    void ignoresStaleReply() throws InterruptedException {
        PlanConfirmation confirm = withClients();
        CountDownLatch done = new CountDownLatch(1);
        boolean[] result = {true};

        Thread worker = new Thread(() -> {
            result[0] = confirm.confirm();
            done.countDown();
        });
        worker.start();
        waitUntilPending(confirm);

        assertFalse(confirm.reply(999, true), "过期回答不该被接受");
        assertFalse(done.await(200, TimeUnit.MILLISECONDS), "它应该还在等真正的回答");

        confirm.cancelPending();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertFalse(result[0]);
    }

    @Test
    @DisplayName("用户中断时要把等在那儿的确认放行，否则执行线程永远醒不过来")
    void cancelReleasesTheWaiter() throws InterruptedException {
        PlanConfirmation confirm = withClients();
        CountDownLatch done = new CountDownLatch(1);
        boolean[] result = {true};

        new Thread(() -> {
            result[0] = confirm.confirm();
            done.countDown();
        }).start();
        waitUntilPending(confirm);

        confirm.cancelPending();

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertFalse(result[0]);
    }

    @Test
    @DisplayName("没人在等的时候取消，什么也不做")
    void cancelWithoutPendingIsHarmless() {
        assertDoesNotThrow(() -> withClients().cancelPending());
    }

    private static void waitUntilPending(PlanConfirmation confirm) throws InterruptedException {
        for (int i = 0; i < 200 && !confirm.isPending(); i++) {
            Thread.sleep(5);
        }
        assertTrue(confirm.isPending(), "问题一直没发出去");
    }
}
