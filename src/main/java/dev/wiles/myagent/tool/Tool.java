package dev.wiles.myagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.llm.ToolSpec;

/**
 * 一个工具。实现这个接口，agent 就多一项能力。
 *
 * 四个方法里，前三个是给**模型**看的（拼成菜单发过去），
 * 只有 execute 是给**我们自己**用的（模型点单后真正干活）。
 *
 * name 和 description 的质量直接决定模型调用的正确率：
 * 名字要动词开头、说清楚干什么；description 要写明**什么时候该用、什么时候不该用**。
 * 这不是文档，是提示词——模型只能靠这几句话判断该不该调你。
 */
public interface Tool {

    /** 工具名，模型用它点单。小写下划线风格，全局唯一。 */
    String name();

    /** 给模型看的说明。写清用途和边界，比写给人看的还要啰嗦一点。 */
    String description();

    /** 参数的 JSON Schema 字面量。字段的 description 同样是给模型看的提示词。 */
    String parametersSchema();

    /**
     * 真正干活。
     *
     * @param args 模型传来的参数，已经从 JSON 字符串解析成对象
     * @return 成功或失败，两种都是正常返回值；失败信息会原样交给模型
     */
    ToolResult execute(JsonNode args);

    /** 转成协议要求的工具说明书格式。 */
    default ToolSpec toSpec() {
        return ToolSpec.of(name(), description(), parametersSchema());
    }
}
