package dev.wiles.myagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行配置。读取优先级：环境变量 > ~/.myagent/config.json > 内置默认值。
 *
 * config.json 形如：
 * { "apiKey": "sk-xxx", "baseUrl": "https://api.deepseek.com", "model": "deepseek-chat" }
 */
public record Config(String apiKey, String baseUrl, String model) {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".myagent", "config.json");

    public static Config load() {
        JsonNode file = readConfigFile();
        return new Config(
                pick(System.getenv("DEEPSEEK_API_KEY"), text(file, "apiKey"), null),
                pick(System.getenv("DEEPSEEK_BASE_URL"), text(file, "baseUrl"), DEFAULT_BASE_URL),
                pick(System.getenv("DEEPSEEK_MODEL"), text(file, "model"), DEFAULT_MODEL));
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 拼接成 /chat/completions 的完整地址，兼容 baseUrl 末尾带不带斜杠。 */
    public String chatCompletionsUrl() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/chat/completions";
    }

    private static JsonNode readConfigFile() {
        if (!Files.isReadable(CONFIG_FILE)) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(Files.readString(CONFIG_FILE));
        } catch (Exception e) {
            System.err.println("警告: 读取 " + CONFIG_FILE + " 失败，已忽略: " + e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? null : node.get(field).asText();
    }

    private static String pick(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    /** toString 脱敏，避免密钥被日志打出去。 */
    @Override
    public String toString() {
        String masked = !hasApiKey() ? "<未配置>"
                : apiKey.length() <= 8 ? "***"
                : apiKey.substring(0, 6) + "..." + apiKey.substring(apiKey.length() - 4);
        return "Config[apiKey=" + masked + ", baseUrl=" + baseUrl + ", model=" + model + "]";
    }
}
