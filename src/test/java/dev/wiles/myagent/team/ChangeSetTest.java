package dev.wiles.myagent.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 改动清单是验收的「事实」那一半：漏一个文件，验收就对着半份现场说通过。
 */
class ChangeSetTest {

    @Test
    @DisplayName("快照跳过 .git/__pycache__ 和二进制，其余文本文件按相对路径收进来")
    void snapshotSkipsNoise(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("src/__pycache__"));
        Files.writeString(root.resolve("src/a.py"), "print(1)\n");
        Files.writeString(root.resolve(".git/HEAD"), "ref: x\n");
        Files.writeString(root.resolve("src/__pycache__/a.pyc"), "junk");
        Files.write(root.resolve("blob.bin"), new byte[]{1, 0, 2});

        Map<String, String> snap = ChangeSet.snapshot(root);

        assertEquals(Map.of("src/a.py", "print(1)\n"), snap);
        assertTrue(ChangeSet.snapshot(null).isEmpty(), "没有工作区就是空快照，不能炸");
    }

    @Test
    @DisplayName("新增 / 修改 / 删除各列一条，修改的带 unified diff，没改动就是空串")
    void reportListsEveryKindOfChange() {
        Map<String, String> before = Map.of(
                "src/merge.py", "def f(a):\n    return a\n",
                "tests/test_x.py", "assert 1\n",
                "gone.txt", "bye\n");
        Map<String, String> after = Map.of(
                "src/merge.py", "def f(a):\n    return dict(a)\n",
                "tests/test_x.py", "assert 1\n",
                "new.py", "x = 1\n");

        String report = ChangeSet.report(before, after);

        assertTrue(report.startsWith("共 3 个文件有改动"), report);
        assertTrue(report.contains("### 修改 src/merge.py"), report);
        assertTrue(report.contains("-    return a") && report.contains("+    return dict(a)"), report);
        assertTrue(report.contains("### 新增 new.py") && report.contains("x = 1"), report);
        assertTrue(report.contains("### 删除 gone.txt"), report);
        assertFalse(report.contains("test_x.py"), "没改的文件不该出现在清单里");
        assertEquals("", ChangeSet.report(before, before));
    }

    @Test
    @DisplayName("diff 只带 3 行上下文，块头的行号从 1 起算")
    void unifiedDiffHasContextAndHeader() {
        String old = "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\n";
        String now = "l1\nl2\nl3\nl4\nL5\nl6\nl7\nl8\n";

        String diff = ChangeSet.unifiedDiff(old, now);

        assertTrue(diff.startsWith("@@ -2,7 +2,7 @@\n"), diff);
        assertTrue(diff.contains(" l2\n l3\n l4\n-l5\n+L5\n l6\n l7\n l8\n"), diff);
        assertFalse(diff.contains(" l1\n"), "第 1 行离改动超过 3 行，不该进块");
    }
}
