package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.ChatResponse;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.llm.LlmException;
import dev.wiles.myagent.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionTitleTest {

    /** 回一句固定的话；reply 为 null 时模拟网关挂了。 */
    static final class Fake implements LlmClient {
        final List<List<ChatMessage>> seen = new ArrayList<>();
        final String reply;

        Fake(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            seen.add(messages);
            if (reply == null) {
                throw new LlmException("网关挂了");
            }
            return new ChatResponse("id", "fake",
                    List.of(new ChatResponse.Choice(0, ChatMessage.assistant(reply), "stop")), null);
        }

        @Override
        public String modelName() {
            return "fake";
        }
    }

    @Test
    @DisplayName("模型给的标题去掉引号、句号、「标题：」前缀，只留第一行")
    void cleansModelOutput() {
        assertEquals("排查登录接口 500", SessionTitle.clean("「排查登录接口 500」。"));
        assertEquals("修复分页", SessionTitle.clean("标题：\"修复分页\"\n这是因为……"));
        assertNull(SessionTitle.clean("  “”  "));
        assertNull(SessionTitle.clean(null));
    }

    @Test
    @DisplayName("太长的截到 20 个字加省略号——按字算，中文不会被劈成半个")
    void truncatesLongTitles() {
        String t = SessionTitle.clean("一二三四五六七八九十一二三四五六七八九十多出来的");
        assertEquals("一二三四五六七八九十一二三四五六七八九十…", t);
    }

    @Test
    @DisplayName("调一次模型拿标题；首句太长只喂前面一截")
    void generatesFromFirstMessage() {
        Fake fake = new Fake("排查登录 500");
        String longMessage = "登录老是 500 " + "日志".repeat(5000);

        assertEquals(Optional.of("排查登录 500"), SessionTitle.generate(fake, longMessage));
        String sent = fake.seen.get(0).get(1).content();
        assertTrue(sent.length() <= SessionTitle.MAX_INPUT, "喂了 " + sent.length() + " 字");
    }

    @Test
    @DisplayName("调用挂了、首句是空的：没有标题，但不抛——界面沿用默认标题")
    void failureIsQuiet() {
        assertEquals(Optional.empty(), SessionTitle.generate(new Fake(null), "随便"));
        Fake idle = new Fake("不该被调用");
        assertEquals(Optional.empty(), SessionTitle.generate(idle, "  "));
        assertTrue(idle.seen.isEmpty());
    }
}
