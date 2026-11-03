package dev.wiles.myagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 带图的消息在线上和存档里长什么样，以及读回来能不能还原。 */
class ChatMessageImageTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String PNG = "data:image/png;base64,iVBORw0KGgo=";

    @Test
    @DisplayName("不带图：content 还是一个字符串，和以前一字不差")
    void plainMessageUnchanged() throws Exception {
        JsonNode n = M.readTree(M.writeValueAsString(ChatMessage.user("你好")));
        assertEquals("你好", n.get("content").asText());
        assertTrue(n.get("content").isTextual());
        assertFalse(n.has("images"));
    }

    @Test
    @DisplayName("带图：content 是文字段加图片段的数组（OpenAI 的写法），没有多余的 images 字段")
    void imageMessageWire() throws Exception {
        JsonNode n = M.readTree(M.writeValueAsString(ChatMessage.user("这个报错 [图片 #1]", List.of(PNG))));
        assertTrue(n.get("content").isArray());
        assertEquals("text", n.get("content").get(0).get("type").asText());
        assertEquals("这个报错 [图片 #1]", n.get("content").get(0).get("text").asText());
        assertEquals("image_url", n.get("content").get(1).get("type").asText());
        assertEquals(PNG, n.get("content").get(1).get("image_url").get("url").asText());
        assertFalse(n.has("images"));
    }

    @Test
    @DisplayName("存档读回来：文字还是文字、图片还是图片；content() 只给文字")
    void roundTrip() throws Exception {
        ChatMessage back = M.readValue(M.writeValueAsString(ChatMessage.user("看图", List.of(PNG, PNG))),
                ChatMessage.class);
        assertEquals("看图", back.content());
        assertEquals(List.of(PNG, PNG), back.imagesOrEmpty());
    }

    @Test
    @DisplayName("老存档（字符串 content、工具调用）照常读得出来")
    void oldFormatsStillRead() throws Exception {
        ChatMessage plain = M.readValue("{\"role\":\"user\",\"content\":\"hi\"}", ChatMessage.class);
        assertEquals("hi", plain.content());
        assertNull(plain.images());
        ChatMessage tool = M.readValue("{\"role\":\"tool\",\"content\":\"ok\",\"tool_call_id\":\"c1\"}", ChatMessage.class);
        assertEquals("c1", tool.toolCallId());
        ChatMessage call = M.readValue("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"bash\",\"arguments\":\"{}\"}}]}", ChatMessage.class);
        assertNull(call.content());
        assertEquals("bash", call.toolCalls().get(0).function().name());
    }

    @Test
    @DisplayName("只有图没有字：不发空的文字段")
    void imageOnly() throws Exception {
        JsonNode n = M.readTree(M.writeValueAsString(ChatMessage.user("", List.of(PNG))));
        assertEquals(1, n.get("content").size());
        assertEquals("image_url", n.get("content").get(0).get("type").asText());
    }
}
