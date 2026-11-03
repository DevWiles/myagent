package dev.wiles.myagent.context;

import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.prompt.PromptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 给一段会话起个短标题，界面拿它当终端标签页的标题（照 Claude Code：图标 + 摘要）。
 *
 * <p>为什么不直接用首句：首句常常是一大段话、一段贴进来的日志，截成十几个字只剩开头的客套
 * （「帮我看一下这个……」），开了好几个标签页时根本认不出哪个是哪个。让模型概括一次，
 * 代价是每段会话多一次很短的调用。
 *
 * <p>失败不要紧：标题只是锦上添花，调用挂了、返回空的，界面就继续显示默认标题。
 */
public final class SessionTitle {

    private static final Logger log = LoggerFactory.getLogger(SessionTitle.class);
    private static final PromptRepository REPO = PromptRepository.createDefault();
    private static final String PROMPT = "session-title.md";

    /** 标签页放不下更长的；模型偶尔不听话，这里再截一刀。 */
    static final int MAX_CHARS = 20;
    /** 喂给模型的首句上限。贴了一屏日志的话，前面这些足够看出要干什么。 */
    static final int MAX_INPUT = 2_000;

    private SessionTitle() {
    }

    public static Optional<String> generate(LlmClient client, String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return Optional.empty();
        }
        String input = firstMessage.length() <= MAX_INPUT ? firstMessage : firstMessage.substring(0, MAX_INPUT);
        try {
            String raw = client.temperature(0.2).maxTokens(60)
                    .chat(List.of(ChatMessage.system(REPO.loadRequired(PROMPT)), ChatMessage.user(input))).text();
            return Optional.ofNullable(clean(raw));
        } catch (RuntimeException e) {
            log.debug("会话标题生成失败，界面沿用默认标题: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 只留第一行，去掉两端的引号、书名号和句末标点，超长就截。剩不下东西返回 null。 */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.strip().lines().findFirst().orElse("").strip();
        line = line.replaceAll("^(标题[:：]\\s*)", "");
        line = line.replaceAll("^[\"'“”‘’「」『』《》]+|[\"'“”‘’「」『』《》。.!！?？]+$", "").strip();
        if (line.isEmpty()) {
            return null;
        }
        return line.codePointCount(0, line.length()) <= MAX_CHARS ? line
                : line.substring(0, line.offsetByCodePoints(0, MAX_CHARS)) + "…";
    }
}
