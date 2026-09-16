package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 把「要不要放行」这个问题发给远端界面，然后阻塞等回答。
 *
 * <p>{@link ApprovalPolicy} 的注释里写着「问谁在不同场景下不一样」——这就是那个
 * 「问界面」的实现。终端版是 {@code TerminalApproval}，两者互不相干。
 *
 * <p>阻塞是必须的：工具执行在 agent 线程上同步往下走，approve() 一返回 false
 * 这次调用就废了。所以这里只能把 agent 线程停住，等 WebSocket 线程把答案塞进来。
 * 三种情况必须放行 agent 线程，否则整个会话就死在这儿：
 * <ol>
 *   <li>用户答了 —— 按答案走</li>
 *   <li>没有任何客户端连着 —— 没人可问，按拒绝（和 denyAll 的理由一样）</li>
 *   <li>等超时 / 用户按了 Ctrl-C —— 按拒绝</li>
 * </ol>
 */
public final class RemoteApproval implements ApprovalPolicy {

    /** 等用户点按钮的上限。人可能去泡咖啡了，但不能无限等——连接断了也得有个兜底。 */
    private static final long TIMEOUT_SECONDS = 600;

    private final Consumer<ObjectNode> publish;
    private final BooleanSupplier hasClients;

    private final AtomicInteger nextId = new AtomicInteger(1);
    /** 容量 1 就够：同一时刻只可能有一个待答问题（工具是串行执行的）。 */
    private final BlockingQueue<String> answers = new ArrayBlockingQueue<>(1);

    /** 用户选了「本会话都同意」。volatile：置位的是 WebSocket 线程，读的是 agent 线程。 */
    private volatile boolean approveEverything = false;
    /** 当前待答问题的 id，0 表示没有。前端回错 id 时用来识别过期回答。 */
    private volatile int pendingId = 0;

    public RemoteApproval(Consumer<ObjectNode> publish, BooleanSupplier hasClients) {
        this.publish = publish;
        this.hasClients = hasClients;
    }

    @Override
    public boolean approve(String action, String detail, String reason) {
        if (approveEverything) {
            return true;
        }
        if (!hasClients.getAsBoolean()) {
            return false;                       // 没人在看，没人能批
        }

        int id = nextId.getAndIncrement();
        answers.clear();                        // 丢掉上一轮的过期回答
        pendingId = id;

        ObjectNode request = Protocol.event("approval_request");
        request.put("id", id);
        request.put("action", action);
        request.put("detail", detail);
        request.put("reason", reason);
        publish.accept(request);

        try {
            String answer = answers.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (answer == null) {
                return false;                   // 超时，按拒绝
            }
            if ("always".equals(answer)) {
                approveEverything = true;
                return true;
            }
            return "yes".equals(answer);
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
     * @param id       回的是哪个问题。对不上说明是过期回答（比如用户在超时后才点），直接丢弃
     * @param decision yes / no / always
     * @return 是否被接受
     */
    public boolean reply(int id, String decision) {
        if (id != pendingId || pendingId == 0) {
            return false;
        }
        return answers.offer(decision);
    }

    /** 用户中断了本轮。把等在那儿的 approve() 按拒绝放行，否则 agent 线程醒不过来。 */
    public void cancelPending() {
        if (pendingId != 0) {
            answers.offer("no");
        }
    }

    /** 是否正卡在等人批。界面重连时用它决定要不要重发问题。 */
    public boolean isPending() {
        return pendingId != 0;
    }

    /** /clear 之后应当忘掉「本会话都同意」——那是上一场会话的授权。 */
    public void reset() {
        approveEverything = false;
    }
}
