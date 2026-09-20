package dev.wiles.myagent.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * DAG 里的一个任务：既是计划的一个节点（不可变的那部分），也是它的执行状态（会变的那部分）。
 *
 * <p>字段分成泾渭分明的两拨，这个划分比字段本身更重要：
 * <pre>
 *   final   id / description / role / dependencies   —— 规划产出，一旦定下就不许改
 *   可变     status / result / error / 两个时间戳      —— 执行过程中一步步填上
 *   半可变   dependents                               —— 反向边，由建图的人一次性补齐
 * </pre>
 *
 * <p><b>为什么 dependents 不在构造时传</b>：造 n1 的时候，依赖它的 n2 还没造出来，
 * 鸡生蛋。所以正向边（我依赖谁）来自规划输出，反向边（谁依赖我）由建图阶段
 * 遍历一遍正向边自动补，见 {@link #addDependent}。这条不变式必须守住——
 * <b>反向边永远是推导出来的，不许让规划的模型自己填</b>，否则两个方向对不上，
 * 调度就会漏跑或者死等。
 *
 * <p><b>为什么状态迁移要拦</b>：非法迁移（跑完了又 start、没跑就宣布成功）全都是
 * 调度器的 bug。让它当场抛，总比留着一个状态错乱的 DAG 让人半夜对着日志猜要好。
 *
 * <p>线程安全：<b>目前没有</b>。同层并发执行是后面的事，到时候要么给可变字段加
 * volatile，要么把状态收到调度器里单线程改。现在先不为还没发生的需求买单。
 */
public class Task {

    private final String id;
    private final String description;

    /**
     * 派谁来干这一步。
     *
     * <p><b>类型不再是独立字段，而是从角色推导</b>（{@link #type()}）。
     * 两个字段并存的话，总有一天会出现「角色是 coder、类型是 FILE_READ」的任务——
     * 可能来自模型，也可能来自某次只改了一处的重构。到那时候派工具该听谁的？
     * 按类型派就没有写工具，按角色派就违背了它自己声明的类型，两条路都得猜。
     * 一个字段，这种矛盾在类型系统层面就不存在。
     */
    private final AgentRole role;

    /**
     * 做到什么程度算这一步做完了。规划阶段由模型给出，执行阶段原样交给执行这一步的子模型。
     *
     * <p>为什么值得单独一个字段，而不是塞进 {@code description} 里：
     * <b>description 说的是「做什么」，它说的是「做到什么算完」</b>，两件事。
     * 少了它，子循环只能靠自己感觉「差不多了」就收口——ReAct 循环最常见的翻车方式，
     * 一是没做完就宣布成功，二是做完了还在原地打转直到撞上步数上限。
     *
     * <p>老的构造入口不带它，这时是 {@code null}——测试和历史代码照常能造任务。
     */
    private final String acceptance;
    private final List<String> dependencies;
    private final List<String> dependents = new ArrayList<>();

    /** 谁把我拆出来的；根任务没有爹，是 null。 */
    private final String parentId;

    /** 0 = 规划直接产出，1 = PLANNING 任务再拆出来的。上限见 {@link PlanLimits#MAX_DEPTH}。 */
    private final int depth;

    private TaskStatus status = TaskStatus.PENDING;
    private String result;
    private String error;
    private long startTime;
    private long endTime;

    /**
     * 造一个根任务，只说类型不说角色。
     *
     * <p>落到这个类型的默认角色上（见 {@link AgentRoles#defaultFor}）。
     * 留着这条入口不是为了兼容老代码——是因为<b>「只知道要读文件，不在乎派谁去读」
     * 是一种正当的表达</b>：调度测试、老存档、模型偷懒只填了 type，
     * 三者都属于这一类，没必要逼它们各自去查一遍默认角色。
     */
    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, AgentRoles.defaultFor(type), dependencies, null, null, 0);
    }

    /** 带验收标准、只说类型。 */
    public Task(String id, String description, TaskType type, List<String> dependencies,
                String acceptance) {
        this(id, description, AgentRoles.defaultFor(type), dependencies, acceptance, null, 0);
    }

    /** 造一个根任务，指名角色。规划器走的是这条路。 */
    public Task(String id, String description, AgentRole role, List<String> dependencies,
                String acceptance) {
        this(id, description, role, dependencies, acceptance, null, 0);
    }

    /**
     * 私有：子任务只能通过 {@link #subTask} 从父任务身上长出来。
     *
     * <p>这样写是为了让「不许有孙子」这条约束<b>在模型层无法绕过</b>——
     * 解析模型输出的代码就算想造一个 depth=2 的任务，也没有入口。
     * 把约束做成「写不出来」，比做成「写出来会被校验拦下」更硬。
     */
    private Task(String id, String description, AgentRole role, List<String> dependencies,
                 String acceptance, String parentId, int depth) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("任务 id 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("任务 " + id + " 缺少描述");
        }
        if (role == null) {
            throw new IllegalArgumentException("任务 " + id + " 缺少角色");
        }
        if (depth < 0 || depth > PlanLimits.MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "任务 " + id + " 的深度 " + depth + " 越界，只允许 0.." + PlanLimits.MAX_DEPTH);
        }
        this.id = id;
        this.description = description;
        this.role = role;
        // 空白和 null 一视同仁：模型很爱塞一个 "" 进来，让下游各自判空是自找麻烦
        this.acceptance = acceptance == null || acceptance.isBlank() ? null : acceptance.strip();
        this.parentId = parentId;
        this.depth = depth;
        // 防御性拷贝：调用方后面改它的 list，不该动到已经成型的计划
        this.dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        if (this.dependencies.contains(id)) {
            // 自环是最短的环，在这儿顺手挡掉，建图时的环检测就少一种边界情况
            throw new IllegalArgumentException("任务 " + id + " 依赖了自己");
        }
    }

    /**
     * 从存档里把一个任务原样立起来，<b>包括它当时的状态和产出</b>。
     *
     * <p>包私有，只给存档层（{@link PlanCheckpoint}）用。为什么非要开这个后门：
     * 对外的状态迁移全都带前置校验（跑完了不许再 start、没跑不许宣布成功），
     * 那是为了让调度器的 bug 当场暴露；而反序列化要做的恰恰是<b>直接落到某个状态</b>，
     * 走正常入口的话得把当初那串迁移重放一遍，既别扭又会把时间戳弄成假的。
     *
     * <p>开成包私有而不是 public，是因为这个能力一旦漏到外面，
     * 就等于把状态机的全部约束作废了——谁都能随手把一个任务改成 SUCCEEDED。
     */
    static Task restore(String id, String description, AgentRole role, List<String> dependencies,
                        String acceptance, String parentId, int depth,
                        TaskStatus status, String result, String error,
                        long startTime, long endTime) {
        Task task = new Task(id, description, role, dependencies, acceptance, parentId, depth);
        task.status = status == null ? TaskStatus.PENDING : status;
        task.result = result;
        task.error = error;
        task.startTime = startTime;
        task.endTime = endTime;
        return task;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    /** 派谁干这一步。 */
    public AgentRole role() {
        return role;
    }

    /**
     * 这一步属于哪一类活。<b>推导自角色，不是独立字段</b>——
     * 调度（能不能并发）和界面（图标、颜色）继续认它，协议里也照发。
     */
    public TaskType type() {
        return role.taskType();
    }

    /** 做到什么算完。没给就是 null，调用方自己兜底。 */
    public String acceptance() {
        return acceptance;
    }

    public TaskStatus status() {
        return status;
    }

    /** 谁把我拆出来的。根任务返回 null。 */
    public String parentId() {
        return parentId;
    }

    public int depth() {
        return depth;
    }

    /** 还能不能再往下拆一层。到了 {@link PlanLimits#MAX_DEPTH} 就不行了。 */
    public boolean canSpawnSubtasks() {
        return depth < PlanLimits.MAX_DEPTH;
    }

    /**
     * 拆出一个子任务。通常由 {@link TaskType#PLANNING} 任务执行完之后调用。
     *
     * <p>越界时抛 {@link IllegalStateException} 而不是返回 null——
     * 模型想再套一层是完全可能发生的，得让调用方当场知道，然后把这句话回灌给模型让它改。
     */
    public Task subTask(String id, String description, TaskType type, List<String> dependencies) {
        return subTask(id, description, AgentRoles.defaultFor(type), dependencies, null);
    }

    /** 带验收标准的子任务，只说类型。 */
    public Task subTask(String id, String description, TaskType type, List<String> dependencies,
                        String acceptance) {
        return subTask(id, description, AgentRoles.defaultFor(type), dependencies, acceptance);
    }

    /** 带验收标准的子任务，指名角色。 */
    public Task subTask(String id, String description, AgentRole role, List<String> dependencies,
                        String acceptance) {
        if (!canSpawnSubtasks()) {
            throw new IllegalStateException("任务 " + this.id + " 已经在第 " + depth
                    + " 层，不能再拆子任务（最深 " + PlanLimits.MAX_DEPTH + " 层）");
        }
        return new Task(id, description, role, dependencies, acceptance, this.id, depth + 1);
    }

    public String result() {
        return result;
    }

    public String error() {
        return error;
    }

    /** 我依赖谁。不可变。 */
    public List<String> dependencies() {
        return dependencies;
    }

    /** 谁依赖我。由建图阶段补齐，对外只读。 */
    public List<String> dependents() {
        return List.copyOf(dependents);
    }

    /** 登记一条反向边。重复登记是幂等的——建图代码不必自己去重。 */
    public void addDependent(String taskId) {
        if (taskId != null && !taskId.isBlank() && !dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    public long startTime() {
        return startTime;
    }

    public long endTime() {
        return endTime;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** 耗时，单位毫秒；还没了结就返回 -1，而不是一个会骗人的 0。 */
    public long durationMillis() {
        return endTime == 0 ? -1 : endTime - startTime;
    }

    public void start() {
        require(TaskStatus.PENDING, "开始执行");
        status = TaskStatus.RUNNING;
        startTime = System.currentTimeMillis();
    }

    public void succeed(String result) {
        require(TaskStatus.RUNNING, "标记成功");
        status = TaskStatus.SUCCEEDED;
        this.result = result;
        endTime = System.currentTimeMillis();
    }

    public void fail(String error) {
        require(TaskStatus.RUNNING, "标记失败");
        status = TaskStatus.FAILED;
        this.error = error;
        endTime = System.currentTimeMillis();
    }

    /**
     * 跳过：上游塌了，这个任务没机会跑。
     *
     * <p>注意它<b>不</b>设 startTime——没跑过就是没跑过，时间戳得对得起事实，
     * 界面上才能如实显示「跳过」而不是「耗时 0 毫秒」。
     */
    public void skip(String reason) {
        require(TaskStatus.PENDING, "跳过");
        status = TaskStatus.SKIPPED;
        this.error = reason;
        endTime = System.currentTimeMillis();
    }

    private void require(TaskStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "任务 " + id + " 当前是 " + status + "，不能" + action + "（需要 " + expected + "）");
        }
    }

    @Override
    public String toString() {
        return "Task[" + id + " " + role.id() + " " + status + " " + description + "]";
    }
}
