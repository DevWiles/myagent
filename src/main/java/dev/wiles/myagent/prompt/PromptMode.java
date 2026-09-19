package dev.wiles.myagent.prompt;

/**
 * 这一份提示词是给谁用的。
 *
 * <p>一个模式一个文件，而不是在一段大字符串里用 if-else 拼。前者改哪个模式
 * 就只碰哪个文件，diff 一眼能看懂；后者改一处要在脑子里把所有分支跑一遍，
 * 而且很容易出现「本该只影响规划的改动，把主循环也带歪了」。
 */
public enum PromptMode {

    /** 主循环：用户直接对话的那个。 */
    AGENT("modes/agent.md"),

    /** 计划里的一步：只干这一步，不越界。 */
    TASK("modes/task.md");

    private final String resourcePath;

    PromptMode(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String resourcePath() {
        return resourcePath;
    }
}
