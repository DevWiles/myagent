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

    /** 网络抖动和 5xx 重试几次，其余错误立刻抛出。 */
    private static final int MAX_RETRIES = 2;

    /**
     * 429 单独计数，而且等得久得多。
     *
     * <h3>为什么不能和 5xx 共用「1s、2s 两次」</h3>
     * 百炼的限流按<b>分钟</b>算 token（报的是 {@code insufficient_quota}，文档锚点是 token-limit），
     * 等 3 秒窗口根本没滚过去，两次重试必然原样再撞一次，整轮就此作废。
     * AsynCodeBench cookiecutter 上，单 agent 和 4 个并行 worker 全是这么死的，
     * 中间那次流水线没撞上反而跑完了——限流成了「哪种模式赢」的决定因素，结论全被污染。
     *
     * <p>有 {@code Retry-After} 就听它的（封顶 {@link #RATE_LIMIT_MAX_WAIT_MILLIS}），
     * 没有就 5、10、20、40、60、60 秒退避，合计三分多钟——足够一个分钟窗口滚过去两三次，
     * 真是额度用完了也不过是多等三分钟再报错。等待期间照样每 50ms 看一眼按停。
     */
    static final int MAX_RATE_LIMIT_RETRIES = 6;
    static final long RATE_LIMIT_BASE_WAIT_MILLIS = 5_000;
    static final long RATE_LIMIT_MAX_WAIT_MILLIS = 60_000;

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

    /**
     * 按过几次停。每次 chat 开始时记下当时的号（ticket），之后每一段退避、每次发请求前都对一下：
     * 号变了就是这次 chat 期间有人按了停。
     *
     * <p>光靠 {@link #currentCall} 不够：重试之间要睡 1–2 秒，那时它是 null，
     * cancel 掐了个空，醒来照样发下一次、白等一整次回答。也不用「置标志、下次清掉」：
     * 客户端跨轮复用，标志在哪儿清都有窗口——清早了吞掉按停，清晚了连累下一轮。
     * 号只增不减，每次 chat 跟自己开始时那个号比，两头都不会错。
     */
    private final java.util.concurrent.atomic.AtomicInteger cancels =
            new java.util.concurrent.atomic.AtomicInteger();

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
                // 两个 SSE 分片之间最多等多久。百炼上十几万 token 的上下文首 token 能等好几分钟，
                // 5 分钟在 AsynCodeBench 的验收席上真掐断过一次（整场落成 PARTIAL）
                .readTimeout(Duration.ofMinutes(10))
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
                null,                               // 非流式的响应本来就带 usage
                responseFormat(),
                config.thinking());

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
                StreamOptions.INCLUDE_USAGE,        // 不给这个字段，流式就一个 token 数都不回
                responseFormat(),
                config.thinking());

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

        // 流式只重试**收到响应头之前**的失败：TLS 握手被掐、连接被重置、限流 / 5xx——
        // 这时一个字都还没打到用户屏幕上，重来不会重复输出。
        // 读流途中断了不重试：前半段已经出去了，由 Agent 统一把这一轮封口。
        int ticket = cancels.get();
        int attempt = 0;            // 网络 / 5xx 的重试次数
        int rateLimited = 0;        // 429 的重试次数，单独计
        long waitMillis = 0;
        for (int tries = 0; ; tries++) {
            if (tries > 0) {
                sleepMillis(waitMillis, ticket);
                log.warn("流式请求重试第 {} 次", tries);
            }
            Call call = http.newCall(request);
            currentCall = call;
            if (cancels.get() != ticket) {
                call.cancel();       // 按停赶在 currentCall 赋值之前到了，cancel() 掐了个空，这里补上
            }
            Response response;
            try {
                response = call.execute();
            } catch (IOException e) {
                currentCall = null;
                if (call.isCanceled()) {
                    throw new LlmException("请求已取消", e);
                }
                if (attempt < MAX_RETRIES) {
                    attempt++;
                    waitMillis = quickBackoff(attempt);
                    log.warn("流式请求还没拿到响应就失败，准备重试: {}", e.getMessage());
                    continue;
                }
                throw new LlmException("流式请求失败: " + e.getMessage(), e);
            }
            try (response) {
                if (!response.isSuccessful()) {
                    ResponseBody rb = response.body();
                    String text = rb == null ? "" : rb.string();
                    if (response.code() == 429 && rateLimited < MAX_RATE_LIMIT_RETRIES) {
                        rateLimited++;
                        waitMillis = rateLimitWait(rateLimited, response.header("Retry-After"));
                        log.warn("HTTP 429，{} 秒后第 {} 次重试: {}", waitMillis / 1000, rateLimited, brief(text));
                        continue;
                    }
                    if (response.code() >= 500 && attempt < MAX_RETRIES) {
                        attempt++;
                        waitMillis = quickBackoff(attempt);
                        log.warn("HTTP {}，准备重试: {}", response.code(), brief(text));
                        continue;
                    }
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
    }

    @Override
    public void cancel() {
        cancels.incrementAndGet();
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
        int ticket = cancels.get();
        int attempt = 0;            // 网络 / 5xx 的重试次数
        int rateLimited = 0;        // 429 的重试次数，单独计，理由见 MAX_RATE_LIMIT_RETRIES
        long waitMillis = 0;

        for (int tries = 0; ; tries++) {
            if (tries > 0) {
                sleepMillis(waitMillis, ticket);
                log.warn("重试第 {} 次", tries);
            }

            Request request = new Request.Builder()
                    .url(config.chatCompletionsUrl())
                    .addHeader("Authorization", "Bearer " + config.apiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            Call call = http.newCall(request);
            currentCall = call;
            if (cancels.get() != ticket) {
                call.cancel();
            }
            try (Response response = call.execute()) {
                ResponseBody rb = response.body();
                String text = rb == null ? "" : rb.string();

                if (response.isSuccessful()) {
                    return text;
                }
                if (response.code() == 429 && rateLimited < MAX_RATE_LIMIT_RETRIES) {
                    rateLimited++;
                    waitMillis = rateLimitWait(rateLimited, response.header("Retry-After"));
                    log.warn("HTTP 429，{} 秒后第 {} 次重试: {}", waitMillis / 1000, rateLimited, brief(text));
                    continue;
                }
                if (response.code() >= 500 && attempt < MAX_RETRIES) {
                    attempt++;
                    waitMillis = quickBackoff(attempt);
                    log.warn("HTTP {}，准备重试: {}", response.code(), brief(text));
                    continue;
                }
                throw new LlmException(explain(response.code()) + " (HTTP " + response.code() + "): "
                        + brief(text), response.code(), null);

            } catch (IOException e) {
                if (call.isCanceled()) {
                    // 原来这里不分辨：被掐断的请求也当网络错误，睡一觉再发一次
                    throw new LlmException("请求已取消", e);
                }
                if (attempt < MAX_RETRIES) {
                    attempt++;
                    waitMillis = quickBackoff(attempt);
                    log.warn("网络异常，准备重试: {}", e.getMessage());
                    continue;
                }
                throw new LlmException("请求模型失败: " + e.getMessage(), e);
            }
        }
    }

    /** 网络 / 5xx：1s、2s。 */
    private static long quickBackoff(int attempt) {
        return 1000L << (attempt - 1);
    }

    /**
     * 429 等多久：服务端给了 {@code Retry-After}（秒）就听它的，否则 5s 起翻倍；都封顶 60s。
     * {@code Retry-After} 也可能是 HTTP 日期——那种写法不认，按退避走，宁可多等一会儿也不解析错。
     */
    static long rateLimitWait(int retry, String retryAfter) {
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                return Math.min(RATE_LIMIT_MAX_WAIT_MILLIS, Math.max(1_000, seconds * 1000));
            } catch (NumberFormatException ignored) {
                // 日期格式，见上
            }
        }
        long wait = RATE_LIMIT_BASE_WAIT_MILLIS << Math.min(retry - 1, 10);
        return Math.min(RATE_LIMIT_MAX_WAIT_MILLIS, wait);
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

    /** 睡这么久，切成小段睡，每段醒来看一眼有没有人按停。 */
    private void sleepMillis(long millis, int ticket) {
        long end = System.nanoTime() + millis * 1_000_000L;
        try {
            while (true) {
                if (cancels.get() != ticket) {
                    throw new LlmException("请求已取消");
                }
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return;
                }
                Thread.sleep(Math.min(50, left));
            }
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
