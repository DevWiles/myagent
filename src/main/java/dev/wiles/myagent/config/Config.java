package dev.wiles.myagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.context.ContextManager;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行配置。读取优先级：环境变量 > ~/.myagent/config.json > 内置默认值。
 *
 * config.json 形如：
 * { "apiKey": "sk-xxx", "baseUrl": "https://api.deepseek.com", "model": "deepseek-chat" }
 *
 * <p>没有「便利构造器」是故意的：少一个字段就默默退回默认值，是这种配置最容易出的事故。
 * 加字段时让所有构造点都编译不过，比让它们悄悄丢掉一个旋钮好。
 */
public record Config(String apiKey, String baseUrl, String model, int contextTokens) {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".myagent", "config.json");

    public static Config load() {
        JsonNode file = readConfigFile();
        return new Config(
                pick(System.getenv("DEEPSEEK_API_KEY"), text(file, "apiKey"), null),
                pick(System.getenv("DEEPSEEK_BASE_URL"), text(file, "baseUrl"), DEFAULT_BASE_URL),
                pick(System.getenv("DEEPSEEK_MODEL"), text(file, "model"), DEFAULT_MODEL),
                contextTokens(pick(System.getenv("MYAGENT_CONTEXT_TOKENS"),
                        text(file, "contextTokens"), null)));
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

    /**
     * 解析上下文窗口大小。<b>配错了退回默认值，绝不抛</b>——
     * 为一个可选旋钮让整个 agent 起不来，比忽略它糟糕得多。
     *
     * <p>低于 {@link ContextManager#MIN_MAX_TOKENS} 的值会被抬上去并告警：
     * 那种值不会报错，只会让会话每轮都在压缩、什么都记不住，
     * 症状和「模型变笨了」一模一样，极难往配置上想。
     */
    static int contextTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return ContextManager.DEFAULT_MAX_TOKENS;
        }
        int value;
        try {
            value = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            System.err.println("警告: 上下文窗口 \"" + raw + "\" 不是整数，已按默认值 "
                    + ContextManager.DEFAULT_MAX_TOKENS + " 处理");
            return ContextManager.DEFAULT_MAX_TOKENS;
        }
        if (value < ContextManager.MIN_MAX_TOKENS) {
            System.err.println("警告: 上下文窗口 " + value + " 太小，已抬到下限 "
                    + ContextManager.MIN_MAX_TOKENS + "——再小会导致每轮都在压缩");
            return ContextManager.MIN_MAX_TOKENS;
        }
        return value;
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
        return "Config[apiKey=" + masked + ", baseUrl=" + baseUrl + ", model=" + model
                + ", contextTokens=" + contextTokens + "]";
    }
}
