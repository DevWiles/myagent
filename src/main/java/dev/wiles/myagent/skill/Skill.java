package dev.wiles.myagent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一份 skill：{@code SKILL.md} 里的一段说明书，用到的时候才整段读进上下文。
 *
 * <h3>标准长什么样</h3>
 * 一个目录一份技能，目录里必有 {@code SKILL.md}：{@code ---} 围起来的 YAML 头，
 * 后面是 markdown 正文。旁边可以带脚本和素材。
 * 这是 Agent Skills 那套通用格式，{@code ~/.claude/skills/} 下现成的那些直接能读。
 *
 * <h3>两条容易想当然的规矩</h3>
 * <ul>
 *   <li><b>所有头字段都是可选的</b>。连 {@code name} 都可以不写——
 *       没有头、只有正文的 SKILL.md 是合法的。解析器把缺字段当常态，不当错误。</li>
 *   <li><b>身份是目录名，不是 {@code name} 字段。</b>头里那个 name 只影响显示。
 *       拿它当 id 的话，两份技能写了同一个 name 就会互相覆盖，
 *       而目录名明明是分开的——用户看着两个目录，却只有一个生效。</li>
 * </ul>
 *
 * @param id          目录名，唯一标识，模型按它点单
 * @param title       头里的 {@code name}，只给人看；没写就等于 id
 * @param description 干什么用、什么时候用。清单里就靠它被选中
 * @param body        正文，调用时才整段给出去
 * @param dir         技能目录的绝对路径。<b>必须交给模型</b>——
 *                    正文里引用的脚本和素材全是相对这里的
 * @param frontMatter 头里的全部键值，原样留着。我们不认识的字段
 *                    （{@code allowed-tools} 这类）也得能亮给模型看
 */
public record Skill(String id, String title, String description, String body,
                    Path dir, Map<String, String> frontMatter) {

    private static final Logger log = LoggerFactory.getLogger(Skill.class);

    /** 清单里一份技能的描述最多占多少字符。和 Agent Skills 那边的上限对齐。 */
    public static final int MAX_DESCRIPTION = 1536;

    /** 正文上限。技能是说明书不是数据集，超了说明这个文件被当成别的东西用了。 */
    static final int MAX_BODY = 100_000;

    private static final Pattern KEY_VALUE = Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*)\\s*:\\s*(.*)$");

    /**
     * 读一个技能目录。读不成返回 null 并 warn——<b>一份坏技能只该作废它自己</b>，
     * 和 {@code McpConfig} 是同一条纪律：外挂东西写坏了，代价不能是整个 agent 起不来。
     */
    public static Skill read(Path dir) {
        Path file = dir.resolve("SKILL.md");
        if (!Files.isReadable(file)) {
            return null;                     // 没有 SKILL.md 的目录就不是技能，静默跳过
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            log.warn("读不动 {}，跳过这份技能: {}", file, e.getMessage());
            return null;
        }

        String id = dir.getFileName().toString();
        Parsed parsed = split(text);
        String description = combined(parsed.front);
        if (description.isBlank()) {
            // 不拦，但要说一声：没有描述的技能在清单里就是一行没有理由的名字，
            // 模型永远不会主动选它——用户会以为是功能坏了
            log.warn("技能 {} 没写 description，模型不会知道什么时候该用它", id);
        }
        String body = parsed.body;
        if (body.length() > MAX_BODY) {
            log.warn("技能 {} 的正文有 {} 字符，截到 {}", id, body.length(), MAX_BODY);
            body = body.substring(0, MAX_BODY) + "\n\n（正文过长，后面被截断了）";
        }
        String title = parsed.front.getOrDefault("name", "");
        return new Skill(id, title.isBlank() ? id : title, description, body, dir,
                Map.copyOf(parsed.front));
    }

    private record Parsed(Map<String, String> front, String body) {
    }

    /**
     * 拆头和正文。
     *
     * <p>没有头也<b>不是错</b>（规范里所有字段都可选），整篇都当正文。
     * 只认最朴素的 {@code key: value}；值里出现冒号照样对
     * （描述里「当用户说：xxx」很常见），所以只按<b>第一个</b>冒号切。
     * 换行续写的值按 YAML 的折叠语义用空格接起来——描述通常很长，多半会换行。
     */
    private static Parsed split(String text) {
        Map<String, String> front = new LinkedHashMap<>();
        String[] lines = text.split("\\R", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !"---".equals(lines[i].strip())) {
            return new Parsed(front, text.strip());
        }
        i++;
        String key = null;
        StringBuilder value = new StringBuilder();
        for (; i < lines.length; i++) {
            String line = lines[i];
            if ("---".equals(line.strip())) {
                i++;
                break;
            }
            Matcher matcher = KEY_VALUE.matcher(line);
            if (matcher.matches()) {
                put(front, key, value);
                key = matcher.group(1).toLowerCase(Locale.ROOT);
                value.setLength(0);
                value.append(matcher.group(2).strip());
            } else if (key != null && !line.isBlank()) {
                if (!value.isEmpty()) {
                    value.append(' ');
                }
                value.append(line.strip());
            }
        }
        put(front, key, value);
        StringBuilder body = new StringBuilder();
        for (; i < lines.length; i++) {
            body.append(lines[i]).append('\n');
        }
        return new Parsed(front, body.toString().strip());
    }

    private static void put(Map<String, String> front, String key, StringBuilder value) {
        if (key != null) {
            front.put(key, unquote(value.toString().strip()));
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 清单里显示的那句话：{@code description} 接上 {@code when_to_use}，封顶截断。
     *
     * <p>两个字段合成一句是规范定的：前者说「干什么」，后者说「什么时候用」，
     * 而模型做选择时两件事缺一不可。上限也照搬——<b>清单里每一个字都是每一轮的固定开销</b>，
     * 这笔账在 MCP 工具菜单上刚算过一次。
     */
    static String combined(Map<String, String> front) {
        String description = front.getOrDefault("description", "").strip();
        String when = front.getOrDefault("when_to_use", "").strip();
        String merged = when.isEmpty() ? description
                : (description.isEmpty() ? when : description + " " + when);
        return merged.length() <= MAX_DESCRIPTION
                ? merged
                : merged.substring(0, MAX_DESCRIPTION) + "…";
    }

    /** 模型能不能自己选它。写了 {@code disable-model-invocation: true} 的只能由人点名。 */
    public boolean modelInvocable() {
        return !"true".equalsIgnoreCase(frontMatter().getOrDefault("disable-model-invocation", ""));
    }
}
