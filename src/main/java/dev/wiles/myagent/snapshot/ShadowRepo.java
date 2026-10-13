package dev.wiles.myagent.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 影子仓库：给工作区拍可恢复的快照，<b>不碰用户自己的 git</b>。抄的是 OpenCode 的做法。
 *
 * <h3>结构</h3>
 * {@code ~/.myagent/snapshot/<工作区路径的哈希>/} 是一个裸仓库。每条 git 命令都带
 * {@code GIT_DIR=影子}、{@code GIT_WORK_TREE=工作区}、{@code GIT_INDEX_FILE=影子/index}：
 * 对象、引用、索引全在影子里，工作区里只多不少——它自己的 {@code .git}（如果有）
 * 一个字节都不会被动到。工作区不是 git 仓库也照样能用。
 *
 * <h3>快照是 tree，不是 commit</h3>
 * {@code git add -A && git write-tree} 只产生 tree/blob 对象，返回一个 sha。
 * 没有 commit、没有分支、没有历史要维护；同一份内容第二次拍是零成本（对象已存在）。
 * sha 和标签记在影子里的 {@code history.jsonl}，服务重启后照样能撤销。
 *
 * <h3>恢复</h3>
 * 先 {@code add -A} 让索引等于现在的工作区，再 {@code diff --cached --name-status <tree>}：
 * 相对快照，M/D 的用 {@code checkout <tree> -- path} 还原，A 的（快照后新建的）删掉。
 * 只动有差别的文件，其余一个不碰。
 *
 * <h3>忽略</h3>
 * git 会照常读工作区里的 {@code .gitignore}；另外影子的 {@code info/exclude} 里写死了
 * {@code .git/}、构建产物、虚拟环境这些——它们不该进快照，也绝不会被恢复动到。
 */
public final class ShadowRepo {

    private static final Logger log = LoggerFactory.getLogger(ShadowRepo.class);

    private static final List<String> DEFAULT_EXCLUDES = List.of(
            ".git/", ".hg/", ".svn/", "node_modules/", "target/", "build/", "dist/",
            ".venv/", "venv/", "__pycache__/", ".pytest_cache/", ".mypy_cache/",
            ".idea/", ".gradle/", ".tox/", ".myagent/");

    /** 一轮开跑前拍的。老记录没有 kind 字段，一律当它。 */
    public static final String KIND_TURN = "turn";
    /** 一次会动手的工具调用前拍的。 */
    public static final String KIND_TOOL = "tool";

    /**
     * 一次快照。{@code sessionId} 和 {@code historySize} 是检查点语义（属于哪段会话、
     * 拍的时候对话记录有几条），老记录里没有，读出来是 null / -1。
     * {@code kind} 是 {@link #KIND_TURN} 或 {@link #KIND_TOOL}。
     */
    public record Snapshot(String tree, String label, long time, String sessionId, int historySize,
                           String kind) {

        public Snapshot(String tree, String label, long time, String sessionId, int historySize) {
            this(tree, label, time, sessionId, historySize, KIND_TURN);
        }
    }

    private final Path workspace;
    private final Path dir;

    private ShadowRepo(Path workspace, Path dir) {
        this.workspace = workspace;
        this.dir = dir;
    }

    /** 默认位置：{@code ~/.myagent/snapshot}。 */
    public static Path defaultBase() {
        return Path.of(System.getProperty("user.home"), ".myagent", "snapshot");
    }

    /**
     * 打开（没有就初始化）这个工作区的影子仓库。
     *
     * @return 机器上没有 git、或初始化失败时返回 null——快照是保险，保险买不到不该让主业务停摆
     */
    public static ShadowRepo open(Path workspace, Path base) {
        Path ws = workspace.toAbsolutePath().normalize();
        Path dir = base.resolve(hash(ws.toString()));
        ShadowRepo repo = new ShadowRepo(ws, dir);
        try {
            if (!Files.isDirectory(dir.resolve("objects"))) {
                Files.createDirectories(dir);
                repo.git(List.of("init", "-q", "--bare", dir.toString()), false);
            }
            Path exclude = dir.resolve("info").resolve("exclude");
            Files.createDirectories(exclude.getParent());
            Files.writeString(exclude, String.join("\n", DEFAULT_EXCLUDES) + "\n");
            return repo;
        } catch (IOException | RuntimeException e) {
            log.warn("影子仓库不可用，本次不拍快照: {}", e.getMessage());
            return null;
        }
    }

    public Path dir() {
        return dir;
    }

    /** 拍一张。同样内容第二次拍得到同一个 sha。 */
    public Snapshot capture(String label) throws IOException {
        return capture(label, null, -1);
    }

    /** 拍一张并记下它属于哪段会话、当时对话有几条——这就是一个检查点。 */
    public Snapshot capture(String label, String sessionId, int historySize) throws IOException {
        return record(writeTree(), label, sessionId, historySize, KIND_TURN);
    }

    /**
     * 只把工作区写成一个 tree，不记账。
     *
     * <p>和 {@link #record} 拆开，是为了让调用方先比一比：工具级检查点在内容和上一张
     * 一模一样时不该再记一笔（跑了一次测试、文件一个没变），否则 /rewind 的清单
     * 会被一串回过去也毫无变化的点淹掉。
     */
    public String writeTree() throws IOException {
        git(List.of("add", "-A", "--", "."), true);
        return git(List.of("write-tree"), true).strip();
    }

    /** 把一个已经写好的 tree 记进 history.jsonl，成为一个检查点。 */
    public Snapshot record(String tree, String label, String sessionId, int historySize,
                           String kind) throws IOException {
        Snapshot snap = new Snapshot(tree, label == null ? "" : label, System.currentTimeMillis(),
                sessionId, historySize, kind == null ? KIND_TURN : kind);
        Files.writeString(dir.resolve("history.jsonl"), line(snap) + "\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        return snap;
    }

    /** history.jsonl 的一行。轮级的不写 kind，和老记录一字不差。 */
    private static String line(Snapshot snap) {
        StringBuilder line = new StringBuilder("{\"tree\":\"").append(snap.tree())
                .append("\",\"label\":").append(quote(snap.label()))
                .append(",\"time\":").append(snap.time());
        if (snap.sessionId() != null) {
            line.append(",\"session\":").append(quote(snap.sessionId()))
                    .append(",\"history\":").append(snap.historySize());
        }
        if (!KIND_TURN.equals(snap.kind())) {
            line.append(",\"kind\":").append(quote(snap.kind()));
        }
        return line.append("}").toString();
    }

    /** 某张快照的对象还在不在（gc 会回收过期的）。 */
    public boolean exists(String tree) {
        try {
            git(List.of("cat-file", "-e", tree + "^{tree}"), false);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 把工作区恢复到某张快照。只动有差别的文件。
     *
     * @return 被还原或删除的相对路径
     */
    public List<String> restore(String tree) throws IOException {
        git(List.of("add", "-A", "--", "."), true);
        String diff = git(List.of("diff", "--cached", "--name-status", "--no-renames", "-z", tree), true);
        List<String> checkout = new ArrayList<>();
        List<String> delete = new ArrayList<>();
        String[] parts = diff.split("\0");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            String status = parts[i];
            String path = parts[i + 1];
            if (status.startsWith("A")) {
                delete.add(path);       // 快照里没有、现在有：是之后新建的
            } else {
                checkout.add(path);     // M / D / T：快照里有，按快照的内容写回
            }
        }
        if (!checkout.isEmpty()) {
            List<String> cmd = new ArrayList<>(List.of("checkout", "-q", tree, "--"));
            cmd.addAll(checkout);
            git(cmd, true);
        }
        for (String rel : delete) {
            Path p = workspace.resolve(rel).normalize();
            if (p.startsWith(workspace)) {
                Files.deleteIfExists(p);
            }
        }
        List<String> touched = new ArrayList<>(checkout);
        touched.addAll(delete);
        return touched;
    }

    /** 相对某张快照的 unified diff（含新建文件）。给验收和界面看。 */
    public String diff(String tree) throws IOException {
        git(List.of("add", "-A", "--", "."), true);
        return git(List.of("diff", "--cached", "--no-renames", tree), true);
    }

    /** 影子里记过的快照，按时间顺序。 */
    public List<Snapshot> history() throws IOException {
        Path file = dir.resolve("history.jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Snapshot> out = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            com.fasterxml.jackson.databind.JsonNode n = m.readTree(line);
            out.add(new Snapshot(n.get("tree").asText(), n.path("label").asText(""), n.path("time").asLong(),
                    n.hasNonNull("session") ? n.get("session").asText() : null,
                    n.path("history").asInt(-1), n.path("kind").asText(KIND_TURN)));
        }
        return out;
    }

    /**
     * 收拾一下：{@code days} 天前没人引用的对象丢掉，history.jsonl 里同样老的记录也清掉。
     * 这就是检查点的保留期——过了期的检查点回不去了。偶尔调一次就行。
     */
    public void gc(int days) {
        try {
            git(List.of("gc", "-q", "--prune=" + Math.max(1, days) + ".days.ago"), true);
            Path file = dir.resolve("history.jsonl");
            if (Files.exists(file)) {
                long cutoff = System.currentTimeMillis() - Math.max(1, days) * 86_400_000L;
                List<String> keep = new ArrayList<>();
                for (Snapshot snap : history()) {
                    if (snap.time() >= cutoff) {
                        keep.add(line(snap));
                    }
                }
                Files.writeString(file, keep.isEmpty() ? "" : String.join("\n", keep) + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("影子仓库 gc 失败（不要紧）: {}", e.getMessage());
        }
    }

    // ── git ─────────────────────────────────────────────────────

    private String git(List<String> args, boolean withWorkTree) throws IOException {
        List<String> cmd = new ArrayList<>(args.size() + 1);
        cmd.add("git");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("GIT_DIR", dir.toString());
        if (withWorkTree) {
            env.put("GIT_WORK_TREE", workspace.toString());
            env.put("GIT_INDEX_FILE", dir.resolve("index").toString());
        }
        env.remove("GIT_INDEX_VERSION");
        Process p = pb.start();
        p.getOutputStream().close();
        byte[] out;
        try (var in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        boolean done;
        try {
            done = p.waitFor(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("git 被中断");
        }
        if (!done) {
            p.destroyForcibly();
            throw new IOException("git " + args.get(0) + " 超过 120 秒");
        }
        String text = new String(out, StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " 失败: " + text.strip());
        }
        return text;
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
