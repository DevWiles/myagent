package dev.wiles.myagent.llm;

/**
 * 流式输出的回调。
 *
 * 模型一个字一个字往外吐的时候，调用方靠它把内容实时打到屏幕上，
 * 而不是等整段生成完再一次性蹦出来——后者在长回答时要干等十几秒。
 */
@FunctionalInterface
public interface StreamListener {

    /** 收到一小段新文本。注意是增量，不是全文。 */
    void onTextDelta(String delta);

    /** 模型开始生成某个工具调用。此时参数还没传完，只有名字可用。 */
    default void onToolCallStarted(String toolName) {
    }

    /** 什么都不做，用于不关心过程的调用方。 */
    static StreamListener noop() {
        return delta -> {
        };
    }
}
