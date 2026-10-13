package dev.wiles.myagent.team;

import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.plan.AgentRoles;
import dev.wiles.myagent.plan.AgentTaskRunner;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskBrief;
import dev.wiles.myagent.plan.TaskOutcome;
import dev.wiles.myagent.plan.TaskRunner;
import dev.wiles.myagent.snapshot.ShadowRepo;
import dev.wiles.myagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 单写者 + 收尾独立审查：主 agent 一个人从头写到尾，它说做完了，再派一个看不到它上下文的审查者
 * 跑完整测试、对照原话挑问题；有问题就把意见交回<b>同一个</b>主 agent 接着修。
 *
 * <h3>为什么不再是「规划 → 多人施工 → 验收」</h3>
 * cookiecutter 上单 agent 180/202、流水线 143/202。分头写代码的几道工序互相看不到对方
 * 临时定下的接口细节（ADPR 0/3），交接只剩几句话，验收还能指挥「重新规划」把开了头的活推倒重来。
 * Claude Code、Codex 的做法都是写代码这条线留在一个 agent 手里，子 agent 只查和审——这里照做。
 *
 * <h3>规则都由程序执行，不靠提示词</h3>
 * <ul>
 *   <li>触发：主 agent 给出最终答复、且这一轮<b>确实改了文件</b>（影子仓库比对开工前后）</li>
 *   <li>审查材料：用户原话 + 程序算的 diff + 主 agent 的收尾说明（只作参考）</li>
 *   <li>审查方只提意见，不改文件、不能指挥重拟；怎么改由主 agent 决定——只有它有完整上下文</li>
 *   <li>最多审 {@link #MAX_REVIEWS} 轮：审 → 修 → 再审，最后一次修改一定被审过</li>
 *   <li>保底：修完之后审查报的测试通过数比修之前少，工作区退回修之前（{@link RoundGuard}）</li>
 * </ul>
 * 审查自己没跑成（撞步数、模型失败）就到此为止，主 agent 的结果原样交出——
 * 审查是加分项，不能因为它出岔子把本来做完的活判成失败。
 */
public final class ReviewLoop {

    private static final Logger log = LoggerFactory.getLogger(ReviewLoop.class);

    /** 最多审几轮。2 = 审、修、再审：修过的东西一定再被审一次，不留没人看过的改动。 */
    public static final int MAX_REVIEWS = 2;

    /** 审查方的步数。和流水线验收席同一个数：跑全量测试 + 每条要求写探针 + 读 diff，给少了撞上限就白审。 */
    public static final int REVIEW_STEPS = Pipeline.REVIEW_STEPS;

    /** diff 太长就截断：审查方要的是看到改了什么，不是逐字读一个几万行的生成文件。 */
    static final int MAX_DIFF_CHARS = 40_000;

    /** 主 agent 的一轮。抽成接口是为了能用假的测干净整个回路，一次模型调用都不发。 */
    public interface MainTurn {
        /** 跑一轮，返回最终答复。 */
        String run(String input);

        /** 到目前为止主 agent 有没有失败过（撞步数、模型调用失败）。失败了就不审：活没做完，审也是白审。 */
        boolean failed();
    }

    /**
     * 这一场的经过。
     *
     * @param text     交给用户的最终答复（主 agent 最后一轮说的话，退回过就附上说明）
     * @param reviews  实际审了几轮
     * @param verdicts 每轮审查的结论行（通过 / 重新执行 / 没审成）
     * @param scores   每轮审查报的测试成绩，形如 "180/202"；没报是 "-"
     * @param restored 保底退回过工作区
     */
    public record Outcome(String text, int reviews, List<String> verdicts, List<String> scores,
                          boolean restored) {
    }

    private final TaskRunner reviewer;
    private final Path root;
    private final AgentTaskRunner cancellable;

    /**
     * 按过停。不能只靠主 agent 自己的取消标志：{@code Agent.run} 一开头会把它清零，
     * 按停要是落在「审完了、还没把意见交回去」的间隙里，下一轮修改照跑不误。
     */
    private volatile boolean cancelled;

    public ReviewLoop(Supplier<LlmClient> clients, ToolRegistry tools, Path workspaceRoot,
                      BiConsumer<Task, AgentEvent> events) {
        AgentTaskRunner runner = new AgentTaskRunner(clients, tools, workspaceRoot, REVIEW_STEPS);
        if (events != null) {
            runner.onEvent(events);
        }
        this.reviewer = runner;
        this.cancellable = runner;
        this.root = workspaceRoot;
    }

    /** 测试用：换掉审查方。 */
    ReviewLoop(TaskRunner reviewer, Path workspaceRoot) {
        this.reviewer = reviewer;
        this.cancellable = null;
        this.root = workspaceRoot;
    }

    /** 掐断正在跑的审查。 */
    public void cancel() {
        cancelled = true;
        if (cancellable != null) {
            cancellable.cancel();
        }
    }

    public Outcome run(MainTurn main, String userInput) {
        Path baseDir = null;
        ShadowRepo base = null;
        String startTree = null;
        try {
            baseDir = Files.createTempDirectory("myagent-review-base");
            base = ShadowRepo.open(root, baseDir);
            startTree = base == null ? null : base.writeTree();
        } catch (IOException | RuntimeException e) {
            log.warn("拍不了开工前的快照，这一场不做收尾审查: {}", e.getMessage());
        }

        cancelled = false;
        List<String> verdicts = new ArrayList<>();
        List<String> scores = new ArrayList<>();
        boolean restored = false;
        String text = main.run(userInput);

        try (RoundGuard guard = startTree == null ? null : RoundGuard.open(root)) {
            for (int round = 1; round <= MAX_REVIEWS && startTree != null; round++) {
                if (cancelled || main.failed()) {
                    break;              // 按了停；或者主 agent 自己都没收口，审了也是对着半成品挑刺
                }
                String diff = diffOf(base, startTree);
                if (diff.isBlank()) {
                    break;              // 没改文件：问答、只读调研，没有可审的
                }
                Task task = new Task(round == 1 ? "final-review" : "final-review" + round,
                        TeamPrompt.finalReviewBrief(round, diff, text), AgentRoles.ACCEPTOR,
                        List.of(), TeamPrompt.finalReviewAcceptance(REVIEW_STEPS));
                task.start();
                TaskOutcome outcome = runReviewer(task, userInput);
                if (!outcome.ok()) {
                    verdicts.add("没审成");
                    scores.add("-");
                    log.info("第 {} 轮收尾审查没跑成，主 agent 的结果原样交出: {}", round, brief(outcome.output()));
                    break;
                }
                String review = outcome.output();
                int[] score = Pipeline.testScoreOf(review);
                Pipeline.Verdict verdict = Pipeline.verdictOf(review);
                verdicts.add(verdict == Pipeline.Verdict.PASS ? "通过" : "重新执行");
                scores.add(score == null ? "-" : score[0] + "/" + score[1]);

                if (guard != null && guard.regressed(round, score)) {
                    restored = true;
                    text = text + "\n\n（按审查意见修改后测试通过数变少了，工作区已退回修改之前的状态。）";
                    break;
                }
                if (cancelled || verdict == Pipeline.Verdict.PASS || round == MAX_REVIEWS) {
                    break;              // 按了停；通过了；或者轮次用完——最后这次修改已经被审过了
                }
                text = main.run(feedback(review));
            }
        } finally {
            delete(baseDir);
        }
        return new Outcome(text, verdicts.size(), List.copyOf(verdicts), List.copyOf(scores), restored);
    }

    private TaskOutcome runReviewer(Task task, String userInput) {
        if (reviewer instanceof AgentTaskRunner runner) {
            runner.withBrief(new TaskBrief(userInput, List.of()));
        }
        try {
            return reviewer.run(task, List.of());
        } catch (RuntimeException e) {
            return TaskOutcome.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 审查意见交回主 agent 的那句话。以用户口吻：是「我」请人审的，意见是带给它的。 */
    static String feedback(String review) {
        return "[独立审查意见] 我请了一个看不到你上下文的审查者检查你刚才的改动——"
                + "它跑了完整测试、对照了我的原始需求。结论是还有问题：\n\n"
                + review.strip()
                + "\n\n请逐条核实：确认是真问题就修，不认同的说明理由。"
                + "修完跑一遍相关测试，最后简要说明改了什么。";
    }

    private static String diffOf(ShadowRepo base, String startTree) {
        try {
            String diff = base.diff(startTree);
            if (diff.length() > MAX_DIFF_CHARS) {
                return diff.substring(0, MAX_DIFF_CHARS)
                        + "\n…（diff 共 " + diff.length() + " 字符，后面截掉了；需要的话自己用 git diff / 读文件看）";
            }
            return diff;
        } catch (IOException | RuntimeException e) {
            log.warn("算不出 diff，这一轮不审: {}", e.getMessage());
            return "";
        }
    }

    private static String brief(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException e) {
            log.debug("收尾审查的临时影子仓库没删掉: {}", e.getMessage());
        }
    }
}
