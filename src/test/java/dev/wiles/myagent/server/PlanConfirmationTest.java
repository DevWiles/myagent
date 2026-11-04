package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.agent.PlanModeTools.Answer;
import dev.wiles.myagent.agent.PlanModeTools.Ask;
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

        assertFalse(confirm.review(Ask.EXIT, "计划").approved());
        assertTrue(published.isEmpty(), "没人能答就别发问了");
    }

    @Test
    @DisplayName("交计划：请求里带着 kind=exit 和计划全文；界面回同意，执行线程就放行")
    void approves() throws InterruptedException {
        PlanConfirmation confirm = withClients();
        Answer[] result = new Answer[1];
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            result[0] = confirm.review(Ask.EXIT, "## 计划\n改 a.py");
            done.countDown();
        });
        worker.start();

        waitUntilPending(confirm);
        ObjectNode request = published.get(0);
        assertEquals("plan_confirm_request", request.get("t").asText());
        assertEquals("exit", request.get("kind").asText());
        assertEquals("## 计划\n改 a.py", request.get("plan").asText());
        assertTrue(confirm.reply(request.get("id").asInt(), true, ""));

        assertTrue(done.await(2, TimeUnit.SECONDS), "review() 没醒过来");
        assertTrue(result[0].approved());
        assertFalse(confirm.isPending());
    }

    @Test
    @DisplayName("提议进 plan 模式：kind=enter、不带计划；否决时附的话原样带回去")
    void rejectionCarriesFeedback() throws InterruptedException {
        PlanConfirmation confirm = withClients();
        Answer[] result = new Answer[1];
        CountDownLatch done = new CountDownLatch(1);

        new Thread(() -> {
            result[0] = confirm.review(Ask.ENTER, "");
            done.countDown();
        }).start();
        waitUntilPending(confirm);
        ObjectNode request = published.get(0);
        assertEquals("enter", request.get("kind").asText());
        assertFalse(request.has("plan"));
        assertTrue(confirm.reply(request.get("id").asInt(), false, "  直接改就行  "));

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertFalse(result[0].approved());
        assertEquals("直接改就行", result[0].feedback());
    }

    @Test
    @DisplayName("id 对不上的回答是过期回答，丢弃")
    void ignoresStaleReply() throws InterruptedException {
        PlanConfirmation confirm = withClients();
        CountDownLatch done = new CountDownLatch(1);
        Answer[] result = {new Answer(true, "")};

        Thread worker = new Thread(() -> {
            result[0] = confirm.review(Ask.EXIT, "计划");
            done.countDown();
        });
        worker.start();
        waitUntilPending(confirm);

        assertFalse(confirm.reply(999, true, ""), "过期回答不该被接受");
        assertFalse(done.await(200, TimeUnit.MILLISECONDS), "它应该还在等真正的回答");

        confirm.cancelPending();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertFalse(result[0].approved());
    }

    @Test
    @DisplayName("用户中断时要把等在那儿的确认放行，否则执行线程永远醒不过来")
    void cancelReleasesTheWaiter() throws InterruptedException {
        PlanConfirmation confirm = withClients();
        CountDownLatch done = new CountDownLatch(1);
        Answer[] result = {new Answer(true, "")};

        new Thread(() -> {
            result[0] = confirm.review(Ask.EXIT, "计划");
            done.countDown();
        }).start();
        waitUntilPending(confirm);

        confirm.cancelPending();

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertFalse(result[0].approved());
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
