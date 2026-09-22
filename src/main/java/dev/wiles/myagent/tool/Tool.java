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

    /**
     * 能不能和别的调用<b>同时</b>执行。
     *
     * <p>模型一次响应里常常点好几个工具（「我先把这三个文件都读了」），
     * 互相之间没有依赖的话串行执行纯属浪费。但「没有依赖」这件事模型不会声明，
     * 只能由工具自己保证：<b>只有怎么跑都无所谓的工具才许说 true</b>——
     * 也就是不改文件、不跑命令、不弹审批框的那些（读文件、列目录、搜索）。
     *
     * <p>默认 false 是刻意的：写文件和 bash 的执行顺序就是语义的一部分
     * （读 a → 改 a → 再读 a，乱序就是另一段程序），审批框也只能一次弹一个。
     * 新工具忘了覆盖这个方法，代价只是慢一点；反过来默认 true 的话，
     * 忘了覆盖的代价是文件写坏——两种默认值错的成本差着量级。
     */
    default boolean parallelSafe() {
        return false;
    }

    /** 转成协议要求的工具说明书格式。 */
    default ToolSpec toSpec() {
        return ToolSpec.of(name(), description(), parametersSchema());
    }
}
