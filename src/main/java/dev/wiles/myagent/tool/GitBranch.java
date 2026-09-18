package dev.wiles.myagent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 当前工作区在哪个 git 分支上。
 *
 * <h3>为什么自己读 .git/HEAD，不调 git 命令</h3>
 * 这个值在两个地方要用：状态栏（每次重绘）和会话存档（每轮存盘）。
 * 都是<b>高频、且不能失败</b>的路径。起一个 {@code git} 子进程要几十毫秒，
 * 还要处理 git 没装、仓库损坏、命令卡住——而我们要的东西就写在一个小文本文件里。
 *
 * <p>另外这里<b>只读不写</b>，天生安全：不像 {@code bash} 工具那样要过
 * {@link dev.wiles.myagent.tool.safety.CommandGuard}。
 *
 * <h3>三种现场</h3>
 * <ul>
 *   <li>普通仓库：{@code .git/HEAD} 里是 {@code ref: refs/heads/dev}</li>
 *   <li>detached HEAD（比如 checkout 了某个 tag）：里面直接是一串 sha，取前 7 位</li>
 *   <li>worktree：{@code .git} 是个<b>文件</b>，内容 {@code gitdir: /path/to/.git/worktrees/x}，
 *       得跟过去再读那边的 HEAD。这个项目自己就用过 worktree，漏掉这条就会显示成「不在仓库里」</li>
 * </ul>
 *
 * <p>任何一步不认识就返回 null，调用方按「没有分支」处理。分支只是个显示信息，
 * 为它抛异常或者卡住，代价远大于它的价值。
 */
public final class GitBranch {

    private static final Logger log = LoggerFactory.getLogger(GitBranch.class);

    private static final String REF_PREFIX = "ref: refs/heads/";
    /** detached HEAD 时显示的 sha 长度，和 {@code git log --oneline} 一致。 */
    private static final int SHORT_SHA = 7;

    private GitBranch() {
    }

    /** 这个目录（或它的某个上层）所在的分支名；不在 git 仓库里就返回 null。 */
    public static String of(Path workspace) {
        if (workspace == null) {
            return null;
        }
        Path dir = workspace.toAbsolutePath().normalize();
        // 往上找：agent 完全可能被开在仓库的子目录里
        for (Path at = dir; at != null; at = at.getParent()) {
            String branch = readHead(at.resolve(".git"));
            if (branch != null) {
                return branch;
            }
        }
        return null;
    }

    /** @param gitPath 可能是目录（普通仓库），也可能是文件（worktree 或 submodule） */
    private static String readHead(Path gitPath) {
        try {
            if (Files.isRegularFile(gitPath)) {
                String pointer = Files.readString(gitPath, StandardCharsets.UTF_8).strip();
                if (!pointer.startsWith("gitdir:")) {
                    return null;
                }
                Path real = Path.of(pointer.substring("gitdir:".length()).strip());
                return headOf(real);
            }
            return Files.isDirectory(gitPath) ? headOf(gitPath) : null;
        } catch (IOException | RuntimeException e) {
            log.debug("读不出 {} 的分支: {}", gitPath, e.toString());
            return null;
        }
    }

    private static String headOf(Path gitDir) throws IOException {
        Path head = gitDir.resolve("HEAD");
        if (!Files.isRegularFile(head)) {
            return null;
        }
        String text = Files.readString(head, StandardCharsets.UTF_8).strip();
        if (text.startsWith(REF_PREFIX)) {
            String branch = text.substring(REF_PREFIX.length()).strip();
            return branch.isEmpty() ? null : branch;
        }
        // detached：HEAD 里直接躺着一个 sha
        if (text.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            return text.substring(0, SHORT_SHA);
        }
        return null;
    }
}
