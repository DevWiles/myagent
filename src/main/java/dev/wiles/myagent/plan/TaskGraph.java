package dev.wiles.myagent.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一堆任务连成的有向无环图，外加「谁能跑了」「谁被连累了」这两个调度要用的判断。
 *
 * <p>这个类只管<b>结构</b>：id 不重、依赖存在、没有环。至于「一次该拆几个任务」
 * 那是规划质量的问题，归 {@link PlanLimits} 和规划那一层管——
 * 放这儿会很别扭，因为 {@link #addSubTasks} 让图会长大，总数本来就会超过一次规划的上限。
 *
 * <p><b>为什么校验必须在开跑之前做完</b>：这三种烂图的症状差别很大，
 * 但都指向同一个结论——早发现比晚发现便宜太多。
 * <ul>
 *   <li>重复 id：后面的悄悄覆盖前面的，一个任务凭空消失，还不报错</li>
 *   <li>悬空依赖：下游永远等一个不存在的上游，静默挂死</li>
 *   <li>成环：大家都在等别人，同样是静默挂死，而且从日志上看就是「不动了」</li>
 * </ul>
 *
 * <p>线程安全：<b>没有</b>。同层并发是后面的事，到时候要么把图的读写收进调度线程，
 * 要么给状态加锁。现在先不为还没发生的需求买单。
 */
public final class TaskGraph {

    /** LinkedHashMap：保住插入顺序，就绪集合和分层结果才是稳定的、可断言的。 */
    private final Map<String, Task> tasks;

    private TaskGraph(Map<String, Task> tasks) {
        this.tasks = tasks;
    }

    /**
     * 校验并建图。校验不过直接抛，宁可这一轮规划作废，也不让烂图跑起来。
     */
    public static TaskGraph of(Collection<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("计划里一个任务都没有");
        }
        Map<String, Task> map = new LinkedHashMap<>();
        for (Task task : tasks) {
            if (map.put(task.id(), task) != null) {
                throw new IllegalArgumentException("任务 id 重复: " + task.id());
            }
        }
        validate(map);
        // 先校验后连边：校验没过就抛，谁的 dependents 都没被碰过
        for (Task task : map.values()) {
            linkDependents(map, task);
        }
        return new TaskGraph(map);
    }

    /**
     * 往图里追加一批子任务——{@link TaskType#PLANNING} 任务跑完后长出来的那些。
     *
     * <p><b>要么全进，要么一个都不进。</b>先在一张合并出来的候选图上校验，
     * 过了才真正落盘连边。否则一批任务里第三个成了环，前两个已经把反向边连上了，
     * 图就成了半截状态——这种脏数据比直接失败难查得多。
     */
    public void addSubTasks(Collection<Task> subTasks) {
        if (subTasks == null || subTasks.isEmpty()) {
            throw new IllegalArgumentException("要追加的子任务是空的");
        }
        Map<String, Task> candidate = new LinkedHashMap<>(tasks);
        for (Task task : subTasks) {
            if (candidate.put(task.id(), task) != null) {
                throw new IllegalArgumentException("追加的任务 id 和已有任务撞车: " + task.id());
            }
        }
        validate(candidate);

        // 校验过了才动真格。只需要连新任务的边——老任务之间的边早就连好了
        tasks.putAll(candidate);
        for (Task task : subTasks) {
            linkDependents(tasks, task);
        }
    }

    public int size() {
        return tasks.size();
    }

    public Collection<Task> tasks() {
        return tasks.values();
    }

    public Task get(String id) {
        Task task = tasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("没有 id 为 " + id + " 的任务");
        }
        return task;
    }

    /**
     * 当前能开跑的任务：自己还是 PENDING，且<b>每一个</b>依赖都已 SUCCEEDED。
     *
     * <p>注意「上游失败」和「上游还没跑」在这里长得一样——两种情况下依赖都不是 SUCCEEDED，
     * 所以都不就绪。区别在于前者永远不会就绪，得靠 {@link #propagateFailure} 把它了结掉，
     * 否则调度循环会一直转下去等一个等不到的东西。
     */
    public List<Task> ready() {
        List<Task> result = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (task.status() != TaskStatus.PENDING) {
                continue;
            }
            boolean allDone = true;
            for (String dep : task.dependencies()) {
                if (get(dep).status() != TaskStatus.SUCCEEDED) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                result.add(task);
            }
        }
        return result;
    }

    /** 全部进了终态就没得跑了，调度循环该收工。 */
    public boolean isDone() {
        return tasks.values().stream().allMatch(Task::isTerminal);
    }

    /**
     * 某个任务塌了，把它下游还没跑的全部标成 SKIPPED，一路传到底。
     *
     * @return 这次被跳过的任务，顺序是广度优先，便于界面按层级显示
     */
    public List<Task> propagateFailure(String failedId) {
        Task failed = get(failedId);
        List<Task> skipped = new ArrayList<>();
        Deque<Task> queue = new ArrayDeque<>();
        queue.add(failed);

        while (!queue.isEmpty()) {
            Task upstream = queue.poll();
            String reason = "上游 " + upstream.id()
                    + (upstream.status() == TaskStatus.SKIPPED ? " 被跳过" : " 失败");
            for (String id : upstream.dependents()) {
                Task downstream = get(id);
                // 已经了结的不碰：它可能早就被别的分支跳过了，覆盖理由只会让报告更糊涂
                if (downstream.isTerminal()) {
                    continue;
                }
                downstream.skip(reason);
                skipped.add(downstream);
                queue.add(downstream);
            }
        }
        return skipped;
    }

    /**
     * 拓扑分层：第 0 层没有依赖，第 n 层最长的一条依赖链上游有 n 个任务。
     *
     * <p>同一层内部互不依赖，所以将来并发执行就是一层一层地放出去。
     * 现在先用来给用户看计划——按层展示，一眼能看出哪些事情本可以并行。
     *
     * <p>算法是<b>带记忆的 DFS 求最长路</b>：一个任务的层号 = 所有依赖的层号最大值 + 1。
     * 取最大值而不是最小值，是因为它得等<b>最慢</b>的那个上游；
     * 取最小值的话，菱形图里 n4 会跟 n2 排到同一层，而那时 n3 还没跑完。
     *
     * <p>不用担心无限递归——建图时已经确保无环了。
     */
    public List<List<Task>> layers() {
        Map<String, Integer> depth = new HashMap<>();
        int deepest = 0;
        for (Task task : tasks.values()) {
            deepest = Math.max(deepest, depthOf(task.id(), depth));
        }

        List<List<Task>> layers = new ArrayList<>();
        for (int i = 0; i <= deepest; i++) {
            layers.add(new ArrayList<>());
        }
        // 按插入顺序遍历，同一层内部的顺序才是稳定的
        for (Task task : tasks.values()) {
            layers.get(depth.get(task.id())).add(task);
        }
        return layers;
    }

    private int depthOf(String id, Map<String, Integer> memo) {
        Integer cached = memo.get(id);
        if (cached != null) {
            return cached;
        }
        int depth = 0;
        for (String dep : get(id).dependencies()) {
            depth = Math.max(depth, depthOf(dep, memo) + 1);
        }
        memo.put(id, depth);
        return depth;
    }

    // ── 校验 ────────────────────────────────────────────────────

    /** 只读校验，不碰任何任务的状态——这是 {@link #addSubTasks} 能做到原子性的前提。 */
    private static void validate(Map<String, Task> map) {
        for (Task task : map.values()) {
            for (String dep : task.dependencies()) {
                if (!map.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "任务 " + task.id() + " 依赖了不存在的任务 " + dep);
                }
            }
        }
        detectCycle(map);
    }

    /** DFS 染色用的三种颜色。没进 map 的就是「白」——还没碰过。 */
    private enum Color {
        /** 正在这条 DFS 路径上（灰）。再次撞见它，说明绕回来了，就是环。 */
        VISITING,
        /** 它和它的所有下游都查过了，干净（黑）。不必再进去。 */
        DONE
    }

    /**
     * DFS 三色染色查环：沿着依赖一路往下走，撞见还在当前路径上的节点就是绕回来了。
     *
     * <p>相比 Kahn 入度法，好处是<b>能把环的具体路径报出来</b>——
     * 当前的递归路径栈就是环本身，切出来就是 {@code n1 → n3 → n2 → n1}。
     * Kahn 只能告诉你「这几个任务卡住了」，而卡住的里面既有环上的、也有环下游被牵连的，
     * 分不清谁是罪魁。规划出问题时，这句报错要原样回灌给模型让它改，说得越准越好。
     *
     * <p>走正向边而不是 dependents：校验发生在连反向边之前，那会儿 dependents 还是空的。
     *
     * <p>递归深度等于依赖链长度。任务数有 {@link PlanLimits#MAX_TASKS} 封顶，
     * 最长的链也就十几层，爆不了栈。
     */
    private static void detectCycle(Map<String, Task> map) {
        Map<String, Color> colors = new HashMap<>();
        List<String> path = new ArrayList<>();
        for (String id : map.keySet()) {
            visit(id, map, colors, path);
        }
    }

    private static void visit(String id, Map<String, Task> map,
                              Map<String, Color> colors, List<String> path) {
        Color color = colors.get(id);
        if (color == Color.DONE) {
            return;
        }
        if (color == Color.VISITING) {
            throw new IllegalArgumentException("任务之间存在循环依赖（→ 表示「依赖」）: "
                    + describeCycle(path, id));
        }

        colors.put(id, Color.VISITING);
        path.add(id);
        for (String dep : map.get(id).dependencies()) {
            visit(dep, map, colors, path);
        }
        path.remove(path.size() - 1);
        colors.put(id, Color.DONE);
    }

    /** 把递归路径里从环的入口开始那一段切出来，首尾相接地写出来。 */
    private static String describeCycle(List<String> path, String repeated) {
        List<String> cycle = new ArrayList<>(path.subList(path.indexOf(repeated), path.size()));
        cycle.add(repeated);
        return String.join(" → ", cycle);
    }

    private static void linkDependents(Map<String, Task> map, Task task) {
        for (String dep : task.dependencies()) {
            map.get(dep).addDependent(task.id());
        }
    }
}
