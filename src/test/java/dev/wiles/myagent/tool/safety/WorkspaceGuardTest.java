package dev.wiles.myagent.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceGuardTest {

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    @DisplayName("家目录禁止启动——那里的 rm -rf * 就是删光个人文件")
    void rejectsHome() {
        assertNotNull(WorkspaceGuard.rejectionReason(HOME));
        assertFalse(WorkspaceGuard.isSafe(HOME));
    }

    @Test
    @DisplayName("根目录和系统目录禁止启动")
    void rejectsSystemDirs() {
        assertFalse(WorkspaceGuard.isSafe(Path.of("/")));
        assertFalse(WorkspaceGuard.isSafe(Path.of("/usr")));
        assertFalse(WorkspaceGuard.isSafe(Path.of("/etc")));
        assertFalse(WorkspaceGuard.isSafe(Path.of("/Users")));
        assertFalse(WorkspaceGuard.isSafe(Path.of("/Applications")));
    }

    @Test
    @DisplayName("Documents / Desktop 这类个人目录禁止启动")
    void rejectsPersonalDirs() {
        assertFalse(WorkspaceGuard.isSafe(HOME.resolve("Documents")));
        assertFalse(WorkspaceGuard.isSafe(HOME.resolve("Desktop")));
        assertFalse(WorkspaceGuard.isSafe(HOME.resolve("Downloads")));
    }

    @Test
    @DisplayName("具体的项目目录允许启动")
    void allowsProjectDirs(@TempDir Path tmp) {
        assertTrue(WorkspaceGuard.isSafe(tmp));
        assertTrue(WorkspaceGuard.isSafe(HOME.resolve("code/demo-that-does-not-exist")));
    }

    @Test
    @DisplayName("绕一圈写的路径也要能识别出是家目录")
    void normalizesPath() {
        assertFalse(WorkspaceGuard.isSafe(HOME.resolve("Documents/..")));
    }

    @Test
    @DisplayName("装着多个代码仓库的父目录禁止启动——固定清单猜不到用户把项目放哪")
    void rejectsRepositoryContainer(@TempDir Path tmp) throws Exception {
        java.nio.file.Files.createDirectories(tmp.resolve("projA/.git"));
        java.nio.file.Files.createDirectories(tmp.resolve("projB/.git"));

        assertFalse(WorkspaceGuard.isSafe(tmp));
        assertTrue(WorkspaceGuard.rejectionReason(tmp).contains("仓库"));
    }

    @Test
    @DisplayName("仓库藏在孙子目录里也要能发现——~/Workspace/projects/xxx 这种层次很常见")
    void rejectsGrandparentOfRepositories(@TempDir Path tmp) throws Exception {
        java.nio.file.Files.createDirectories(tmp.resolve("projects/projA/.git"));
        java.nio.file.Files.createDirectories(tmp.resolve("projects/projB/.git"));

        assertFalse(WorkspaceGuard.isSafe(tmp), "站在 ~/Workspace 也该被拦住");
    }

    @Test
    @DisplayName("单个项目目录照常放行，哪怕它自己是个仓库")
    void allowsSingleRepository(@TempDir Path tmp) throws Exception {
        java.nio.file.Files.createDirectories(tmp.resolve(".git"));
        java.nio.file.Files.createDirectories(tmp.resolve("src/main"));

        assertTrue(WorkspaceGuard.isSafe(tmp), "项目自己的 .git 不该把自己拦下来");
    }

    @Test
    @DisplayName("只有一个子仓库时不拦——那还算不上项目容器")
    void allowsSingleChildRepository(@TempDir Path tmp) throws Exception {
        java.nio.file.Files.createDirectories(tmp.resolve("only/.git"));

        assertTrue(WorkspaceGuard.isSafe(tmp));
    }

    @Test
    @DisplayName("拒绝理由要说清楚为什么，否则用户只会觉得是程序有毛病")
    void reasonExplainsWhy() {
        String reason = WorkspaceGuard.rejectionReason(HOME);
        assertTrue(reason.contains("家目录"));
        assertTrue(reason.length() > 20);
    }
}
