package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 上下文压缩：把一份<b>只追加、永不改写</b>的会话记录，压成能发出去的窗口。
 *
 * <h3>记录和窗口是两样东西</h3>
 * 这是这个类最重要的一条约定。记录（{@link SessionSnapshot#messages}）只增不改，
 * 压缩产出的是<b>派生物</b>——一串 {@link SummarySegment} 加一段 reduce 出来的摘要，
 * 由 {@link ContextWindow} 现场排版成窗口。原文永远在，随时可以换个预算重新组装。
 *
 * <h3>两阶段：map 只算新的，reduce 每次重来</h3>
 * <pre>
 *   记录 [0 ....................... coversUpTo) [最近几轮原样 .....]
 *        └── 切块 ──→ map ──→ 段（缓存，一辈子只算一次）
 *                              └── reduce ──→ 一段连贯摘要（一次性，用完即弃）
 * </pre>
 * <b>map 的输入永远是原文，reduce 的输出绝不回流</b>。旧实现把摘要写回历史，
 * 而摘要是 {@code role=user}、正好是切点，于是下次压缩把它当原文再压一遍——
 * 摘要的摘要的摘要，几轮之后退化成没有信息量的套话，日志里还完全看不出来。
 * 记录只追加之后，这条路在结构上就走不通了。
 *
 * <h3>三条线，不是一条</h3>
 * 见 {@link Stage}。80% 后台把摘要先算好，93.5% 有现货才落地，98.7% 没现货也得压。
 * 压缩因此基本不出现在用户能感知的路径上。
 *
 * <h3>切的时候不能把 tool 消息切成孤儿</h3>
 * 每条 {@code role=tool} 都必须紧跟在那条带 {@code tool_calls} 的 assistant 后面，
 * 随手截断就会留下无主的 tool 消息，之后<b>每一次</b>请求都会 400。所以窗口的尾巴
 * 只从 {@code role=user} 开始。<b>但 map 的分块不受这条约束</b>——
 * map 的输入是拼成一段纯文本的工作记录，不是消息数组，那里怎么切都不会 400。
 */
public final class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    /**
     * 上下文窗口的默认值。
     *
     * <h3>为什么是 1,000,000</h3>
     * DeepSeek 现在（2026-09 实测）只提供 {@code deepseek-flash} 和 {@code deepseek-v4-pro}，
     * 两个都是 1M 窗口。老的 {@code deepseek-chat} 已于 2026-07-24 退役，
     * 现在只是被路由过去——用它发请求，响应里的 {@code model} 字段回的是 {@code deepseek-flash}。
     *
     * <p>这个数曾经是 64,000，那是 V3 时代的事，之后没人回来改。
     * 差了 16 倍的后果不是「浪费」而是<b>主动毁内容</b>：按 64,000 算，
     * 聊到 51,200 token 就开始摘要，而模型还能再吃 95%。
     * 压缩的本意是避免撞窗口，那样就成了在离窗口还有 95% 的地方提前丢东西。
     *
     * <p>换别的模型请改 {@code MYAGENT_CONTEXT_TOKENS} 或 config.json 的
     * {@code contextTokens}——API 不返回上下文长度，代码没法自己发现这件事。
     *
     * <h3>1M 之下压缩基本是休眠的</h3>
     * 正常会话到不了 800,000。这不代表白做：它仍是安全网，{@code /compact} 在成本上
     * 也有意义（每轮重发几十万 token 是真金白银）。但要<b>验证</b>压缩，
     * 必须把窗口调小——{@code MYAGENT_CONTEXT_TOKENS=12000} 几轮就能把三条线都逼出来。
     */
    public static final int DEFAULT_MAX_TOKENS = 1_000_000;

    /**
     * 配得再小也不能低于这个数，否则压缩会压了等于没压。
     *
     * <p>这个下限是算出来的，绑的是 <b>map 的块预算</b>（{@code maxTokens × 0.25}）：
     * 4,000 下每块 1,000 token，差不多正好装下一轮「提问 + 读一个文件 + 回答」。
     * 再低下去，块预算连一轮都装不下，{@code splitOversizedTurn} 会把每一轮都从中间切开，
     * 摘出来的东西前言不搭后语。
     *
     * <p>另外两处也在这个量级上顶住：工具菜单实测约 700 token
     * （5 个内置工具；带上 remember/recall 约 1,200），而阶梯的触发线是
     * {@code 4,000 × 0.987 − 1,200 ≈ 2,748}，留给历史的空间还够用；
     * 记忆索引则按 {@code MemoryIndex.budgetFor} 跟着窗口缩，不会反过来把窗口撑爆。
     */
    public static final int MIN_MAX_TOKENS = 4_000;

    /** 后台开始预压缩的线。到这儿只算不换。 */
    public static final double PRECOMPUTE_RATIO = 0.80;
    /** 落地线。<b>有现货才落</b>，没有就继续跑——同步压缩留到最后一刻。 */
    public static final double APPLY_RATIO = 0.935;
    /** 硬线。没现货也得压，此时才允许卡顿。 */
    public static final double HARD_RATIO = 0.987;

    /**
     * 拿不到实测 token 数时的保守档。
     *
     * <p>{@link TokenEstimator} 自述偏差 10~20%。用估算值去卡 93.5%，估低 15%
     * 就是实际 107%，请求直接失败。所以没有 usage 时整体往前挪，把误差让出来。
     * 有 usage 时（见 {@code StreamOptions}）才用上面那三条。
     */
    public static final double ESTIMATED_PRECOMPUTE_RATIO = 0.65;
    public static final double ESTIMATED_APPLY_RATIO = 0.80;
    public static final double ESTIMATED_HARD_RATIO = 0.88;

    /**
     * 降级阶梯的触发线：窗口超过窗口上限的这个比例才开始裁工具输出。
     *
     * <h3>为什么不是「压缩后希望降到的 40%」</h3>
     * 曾经就是 40%，那是个不小的错误。40% 的本意是<b>摘要落地后希望降到的水位</b>，
     * 拿它当每个请求的裁剪线，后果是「装得下的东西也被毁掉」——
     * 实测现象：4,000 的窗口里读一个约 2,600 token 的文件，本来绰绰有余，
     * 却因为超过 1,600 当场被裁成 120 个字符的开头，模型接着说
     * 「我没有读到完整内容」。用户要它读文件，它读了，然后系统把内容扔了。
     *
     * <p>裁剪是<b>最后一道闸</b>，只在「再不裁就发不出去」时才该动手。
     * 该在什么水位上压缩是三条线的事，不是这里的事。
     */
    private static final double FIT_RATIO = HARD_RATIO;
    /** 每个 map 块的预算，按窗口大小取比例——窗口调到 4,000 时块也得跟着小。 */
    private static final double CHUNK_RATIO = 0.25;

    /** 一次压缩最多顺手捡几条记忆。捡太多会把噪音一起带进长期记忆。 */
    private static final int MAX_HARVEST = 3;
    /** 模型用它把「记住」那段和摘要隔开。找不到就说明它没捡到东西，全文都是摘要。 */
    private static final String HARVEST_MARKER = "===值得记住===";

    private final int maxTokens;

    /** 顺手捡到的长期记忆往哪送。默认什么都不做——不接记忆时行为和以前一字不差。 */
    private Consumer<Harvested> harvest = h -> {
    };

    /**
     * 真的接了下游吗。
     *
     * <p>不判这个的话，{@code --readonly}（刻意不接 sink）下会打出
     * 「压缩时顺手记下了 1 条长期记忆」——因为空实现的 accept 也算成功。
     * 记忆一条都没落盘，日志却说记下了，谁照着日志查都得绕一圈。
     */
    private boolean harvestWired;

    public ContextManager() {
        this(DEFAULT_MAX_TOKENS);
    }

    public ContextManager(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /** 上下文窗口上限，界面要用它算占用比例。 */
    public int maxTokens() {
        return maxTokens;
    }

    // ------------------------------------------------------------------
    // 三条线
    // ------------------------------------------------------------------

    /** 当前占用该触发什么动作。 */
    public enum Stage {
        /** 什么都不用做。跌回这一档时要把没落地的待用件整个丢掉。 */
        STEADY,
        /** 后台把摘要先算好，但不换上下文。 */
        PRECOMPUTE,
        /** 有现货就落地；没有就接着跑，别为了压缩把用户卡在这儿。 */
        APPLY,
        /** 没现货也得压，此时允许卡顿——再不压请求就要失败了。 */
        FORCE
    }

    /**
     * @param tokens   当前窗口占用
     * @param measured 这个数是实测的（响应里的 {@code prompt_tokens}）还是估出来的
     */
    public Stage stageFor(int tokens, boolean measured) {
        double ratio = (double) tokens / maxTokens;
        if (ratio >= (measured ? HARD_RATIO : ESTIMATED_HARD_RATIO)) {
            return Stage.FORCE;
        }
        if (ratio >= (measured ? APPLY_RATIO : ESTIMATED_APPLY_RATIO)) {
            return Stage.APPLY;
        }
        if (ratio >= (measured ? PRECOMPUTE_RATIO : ESTIMATED_PRECOMPUTE_RATIO)) {
            return Stage.PRECOMPUTE;
        }
        return Stage.STEADY;
    }

    public int estimate(List<ChatMessage> messages, List<ToolSpec> tools) {
        return TokenEstimator.estimate(messages) + TokenEstimator.estimateTools(tools);
    }

    // ------------------------------------------------------------------
    // 待用件
    // ------------------------------------------------------------------

    /**
     * 一次压缩算出来的东西。「待用件」指的就是它——算好了还没换进窗口的那份。
     *
     * @param segments   全部段（已有的 + 这次新 map 出来的）
     * @param summary    reduce 的产物，可能为 null（没有任何段时）
     * @param coversUpTo 摘要覆盖到记录的哪个下标（开区间）。<b>必须是一条 user 消息的位置</b>
     */
    public record Compaction(List<SummarySegment> segments, String summary, int coversUpTo) {
        public Compaction {
            segments = List.copyOf(segments == null ? List.of() : segments);
        }

        public boolean isEmpty() {
            return summary == null || summary.isBlank();
        }
    }

    /**
     * 压缩时顺手捡到的一条长期记忆。
     *
     * <p>字段和 {@code MemoryEntry} 对得上，但<b>这里不引用那个类</b>：
     * 上下文压缩不该知道记忆是怎么存的，它只负责把候选递出去，
     * 由 {@code Repl} / {@code AgentServer} 决定收不收、怎么收。
     */
    public record Harvested(String name, String description, String content) {
    }

    /**
     * 接上「压缩时顺手记一笔」。
     *
     * <h3>为什么挂在 map 上而不是 reduce 上</h3>
     * 两个理由。一是<b>单段时根本不跑 reduce</b>（跑了就成了摘要的摘要），
     * 挂在 reduce 上那条路就永远捡不到。二是 map 读的是<b>原文</b>，
     * reduce 只看得到摘要——要抽「下次打开这个项目还用得上的事实」，
     * 看原文严格优于看摘要。封顶仍是整次压缩 {@value #MAX_HARVEST} 条，由计数器跨块共享。
     *
     * <h3>为什么不改摘要的格式契约</h3>
     * 摘要在长对话的关键路径上，压不出摘要整个会话就废了。
     * 所以「记住」那段是<b>追加</b>在摘要后面、用一行标记隔开的：
     * 模型没给这段、或者给歪了，{@link #splitSummary} 就把全文当摘要——
     * 和加这个功能之前的行为一模一样。<b>捡记忆失败绝不能伤到压缩本身。</b>
     */
    public ContextManager onHarvest(Consumer<Harvested> harvest) {
        this.harvest = harvest == null ? h -> { } : harvest;
        this.harvestWired = harvest != null;
        return this;
    }

    // ------------------------------------------------------------------
    // map / reduce
    // ------------------------------------------------------------------

    /**
     * 算一份待用件。<b>这是唯一会发模型调用的方法</b>，可能很慢——
     * 正常路径上它跑在后台线程里，只有到了 {@link Stage#FORCE} 才同步调用。
     *
     * @param record 会话记录，不含 system
     * @param known  已经算过的段，直接复用，不会重算
     * @param client 摘要用的模型客户端；<b>后台调用时必须是独立实例</b>，
     *               {@code DeepSeekClient} 有可变状态，和主循环共用会互相踩 {@code currentCall}
     * @return 待用件；记录还不够长（凑不出可压的区间）时返回 null
     */
    public Compaction compute(List<ChatMessage> record, List<SummarySegment> known,
                              LlmClient client) {
        if (record == null || record.isEmpty()) {
            return null;
        }
        int upTo = ContextWindow.startOfLastTurns(record, ContextWindow.KEEP_RECENT_TURNS);
        if (upTo <= 0) {
            return null;    // 还不够三轮，没有可压的区间
        }

        List<SummarySegment> segments = map(record, upTo, known, client);
        if (segments.isEmpty()) {
            return null;
        }
        return new Compaction(segments, reduce(segments, client), upTo);
    }

    /**
     * map：把 {@code [已覆盖, upTo)} 这段原文切块，一块一块摘要。
     *
     * <p>只算<b>新增</b>的块。段的下标是记录的下标，而记录只追加，
     * 所以算过的段永远有效——稳态下每次压缩通常只新增一块。
     */
    List<SummarySegment> map(List<ChatMessage> record, int upTo, List<SummarySegment> known,
                             LlmClient client) {
        List<SummarySegment> segments = new ArrayList<>();
        if (known != null) {
            known.stream().filter(s -> s.fitsIn(record.size())).forEach(segments::add);
        }
        int covered = SummarySegment.coveredUpTo(segments);
        if (covered >= upTo) {
            return segments;        // 全都算过了，一次调用都不用发
        }

        int[] budget = {MAX_HARVEST};
        int mapped = 0;
        for (int[] chunk : chunks(record, covered, upTo)) {
            segments.add(summarizeChunk(record, chunk[0], chunk[1], client, budget));
            mapped++;
        }
        log.info("map: 新增 {} 块，覆盖记录 [{}, {})", mapped, covered, upTo);
        return segments;
    }

    /**
     * 切块。优先对齐轮边界（一条 {@code role=user} 起一轮），
     * 单轮就超预算时才按消息边界内部切——map 的输入是纯文本，那样切不会有孤儿问题。
     *
     * @return 每项是 {@code [from, to)}
     */
    List<int[]> chunks(List<ChatMessage> record, int from, int upTo) {
        int budget = Math.max(1, (int) (maxTokens * CHUNK_RATIO));
        List<int[]> result = new ArrayList<>();
        int start = from;
        int cursor = from;
        int acc = 0;

        while (cursor < upTo) {
            int turnEnd = nextTurnStart(record, cursor + 1, upTo);
            int turnCost = TokenEstimator.estimate(record.subList(cursor, turnEnd));

            if (turnCost > budget) {
                // 这一轮自己就超预算。先把攒着的收掉，再把它按消息切开
                if (cursor > start) {
                    result.add(new int[]{start, cursor});
                }
                splitOversizedTurn(record, cursor, turnEnd, budget, result);
                start = turnEnd;
                cursor = turnEnd;
                acc = 0;
                continue;
            }
            if (acc + turnCost > budget && cursor > start) {
                result.add(new int[]{start, cursor});
                start = cursor;
                acc = 0;
            }
            acc += turnCost;
            cursor = turnEnd;
        }
        if (cursor > start) {
            result.add(new int[]{start, cursor});
        }
        return result;
    }

    /** 下一条 user 消息的位置；没有就是区间末尾。 */
    private static int nextTurnStart(List<ChatMessage> record, int from, int upTo) {
        for (int i = from; i < upTo; i++) {
            if (ChatMessage.ROLE_USER.equals(record.get(i).role())) {
                return i;
            }
        }
        return upTo;
    }

    /** 一轮里塞了个超大文件时用：按消息边界硬切，宁可不连贯也得让每块装得下。 */
    private static void splitOversizedTurn(List<ChatMessage> record, int from, int to,
                                           int budget, List<int[]> into) {
        int start = from;
        int acc = 0;
        for (int i = from; i < to; i++) {
            int cost = TokenEstimator.estimate(record.get(i));
            if (acc > 0 && acc + cost > budget) {
                into.add(new int[]{start, i});
                start = i;
                acc = 0;
            }
            acc += cost;
        }
        if (start < to) {
            into.add(new int[]{start, to});
        }
    }

    /**
     * reduce：把若干段合成一段连贯摘要。
     *
     * <p><b>只有一段时直接返回那一段</b>，不发调用。再摘一次就是摘要的摘要，
     * 白花一次钱换来信息损耗，正是这次重构要消灭的东西。
     */
    String reduce(List<SummarySegment> segments, LlmClient client) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        if (segments.size() == 1) {
            return segments.get(0).text();
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            joined.append("【第 ").append(i + 1).append(" 段】\n")
                    .append(segments.get(i).text()).append("\n\n");
        }
        if (client == null) {
            return joined.toString().strip();   // 没客户端就原样拼起来，总比没有强
        }
        try {
            var response = client.chat(List.of(
                    ChatMessage.system("你负责把分段摘要合并成一份连贯摘要。不要寒暄。"),
                    ChatMessage.user("""
                            下面是同一段工作记录被分块摘要后的结果，按时间先后排列。
                            把它们合并成一份连贯的摘要，要求：

                            1. 保留每一段里的文件路径、配置值、报错原因，原样保留不要改写
                            2. 后面的段推翻了前面的结论时，以后面的为准，并写明这一点
                            3. 去掉重复的内容，但不要为了简短丢掉具体事实
                            4. 不要输出「第一段说……第二段说……」这种转述结构

                            %s
                            """.formatted(joined))), null);

            String text = response.text();
            return text == null || text.isBlank() ? joined.toString().strip() : text.strip();

        } catch (LlmException e) {
            // 合并失败不是灾难：原样拼起来的版本信息量一点没少，只是读起来不连贯
            log.warn("reduce 失败，退回原样拼接: {}", e.getMessage());
            return joined.toString().strip();
        }
    }

    /**
     * 摘一块。失败只降级<b>这一块</b>，其余块照常——
     * 这比旧实现的全有全无（一次调用挂了整段历史变成一句「已省略」）严格更好。
     */
    private SummarySegment summarizeChunk(List<ChatMessage> record, int from, int to,
                                          LlmClient client, int[] harvestBudget) {
        List<ChatMessage> slice = record.subList(from, to);
        if (client == null) {
            return new SummarySegment(from, to, placeholder(slice.size()));
        }
        StringBuilder transcript = new StringBuilder();
        for (ChatMessage m : slice) {
            transcript.append('[').append(m.role()).append("] ");
            if (m.hasToolCalls()) {
                m.toolCallsOrEmpty().forEach(c ->
                        transcript.append("调用 ").append(c.name()).append(' ')
                                .append(c.argumentsJson()).append(' '));
            }
            if (m.content() != null) {
                transcript.append(m.content());
            }
            transcript.append('\n');
        }

        try {
            var response = client.chat(List.of(
                    ChatMessage.system("你负责压缩对话历史。输出要点式摘要，不要寒暄。"),
                    ChatMessage.user("""
                            把下面这段编码助手的工作记录压缩成简洁摘要，必须保留：
                            1. 用户在这段里提出的目标和要求
                            2. 已经改过哪些文件，改了什么（文件路径必须原样保留）
                            3. 已经查明的关键事实（配置值、报错原因、命令结果等）
                            4. 还没做完的事

                            不要保留寒暄和推理过程，只留结论。

                            摘要写完之后，如果这段记录里有**下次打开这个项目还用得上**的事实
                            （用户的纠正和偏好、构建命令、架构约定、某个坑的成因），
                            另起一行写 %s，然后每行一条，用 | 分成三段：

                            短名字(小写连字符) | 一句话摘要 | 详细说明

                            只挑真正跨会话有用的，最多 %d 条。一次性的中间结果、
                            读一遍文件就知道的事、这轮做完就没意义的进度，都不要写。
                            没有值得记的就把这段整个省掉——宁可不记，不要凑数。

                            ----- 工作记录 -----
                            %s
                            """.formatted(HARVEST_MARKER, Math.max(harvestBudget[0], 0),
                            transcript))), null);

            String[] parts = splitSummary(response.text());
            if (parts[0].isBlank()) {
                return new SummarySegment(from, to, placeholder(slice.size()));
            }
            emitHarvest(parts[1], harvestBudget);
            return new SummarySegment(from, to, parts[0]);

        } catch (LlmException e) {
            log.warn("第 [{}, {}) 块摘要失败，只降级这一块: {}", from, to, e.getMessage());
            return new SummarySegment(from, to, placeholder(slice.size()));
        }
    }

    private static String placeholder(int count) {
        return "（这一段共 " + count + " 条对话，摘要没能生成，原文已不在上下文里。"
                + "需要相关信息请重新查看文件或询问用户）";
    }

    /**
     * 把模型的回答切成「摘要」和「值得记住的」两段。
     *
     * <p><b>找不到标记就是全文都是摘要</b>——这是这个功能的安全底座：
     * 模型没给这段、给歪了、或者标记被它翻译成了别的写法，
     * 结果都只是「这次没捡到记忆」，摘要照常可用。
     *
     * @return 长度恒为 2 的数组：[摘要, 记忆段落]
     */
    private static String[] splitSummary(String text) {
        if (text == null) {
            return new String[]{"", ""};
        }
        int marker = text.indexOf(HARVEST_MARKER);
        if (marker < 0) {
            return new String[]{text.strip(), ""};
        }
        return new String[]{
                text.substring(0, marker).strip(),
                text.substring(marker + HARVEST_MARKER.length()).strip()};
    }

    /**
     * 解析「值得记住的」那几行，逐条递出去。
     *
     * <p>整段包在 try 里：捡记忆是<b>顺带</b>做的事，解析炸了、下游存盘炸了，
     * 都不该影响这次压缩的结果——那可是会话能不能继续下去的关键路径。
     *
     * @param budget 单元素数组当计数器用，跨块共享，保证整次压缩不超 {@value #MAX_HARVEST} 条
     */
    private void emitHarvest(String block, int[] budget) {
        if (!harvestWired || block == null || block.isBlank() || budget[0] <= 0) {
            return;     // 没接下游就别解析，更别打「记下了 N 条」
        }
        int taken = 0;
        for (String line : block.lines().toList()) {
            if (budget[0] <= 0) {
                break;
            }
            String[] fields = line.split("\\|");
            if (fields.length < 3) {
                continue;   // 模型写歪的行直接跳过，不去猜它想说什么
            }
            String name = fields[0].strip().replaceAll("^[-*\\s]+", "");
            String description = fields[1].strip();
            String content = fields[2].strip();
            if (name.isBlank() || description.isBlank() || content.isBlank()) {
                continue;
            }
            // try 包在**单条**上，不是包在整个循环上：
            // 一条名字不合规就让剩下几条全丢掉，未免太亏
            try {
                harvest.accept(new Harvested(name, description, content));
                budget[0]--;
                taken++;
            } catch (RuntimeException e) {
                log.warn("这条记忆没收下（{}）: {}", name, e.toString());
            }
        }
        if (taken > 0) {
            log.info("压缩时顺手记下了 {} 条长期记忆", taken);
        }
    }

    // ------------------------------------------------------------------
    // 窗口组装与降级阶梯
    // ------------------------------------------------------------------

    /**
     * 一次组装的结果。
     *
     * @param tokensRaw <b>一级</b>（什么都不裁）的窗口有多大。
     *                  判三条线要用这个数，<b>不能用裁完之后的</b>：
     *                  阶梯每一轮都会把窗口压回软目标附近，拿裁后的数去判线，
     *                  占用率就永远到不了 80%，于是摘要一次都不会自动发生，
     *                  而工具输出被反反复复裁掉——一个稳定但完全错误的平衡态。
     *                  裁后的数描述「这一份发出去多大」，裁前的数才描述「压力有多大」
     * @param level 走到了降级阶梯的第几级。0 = 原样发出，没裁任何东西；
     *              大于 0 说明工具输出被裁过——<b>这件事必须让用户知道</b>，
     *              否则他看到的现象是「它怎么突然忘了刚读过的文件」，而界面一声不吭
     */
    public record Result(List<ChatMessage> window, int tokensBefore, int tokensAfter,
                         int tokensRaw, boolean summarized, int level) {
        public int saved() {
            return tokensBefore - tokensAfter;
        }

        /** 这一轮有没有真的削掉过内容（摘要顶替 或 工具输出被裁）。 */
        public boolean reduced() {
            return summarized || level > 0;
        }
    }

    /**
     * 组装窗口，必要时走降级阶梯。
     *
     * <h3>阶梯是最后一道闸，不是常规路径</h3>
     * 只有窗口大到 {@link #FIT_RATIO}（再不裁就发不出去）时才会动手。
     * 平时哪怕占了七八成也原样发——该压缩的时候自然有三条线管，
     * 轮不到在这儿把用户刚读回来的文件毁掉。
     *
     * <ol>
     *   <li>摘要块 + 最近几轮原样</li>
     *   <li>裁较早那些轮的工具输出（<b>最后一轮始终原样</b>）</li>
     *   <li>连最后一轮的工具输出也裁</li>
     * </ol>
     * 三级走完还装不下就<b>照发</b>。「最近三轮原样」是硬约束，不为预算让路。
     * 真正发不出去的情况（超过 {@link #maxTokens} 本身）由 {@link #exceedsHardLimit} 判，
     * 调用方据此报错，而不是偷偷把轮数降下去。
     *
     * @param coversUpTo 摘要覆盖到哪（开区间）。尾巴从这里开始，<b>不是</b>从「最近三轮」开始——
     *                   两次压缩之间对话会长，那段增量既不在摘要里也不能丢
     */
    public Result assemble(ChatMessage system, PinnedFacts pinned, String summary,
                           List<ChatMessage> record, int coversUpTo, List<ToolSpec> tools) {
        List<ChatMessage> safeRecord = record == null ? List.of() : record;
        int start = Math.max(0, Math.min(coversUpTo, safeRecord.size()));
        List<ChatMessage> tail = safeRecord.subList(start, safeRecord.size());

        int before = estimate(safeRecord, tools);
        int budget = (int) (maxTokens * FIT_RATIO) - TokenEstimator.estimateTools(tools);
        ChatMessage pinnedMessage = pinned == null ? null : pinned.render();
        ChatMessage summaryMessage = ContextWindow.summaryMessage(summary);

        // 一级：原样。这一份的大小同时也是「压力」的度量，后面几级都要带着它
        List<ChatMessage> window =
                ContextWindow.assemble(system, pinnedMessage, summaryMessage, tail);
        int raw = estimate(window, tools);
        if (raw <= budget) {
            return finish(window, tools, before, raw, summaryMessage != null, 0,
                    "摘要 + 最近几轮原样");
        }

        // 二级：裁较早那些轮，最后一轮不动
        int lastTurn = ContextWindow.startOfLastTurns(tail, 1);
        if (lastTurn > 0) {
            window = ContextWindow.assemble(system, pinnedMessage, summaryMessage,
                    ContextWindow.trimToolOutputs(tail, lastTurn));
            if (estimate(window, tools) <= budget) {
                return finish(window, tools, before, raw, summaryMessage != null, 1,
                        "裁较早轮的工具输出");
            }
        }

        // 三级：全裁
        window = ContextWindow.assemble(system, pinnedMessage, summaryMessage,
                ContextWindow.trimToolOutputs(tail, tail.size()));
        return finish(window, tools, before, raw, summaryMessage != null, 2, "裁全部工具输出");
    }

    /**
     * 窗口超过上限了吗——超了就是发出去必然失败，得把话对用户说清楚。
     *
     * <p>能走到这一步说明最近几轮的<b>原文</b>本身就装不下（粘了一大段文本，
     * 或者单轮里读了个超大文件）。此时降轮数也救不了：巨无霸很可能就在最后一轮里。
     */
    public boolean exceedsHardLimit(List<ChatMessage> window, List<ToolSpec> tools) {
        return estimate(window, tools) > maxTokens;
    }

    private Result finish(List<ChatMessage> window, List<ToolSpec> tools, int before,
                          int raw, boolean summarized, int level, String how) {
        int after = estimate(window, tools);
        // debug 而不是 info：组装是纯函数，一轮里会被调好几次（状态栏一次、
        // 判线一次、落地后再一次），按 info 打就是同样的三四行刷满日志，
        // 把真正的事件（map 了几块、落地了没有）淹掉
        log.debug("窗口组装({}): 记录 {} / 不裁 {} -> 实发 {} tokens", how, before, raw, after);
        return new Result(window, before, after, raw, summarized, level);
    }
}
