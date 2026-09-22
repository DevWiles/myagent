package dev.wiles.myagent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 点一份技能，把它的说明书整段读进来。
 *
 * <h3>渐进披露：清单常驻，正文按需</h3>
 * 每一轮固定要付的只有「有哪些技能、各自什么时候用」这份清单，
 * 正文只在真的点了才进上下文。一份技能的正文动辄上百行，
 * 十份技能全程挂着就是几万 token 的常驻税——这笔账在 MCP 工具菜单上刚算过。
 *
 * <h3>清单为什么放在工具描述里，而不是系统提示词</h3>
 * 两处都是每轮必发，成本一样，但放这儿多两个好处：
 * <b>「有什么」和「怎么点」挨在一起</b>，模型不用把两段话对起来；
 * 以及<b>一份技能都没有时这个工具压根不注册</b>，连一个字都不花。
 * 没配技能的人是绝大多数，零成本该是他们的默认待遇。
 *
 * <h3>不执行 <code>!`命令`</code>，但必须说出来</h3>
 * 规范里正文可以写 <code>!`git diff`</code> 这种动态注入，由宿主先跑完再把输出填进去。
 * 我们还没做这件事。<b>但不能装作没看见</b>——不填的话模型读到的是一句空指令，
 * 它会以为「这里本来就没内容」，然后基于不存在的材料往下编。
 * 所以数出来有几处，如实告诉它，让它自己拿 bash 去补。
 */
public final class SkillTool implements Tool {

    /** 正文里的动态注入标记：<code>!`命令`</code>。 */
    private static final Pattern DYNAMIC = Pattern.compile("!`[^`\\n]+`");

    private final SkillLibrary library;

    public SkillTool(SkillLibrary library) {
        this.library = library;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        List<Skill> skills = library.invocable();
        return """
                读取一份技能说明书——针对某类任务的既定做法，由用户事先写好。
                任务对得上就先读它再动手：里面是这台机器上的具体约定（路径、格式、\
                先做什么后做什么），这些你猜不出来。

                可用的技能:
                %s""".formatted(skills.stream()
                .map(skill -> "- " + skill.id() + ": "
                        + (skill.description().isBlank() ? "（没写说明）" : skill.description()))
                .collect(Collectors.joining("\n")));
    }

    @Override
    public String parametersSchema() {
        return """
                {"type":"object",
                 "properties":{"name":{"type":"string","description":"技能的名字，取自上面的清单"}},
                 "required":["name"]}""";
    }

    /**
     * 只读。技能本身就是一段文字，读它不改变任何东西——
     * 真正动手的是模型读完之后调的那些工具，那些各有各的关卡。
     */
    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String wanted = args == null || args.get("name") == null
                ? "" : args.get("name").asText("").strip();
        if (wanted.isEmpty()) {
            return ToolResult.error("要指定 name。" + names());
        }
        Skill skill = library.find(wanted).orElse(null);
        if (skill == null) {
            // 报错里带上全部名字：这句话的第一读者是模型，只说「没有这个技能」
            // 它只能再猜一次，而猜错的代价是又一轮调用
            return ToolResult.error("没有叫 " + wanted + " 的技能。" + names());
        }
        return ToolResult.ok(render(skill));
    }

    private String names() {
        List<Skill> skills = library.invocable();
        return skills.isEmpty() ? "这台机器上一份技能都没装。"
                : "可用的是: " + skills.stream().map(Skill::id).collect(Collectors.joining(", "));
    }

    static String render(Skill skill) {
        StringBuilder sb = new StringBuilder("# 技能: ").append(skill.title()).append('\n');
        // 目录必须给：正文里引用的脚本和素材全是相对它的，
        // 不给的话模型只能拿着一个相对路径去工作区里瞎找
        sb.append("技能目录: ").append(skill.dir()).append('\n')
                .append("（正文里提到的脚本、模板、素材都在这个目录下，按需自己去读）\n");

        String allowed = skill.frontMatter().getOrDefault("allowed-tools", "");
        if (!allowed.isBlank()) {
            // 声明了但我们不强制。说清楚是谁在负责，别让人以为有一道其实不存在的关卡
            sb.append("这份技能声明它只该用这些工具: ").append(allowed)
                    .append("（myagent 不强制，按它说的做）\n");
        }

        int dynamic = count(skill.body());
        if (dynamic > 0) {
            sb.append("注意: 正文里有 ").append(dynamic)
                    .append(" 处 !`命令` 动态注入，myagent 不会替你执行。")
                    .append("需要那些输出的话自己用 bash 跑一遍，别当成那里本来就没内容。\n");
        }
        return sb.append('\n').append(skill.body()).toString();
    }

    private static int count(String body) {
        Matcher matcher = DYNAMIC.matcher(body);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }
}
