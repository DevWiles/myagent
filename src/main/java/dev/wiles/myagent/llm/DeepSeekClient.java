package dev.wiles.myagent.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.config.Config;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * DeepSeek 的 LlmClient 实现。
 *
 * DeepSeek 用的是 OpenAI 兼容协议，所以这份实现换个 baseUrl/model 就能对接
 * Kimi、通义、本地 Ollama 等一大票服务，不必改代码。
 */
public final class DeepSeekClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final MediaType JSON = MediaType.get("application/json");

    /** 限流和服务端抖动重试几次，其余错误立刻抛出。 */
    private static final int MAX_RETRIES = 2;

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Config config;
    private final OkHttpClient http;

    private Double temperature;
    private Integer maxTokens;
    /** 开了就在请求里带上 response_format，让服务端保证输出是合法 JSON。 */
    private boolean jsonMode;
    /** 正在进行的请求，供 cancel() 掐断。 */
    private volatile Call currentCall;

    public DeepSeekClient(Config config) {
        this(config, defaultHttpClient());
    }

    public DeepSeekClient(Config config, OkHttpClient http) {
        if (!config.hasApiKey()) {
            throw new LlmException("未配置 API key：请设置环境变量 DEEPSEEK_API_KEY，"
                    + "或在 ~/.myagent/config.json 里写 apiKey");
        }
        this.config = config;
        this.http = http;
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .writeTimeout(Duration.ofSeconds(30))
                // 模型出长回答可能要一两分钟，读超时必须给足
                .readTimeout(Duration.ofMinutes(5))
                .build();
    }

    @Override
    public DeepSeekClient temperature(double value) {
        this.temperature = value;
        return this;
    }

    @Override
    public DeepSeekClient maxTokens(int value) {
        this.maxTokens = value;
        return this;
    }

    @Override
    public DeepSeekClient jsonMode(boolean on) {
        this.jsonMode = on;
        return this;
    }

    /**
     * 开了 JSON 模式才带这个字段。
     *
     * <p>不能无条件带上 {@code {"type":"text"}}：有些 OpenAI 兼容网关不认这个取值，
     * 会直接 400。原则和 {@code tools} 字段一样——<b>没要用的功能就别出现在报文里</b>。
     */
    private ResponseFormat responseFormat() {
        return jsonMode ? ResponseFormat.JSON_OBJECT : null;
    }

    @Override
    public String modelName() {
        return config.model();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        boolean withTools = tools != null && !tools.isEmpty();

        ChatRequest payload = new ChatRequest(
                config.model(),
                messages,
                withTools ? tools : null,           // 空数组会被部分网关判为非法，必须省略
                withTools ? "auto" : null,          // auto：由模型自行决定要不要调工具
                temperature,
                maxTokens,
                false,                              // 流式输出留到后面再做
                responseFormat());

        String body = writeJson(payload);
        log.debug("-> {} ({} 条消息, {} 个工具)", config.chatCompletionsUrl(), messages.size(),
                withTools ? tools.size() : 0);

        String responseBody = execute(body);
        ChatResponse response = readJson(responseBody);

        if (log.isDebugEnabled() && response.usage() != null) {
            log.debug("<- finish_reason={} tokens: prompt={} completion={}",
                    response.finishReason(),
                    response.usage().promptTokens(),
                    response.usage().completionTokens());
        }
        return response;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools,
                             StreamListener listener) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        boolean withTools = tools != null && !tools.isEmpty();
        ChatRequest payload = new ChatRequest(
                config.model(), messages,
                withTools ? tools : null,
                withTools ? "auto" : null,
                temperature, maxTokens,
                true,                               // 唯一的区别就是这个 true
                responseFormat());

        String body = writeJson(payload);
        log.debug("-> {} (流式, {} 条消息, {} 个工具)", config.chatCompletionsUrl(),
                messages.size(), withTools ? tools.size() : 0);

        Request request = new Request.Builder()
                .url(config.chatCompletionsUrl())
                .addHeader("Authorization", "Bearer " + config.apiKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(body, JSON))
                .build();

        // 流式不做重试：内容可能已经打到用户屏幕上了，重来一次会把前半段重复输出一遍。
        // 失败就抛出去，由 Agent 统一回滚本轮对话。
        Call call = http.newCall(request);
        currentCall = call;
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                ResponseBody rb = response.body();
                String text = rb == null ? "" : rb.string();
                throw new LlmException(explain(response.code()) + " (HTTP " + response.code()
                        + "): " + brief(text), response.code(), null);
            }
            ResponseBody rb = response.body();
            if (rb == null) {
                throw new LlmException("流式响应没有 body");
            }
            return readEventStream(rb.charStream(), listener);
        } catch (IOException e) {
            if (call.isCanceled()) {
                throw new LlmException("请求已取消", e);
            }
            throw new LlmException("流式请求失败: " + e.getMessage(), e);
        } finally {
            currentCall = null;
        }
    }

    @Override
    public void cancel() {
        Call call = currentCall;
        if (call != null) {
            call.cancel();      // 正阻塞在读响应的线程会立刻抛 IOException 出来
        }
    }

    /**
     * 解析 SSE（Server-Sent Events）。
     *
     * 格式很简单：每行 "data: {json}"，空行分隔，最后一行是 "data: [DONE]"。
     * 注意不能用 body.string() 一次读完——那样就退化成非流式，
     * 必须按行读，读到一行处理一行。
     */
    private ChatResponse readEventStream(java.io.Reader source, StreamListener listener)
            throws IOException {
        StreamAccumulator accumulator = new StreamAccumulator();
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;                       // 空行和 event:/id: 之类一概忽略
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                try {
                    accumulator.accept(mapper.readTree(payload), listener);
                } catch (Exception e) {
                    log.warn("跳过无法解析的流式分片: {}", brief(payload));
                }
            }
        }
        return accumulator.toResponse();
    }

    /** 发请求并返回响应体字符串，内含有限次重试。 */
    private String execute(String jsonBody) {
        IOException lastIoError = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                sleepBackoff(attempt);
                log.warn("重试第 {} 次", attempt);
            }

            Request request = new Request.Builder()
                    .url(config.chatCompletionsUrl())
                    .addHeader("Authorization", "Bearer " + config.apiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            Call call = http.newCall(request);
            currentCall = call;
            try (Response response = call.execute()) {
                ResponseBody rb = response.body();
                String text = rb == null ? "" : rb.string();

                if (response.isSuccessful()) {
                    return text;
                }
                if (isRetryable(response.code()) && attempt < MAX_RETRIES) {
                    log.warn("HTTP {}，准备重试: {}", response.code(), brief(text));
                    continue;
                }
                throw new LlmException(explain(response.code()) + " (HTTP " + response.code() + "): "
                        + brief(text), response.code(), null);

            } catch (IOException e) {
                lastIoError = e;
                if (attempt < MAX_RETRIES) {
                    log.warn("网络异常，准备重试: {}", e.getMessage());
                    continue;
                }
            }
        }
        throw new LlmException("请求模型失败: " + lastIoError.getMessage(), lastIoError);
    }

    private static boolean isRetryable(int code) {
        return code == 429 || code >= 500;
    }

    private static String explain(int code) {
        return switch (code) {
            case 401 -> "鉴权失败，检查 API key";
            case 402 -> "账户余额不足";
            case 422 -> "请求参数非法";
            case 429 -> "触发限流";
            default -> code >= 500 ? "服务端错误" : "请求失败";
        };
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(1000L * (1L << (attempt - 1)));   // 1s, 2s
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException("请求被中断", ie);
        }
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...(已截断)";
    }

    private String writeJson(ChatRequest payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new LlmException("序列化请求失败", e);
        }
    }

    private ChatResponse readJson(String body) {
        try {
            return mapper.readValue(body, ChatResponse.class);
        } catch (Exception e) {
            throw new LlmException("解析模型响应失败: " + brief(body), e);
        }
    }
}
