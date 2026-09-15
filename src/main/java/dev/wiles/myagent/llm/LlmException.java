package dev.wiles.myagent.llm;

/** 调用模型过程中的任何失败：网络、鉴权、限流、报文解析。 */
public class LlmException extends RuntimeException {

    private final int statusCode;

    public LlmException(String message) {
        this(message, 0, null);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public LlmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP 状态码；非 HTTP 错误为 0。 */
    public int statusCode() {
        return statusCode;
    }
}
