package dev.wiles.myagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条到 MCP server 的连接。JSON-RPC 2.0，stdio 传输，一行一条消息。
 *
 * <h3>为什么自己写而不是引官方 SDK</h3>
 * 和 HTML 转正文、SSE 解析是同一条项目纪律（依赖只留 Jackson/OkHttp/slf4j），
 * 而且这个轮子同样出乎意料地小：stdio 版的 MCP 就是「往子进程 stdin 写一行 JSON、
 * 从 stdout 读一行 JSON、用 id 配对」。握手三步 + 两个方法（tools/list、tools/call），
 * 全部协议知识就这么多。
 *
 * <h3>读写模型</h3>
 * 一个后台线程独占读 stdout，按 id 把响应派发给等着的 future；
 * 请求方写一行然后阻塞在自己的 future 上（带超时）。
 * 这比「请求方自己读下一行」稳：server 有权在任何时刻插进来一条通知
 * 或者乱序回复，靠「下一行一定是我的回复」的假设迟早翻车。
 *
 * <h3>stderr 必须有人读</h3>
 * MCP 约定 stderr 是 server 的日志通道。没人读的话管道缓冲区（64K）一满，
 * server 的下一次日志写入就阻塞——表现是「工具调着调着永远卡住」，
 * 和协议、和我们的代码都毫无关系，极难往 stderr 上想。所以 spawn 版
 * 必须起一个专门的线程把它排干（内容进 debug 日志，平时没人看见）。
 */
public final class McpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);

    /** 我们说的协议版本。server 报别的版本也接着用——v1 不做版本谈判，只留日志。 */
    static final String PROTOCOL_VERSION = "2024-11-05";

    /** 单次请求默认等多久。工具真在干活（查询、下载）时一分钟不算多。 */
    static final long DEFAULT_TIMEOUT_MILLIS = 60_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String name;
    private final OutputStream out;
    private final Process process;           // 管道版测试为 null
    private final long timeoutMillis;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Thread reader;
    private volatile boolean closed;

    /** 拉起一个真的 server 子进程。 */
    public static McpClient spawn(String name, McpConfig.ServerSpec spec) throws IOException {
        ProcessBuilder builder = new ProcessBuilder();
        List<String> command = new ArrayList<>();
        command.add(spec.command());
        command.addAll(spec.args());
        builder.command(command);
        builder.environment().putAll(spec.env());
        Process process = builder.start();

        // 排干 stderr（理由见类注释）。daemon：它不该拦着 JVM 退出
        Thread drainer = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.debug("[mcp:{}] {}", name, line);
                }
            } catch (IOException ignored) {
                // 进程没了，排水工也就下班了
            }
        }, "mcp-stderr-" + name);
        drainer.setDaemon(true);
        drainer.start();

        return new McpClient(name, process.getInputStream(), process.getOutputStream(),
                process, DEFAULT_TIMEOUT_MILLIS);
    }

    /** 测试用：直接给两根管子，不 spawn 进程，超时可调小。 */
    McpClient(String name, InputStream in, OutputStream out, long timeoutMillis) {
        this(name, in, out, null, timeoutMillis);
    }

    private McpClient(String name, InputStream in, OutputStream out,
                      Process process, long timeoutMillis) {
        this.name = name;
        this.out = out;
        this.process = process;
        this.timeoutMillis = timeoutMillis;
        this.reader = new Thread(() -> readLoop(in), "mcp-read-" + name);
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public String name() {
        return name;
    }

    /** 读循环：一行一条消息，按 id 配对。不认识的消息（通知、server 发起的请求）先忽略。 */
    private void readLoop(InputStream in) {
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                dispatch(line);
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("MCP server {} 的输出流断了: {}", name, e.getMessage());
            }
        }
        // 流到头 = server 没了。所有还在等的请求都不会有下文了，统一叫醒
        failAllPending("MCP server " + name + " 已退出");
    }

    private void dispatch(String line) {
        JsonNode message;
        try {
            message = mapper.readTree(line);
        } catch (Exception e) {
            log.warn("MCP server {} 发来一行看不懂的东西，已忽略: {}", name, brief(line));
            return;
        }
        JsonNode id = message.get("id");
        if (id == null || !id.canConvertToLong()) {
            // 没有 id 的是通知（进度、日志），有 method 有 id 的是 server 发起的请求
            //（sampling 之类）——v1 都不支持，静静跳过，别让它们卡住谁
            return;
        }
        CompletableFuture<JsonNode> waiter = pending.remove(id.asLong());
        if (waiter == null) {
            return;                          // 超时后才回来的迟到响应，没人等了
        }
        JsonNode error = message.get("error");
        if (error != null) {
            waiter.completeExceptionally(new McpException(
                    error.path("message").asText("server 报错") + " (code "
                            + error.path("code").asInt() + ")"));
        } else {
            waiter.complete(message.path("result"));
        }
    }

    private void failAllPending(String reason) {
        for (CompletableFuture<JsonNode> waiter : pending.values()) {
            waiter.completeExceptionally(new McpException(reason));
        }
        pending.clear();
    }

    /** 发一个请求并等结果。超时抛 McpException，但**不注销连接**——迟到的响应会被丢弃。 */
    JsonNode request(String method, JsonNode params) {
        long id = nextId.getAndIncrement();
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        CompletableFuture<JsonNode> waiter = new CompletableFuture<>();
        pending.put(id, waiter);
        try {
            writeLine(message);
            return waiter.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException(method + " 等了 " + timeoutMillis / 1000 + " 秒没有回音");
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new McpException(method + " 被中断");
        } catch (Exception e) {
            pending.remove(id);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof McpException me ? me : new McpException(cause.getMessage());
        }
    }

    private void notification(String method) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        writeLine(message);
    }

    /** 写要上锁：多个工具并发调用同一个 server 时，两行 JSON 交叉写出去谁都解不开。 */
    private synchronized void writeLine(ObjectNode message) {
        try {
            out.write((mapper.writeValueAsString(message) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new McpException("写给 MCP server " + name + " 失败: " + e.getMessage());
        }
    }

    /** 握手：initialize → 收 serverInfo → 发 initialized 通知。做完才许调别的。 */
    public void initialize() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "myagent");
        clientInfo.put("version", "0.1");
        params.set("clientInfo", clientInfo);

        JsonNode result = request("initialize", params);
        String serverVersion = result.path("protocolVersion").asText("");
        if (!PROTOCOL_VERSION.equals(serverVersion)) {
            // 不同版本先接着用：tools/list 和 tools/call 是协议里最稳的两块地
            log.info("MCP server {} 用的协议版本是 {}（我们说 {}），先按能用处理",
                    name, serverVersion, PROTOCOL_VERSION);
        }
        notification("notifications/initialized");
    }

    /** server 上有哪些工具。带翻页——大多数 server 一页就完，但翻页是协议的一部分。 */
    public List<RemoteTool> listTools() {
        List<RemoteTool> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", cursor == null ? null : params);
            for (JsonNode tool : result.path("tools")) {
                tools.add(new RemoteTool(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        tool.get("inputSchema")));
            }
            JsonNode next = result.get("nextCursor");
            cursor = next == null || next.isNull() ? null : next.asText();
        } while (cursor != null && !cursor.isEmpty());
        return tools;
    }

    /** server 端一个工具的原样描述。schema 可能为 null（server 没给）。 */
    public record RemoteTool(String name, String description, JsonNode inputSchema) {
    }

    /**
     * 调 server 上的一个工具。
     *
     * @return content 里的文本拼起来的结果；非文本块（图片、资源）用占位符注明——
     *         丢弃它们可以，<b>不吭声地丢弃</b>不行，模型得知道自己少看了什么
     */
    public CallResult callTool(String toolName, JsonNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        JsonNode result = request("tools/call", params);

        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            switch (block.path("type").asText()) {
                case "text" -> text.append(block.path("text").asText());
                case "image" -> text.append("[图片，").append(block.path("mimeType").asText("?"))
                        .append("，这个通道看不了]");
                case "resource" -> text.append("[资源: ")
                        .append(block.path("resource").path("uri").asText("?")).append(']');
                default -> text.append("[").append(block.path("type").asText("未知")).append(" 内容]");
            }
        }
        return new CallResult(result.path("isError").asBoolean(false), text.toString());
    }

    /** isError 是**工具级**失败（查无此人、参数不对），不是协议失败——两种都要交给模型。 */
    public record CallResult(boolean isError, String text) {
    }

    @Override
    public void close() {
        closed = true;
        failAllPending("连接已关闭");
        if (process != null) {
            process.destroy();               // 先客气；子进程该在 stdin 关闭时自己退
        }
        reader.interrupt();
    }

    /** 协议层面的失败：超时、进程没了、server 回了 error。都会变成给模型的反馈。 */
    public static final class McpException extends RuntimeException {
        McpException(String message) {
            super(message);
        }
    }

    private static String brief(String line) {
        return line.length() <= 200 ? line : line.substring(0, 200) + "…";
    }
}
