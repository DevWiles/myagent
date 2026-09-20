package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.tool.safety.ApprovalOrigin;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 这个类最要命的失败模式不是「批错了」，是「卡住了」——
 * decide() 阻塞在 agent 线程上，醒不过来整个会话就废了。
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
    private CompletableFuture<ApprovalVerdict> askAsync(RemoteApproval approval) {
        return CompletableFuture.supplyAsync(
                () -> approval.decide(ApprovalRequest.bash("rm -rf build", "会删除文件")));
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

    private ObjectNode lastRequest() {
        return published.get(published.size() - 1);
    }

    private int lastRequestId() {
        return lastRequest().get("id").asInt();
    }

    private boolean reply(RemoteApproval approval, String decision) {
        return approval.reply(lastRequestId(), decision, List.of(), "");
    }

    @Test
    @DisplayName("没有界面连着就直接拒绝——没人可问，不能干等")
    void deniesWhenNobodyIsWatching() {
        RemoteApproval approval = new RemoteApproval(published::add, () -> false);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(TIMEOUT_SECONDS), () ->
                assertFalse(approval.decide(ApprovalRequest.bash("rm -rf /", "危险")).approved()));
        assertTrue(published.isEmpty(), "没人可问的时候不该发问题出去");
    }

    @Test
    @DisplayName("回 yes 就放行")
    void yesApproves() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        assertTrue(reply(approval, "yes"));
        assertTrue(asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).approved());
    }

    @Test
    @DisplayName("回 no 就拒绝")
    void noDenies() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        reply(approval, "no");
        assertFalse(asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).approved());
    }

    @Test
    @DisplayName("always 回来的是一条规则，不是一个「以后都别问」的开关")
    void alwaysReturnsRulesNotASwitch() throws Exception {
        // 这是这一版最要紧的语义改动。以前 always 会在这个类里置一个全局位，
        // 于是为了批一条 mvn test，后面的 curl 和 rm 也一并静默放行了。
        // 现在它只是把用户挑定的规则带回去，存不存、以后拦不拦，是 PolicyGate 的事
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        String pattern = lastRequest().get("suggestions").get(0).get("patterns").get(0).asText();
        approval.reply(lastRequestId(), "always", List.of(pattern), "");

        ApprovalVerdict verdict = asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(verdict.approved());
        assertEquals(List.of(pattern), verdict.remember());

        // 这个类自己不记事：同一件事再问一次，它照样发问题出去
        int asksSoFar = published.size();
        CompletableFuture<ApprovalVerdict> again = askAsync(approval);
        awaitRequestAfter(asksSoFar);
        reply(approval, "no");
        again.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("界面回一条没提议过的规则，一律不认")
    void onlyOfferedRulesAreAccepted() throws Exception {
        // 不校验的话，一个写错的（或者旧版的）界面就能让后端存下 * 这种规则，
        // 而那等于把整个审批系统悄悄关掉
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        approval.reply(lastRequestId(), "always", List.of("*", "rm -rf *"), "");

        ApprovalVerdict verdict = asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(verdict.approved(), "规则不认不等于这次不批");
        assertTrue(verdict.remember().isEmpty(), "凭空冒出来的规则不该被记下: " + verdict.remember());
    }

    @Test
    @DisplayName("拒绝时用户补的那句话要原样带回去——那是模型改做法的唯一依据")
    void rejectionCarriesFeedback() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        approval.reply(lastRequestId(), "no", List.of(), "别删 build，跑 mvn clean");

        ApprovalVerdict verdict = asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(verdict.approved());
        assertTrue(verdict.note().contains("别删 build，跑 mvn clean"), verdict.note());
    }

    @Test
    @DisplayName("补的话太长要截断——它要进提示词，不能让用户粘一整篇进去")
    void feedbackIsClipped() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        approval.reply(lastRequestId(), "no", List.of(), "长".repeat(RemoteApproval.MAX_FEEDBACK * 2));

        ApprovalVerdict verdict = asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(verdict.note().length() <= RemoteApproval.MAX_FEEDBACK + 16, verdict.note());
    }

    @Test
    @DisplayName("对不上号的回答直接丢弃，不能让它顶掉当前这个问题")
    void staleReplyIsIgnored() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        int id = lastRequestId();
        assertFalse(approval.reply(id + 99, "yes", List.of(), ""), "过期 id 不该被接受");
        assertTrue(approval.isPending(), "问题还该挂着");

        approval.reply(id, "no", List.of(), "");
        assertFalse(asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).approved());
    }

    @Test
    @DisplayName("用户按了中断，等在审批上的那轮要被放行成拒绝")
    void cancelUnblocks() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        approval.cancelPending();
        assertFalse(asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).approved());
    }

    @Test
    @DisplayName("问题里要带齐动作、细节和原因——少一个用户就没法判断")
    void requestCarriesEnoughToDecide() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        ObjectNode request = published.get(0);
        assertEquals("approval_request", request.get("t").asText());
        assertEquals("bash", request.get("kind").asText());
        assertEquals("执行命令", request.get("action").asText());
        assertEquals("rm -rf build", request.get("detail").asText());
        assertEquals("会删除文件", request.get("reason").asText());
        assertTrue(request.get("suggestions").size() > 0, "没有给出可选的规则档位");
        assertTrue(request.get("preferred").asInt() < request.get("suggestions").size());

        reply(approval, "no");
        asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("写文件的请求要把 diff 一起送过去——看不见改了什么就没法判断")
    void writeRequestCarriesDiff() throws Exception {
        RemoteApproval approval = withClients();
        CompletableFuture<ApprovalVerdict> asked = CompletableFuture.supplyAsync(() ->
                approval.decide(ApprovalRequest.write("写入文件", "a.txt", "会覆盖", "- 旧\n+ 新\n")));
        awaitRequest();

        assertEquals("write", lastRequest().get("kind").asText());
        assertTrue(lastRequest().get("preview").asText().contains("+ 新"));

        reply(approval, "no");
        asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("计划里跑的时候要说清是谁在问")
    void requestCarriesOrigin() throws Exception {
        RemoteApproval approval = withClients();
        approval.origin(new ApprovalOrigin("t3", "coder", "编码者"));
        CompletableFuture<ApprovalVerdict> asked = askAsync(approval);
        awaitRequest();

        assertEquals("编码者 · t3", lastRequest().get("origin").asText());

        reply(approval, "no");
        asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
