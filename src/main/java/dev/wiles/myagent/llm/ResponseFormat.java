package dev.wiles.myagent.llm;

/**
 * 让服务端来保证输出是合法 JSON，而不是靠提示词求模型自觉。
 *
 * <p>提示词里写「只输出 JSON」是<b>请求</b>，模型完全可能裹上 ```json 围栏、
 * 前面加一句寒暄，或者干脆先跟你商量两句。这个字段是<b>约束</b>——
 * 服务端在解码时就不让它吐出非 JSON 的字符。
 *
 * <p>两者不是二选一，而是层层设防：
 * <ol>
 *   <li>这个字段保证「是合法 JSON」</li>
 *   <li>提示词保证「字段名和结构对」——这个它管不了</li>
 *   <li>解析失败照样回灌重试——网关不支持这个字段时，全靠它兜底</li>
 * </ol>
 *
 * <p><b>两个坑</b>（OpenAI 兼容协议的通病，DeepSeek 同样有）：
 * 提示词里<b>必须出现 json 字样</b>，否则服务端会拒绝或返回空；
 * 而且<b>必须设 max_tokens</b>，否则输出被截断时可能一直吐空白直到烧完额度。
 */
public record ResponseFormat(String type) {

    /** 只保证是个合法 JSON 对象，不校验具体结构。 */
    public static final ResponseFormat JSON_OBJECT = new ResponseFormat("json_object");
}
