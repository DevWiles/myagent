package dev.wiles.myagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 沙箱是唯一挡在模型和你整块硬盘之间的东西，它的测试值得写得最狠。 */
class WorkspaceTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("正常相对路径可以解析")
    void resolvesNormalPath() {
        Workspace ws = new Workspace(tmp);
        assertEquals(tmp.resolve("src/Main.java").normalize(), ws.resolve("src/Main.java"));
    }

    @Test
    @DisplayName("用 .. 往上爬会被拒绝")
    void rejectsParentEscape() {
        Workspace ws = new Workspace(tmp);
        assertThrows(IllegalArgumentException.class, () -> ws.resolve("../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> ws.resolve("../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> ws.resolve("a/b/../../../c"));
    }

    @Test
    @DisplayName("绝对路径会被拒绝")
    void rejectsAbsolutePath() {
        Workspace ws = new Workspace(tmp);
        assertThrows(IllegalArgumentException.class, () -> ws.resolve("/etc/passwd"));
    }

    @Test
    @DisplayName("绕一圈再回来是允许的，只要最终落点还在沙箱内")
    void allowsRoundTripInsideRoot() {
        Workspace ws = new Workspace(tmp);
        assertEquals(tmp.resolve("b").normalize(), ws.resolve("a/../b"));
    }

    @Test
    @DisplayName("空路径被拒绝")
    void rejectsBlankPath() {
        Workspace ws = new Workspace(tmp);
        assertThrows(IllegalArgumentException.class, () -> ws.resolve(""));
        assertThrows(IllegalArgumentException.class, () -> ws.resolve(null));
    }

    @Test
    @DisplayName("指向沙箱外的符号链接会被拒绝（normalize 挡不住这个）")
    void rejectsSymlinkEscape() throws Exception {
        Path outside = tmp.getParent().resolve("outside-" + tmp.getFileName());
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "机密");
        Path link = tmp.resolve("shortcut");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;   // 某些文件系统不支持软链，跳过
        }
        Workspace ws = new Workspace(tmp);
        assertThrows(IllegalArgumentException.class, () -> ws.resolve("shortcut/secret.txt"));
    }

    @Test
    @DisplayName("display 输出相对路径，不泄露机器上的绝对路径")
    void displayIsRelative() {
        Workspace ws = new Workspace(tmp);
        assertEquals("src/Main.java", ws.display(tmp.resolve("src/Main.java")));
    }
}
