package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code prompt} 命令里带的图（用户粘贴的截图），转成发给模型的 {@code data:} URL。
 *
 * <p>线上形状：{@code "images": [{"mime": "image/png", "data": "<base64>"}, ...]}，
 * 顺序就是它们在那句话里的顺序，和文字里的 {@code [图片 #n]} 占位符一一对应。
 *
 * <p>上限是为了别让一次误操作（复制了一张几十兆的设计稿）卡死这一轮：每张图会跟着历史
 * 在之后每一步请求里重发，太大的图慢、贵，还可能被服务端整个拒掉。
 */
final class PromptImages {

    static final Set<String> MIME = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    static final int MAX_COUNT = 10;
    /** 单张解码后的上限。base64 比原图大三分之一，按字符数算是 4/3 倍 */
    static final int MAX_BYTES = 10 * 1024 * 1024;

    private PromptImages() {
    }

    /** 没带图返回空列表。格式不对抛 {@link IllegalArgumentException}，消息直接给用户看。 */
    static List<String> parse(JsonNode cmd) {
        JsonNode list = cmd.path("images");
        if (list.isMissingNode() || list.isNull()) {
            return List.of();
        }
        if (!list.isArray()) {
            throw new IllegalArgumentException("images 要是一个数组");
        }
        if (list.size() > MAX_COUNT) {
            throw new IllegalArgumentException("一句话最多带 " + MAX_COUNT + " 张图，这次有 " + list.size() + " 张");
        }
        List<String> urls = new ArrayList<>();
        for (JsonNode img : list) {
            String mime = img.path("mime").asText("");
            String data = img.path("data").asText("");
            if (!MIME.contains(mime)) {
                throw new IllegalArgumentException("不支持的图片格式「" + mime + "」，只认 png / jpeg / gif / webp");
            }
            if (data.isEmpty() || !data.matches("[A-Za-z0-9+/=\\r\\n]+")) {
                throw new IllegalArgumentException("图片数据不是 base64");
            }
            if ((long) data.length() * 3 / 4 > MAX_BYTES) {
                throw new IllegalArgumentException("有一张图超过 " + MAX_BYTES / 1024 / 1024 + " MB，先缩小一点再贴");
            }
            urls.add("data:" + mime + ";base64," + data);
        }
        return urls;
    }
}
