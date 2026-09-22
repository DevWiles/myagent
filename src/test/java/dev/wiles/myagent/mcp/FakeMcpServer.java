package dev.wiles.myagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 测试用的假 MCP server：两根管子 + 一个应答函数，不 spawn 任何进程。
 *
 * <p>McpClient 有一个包私有构造器直接收流，这个类就是那对流的另一头。
 * 应答函数拿到 client 发来的每条消息（含通知），回一串要写回去的行——
 * 回空串列表就是已读不回（测超时用），一条请求回多行可以夹带通知（测忽略逻辑）。
 */
final class FakeMcpServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PipedOutputStream toClient = new PipedOutputStream();
    private final PipedInputStream clientIn;
    private final PipedInputStream serverIn = new PipedInputStream(64 * 1024);
    private final PipedOutputStream clientOut;
    private final Thread loop;

    /** client 发来的所有消息，按到达顺序。断言握手顺序靠它。 */
    final List<JsonNode> received = new CopyOnWriteArrayList<>();

    FakeMcpServer(Function<JsonNode, List<String>> replies) throws IOException {
        this.clientIn = new PipedInputStream(toClient, 64 * 1024);
        this.clientOut = new PipedOutputStream(serverIn);
        this.loop = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(serverIn, StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    JsonNode message = MAPPER.readTree(line);
                    received.add(message);
                    for (String reply : replies.apply(message)) {
                        toClient.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                        toClient.flush();
                    }
                }
            } catch (IOException ignored) {
                // 管子被测试关掉了，正常收场
            }
        }, "fake-mcp-server");
        loop.setDaemon(true);
        loop.start();
    }

    /** 造一个连着这台假 server 的 client。 */
    McpClient client(long timeoutMillis) {
        return new McpClient("fake", clientIn, clientOut, timeoutMillis);
    }

    /** 模拟 server 猝死：关掉对 client 的输出流，client 的读循环会到头。 */
    void die() throws IOException {
        toClient.close();
    }

    @Override
    public void close() throws IOException {
        toClient.close();
        clientOut.close();
    }

    /** 造一条 JSON-RPC result 响应。 */
    static String result(long id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
    }

    /** 造一条 JSON-RPC error 响应。 */
    static String error(long id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + message + "\"}}";
    }
}
