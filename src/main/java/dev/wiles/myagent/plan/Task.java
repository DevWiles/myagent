package dev.wiles.myagent.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * DAG 里的一个任务：既是计划的一个节点（不可变的那部分），也是它的执行状态（会变的那部分）。
 *
 * <p>字段分成泾渭分明的两拨，这个划分比字段本身更重要：
 * <pre>
 *   final   id / description / type / dependencies   —— 规划产出，一旦定下就不许改
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
    private final TaskType type;
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

    /** 造一个根任务（规划阶段直接产出的那种）。 */
    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type, dependencies, null, 0);
    }

    /**
     * 私有：子任务只能通过 {@link #subTask} 从父任务身上长出来。
     *
     * <p>这样写是为了让「不许有孙子」这条约束<b>在模型层无法绕过</b>——
     * 解析模型输出的代码就算想造一个 depth=2 的任务，也没有入口。
     * 把约束做成「写不出来」，比做成「写出来会被校验拦下」更硬。
     */
    private Task(String id, String description, TaskType type, List<String> dependencies,
                 String parentId, int depth) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("任务 id 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("任务 " + id + " 缺少描述");
        }
        if (type == null) {
            throw new IllegalArgumentException("任务 " + id + " 缺少类型");
        }
        if (depth < 0 || depth > PlanLimits.MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "任务 " + id + " 的深度 " + depth + " 越界，只允许 0.." + PlanLimits.MAX_DEPTH);
        }
        this.id = id;
        this.description = description;
        this.type = type;
        this.parentId = parentId;
        this.depth = depth;
        // 防御性拷贝：调用方后面改它的 list，不该动到已经成型的计划
        this.dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        if (this.dependencies.contains(id)) {
            // 自环是最短的环，在这儿顺手挡掉，建图时的环检测就少一种边界情况
            throw new IllegalArgumentException("任务 " + id + " 依赖了自己");
        }
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public TaskType type() {
        return type;
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
        if (!canSpawnSubtasks()) {
            throw new IllegalStateException("任务 " + this.id + " 已经在第 " + depth
                    + " 层，不能再拆子任务（最深 " + PlanLimits.MAX_DEPTH + " 层）");
        }
        return new Task(id, description, type, dependencies, this.id, depth + 1);
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
        return "Task[" + id + " " + type + " " + status + " " + description + "]";
    }
}
