package dev.wiles.myagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 摘要错了不会让程序崩，只会让规划悄悄变差——所以每条裁剪规则都得盯住。
 */
class WorkspaceDigestTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("列出目录和文件，目录带尾斜杠且排在文件前面")
    void listsDirsBeforeFiles() throws IOException {
        Files.createDirectory(tmp.resolve("src"));
        Files.writeString(tmp.resolve("pom.xml"), "x");

        String digest = WorkspaceDigest.of(tmp);

        assertTrue(digest.contains("src/"), digest);
        assertTrue(digest.contains("pom.xml"), digest);
        assertTrue(digest.indexOf("src/") < digest.indexOf("pom.xml"), "目录该排在文件前面: " + digest);
    }

    @Test
    @DisplayName("子目录按层缩进")
    void indentsNestedEntries() throws IOException {
        Files.createDirectories(tmp.resolve("src/main"));
        Files.writeString(tmp.resolve("src/main/App.java"), "x");

        String digest = WorkspaceDigest.of(tmp);

        assertTrue(digest.contains("src/"), digest);
        assertTrue(digest.contains("  main/"), digest);
        assertTrue(digest.contains("    App.java"), digest);
    }

    @Test
    @DisplayName("构建产物和依赖目录整个跳过")
    void skipsBuildOutput() throws IOException {
        Files.createDirectory(tmp.resolve("target"));
        Files.writeString(tmp.resolve("target/app.jar"), "x");
        Files.createDirectory(tmp.resolve("node_modules"));
        Files.createDirectory(tmp.resolve("src"));

        String digest = WorkspaceDigest.of(tmp);

        assertFalse(digest.contains("target"), digest);
        assertFalse(digest.contains("node_modules"), digest);
        assertFalse(digest.contains("app.jar"), digest);
        assertTrue(digest.contains("src/"), digest);
    }

    @Test
    @DisplayName("隐藏文件不列——.env 这种还可能带密钥")
    void skipsHiddenFiles() throws IOException {
        Files.writeString(tmp.resolve(".env"), "API_KEY=secret");
        Files.createDirectory(tmp.resolve(".git"));
        Files.writeString(tmp.resolve("README.md"), "x");

        String digest = WorkspaceDigest.of(tmp);

        assertFalse(digest.contains(".env"), digest);
        assertFalse(digest.contains("secret"), digest);
        assertFalse(digest.contains(".git"), digest);
        assertTrue(digest.contains("README.md"), digest);
    }

    @Test
    @DisplayName("超过深度上限就不再往下展开")
    void stopsAtMaxDepth() throws IOException {
        Files.createDirectories(tmp.resolve("a/b/c/d"));
        Files.writeString(tmp.resolve("a/b/c/d/Deep.java"), "x");

        String digest = WorkspaceDigest.of(tmp, 3, 100);

        assertTrue(digest.contains("a/"), digest);
        assertTrue(digest.contains("b/"), digest);
        assertTrue(digest.contains("c/"), digest);
        assertFalse(digest.contains("d/"), "第四层不该展开: " + digest);
        assertFalse(digest.contains("Deep.java"), digest);
    }

    @Test
    @DisplayName("撞上条目上限会截断，并且明说截断了")
    void truncatesAndSaysSo() throws IOException {
        for (int i = 0; i < 20; i++) {
            Files.writeString(tmp.resolve("f" + i + ".txt"), "x");
        }

        String digest = WorkspaceDigest.of(tmp, 3, 5);

        assertEquals(5, digest.lines().filter(l -> l.endsWith(".txt")).count(), digest);
        assertTrue(digest.contains("只列出前 5 项"), digest);
    }

    @Test
    @DisplayName("空目录和不可读的根都给一句人话，不抛异常")
    void neverThrows() {
        assertEquals("（空目录）", WorkspaceDigest.of(tmp));
        assertEquals("（工作区不可读）", WorkspaceDigest.of(tmp.resolve("nope")));
        assertEquals("（工作区不可读）", WorkspaceDigest.of(null));
    }
}
