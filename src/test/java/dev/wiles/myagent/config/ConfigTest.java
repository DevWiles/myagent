package dev.wiles.myagent.config;

import dev.wiles.myagent.context.ContextManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只测 contextTokens 的解析。
 *
 * <p>{@code Config.load()} 本身不好测——它读的是真实环境变量和用户主目录下的文件，
 * 而 {@code System.getenv} 在 JVM 里改不了。所以把「怎么解释这个值」单独拎出来测，
 * 剩下的「从哪儿取这个值」是三行 pick，肉眼能看完。
 */
class ConfigTest {

    @Test
    @DisplayName("没配就是默认值")
    void defaultsWhenAbsent() {
        assertEquals(ContextManager.DEFAULT_MAX_TOKENS, Config.contextTokens(null));
        assertEquals(ContextManager.DEFAULT_MAX_TOKENS, Config.contextTokens(""));
        assertEquals(ContextManager.DEFAULT_MAX_TOKENS, Config.contextTokens("   "));
    }

    @Test
    @DisplayName("正常值原样采纳，前后空白不算数")
    void acceptsAnExplicitValue() {
        assertEquals(8_000, Config.contextTokens("8000"));
        assertEquals(8_000, Config.contextTokens("  8000\n"));
        assertEquals(128_000, Config.contextTokens("128000"), "换个大窗口的模型也得让配");
    }

    @Test
    @DisplayName("配成一坨乱码不能让 agent 起不来——退回默认值就是了")
    void garbageFallsBackInsteadOfThrowing() {
        assertEquals(ContextManager.DEFAULT_MAX_TOKENS, Config.contextTokens("abc"));
        assertEquals(ContextManager.DEFAULT_MAX_TOKENS, Config.contextTokens("64k"));
        assertEquals(ContextManager.DEFAULT_MAX_TOKENS, Config.contextTokens("8_000"));
        assertEquals(ContextManager.DEFAULT_MAX_TOKENS, Config.contextTokens("99999999999999"));
    }

    @Test
    @DisplayName("太小的值抬到下限：那种值不报错，只会让会话每轮都在压缩")
    void tooSmallIsRaisedToTheFloor() {
        assertEquals(ContextManager.MIN_MAX_TOKENS, Config.contextTokens("500"));
        assertEquals(ContextManager.MIN_MAX_TOKENS, Config.contextTokens("0"));
        assertEquals(ContextManager.MIN_MAX_TOKENS, Config.contextTokens("-1"));
    }

    @Test
    @DisplayName("下限本身得留得住历史，否则抬上去也是白抬")
    void theFloorLeavesRoomForActualHistory() {
        // 压缩的目标预算 = maxTokens × 0.4 − 工具菜单（实测约 700，带记忆约 1200）
        int budget = (int) (ContextManager.MIN_MAX_TOKENS * 0.4) - 1_200;

        assertTrue(budget > 0, "下限压到这里，压缩预算已经是负数了: " + budget);
    }

    @Test
    @DisplayName("thinking 只认 true/false，别的值当没配")
    void thinkingParsesStrictly() {
        assertEquals(Boolean.FALSE, Config.thinking("false"));
        assertEquals(Boolean.TRUE, Config.thinking(" True "));
        assertNull(Config.thinking(null));
        assertNull(Config.thinking(""));
        assertNull(Config.thinking("off"));
        assertNull(new Config("k", Config.DEFAULT_BASE_URL, Config.DEFAULT_MODEL, 8_000).thinking(),
                "老的四参构造不碰开关");
    }

    @Test
    @DisplayName("snapshotDays 只认正整数，默认 30 天")
    void snapshotDaysParsesStrictly() {
        assertEquals(30, Config.snapshotDays(null));
        assertEquals(7, Config.snapshotDays("7"));
        assertEquals(30, Config.snapshotDays("0"));
        assertEquals(30, Config.snapshotDays("abc"));
        assertEquals(30, new Config("k", Config.DEFAULT_BASE_URL, Config.DEFAULT_MODEL, 8_000).snapshotDays());
    }

    @Test
    @DisplayName("toString 不能把 key 打出去，加了字段也一样")
    void toStringStaysMasked() {
        String s = new Config("sk-abcdef1234567890", Config.DEFAULT_BASE_URL,
                Config.DEFAULT_MODEL, 8_000).toString();

        assertFalse(s.contains("abcdef1234567890"), "密钥漏进日志了: " + s);
        assertTrue(s.contains("8000"), "新字段要能看见，排查配置问题全靠它: " + s);
    }
}
