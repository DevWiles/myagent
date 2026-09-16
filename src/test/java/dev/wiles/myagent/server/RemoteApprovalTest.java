package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 这个类最要命的失败模式不是「批错了」，是「卡住了」——
 * approve() 阻塞在 agent 线程上，醒不过来整个会话就废了。
 * 所以每个用例都带超时断言。
 */
class RemoteApprovalTest {

    /** 每个用例最多等这么久。超了就是死锁，不是慢。 */
    private static final int TIMEOUT_SECONDS = 5;

    private final List<ObjectNode> published = new CopyOnWriteArrayList<>();

    private RemoteApproval withClients() {
        return new RemoteApproval(published::add, () -> true);
    }

    /** 在别的线程上发问，返回一个可以等的结果。 */
    private CompletableFuture<Boolean> askAsync(RemoteApproval approval) {
        return CompletableFuture.supplyAsync(
                () -> approval.approve("执行命令", "rm -rf build", "会删除文件"));
    }

    /** 等到问题总数超过 previous 为止。不能只等「非空」——第二次提问时它一开始就非空。 */
    private void awaitRequestAfter(int previous) {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L;
        while (published.size() <= previous && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(published.size() > previous, "没有把问题发给界面");
    }

    private void awaitRequest() {
        awaitRequestAfter(0);
    }

    private int lastRequestId() {
        return published.get(published.size() - 1).get("id").asInt();
    }

    @Test
    @DisplayName("没有界面连着就直接拒绝——没人可问，不能干等")
    void deniesWhenNobodyIsWatching() {
        RemoteApproval approval = new RemoteApproval(published::add, () -> false);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(TIMEOUT_SECONDS), () ->
                assertFalse(approval.approve("执行命令", "rm -rf /", "危险")));
        assertTrue(published.isEmpty(), "没人可问的时候不该发问题出去");
    }

    @Test
    @DisplayName("回 yes 就放行")
    void yesApproves() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<Boolean> asked = askAsync(approval);
        awaitRequest();

        assertTrue(approval.reply(lastRequestId(), "yes"));
        assertTrue(asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("回 no 就拒绝")
    void noDenies() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<Boolean> asked = askAsync(approval);
        awaitRequest();

        approval.reply(lastRequestId(), "no");
        assertFalse(asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("选了「本会话都同意」之后就不再发问题出去")
    void alwaysStopsAsking() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<Boolean> first = askAsync(approval);
        awaitRequest();
        approval.reply(lastRequestId(), "always");
        assertTrue(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        int asksSoFar = published.size();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(TIMEOUT_SECONDS), () ->
                assertTrue(approval.approve("写文件", "a.txt", "会覆盖")));
        assertEquals(asksSoFar, published.size(), "已经授权了还在问");
    }

    @Test
    @DisplayName("/clear 之后忘掉「都同意」——那是上一场会话的授权")
    void resetForgetsBlanketApproval() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<Boolean> first = askAsync(approval);
        awaitRequest();
        approval.reply(lastRequestId(), "always");
        first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        approval.reset();
        int asksSoFar = published.size();
        CompletableFuture<Boolean> second = askAsync(approval);
        awaitRequestAfter(asksSoFar);       // reset 之后应该重新问
        approval.reply(lastRequestId(), "no");
        assertFalse(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("对不上号的回答直接丢弃，不能让它顶掉当前这个问题")
    void staleReplyIsIgnored() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<Boolean> asked = askAsync(approval);
        awaitRequest();

        int id = lastRequestId();
        assertFalse(approval.reply(id + 99, "yes"), "过期 id 不该被接受");
        assertTrue(approval.isPending(), "问题还该挂着");

        approval.reply(id, "no");
        assertFalse(asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("用户按了中断，等在审批上的那轮要被放行成拒绝")
    void cancelUnblocks() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<Boolean> asked = askAsync(approval);
        awaitRequest();

        approval.cancelPending();
        assertFalse(asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("问题里要带齐动作、细节和原因——少一个用户就没法判断")
    void requestCarriesEnoughToDecide() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<Boolean> asked = askAsync(approval);
        awaitRequest();

        ObjectNode request = published.get(0);
        assertEquals("approval_request", request.get("t").asText());
        assertEquals("执行命令", request.get("action").asText());
        assertEquals("rm -rf build", request.get("detail").asText());
        assertEquals("会删除文件", request.get("reason").asText());

        approval.reply(lastRequestId(), "no");
        asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
