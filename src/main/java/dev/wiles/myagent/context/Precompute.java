package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * 后台预压缩：在真正需要之前，先把摘要算好。
 *
 * <h3>为什么值得多这么一层</h3>
 * 压缩要发模型调用，几秒起步。摆在请求的关键路径上，用户看到的就是
 * 「我说了一句话，它愣了五秒才开始干活」，而且越到会话后期越频繁。
 *
 * <p>把它挪到 80% 那条线上就没这个问题了：那时离真正装不下还有很远，
 * 后台慢慢算，算好了挂着。等到 93.5% 要落地时，现货直接换进窗口，
 * <b>一次调用都不用发</b>。用户全程无感。
 *
 * <h3>为什么必须用独立的客户端实例</h3>
 * {@code DeepSeekClient} 带可变状态：{@code temperature}、{@code jsonMode}，
 * 尤其是 {@code volatile Call currentCall}——{@code cancel()} 靠它掐断请求。
 * 后台和主循环共用一个实例的话，两边会互相覆盖 {@code currentCall}，
 * 表现为「按了 Esc 但停不下来」，或者「后台任务把用户这一轮的请求掐了」。
 * 所以这里要一个工厂，每次现造。
 *
 * <h3>待用件的生命周期</h3>
 * <pre>
 *   80%  ──start()──►  后台算   ──►  挂起（待用件就绪）
 *                                      │
 *   93.5% ──ready()? 是──► take() ──► 落地，清空
 *                                      │
 *   跌回 80% 以下 ──discard()──────────┘  整个丢掉
 * </pre>
 * 「跌回 80% 以下」意味着用户自己 {@code /compact} 过，或者开了新会话——
 * 那份待用件是照着旧的记录边界算的，留着只会张冠李戴。
 *
 * <p><b>已经落地过的段不在丢弃范围内</b>。那些存在 {@link SessionSnapshot} 里，
 * 是 committed 状态，永远有效——记录只追加，段的下标永远指向同一批消息。
 */
public final class Precompute implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Precompute.class);

    private final ContextManager context;
    private final Supplier<LlmClient> clientFactory;
    private final ExecutorService worker;

    private Future<ContextManager.Compaction> pending;

    /**
     * @param clientFactory 每次现造一个<b>独立</b>的客户端；传 null 表示不开后台，
     *                      所有压缩都退化成同步——测试和只读场景用得上
     */
    public Precompute(ContextManager context, Supplier<LlmClient> clientFactory) {
        this.context = context;
        this.clientFactory = clientFactory;
        this.worker = clientFactory == null ? null : Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "precompute");
            // 守护线程：用户按 Ctrl-C 退出时，不该被一个算到一半的摘要拖着不让走
            t.setDaemon(true);
            return t;
        });
    }

    /** 关掉后台的那一版，永远同步压缩。 */
    public static Precompute disabled(ContextManager context) {
        return new Precompute(context, null);
    }

    public boolean enabled() {
        return worker != null;
    }

    /**
     * 起一个后台任务。已经有任务在跑、或者已经有现货挂着时什么都不做——
     * 重复起只会白烧 token，而记录只追加，晚一点算出来的结果也不会更好。
     */
    public synchronized void start(List<ChatMessage> record, List<SummarySegment> known) {
        if (worker == null || pending != null) {
            return;
        }
        List<ChatMessage> snapshot = List.copyOf(record);   // 交给别的线程前先定格
        List<SummarySegment> knownCopy = List.copyOf(known == null ? List.of() : known);
        pending = worker.submit(() -> {
            LlmClient client = clientFactory.get();
            log.debug("后台预压缩开始，记录 {} 条", snapshot.size());
            return context.compute(snapshot, knownCopy, client);
        });
    }

    /** 现货到位了吗。算挂了、算出来是空的，都不算到位。 */
    public synchronized boolean ready() {
        if (pending == null || !pending.isDone() || pending.isCancelled()) {
            return false;
        }
        try {
            ContextManager.Compaction result = pending.get();
            return result != null && !result.isEmpty();
        } catch (Exception e) {
            log.warn("后台预压缩失败，到线时会退回同步压缩: {}", e.toString());
            pending = null;     // 清掉，下次 start 可以重来
            return false;
        }
    }

    /**
     * 取走现货并清空。取之前请先 {@link #ready()}。
     *
     * @return 待用件；没有现货时返回 null
     */
    public synchronized ContextManager.Compaction take() {
        if (pending == null || !pending.isDone() || pending.isCancelled()) {
            return null;
        }
        try {
            return pending.get();
        } catch (Exception e) {
            return null;
        } finally {
            pending = null;
        }
    }

    /**
     * 把待用件整个丢掉，正在跑的也取消。
     *
     * <p>丢的只是<b>没落地</b>的这一份。落地过的段在存档里，不归这里管。
     */
    public synchronized void discard() {
        if (pending == null) {
            return;
        }
        pending.cancel(true);
        pending = null;
        log.debug("占用跌回预压缩线以下，待用件已丢弃");
    }

    /** 还有任务在跑或者有现货挂着吗——给测试和状态栏用。 */
    public synchronized boolean hasPending() {
        return pending != null;
    }

    /** 后台那一趟跑完了吗（成没成另说）。没起过任务也算跑完。 */
    public synchronized boolean settled() {
        return pending == null || pending.isDone();
    }

    @Override
    public void close() {
        discard();
        if (worker != null) {
            worker.shutdownNow();
        }
    }
}
