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
        assertTrue(WorkspaceGuard.isSafe(HOME.resolve("Workspace/projects/myagent")));
        assertTrue(WorkspaceGuard.isSafe(HOME.resolve("code/demo")));
    }

    @Test
    @DisplayName("绕一圈写的路径也要能识别出是家目录")
    void normalizesPath() {
        assertFalse(WorkspaceGuard.isSafe(HOME.resolve("Documents/..")));
    }

    @Test
    @DisplayName("拒绝理由要说清楚为什么，否则用户只会觉得是程序有毛病")
    void reasonExplainsWhy() {
        String reason = WorkspaceGuard.rejectionReason(HOME);
        assertTrue(reason.contains("家目录"));
        assertTrue(reason.length() > 20);
    }
}
