package dev.wiles.myagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GitBranchTest {

    @TempDir
    Path tmp;

    private Path repo(String headContent) throws IOException {
        Path root = tmp.resolve("repo");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/HEAD"), headContent);
        return root;
    }

    @Test
    @DisplayName("普通仓库读出分支名")
    void readsBranchName() throws IOException {
        assertEquals("dev", GitBranch.of(repo("ref: refs/heads/dev\n")));
    }

    @Test
    @DisplayName("带斜杠的分支名不能被截断")
    void keepsSlashesInBranchName() throws IOException {
        assertEquals("feature/session-picker",
                GitBranch.of(repo("ref: refs/heads/feature/session-picker\n")));
    }

    @Test
    @DisplayName("开在子目录里也认得——agent 完全可能被开在仓库的子目录")
    void findsRepoFromSubdirectory() throws IOException {
        Path root = repo("ref: refs/heads/main\n");
        Path deep = root.resolve("src/main/java");
        Files.createDirectories(deep);

        assertEquals("main", GitBranch.of(deep));
    }

    @Test
    @DisplayName("detached HEAD 显示短 sha，不显示成「不在仓库里」")
    void detachedHeadShowsShortSha() throws IOException {
        assertEquals("a1b2c3d",
                GitBranch.of(repo("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678\n")));
    }

    @Test
    @DisplayName("worktree 的 .git 是个文件，得跟着 gitdir 指过去")
    void followsWorktreeGitdirFile() throws IOException {
        Path realGit = tmp.resolve("main-repo/.git/worktrees/feature");
        Files.createDirectories(realGit);
        Files.writeString(realGit.resolve("HEAD"), "ref: refs/heads/frontend\n");

        Path worktree = tmp.resolve("feature-tree");
        Files.createDirectories(worktree);
        Files.writeString(worktree.resolve(".git"), "gitdir: " + realGit + "\n");

        assertEquals("frontend", GitBranch.of(worktree),
                "这个项目自己就用过 worktree，漏掉这条会显示成「不在仓库里」");
    }

    @Test
    @DisplayName("不是仓库、读不出来、null——都返回 null，不抛")
    void neverThrows() throws IOException {
        assertNull(GitBranch.of(null));
        assertNull(GitBranch.of(tmp.resolve("不存在的目录")));

        Path notARepo = tmp.resolve("plain");
        Files.createDirectories(notARepo);
        assertNull(GitBranch.of(notARepo));

        assertNull(GitBranch.of(repo("这是什么东西\n")), "HEAD 内容不认识就当没有");
        assertNull(GitBranch.of(repo("ref: refs/heads/\n")), "空分支名不算分支");
    }
}
