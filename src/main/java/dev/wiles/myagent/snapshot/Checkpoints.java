package dev.wiles.myagent.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 检查点：绑在<b>会话 + 那一刻的对话长度</b>上的工作区快照。
 * 照的是 Claude Code 的形状——{@code /rewind} 可以只回代码、只回对话、或者都回。
 *
 * <h3>两种粒度</h3>
 * <ul>
 *   <li><b>轮级</b>（{@link #mark}）：每一轮开跑前拍。回对话要用到「那一轮开始时记录有几条」，
 *       这个数只有开跑那一刻准；一轮没写任何文件也照样是个检查点，用户可能就想把那句话收回去。
 *       {@code /undo} 回的是最近一个轮级点——撤销「上一轮」，不是「上一步」。</li>
 *   <li><b>工具级</b>（{@link #markTool}）：每次<b>会动手</b>的工具调用（写文件、bash、MCP……）
 *       执行前拍。一轮里改了十个文件，可以回到第七个改之前。只读工具不拍——
 *       它们不改工作区，拍出来和上一张一模一样。</li>
 * </ul>
 *
 * <h3>内容没变就不记</h3>
 * 工具级的点如果和清单里最后一个点的 tree 相同（中间跑的是测试、或者审批被拒了），
 * 就不进清单：回到它和回到上一个点，文件上没有任何区别，只会把 /rewind 的清单刷长。
 * 代价是这种点的「对话位置」也就不单独留了——回对话仍可按上一个点或轮级点回。
 *
 * <h3>持久化</h3>
 * 每张都进影子仓库的 history.jsonl，带 sessionId 和 historySize。换会话（接回一段老的、
 * 或 /clear 开新的）时按 sessionId 重新装载——所以后端重启、接回老会话之后照样能 rewind。
 * 能回多久由影子仓库的保留期决定（{@link ShadowRepo#gc}）。
 */
public final class Checkpoints {

    private static final Logger log = LoggerFactory.getLogger(Checkpoints.class);

    /**
     * @param id          会话内的序号，从 1 起，给用户敲 {@code /rewind 3} 用
     * @param historySize 拍的时候 agent 记录有几条（含 system）；回对话就是截到这个长度
     */
    public record Checkpoint(int id, String sessionId, int historySize, String tree, String label, long time,
                             String kind) {

        public Checkpoint(int id, String sessionId, int historySize, String tree, String label, long time) {
            this(id, sessionId, historySize, tree, label, time, ShadowRepo.KIND_TURN);
        }

        /** 是不是某次工具调用前拍的（否则就是轮级的）。 */
        public boolean beforeTool() {
            return ShadowRepo.KIND_TOOL.equals(kind);
        }
    }

    private final ShadowRepo repo;
    private String sessionId;
    private final List<Checkpoint> items = new ArrayList<>();

    public Checkpoints(ShadowRepo repo) {
        this.repo = repo;
    }

    public boolean available() {
        return repo != null;
    }

    /** 当前会话换了：清掉内存里的，把这段会话以前记过的从影子里装回来。 */
    public synchronized void session(String id) {
        this.sessionId = id;
        items.clear();
        if (repo == null || id == null) {
            return;
        }
        try {
            for (ShadowRepo.Snapshot snap : repo.history()) {
                if (id.equals(snap.sessionId())) {
                    items.add(new Checkpoint(items.size() + 1, id, snap.historySize(), snap.tree(),
                            snap.label(), snap.time(), snap.kind()));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("装载会话 {} 的检查点失败: {}", id, e.getMessage());
        }
    }

    /**
     * 拍一张。失败只记日志、返回 null——保险出问题不能让这一轮跑不起来。
     */
    public synchronized Checkpoint mark(String label, int historySize) {
        if (repo == null) {
            return null;
        }
        try {
            ShadowRepo.Snapshot snap = repo.capture(label, sessionId, historySize);
            Checkpoint cp = new Checkpoint(items.size() + 1, sessionId, historySize, snap.tree(),
                    snap.label(), snap.time());
            items.add(cp);
            log.info("检查点 #{} {} ({})", cp.id(), snap.tree().substring(0, 8), label);
            return cp;
        } catch (IOException | RuntimeException e) {
            log.warn("拍检查点失败，这一轮回不去: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 一次会动手的工具调用之前拍一张。内容和清单里最后一个点相同就不记，返回 null。
     * 失败同样只记日志、返回 null。
     *
     * <p>synchronized 不只是为了清单：并行的 coder（计划里文件不相交的写任务）会同时来拍，
     * 而影子仓库只有一份 index，两个 {@code git add} 撞在一起会互相踩。
     */
    public synchronized Checkpoint markTool(String label, int historySize) {
        if (repo == null) {
            return null;
        }
        try {
            String tree = repo.writeTree();
            Checkpoint last = latest();
            if (last != null && last.tree().equals(tree)) {
                return null;
            }
            ShadowRepo.Snapshot snap = repo.record(tree, label, sessionId, historySize,
                    ShadowRepo.KIND_TOOL);
            Checkpoint cp = new Checkpoint(items.size() + 1, sessionId, historySize, snap.tree(),
                    snap.label(), snap.time(), snap.kind());
            items.add(cp);
            log.debug("检查点 #{} {} ({})", cp.id(), tree.substring(0, 8), label);
            return cp;
        } catch (IOException | RuntimeException e) {
            log.warn("拍工具前检查点失败，这一步回不去: {}", e.getMessage());
            return null;
        }
    }

    public synchronized List<Checkpoint> list() {
        return List.copyOf(items);
    }

    public synchronized Checkpoint get(int id) {
        return id >= 1 && id <= items.size() ? items.get(id - 1) : null;
    }

    public synchronized Checkpoint latest() {
        return items.isEmpty() ? null : items.get(items.size() - 1);
    }

    /** 最近一个轮级点——{@code /undo} 撤销的是上一轮整轮，不是上一步。 */
    public synchronized Checkpoint latestTurn() {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!items.get(i).beforeTool()) {
                return items.get(i);
            }
        }
        return null;
    }

    /**
     * 把工作区恢复到某个检查点。
     *
     * @return 被还原或删除的文件
     * @throws IOException 快照对象已被 gc 回收、或 git 失败
     */
    public synchronized List<String> rewindCode(Checkpoint cp) throws IOException {
        if (repo == null) {
            throw new IOException("影子仓库不可用");
        }
        if (!repo.exists(cp.tree())) {
            throw new IOException("检查点 #" + cp.id() + " 的快照已过保留期被回收，回不去了");
        }
        return repo.restore(cp.tree());
    }

    /**
     * 对话回到某个检查点之后，它后面的检查点就指向一段已经不存在的对话了——从清单里摘掉。
     * 只回代码时不调这个：对话还在，后面的点照样有效。
     */
    public synchronized void truncateAfter(Checkpoint cp) {
        while (!items.isEmpty() && items.get(items.size() - 1).id() > cp.id()) {
            items.remove(items.size() - 1);
        }
    }
}
