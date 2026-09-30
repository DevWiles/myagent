package dev.wiles.myagent.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 影子仓库是用户最后的后悔药，所以这里盯三件事：能回到拍照那一刻（改的还原、新建的删掉、删掉的回来）、
 * 不碰用户自己的 git、被忽略的东西既不进快照也不被恢复动到。
 */
class ShadowRepoTest {

    @TempDir
    Path tmp;

    private ShadowRepo open(Path ws) throws Exception {
        Files.createDirectories(ws);
        ShadowRepo repo = ShadowRepo.open(ws, tmp.resolve("shadow"));
        assertNotNull(repo, "机器上要有 git");
        return repo;
    }

    @Test
    @DisplayName("改过的还原、新建的删掉、删掉的回来——只动有差别的文件")
    void restoresToTheSnapshot() throws Exception {
        Path ws = tmp.resolve("ws");
        ShadowRepo repo = open(ws);
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("src/a.py"), "a = 1\n");
        Files.writeString(ws.resolve("keep.txt"), "keep\n");
        Files.writeString(ws.resolve("gone.txt"), "bye\n");

        ShadowRepo.Snapshot snap = repo.capture("第一轮");

        Files.writeString(ws.resolve("src/a.py"), "a = 2\n");
        Files.writeString(ws.resolve("src/new.py"), "x\n");
        Files.delete(ws.resolve("gone.txt"));
        List<String> touched = repo.restore(snap.tree());

        assertEquals("a = 1\n", Files.readString(ws.resolve("src/a.py")));
        assertFalse(Files.exists(ws.resolve("src/new.py")), "快照后新建的要删掉");
        assertEquals("bye\n", Files.readString(ws.resolve("gone.txt")), "快照后删掉的要回来");
        assertEquals("keep\n", Files.readString(ws.resolve("keep.txt")));
        assertEquals(3, touched.size(), touched.toString());
        assertTrue(touched.containsAll(List.of("src/a.py", "src/new.py", "gone.txt")), touched.toString());
    }

    @Test
    @DisplayName("工作区自己是 git 仓库：它的 .git 一个字节不动，快照和恢复照常")
    void doesNotTouchTheUsersGit() throws Exception {
        Path ws = tmp.resolve("repo");
        Files.createDirectories(ws);
        run(ws, "git", "init", "-q");
        Files.writeString(ws.resolve("f.txt"), "v1\n");
        run(ws, "git", "add", "f.txt");
        run(ws, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        byte[] headBefore = Files.readAllBytes(ws.resolve(".git/HEAD"));
        long indexBefore = Files.size(ws.resolve(".git/index"));
        ShadowRepo repo = open(ws);

        ShadowRepo.Snapshot snap = repo.capture("x");
        Files.writeString(ws.resolve("f.txt"), "v2\n");
        repo.restore(snap.tree());

        assertEquals("v1\n", Files.readString(ws.resolve("f.txt")));
        assertArrayEquals(headBefore, Files.readAllBytes(ws.resolve(".git/HEAD")));
        assertEquals(indexBefore, Files.size(ws.resolve(".git/index")), "用户的 index 不该被改写");
        assertEquals("", run(ws, "git", "status", "--porcelain").strip(), "用户的仓库看上去干干净净");
        assertFalse(Files.exists(ws.resolve(".git/refs/heads/shadow")), "影子不在用户的 .git 里");
    }

    @Test
    @DisplayName(".gitignore 和默认排除的东西不进快照，恢复也不动它们")
    void honoursIgnores() throws Exception {
        Path ws = tmp.resolve("ws2");
        ShadowRepo repo = open(ws);
        Files.writeString(ws.resolve(".gitignore"), "*.log\n");
        Files.writeString(ws.resolve("app.log"), "old\n");
        Files.createDirectories(ws.resolve("node_modules/x"));
        Files.writeString(ws.resolve("node_modules/x/i.js"), "1");
        Files.writeString(ws.resolve("main.py"), "1\n");

        ShadowRepo.Snapshot snap = repo.capture("x");
        Files.writeString(ws.resolve("app.log"), "new\n");
        Files.writeString(ws.resolve("node_modules/x/i.js"), "2");
        Files.writeString(ws.resolve("main.py"), "2\n");
        List<String> touched = repo.restore(snap.tree());

        assertEquals(List.of("main.py"), touched);
        assertEquals("new\n", Files.readString(ws.resolve("app.log")), "被忽略的文件不该被恢复覆盖");
        assertEquals("2", Files.readString(ws.resolve("node_modules/x/i.js")));
    }

    @Test
    @DisplayName("同样内容再拍一次是同一个 sha；history 按顺序记着标签")
    void snapshotsAreContentAddressed() throws Exception {
        Path ws = tmp.resolve("ws3");
        ShadowRepo repo = open(ws);
        Files.writeString(ws.resolve("a"), "1");

        ShadowRepo.Snapshot s1 = repo.capture("一");
        ShadowRepo.Snapshot s2 = repo.capture("二");
        Files.writeString(ws.resolve("a"), "2");
        ShadowRepo.Snapshot s3 = repo.capture("三");

        assertEquals(s1.tree(), s2.tree());
        assertNotEquals(s1.tree(), s3.tree());
        assertEquals(List.of("一", "二", "三"), repo.history().stream().map(ShadowRepo.Snapshot::label).toList());
        assertTrue(repo.diff(s1.tree()).contains("-1") && repo.diff(s1.tree()).contains("+2"));
    }

    @Test
    @DisplayName("非代码文件一样回得去：二进制、中文文件名、子目录里的文档，按字节还原")
    void restoresNonCodeFilesByteForByte() throws Exception {
        Path ws = tmp.resolve("docs");
        ShadowRepo repo = open(ws);
        Files.createDirectories(ws.resolve("资料/图"));
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff, 0, 0, 3};
        Files.write(ws.resolve("资料/图/封面.png"), png);
        Files.writeString(ws.resolve("资料/方案.md"), "# 方案\n第一版\n");
        Files.writeString(ws.resolve("data.csv"), "a,b\n1,2\n");

        ShadowRepo.Snapshot snap = repo.capture("文档");

        Files.write(ws.resolve("资料/图/封面.png"), new byte[]{1, 2, 3});
        Files.writeString(ws.resolve("资料/方案.md"), "# 方案\n被改坏了\n");
        Files.delete(ws.resolve("data.csv"));
        List<String> touched = repo.restore(snap.tree());

        assertArrayEquals(png, Files.readAllBytes(ws.resolve("资料/图/封面.png")));
        assertEquals("# 方案\n第一版\n", Files.readString(ws.resolve("资料/方案.md")));
        assertEquals("a,b\n1,2\n", Files.readString(ws.resolve("data.csv")));
        assertEquals(3, touched.size(), touched.toString());
    }

    private static String run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        return out;
    }
}
