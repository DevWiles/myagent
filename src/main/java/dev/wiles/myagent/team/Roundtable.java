package dev.wiles.myagent.team;

import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.plan.AgentRole;
import dev.wiles.myagent.plan.AgentRoles;
import dev.wiles.myagent.plan.AgentTaskRunner;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskOutcome;
import dev.wiles.myagent.plan.TaskRunner;
import dev.wiles.myagent.plan.TaskStatus;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 圆桌：<b>同一件事，几个角色各看一遍，最后合成一份</b>。
 *
 * <pre>
 *   挑人 → ┌ explorer 看一遍 ┐
 *          ├ reviewer 看一遍 ┤ → analyst 合议 → 一份带分歧的报告
 *          └ verifier 看一遍 ┘
 * </pre>
 *
 * <h3>和 /plan 的区别，一句话</h3>
 * 计划是<b>一件事拆成五步</b>，圆桌是<b>一件事看五遍</b>。
 * 前者的边是依赖（下游要用上游的产出），后者压根没有边——几席之间互不相干，
 * 那正是它的价值：三个人独立看都指到同一处，这个结论比一个人说三遍硬得多。
 *
 * <h3>为什么席位复用 Task 和 TaskRunner</h3>
 * 一席要干的事，和计划里的一个任务是一模一样的：按角色装配子循环、跑、把产出收回来。
 * 复用换来的不只是少写代码——<b>取消、事件转发、按角色收窄工具菜单这三样直接就有了</b>，
 * 而这三样每一样漏掉都是实打实的 bug（掐不断、界面不动、角色形同虚设）。
 *
 * <h3>一席倒了，会照开</h3>
 * 这是它和计划最不一样的地方。计划里一步失败要顺着依赖把下游全跳过，
 * 因为下游<b>真的拿不到</b>它要的东西；圆桌里一席哑火，剩下两席的意见还在，
 * 合议照样有用——只是必须<b>告诉合议者谁没到场</b>（见 {@link TeamPrompt#synthesisBrief}），
 * 否则读的人会把「两个人的共识」当成「三个人的共识」。
 *
 * <h3>只收不写文件的角色</h3>
 * 见 {@link #seatable}。
 *
 * <p>目前<b>串行</b>：一席跑完再跑下一席。几席之间没有任何共享状态，
 * 是天然可并行的——但并行要先解决事件多路复用（协议里工具流水号现在是全局序号）
 * 和 {@link Task} 可变字段的线程安全，那是下一步的事。
 */
public final class Roundtable {

    private static final Logger log = LoggerFactory.getLogger(Roundtable.class);

    /** 一个人不叫圆桌，那是点名。 */
    public static final int MIN_MEMBERS = 2;

    /**
     * 上限四位。
     *
     * <p>不是怕慢，是怕<b>合议读不完</b>：每一席的产出都要整段塞进合议那一次调用的上下文，
     * 五六份之后它只能开始概括，而概括恰恰是圆桌最没价值的产出形态——
     * 用户要的是分歧，不是一份「大家都说得挺好」。
     */
    public static final int MAX_MEMBERS = 4;

    /**
     * 一席能调几轮工具。
     *
     * <p>比角色自己的默认值（8~12）高，因为<b>圆桌一席的活比计划里的一步大得多</b>：
     * 计划里的一步是「读 pom.xml 说清 Java 版本」，圆桌上的一席是「把这个包审一遍」。
     *
     * <p>这个数字是真机跑出来的。头一回开圆桌，两席都把话说完了，却双双撞上角色的
     * 默认上限被判失败，屏幕上是「0/2 位给了意见」——查到的东西一句都没进合议。
     * 预算同时写进席位的提示词（见 {@link TeamPrompt#seatAcceptance}）：
     * 光给够不够，还得让它知道自己有多少，才谈得上取舍。
     */
    public static final int SEAT_STEPS = 16;

    private final TaskRunner runner;
    private final CastPicker picker;

    /** 内建执行体时留一份引用，取消要掐它。外部注入执行体时是 null。 */
    private final AgentTaskRunner cancellable;

    private TeamListener listener = TeamListener.none();

    /** 用户叫停了。volatile：置位的是监听 Ctrl-C 的那个线程。 */
    private volatile boolean cancelled;

    public Roundtable(LlmClient client, ToolRegistry tools, Path workspaceRoot,
                      CastPicker picker) {
        AgentTaskRunner built = new AgentTaskRunner(client, tools, workspaceRoot, SEAT_STEPS)
                // 圆桌上「测试没过」是一条意见，不是这一席失败。执行契约的话，
                // 唯一真跑了测试、真发现问题的那一席反而会被判哑火，意见一句都进不了合议
                .verdictContract(false);
        // 子循环的动静要原样转出去，界面才画得出「审查那一席正在读第三个文件」。
        // 读的是字段不是构造时的那个值——listener() 是构造之后才调的
        built.onEvent((seat, event) -> listener.onAgentEvent(seat, event));
        this.runner = built;
        this.cancellable = built;
        this.picker = picker;
    }

    /** 给测试用：换掉执行体，整套编排（挑人、哑火、合议、取消）就能测干净，一次调用都不发。 */
    Roundtable(TaskRunner runner, CastPicker picker) {
        this.runner = runner;
        this.cancellable = null;
        this.picker = picker;
    }

    public Roundtable listener(TeamListener listener) {
        this.listener = listener == null ? TeamListener.none() : listener;
        return this;
    }

    /**
     * 叫停。三处都要掐：不再开新席位、掐断正在说话的那一席、掐断可能正在进行的挑人请求。
     *
     * <p>漏掉中间那处的话，用户按下停止之后还得眼睁睁看着这一席把话说完——
     * 那和没反应没什么区别。这三处散在调用方手里就一定会漏，所以收在这儿。
     */
    public void cancel() {
        cancelled = true;
        if (cancellable != null) {
            cancellable.cancel();
        }
        if (picker != null) {
            picker.cancel();
        }
    }

    /**
     * 这次能坐上桌的角色。
     *
     * <h3>为什么把会写文件的角色挡在门外</h3>
     * 圆桌的语义是「同一件事看几遍」。让两个 coder 各改一遍同一处代码，
     * 后写的会盖掉先写的，而且<b>它根本不知道自己盖掉了什么</b>——
     * 它读到的是第一个人改之前还是之后的文件，取决于谁先跑。写这件事天然该是一条线，
     * 那是计划的活。
     *
     * <p>判据取自工具白名单而不是 {@code mutates}：{@code verifier} 也算「会动手」
     * （它有 bash），但它在圆桌上给的是<b>事实核查</b>那一视角——
     * 跑一遍测试看真实输出，正是纸上谈兵的几席最缺的。一起挡掉，圆桌就废了一半。
     *
     * <h3>为什么没有工具的角色也坐不上桌</h3>
     * {@code analyst} 一个工具都没有。在计划里它靠<b>上游产出</b>干活，
     * 而圆桌上<b>几席之间互不通气</b>（那是圆桌的前提）——于是它既看不到代码，
     * 也拿不到别人的意见，这一席只能说「我什么都没看到」。
     *
     * <p>这是真机跑出来的：头一回把 analyst 点进圆桌，它老老实实回了一句
     * 「我没有读到过任何代码，这一席拿不出结论」——它没错，是这个座位不该存在。
     * 它真正的位置是<b>合议</b>那一步，那里材料是齐的。
     *
     * @param readOnly {@code --readonly} 下再砍一刀：会动工作区的角色一个都不留
     */
    public static List<AgentRole> seatable(boolean readOnly) {
        return AgentRoles.runnable(readOnly).stream()
                .filter(role -> !role.tools().isEmpty())
                .filter(role -> role.tools().stream()
                        .noneMatch(BuiltinTools.WRITING_TOOLS::contains))
                .toList();
    }

    /**
     * 开一场圆桌。
     *
     * @param requested 用户指名的角色；空就让模型挑
     */
    public TeamResult hold(String goal, List<AgentRole> requested, boolean readOnly) {
        cancelled = false;          // 上一场的取消标志不能带到这一场

        List<AgentRole> available = seatable(readOnly);
        Cast cast = (requested == null || requested.isEmpty())
                ? picker.pick(goal, available)
                : Cast.of(seat(requested, available), "你指名的");
        if (cast.isEmpty()) {
            return finish(new TeamResult(goal, cast, List.of(), null, TeamStatus.FAILED));
        }
        listener.onCastChosen(goal, cast);

        List<Task> seats = seatsOf(goal, cast);
        for (Task seat : seats) {
            if (cancelled) {
                seat.skip("已取消");
                listener.onSeatFinished(seat);
                continue;
            }
            seat.start();
            listener.onSeatStarted(seat);
            // 上游给空——**几席之间互不通气**，这是圆桌的前提：
            // 谁要是看过别人的意见，他那一席就不再是独立的一遍了
            settle(seat, List.of());
            listener.onSeatFinished(seat);
        }

        if (cancelled) {
            // 取消之后不再合议：那是又一次完整的模型调用，而用户刚说了不要。
            // 已经说过话的那几席原样留着——花掉的钱至少还能看
            return finish(new TeamResult(goal, cast, seats, null, TeamStatus.CANCELLED));
        }

        List<Task> spoke = seats.stream()
                .filter(seat -> seat.status() == TaskStatus.SUCCEEDED).toList();
        if (spoke.isEmpty()) {
            log.info("圆桌上一席都没说成话，不合议了");
            return finish(new TeamResult(goal, cast, seats, null, TeamStatus.FAILED));
        }

        // 名单只在这儿算一次，合议那份材料用的是同一份。
        // 两处各判一次「谁说了话」的话（一处看状态、一处看产出空不空），
        // 迟早会出现屏幕上写着「2/2 位给了意见」而合议里说「只有一席到场」
        List<Task> absent = seats.stream().filter(seat -> !spoke.contains(seat)).toList();
        Task verdict = new Task("verdict", TeamPrompt.synthesisBrief(goal, spoke, absent),
                AgentRoles.ANALYST, List.of(), TeamPrompt.synthesisAcceptance());
        verdict.start();
        listener.onSynthesisStarted(verdict);
        settle(verdict, List.of());

        TeamStatus status = verdict.status() != TaskStatus.SUCCEEDED ? TeamStatus.FAILED
                : spoke.size() == seats.size() ? TeamStatus.COMPLETED
                : TeamStatus.PARTIAL;
        return finish(new TeamResult(goal, cast, seats, verdict, status));
    }

    /**
     * 把坐不上桌的角色挡在门外。
     *
     * <p><b>这道闸必须在这儿，不能只在命令解析那一层。</b>{@code hold} 是公开入口，
     * 现在有终端和无头服务两个调用方，以后还会有别的；每个调用方各自记得过滤，
     * 就等于把「会写文件的角色不许进圆桌」这条语义交给自觉。
     * 漏一个的后果是具体的：两个 coder 各改一遍同一处，后写的盖掉先写的，
     * 而它根本不知道自己盖掉了什么。
     *
     * <p>这一条是圆桌自己审出来的——第一次开会，审查那一席就指着
     * {@code hold} 说这道闸靠调用方自觉。
     */
    private static List<AgentRole> seat(List<AgentRole> requested, List<AgentRole> available) {
        List<AgentRole> seated = requested.stream().filter(available::contains).toList();
        requested.stream().filter(role -> !available.contains(role)).forEach(role ->
                log.info("角色 {} 这次坐不上圆桌，跳过", role.id()));
        return seated;
    }

    private TeamResult finish(TeamResult result) {
        listener.onFinished(result);
        return result;
    }

    /** 一席一个 Task。id 用 s1、s2…，抬头短，纪律在验收标准里（见 {@link TeamPrompt}）。 */
    private List<Task> seatsOf(String goal, Cast cast) {
        List<Task> seats = new ArrayList<>(cast.size());
        for (int i = 0; i < cast.size(); i++) {
            AgentRole role = cast.members().get(i);
            seats.add(new Task("s" + (i + 1), TeamPrompt.seatTitle(role, goal), role,
                    List.of(), TeamPrompt.seatAcceptance(role, cast.members(), SEAT_STEPS)));
        }
        return seats;
    }

    /** 跑一个 Task，把它推到终态。<b>不抛异常</b>：一席炸了不该把整场会带走。 */
    private void settle(Task task, List<Task> upstream) {
        TaskOutcome outcome;
        try {
            outcome = runner.run(task, upstream);
        } catch (RuntimeException e) {
            // 执行体是外部实现，可能没兜住异常。和 PlanExecutor 对执行体的态度一样：
            // 翻译成这一席失败，会照开
            log.error("席位 {} 的执行体抛了异常", task.id(), e);
            outcome = TaskOutcome.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            if (outcome.ok()) {
                task.succeed(outcome.output());
            } else {
                task.fail(outcome.output());
            }
        } catch (IllegalStateException e) {
            // 状态迁移非法：执行体在返回之前自己动了这个 Task。这属于执行体的 bug，
            // 但发生在 try 外面的话，整场会就被它带走了——而上面那句注释刚说过不会
            log.error("席位 {} 的状态迁移非法，执行体自己动过它", task.id(), e);
        }
    }
}
