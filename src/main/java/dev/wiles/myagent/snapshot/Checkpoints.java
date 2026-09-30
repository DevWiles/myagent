package dev.wiles.myagent.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 检查点：每条会动手的命令开跑前拍一张，绑在<b>会话 + 那一刻的对话长度</b>上。
 * 照的是 Claude Code 的形状——{@code /rewind} 可以只回代码、只回对话、或者都回。
 *
 * <h3>为什么开跑前就拍，不等第一次写</h3>
 * 回对话要用到「那一轮开始时记录有几条」，这个数只有开跑那一刻准。
 * 一轮没写任何文件也照样是个检查点：用户可能就想把那句话收回去。
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
    public record Checkpoint(int id, String sessionId, int historySize, String tree, String label, long time) {
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
                            snap.label(), snap.time()));
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

    public synchronized List<Checkpoint> list() {
        return List.copyOf(items);
    }

    public synchronized Checkpoint get(int id) {
        return id >= 1 && id <= items.size() ? items.get(id - 1) : null;
    }

    public synchronized Checkpoint latest() {
        return items.isEmpty() ? null : items.get(items.size() - 1);
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
