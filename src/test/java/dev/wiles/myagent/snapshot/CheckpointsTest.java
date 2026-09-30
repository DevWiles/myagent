package dev.wiles.myagent.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 检查点是 /rewind 的底座：编号、绑会话、重启后能装回来、回代码只动有差别的文件、
 * 回对话之后后面的点作废、过了保留期要说清楚回不去。
 */
class CheckpointsTest {

    @TempDir
    Path tmp;

    private Path ws(String name) throws Exception {
        Path ws = tmp.resolve(name);
        Files.createDirectories(ws);
        return ws;
    }

    @Test
    @DisplayName("按会话编号；换会话清空；接回老会话时从影子里装回它的检查点")
    void checkpointsFollowTheSession() throws Exception {
        Path ws = ws("a");
        ShadowRepo repo = ShadowRepo.open(ws, tmp.resolve("shadow"));
        Checkpoints cps = new Checkpoints(repo);
        cps.session("s1");
        Files.writeString(ws.resolve("f"), "1");
        assertEquals(1, cps.mark("第一句", 1).id());
        Files.writeString(ws.resolve("f"), "2");
        assertEquals(2, cps.mark("第二句", 3).id());

        cps.session("s2");
        assertTrue(cps.list().isEmpty(), "换了会话，别人的点不该出现");
        cps.mark("另一段", 1);

        Checkpoints again = new Checkpoints(ShadowRepo.open(ws, tmp.resolve("shadow")));
        again.session("s1");
        assertEquals(List.of("第一句", "第二句"), again.list().stream().map(Checkpoints.Checkpoint::label).toList());
        assertEquals(3, again.get(2).historySize());
        assertEquals("第二句", again.latest().label());
        assertNull(again.get(3));
        assertNull(again.get(0));
    }

    @Test
    @DisplayName("回代码：回到那个点的文件状态；回对话后，它后面的检查点作废")
    void rewindCodeAndTruncate() throws Exception {
        Path ws = ws("b");
        Checkpoints cps = new Checkpoints(ShadowRepo.open(ws, tmp.resolve("shadow")));
        cps.session("s");
        Files.writeString(ws.resolve("f"), "v0");
        Checkpoints.Checkpoint c1 = cps.mark("一", 1);
        Files.writeString(ws.resolve("f"), "v1");
        Checkpoints.Checkpoint c2 = cps.mark("二", 3);
        Files.writeString(ws.resolve("f"), "v2");
        cps.mark("三", 5);

        assertEquals(List.of("f"), cps.rewindCode(c2));
        assertEquals("v1", Files.readString(ws.resolve("f")));
        assertEquals(3, cps.list().size(), "只回代码，后面的点还在");

        cps.truncateAfter(c1);
        assertEquals(List.of(1), cps.list().stream().map(Checkpoints.Checkpoint::id).toList());
        assertEquals(List.of("f"), cps.rewindCode(c1));
        assertEquals("v0", Files.readString(ws.resolve("f")));
    }

    @Test
    @DisplayName("过了保留期：gc 清掉对象和记录，rewind 报「回不去了」而不是静默什么都没恢复")
    void expiredCheckpointFailsLoudly() throws Exception {
        Path ws = ws("c");
        ShadowRepo repo = ShadowRepo.open(ws, tmp.resolve("shadow"));
        Checkpoints cps = new Checkpoints(repo);
        cps.session("s");
        Files.writeString(ws.resolve("f"), "old");
        Checkpoints.Checkpoint c1 = cps.mark("老", 1);
        Files.writeString(ws.resolve("f"), "new");
        // 把记录的时间改到 40 天前，模拟过期；对象靠 gc --prune 回收
        Path hist = repo.dir().resolve("history.jsonl");
        String rewritten = Files.readString(hist).replaceAll("\"time\":\\d+", "\"time\":" + (System.currentTimeMillis() - 40L * 86_400_000));
        Files.writeString(hist, rewritten);

        repo.gc(30);

        assertTrue(repo.history().isEmpty(), "过期记录该被清掉");
        Checkpoints reloaded = new Checkpoints(repo);
        reloaded.session("s");
        assertTrue(reloaded.list().isEmpty());
        // 内存里还握着旧对象的话也不能骗人
        if (!repo.exists(c1.tree())) {
            assertThrows(java.io.IOException.class, () -> cps.rewindCode(c1));
        }
    }

    @Test
    @DisplayName("影子仓库不可用：mark 返回 null 不炸，rewind 报错")
    void unavailableRepoIsHonest() {
        Checkpoints cps = new Checkpoints(null);
        cps.session("s");
        assertFalse(cps.available());
        assertNull(cps.mark("x", 1));
        assertNull(cps.latest());
        assertThrows(java.io.IOException.class,
                () -> cps.rewindCode(new Checkpoints.Checkpoint(1, "s", 1, "deadbeef", "x", 0)));
    }
}
