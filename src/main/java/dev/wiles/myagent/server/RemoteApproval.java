package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.wiles.myagent.tool.safety.ApprovalOrigin;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import dev.wiles.myagent.tool.safety.RuleSuggester;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * 「问界面」的实现。它只负责<b>问和听</b>：规则匹配在 {@code PolicyGate}（它的上游），
 * 留底在 {@code AuditLog.audited}（夹在中间），这里一概不管。
 *
 * <p>阻塞是必须的：工具执行在 agent 线程上同步往下走，decide() 一返回拒绝
 * 这次调用就废了。所以这里只能把 agent 线程停住，等 WebSocket 线程把答案塞进来。
 * 三种情况必须放行 agent 线程，否则整个会话就死在这儿：
 * <ol>
 *   <li>用户答了 —— 按答案走</li>
 *   <li>没有任何客户端连着 —— 没人可问，按拒绝（和 denyAll 的理由一样）</li>
 *   <li>等超时 / 用户按了 Ctrl-C —— 按拒绝</li>
 * </ol>
 *
 * <h3>没有「本会话都同意」了</h3>
 * 以前有一个 {@code approveEverything} 开关：按一次，这个会话里所有危险动作全部静默放行。
 * 它治的是「被问烦了」，代价是把整道防线关掉——为了批一条 {@code mvn test}
 * 顺手授权了 {@code curl} 和 {@code rm}。
 *
 * <p>现在「以后都同意」回来的是<b>一条规则</b>，交给上游的 {@code PolicyGate} 存下来。
 * 治的还是同一个病，但不再靠少看东西来治。
 */
public final class RemoteApproval implements ApprovalPolicy {

    /** 等用户点按钮的上限。人可能去泡咖啡了，但不能无限等——连接断了也得有个兜底。 */
    private static final long TIMEOUT_SECONDS = 600;

    /** 用户补的那句话最长留多少。再长就不是「补一句」了，而且它要进提示词。 */
    static final int MAX_FEEDBACK = 500;

    private final Consumer<ObjectNode> publish;
    private final BooleanSupplier hasClients;

    private final AtomicInteger nextId = new AtomicInteger(1);
    /** 容量 1 就够：同一时刻只可能有一个待答问题（工具是串行执行的）。 */
    private final BlockingQueue<Answer> answers = new ArrayBlockingQueue<>(1);

    /** 当前待答问题的 id，0 表示没有。前端回错 id 时用来识别过期回答。 */
    private volatile int pendingId = 0;
    /**
     * 这一问提议过哪些规则。
     *
     * <p>界面回来的规则<b>必须是其中之一</b>。不校验的话，一个写错的（或者旧版的）界面
     * 就能让后端存下一条从没提议过的规则——比如 {@code *}。
     * 提议是后端算的，那么能被存下的集合也该由后端说了算。
     */
    private volatile Set<String> offered = Set.of();

    /** 当前跑着的是谁。计划和流水线里由服务端在任务起止时更新。 */
    private volatile ApprovalOrigin origin = null;

    public RemoteApproval(Consumer<ObjectNode> publish, BooleanSupplier hasClients) {
        this.publish = publish;
        this.hasClients = hasClients;
    }

    /** 接下来的审批都算在这个任务头上。传 null 表示回到「就是用户刚说的那句话」。 */
    public void origin(ApprovalOrigin who) {
        this.origin = who;
    }

    // synchronized：审批一次只能问一个。pendingId / offered / 容量 1 的 answers
    // 全都按「同一时刻只有一问」设计；调度层的「写独占」规矩已经保证了几乎不会撞，
    // 这把锁是兜底——万一真撞上（比如将来又出现几个会动手的执行体并行跑的场合），
    // 后到的排队等前一问答完，而不是把前一问的现场覆盖掉。
    @Override
    public synchronized ApprovalVerdict decide(ApprovalRequest incoming) {
        if (!hasClients.getAsBoolean()) {
            return ApprovalVerdict.no("当前没有界面连着，没人能确认这个动作。");
        }
        ApprovalOrigin who = origin;
        ApprovalRequest request = who == null ? incoming : incoming.from(who);

        RuleSuggester.Suggestions suggestions = RuleSuggester.forRequest(request);
        int id = nextId.getAndIncrement();
        answers.clear();                        // 丢掉上一轮的过期回答
        offered = flatten(suggestions);
        pendingId = id;
        publish.accept(encode(id, request, suggestions));

        try {
            Answer answer = answers.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (answer == null) {
                return ApprovalVerdict.no("等待确认超时（" + TIMEOUT_SECONDS + " 秒），按拒绝处理。");
            }
            if (!answer.approved()) {
                // 用户那句话原样带走。它是模型下一步唯一的依据，
                // 在这儿概括一下就会变成一句正确的废话
                return answer.feedback().isBlank()
                        ? ApprovalVerdict.no()
                        : ApprovalVerdict.no("用户补充: " + answer.feedback());
            }
            return ApprovalVerdict.yes(accepted(answer.rules()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApprovalVerdict.no("这一轮被中断了。");
        } finally {
            pendingId = 0;
            offered = Set.of();
        }
    }

    private ObjectNode encode(int id, ApprovalRequest request,
                              RuleSuggester.Suggestions suggestions) {
        ObjectNode node = Protocol.event("approval_request");
        node.put("id", id);
        node.put("kind", request.kind().id());
        node.put("action", request.action());
        // 字段还叫 detail 而不是 subject：协议里它一直叫这个，
        // 没更新的界面不该因为一次内部改名而显示空白
        node.put("detail", request.subject());
        node.put("reason", request.reason());
        node.put("preview", request.preview());
        node.put("origin", request.origin() == null ? "" : request.origin().label());
        ArrayNode levels = node.putArray("suggestions");
        for (RuleSuggester.Level level : suggestions.levels()) {
            ObjectNode one = levels.addObject();
            one.put("label", level.label());
            ArrayNode patterns = one.putArray("patterns");
            level.patterns().forEach(patterns::add);
        }
        node.put("preferred", suggestions.preferred());
        return node;
    }

    private static Set<String> flatten(RuleSuggester.Suggestions suggestions) {
        Set<String> all = new LinkedHashSet<>();
        suggestions.levels().forEach(level -> all.addAll(level.patterns()));
        return all;
    }

    /** 只留提议过的那些。界面回了别的，当它没回——宁可下次再问一遍。 */
    private List<String> accepted(List<String> rules) {
        Set<String> allowed = offered;
        List<String> kept = new ArrayList<>();
        for (String rule : rules) {
            if (allowed.contains(rule)) {
                kept.add(rule);
            }
        }
        return kept;
    }

    /**
     * 界面回了答案。由 WebSocket 线程调用。
     *
     * @param id       回的是哪个问题。对不上说明是过期回答（比如用户在超时后才点），直接丢弃
     * @param decision {@code yes} / {@code no} / {@code always}
     * @param rules    {@code always} 时用户挑定的规则模式
     * @param feedback {@code no} 时用户补的那句话
     * @return 是否被接受
     */
    public boolean reply(int id, String decision, List<String> rules, String feedback) {
        if (id != pendingId || pendingId == 0) {
            return false;
        }
        boolean approved = "yes".equals(decision) || "always".equals(decision);
        List<String> remember = "always".equals(decision) && rules != null ? rules : List.of();
        String note = feedback == null ? "" : feedback.strip();
        if (note.length() > MAX_FEEDBACK) {
            note = note.substring(0, MAX_FEEDBACK);
        }
        return answers.offer(new Answer(approved, remember, note));
    }

    /** 用户中断了本轮。把等在那儿的 decide() 按拒绝放行，否则 agent 线程醒不过来。 */
    public void cancelPending() {
        if (pendingId != 0) {
            answers.offer(new Answer(false, List.of(), ""));
        }
    }

    /** 是否正卡在等人批。界面重连时用它决定要不要重发问题。 */
    public boolean isPending() {
        return pendingId != 0;
    }

    private record Answer(boolean approved, List<String> rules, String feedback) {
    }
}
