package dev.wiles.myagent.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * 「写之前先拍」的闸门。会动工作区的工具（写文件、建项目、bash）都从它过一手：
 * 每轮对话<b>第一次</b>动手之前拍一张快照，之后同一轮再写不再拍。
 *
 * <h3>为什么按「轮」不按「次」</h3>
 * 用户要撤销的单位是「刚才那句话引起的全部改动」，不是某一次 write_file。
 * 每次写都拍，撤销就得按一下退一小步，而且大仓库上 add -A 每次都要跑一遍。
 * 服务端在每条用户命令开跑前 {@link #arm} 一下，闸门就知道新的一轮开始了。
 *
 * <h3>拍失败不拦写</h3>
 * 快照是保险。保险出问题只记日志，工具照常执行——不能因为影子仓库坏了就让 agent 干不了活。
 */
public final class SnapshotGate {

    private static final Logger log = LoggerFactory.getLogger(SnapshotGate.class);

    /** 会动工作区的内置工具。run_tests 不在内：它只跑测试运行器，改不了源码。 */
    public static final Set<String> MUTATING = Set.of("write_file", "create_project", "bash");

    private final ShadowRepo repo;
    private final Deque<ShadowRepo.Snapshot> stack = new ArrayDeque<>();
    private String pendingLabel;

    public SnapshotGate(ShadowRepo repo) {
        this.repo = repo;
    }

    /** 新的一轮开始了：下一次写之前要拍。label 是给 /undo 列表看的一句话。 */
    public synchronized void arm(String label) {
        this.pendingLabel = label == null ? "" : label;
    }

    /** 拍一张（如果这一轮还没拍）。并行工具批里几个写同时到，只拍第一次。 */
    public synchronized void captureIfArmed() {
        if (pendingLabel == null || repo == null) {
            return;
        }
        String label = pendingLabel;
        pendingLabel = null;
        try {
            ShadowRepo.Snapshot snap = repo.capture(label);
            stack.push(snap);
            log.info("快照 {} ({})", snap.tree().substring(0, 8), label);
        } catch (IOException | RuntimeException e) {
            log.warn("拍快照失败，这一轮不可撤销: {}", e.getMessage());
        }
    }

    /**
     * 撤销最近一轮：把工作区恢复到那一轮开工前。
     *
     * @return 被动过的文件；没有可撤销的快照时返回 null
     */
    public synchronized List<String> undo() throws IOException {
        ShadowRepo.Snapshot snap = stack.poll();
        if (snap == null || repo == null) {
            return null;
        }
        pendingLabel = null;        // 撤销本身不是一轮
        return repo.restore(snap.tree());
    }

    public synchronized ShadowRepo.Snapshot last() {
        return stack.peek();
    }

    public synchronized int depth() {
        return stack.size();
    }

    /** 给工具套上闸门。不会动工作区的工具原样返回。 */
    public Tool guard(Tool tool) {
        if (!MUTATING.contains(tool.name())) {
            return tool;
        }
        return new Tool() {
            @Override
            public String name() {
                return tool.name();
            }

            @Override
            public String description() {
                return tool.description();
            }

            @Override
            public String parametersSchema() {
                return tool.parametersSchema();
            }

            @Override
            public boolean parallelSafe() {
                return tool.parallelSafe();
            }

            @Override
            public ToolResult execute(JsonNode args) {
                captureIfArmed();
                return tool.execute(args);
            }
        };
    }
}
