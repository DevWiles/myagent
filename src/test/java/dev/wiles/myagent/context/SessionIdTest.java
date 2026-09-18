package dev.wiles.myagent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SessionIdTest {

    private static final long NOON = 1758175930000L;   // 随便一个时刻

    @Test
    @DisplayName("id 是「本地时间 + 四位随机」，按名字排序就是按时间排序")
    void looksLikeALocalTimestamp() {
        String id = SessionId.create(NOON);

        String expected = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(Instant.ofEpochMilli(NOON).atZone(ZoneId.systemDefault()));
        assertTrue(id.startsWith(expected), id + " 应该以 " + expected + " 开头");
        assertTrue(SessionId.isValid(id));
        assertTrue(SessionId.create(NOON).compareTo(SessionId.create(NOON + 1000)) < 0,
                "晚开的会话，id 也该排在后面");
    }

    @Test
    @DisplayName("同一毫秒连着造也不撞——不然后开的终端会覆盖掉前一个的存档")
    void sameMillisStillUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ids.add(SessionId.create(NOON));
        }
        assertTrue(ids.size() > 190, "200 次里撞了 " + (200 - ids.size()) + " 次，太多了");
    }

    @Test
    @DisplayName("给老存档补的 id 是幂等的——同一份档收两次不会变成两条会话")
    void legacyIdIsStable() {
        assertEquals(SessionId.forLegacy(NOON), SessionId.forLegacy(NOON));
        assertTrue(SessionId.isValid(SessionId.forLegacy(NOON)));
    }

    @Test
    @DisplayName("认不出形状的一律不认——id 要拿去拼文件名")
    void rejectsAnythingElse() {
        assertFalse(SessionId.isValid(null));
        assertFalse(SessionId.isValid(""));
        assertFalse(SessionId.isValid("latest"));
        assertFalse(SessionId.isValid("20260918-143210"), "少了随机尾巴");
        assertFalse(SessionId.isValid("20260918-143210-ABCD"), "十六进制只认小写，免得两个名字指同一份档");
        assertFalse(SessionId.isValid("20260918-143210-abcd.json"), "带后缀会拼出 .json.json");
        assertFalse(SessionId.isValid("../../../.ssh/id_rsa"), "这是会读到工作区外面去的");
        assertFalse(SessionId.isValid("20260918-143210-abcd/../../x"));
    }

    @Test
    @DisplayName("能从 id 反推出时刻，给界面显示用")
    void readsTimeBack() {
        Instant back = SessionId.instantOf(SessionId.create(NOON));
        assertNotNull(back);
        assertEquals(NOON / 1000, back.getEpochSecond(), "秒级对得上就够了，id 里没有毫秒");
        assertNull(SessionId.instantOf("不是 id"));
    }
}
