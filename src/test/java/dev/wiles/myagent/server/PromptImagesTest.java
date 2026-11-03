package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptImagesTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode cmd(String json) throws Exception {
        return M.readTree(json);
    }

    @Test
    @DisplayName("没带图（老界面发的 prompt）：空列表")
    void noImages() throws Exception {
        assertEquals(List.of(), PromptImages.parse(cmd("{\"t\":\"prompt\",\"text\":\"hi\"}")));
    }

    @Test
    @DisplayName("带图：按顺序转成 data URL")
    void toDataUrls() throws Exception {
        List<String> urls = PromptImages.parse(cmd("{\"images\":[{\"mime\":\"image/png\",\"data\":\"iVBORw0KGgo=\"},"
                + "{\"mime\":\"image/jpeg\",\"data\":\"/9j/4AAQ\"}]}"));
        assertEquals(List.of("data:image/png;base64,iVBORw0KGgo=", "data:image/jpeg;base64,/9j/4AAQ"), urls);
    }

    @Test
    @DisplayName("格式不认、不是 base64、太多张：拒绝，并说清为什么")
    void rejectsBadInput() {
        var bad = List.of(
                "{\"images\":[{\"mime\":\"image/bmp\",\"data\":\"AAAA\"}]}",
                "{\"images\":[{\"mime\":\"image/png\",\"data\":\"不是base64\"}]}",
                "{\"images\":{\"mime\":\"image/png\"}}");
        for (String json : bad) {
            assertThrows(IllegalArgumentException.class, () -> PromptImages.parse(cmd(json)), json);
        }
        String many = "{\"images\":[" + String.join(",", java.util.Collections.nCopies(
                PromptImages.MAX_COUNT + 1, "{\"mime\":\"image/png\",\"data\":\"AAAA\"}")) + "]}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PromptImages.parse(cmd(many)));
        assertTrue(e.getMessage().contains(String.valueOf(PromptImages.MAX_COUNT)));
    }

    @Test
    @DisplayName("单张超过上限：拒绝")
    void rejectsHugeImage() throws Exception {
        String huge = "A".repeat(PromptImages.MAX_BYTES / 3 * 4 + 400);
        assertThrows(IllegalArgumentException.class,
                () -> PromptImages.parse(cmd("{\"images\":[{\"mime\":\"image/png\",\"data\":\"" + huge + "\"}]}")));
    }
}
