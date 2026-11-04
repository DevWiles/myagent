package dev.wiles.myagent.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 装配一份提示词要用到的那些随场景变化的东西。
 *
 * <p>和 {@link PromptRepository} 里那些固定文本相对：模板是恒定的，
 * 这里装的是每次都不一样的部分——工作区在哪、这一步是什么类型、有没有记忆索引。
 *
 * <p>做成 builder 而不是长参数列表，是因为它会继续长（将来还要加审批策略、
 * 技能索引），而八个参数的构造器在调用点上没人看得出哪个是哪个。
 */
public final class PromptContext {

    private final Map<String, String> variables;
    private final String memoryIndex;
    private final boolean readOnly;

    private PromptContext(Builder b) {
        this.variables = Map.copyOf(b.variables);
        this.memoryIndex = b.memoryIndex;
        this.readOnly = b.readOnly;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 什么都没有的上下文。模板里如果还有占位符，装配会当场抛——见 PromptAssembler。 */
    public static PromptContext empty() {
        return builder().build();
    }

    public Map<String, String> variables() {
        return variables;
    }

    /** 长期记忆索引，拼在最<b>后</b>；没有就是 null。 */
    public String memoryIndex() {
        return memoryIndex;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public static final class Builder {
        private final Map<String, String> variables = new LinkedHashMap<>();
        private String memoryIndex;
        private boolean readOnly;

        /** 填一个 {@code {{名字}}} 占位符。值为 null 按空串算。 */
        public Builder variable(String name, String value) {
            variables.put(name, value == null ? "" : value);
            return this;
        }

        public Builder memoryIndex(String rendered) {
            this.memoryIndex = rendered;
            return this;
        }

        /** 只读模式要<b>明说</b>，不能靠模型从「菜单上没有写工具」自己推断。 */
        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public PromptContext build() {
            return new PromptContext(this);
        }
    }
}
