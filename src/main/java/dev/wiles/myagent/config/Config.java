package dev.wiles.myagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.context.ContextManager;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行配置。读取优先级：MYAGENT_* 环境变量 > ~/.myagent/config.json > DEEPSEEK_* 环境变量 > 内置默认值。
 *
 * <p>{@code DEEPSEEK_API_KEY} 这类名字是好几个工具共用的，终端里常常留着别的工具设的值
 * （比如一把 DeepSeek 官方平台的 key）。原来它压在配置文件之上，配置文件里写着百炼的 key 和地址，
 * 实际发出去的却是那把官方 key，百炼回 401。所以通用名字降到配置文件之后，
 * 只在配置文件没写时兜底；要临时覆盖配置文件，用 myagent 自己的 {@code MYAGENT_API_KEY} 等。
 *
 * config.json 形如：
 * { "apiKey": "sk-xxx", "baseUrl": "https://api.deepseek.com", "model": "deepseek-flash" }
 *
 * <p>没有「便利构造器」是故意的：少一个字段就默默退回默认值，是这种配置最容易出的事故。
 * 加字段时让所有构造点都编译不过，比让它们悄悄丢掉一个旋钮好。
 */
/**
 * @param thinking 思考开关。{@code null} 表示不碰（请求里不带 enable_thinking，服务端按默认来）；
 *                 {@code false} 关掉——百炼上的 deepseek-v4-flash / qwen 默认开思考，
 *                 每步多几秒、多一截 reasoning_tokens，评测和日常用都不需要。
 *                 只在显式配置时才发字段：DeepSeek 官方接口不认这个字段，乱发会 400
 */
public record Config(String apiKey, String baseUrl, String model, int contextTokens, Boolean thinking,
                     int snapshotDays) {

    /** 检查点默认保留 30 天，和 Claude Code 的 cleanupPeriodDays 一个数。 */
    public static final int DEFAULT_SNAPSHOT_DAYS = 30;

    /** 老签名：不碰思考开关。 */
    public Config(String apiKey, String baseUrl, String model, int contextTokens) {
        this(apiKey, baseUrl, model, contextTokens, null, DEFAULT_SNAPSHOT_DAYS);
    }

    public Config(String apiKey, String baseUrl, String model, int contextTokens, Boolean thinking) {
        this(apiKey, baseUrl, model, contextTokens, thinking, DEFAULT_SNAPSHOT_DAYS);
    }

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    /**
     * 默认模型。
     *
     * <p>曾经是 {@code deepseek-chat}，那个名字已于 2026-07-24 退役。
     * 它现在还「能用」只是因为服务端把请求<b>静默路由</b>到了新模型——
     * 拿它发一次请求，响应里的 {@code model} 字段回的是 {@code deepseek-flash}。
     * 靠路由续命的名字总有一天会撤，而那天的现象是「昨天还好好的，今天全报错」。
     */
    public static final String DEFAULT_MODEL = "deepseek-flash";

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".myagent", "config.json");

    public static Config load() {
        JsonNode file = readConfigFile();
        return new Config(
                pick(System.getenv("MYAGENT_API_KEY"), text(file, "apiKey"),
                        pick(System.getenv("DEEPSEEK_API_KEY"), null, null)),
                pick(System.getenv("MYAGENT_BASE_URL"), text(file, "baseUrl"),
                        pick(System.getenv("DEEPSEEK_BASE_URL"), null, DEFAULT_BASE_URL)),
                pick(System.getenv("MYAGENT_MODEL"), text(file, "model"),
                        pick(System.getenv("DEEPSEEK_MODEL"), null, DEFAULT_MODEL)),
                contextTokens(pick(System.getenv("MYAGENT_CONTEXT_TOKENS"),
                        text(file, "contextTokens"), null)),
                thinking(pick(System.getenv("MYAGENT_THINKING"), text(file, "thinking"), null)),
                snapshotDays(pick(System.getenv("MYAGENT_SNAPSHOT_DAYS"), text(file, "snapshotDays"), null)));
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

    /** "true"/"false"（大小写不限）之外的值一律当没配：宁可多想一会儿，也别因为拼错关掉思考还不报错。 */
    static Boolean thinking(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.strip().toLowerCase();
        if (v.equals("true") || v.equals("false")) {
            return Boolean.valueOf(v);
        }
        System.err.println("警告: thinking \"" + raw + "\" 不是 true/false，已忽略");
        return null;
    }

    /** 检查点保留天数；不是正整数就按默认值。 */
    static int snapshotDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SNAPSHOT_DAYS;
        }
        try {
            int v = Integer.parseInt(raw.strip());
            if (v >= 1) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // 走下面的警告
        }
        System.err.println("警告: snapshotDays \"" + raw + "\" 不是正整数，已按默认值 " + DEFAULT_SNAPSHOT_DAYS + " 处理");
        return DEFAULT_SNAPSHOT_DAYS;
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
                + ", contextTokens=" + contextTokens
                + (thinking == null ? "" : ", thinking=" + thinking) + "]";
    }
}
