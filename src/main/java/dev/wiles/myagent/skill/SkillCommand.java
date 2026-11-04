package dev.wiles.myagent.skill;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户敲 {@code /技能名 参数} 点名一份技能，照 Claude Code 的 skill 斜杠命令。
 *
 * <p>和模型自己调 {@code skill} 工具是同一份正文，区别在<b>谁来决定</b>：
 * 斜杠是用户说了算，不用等模型从清单里认出来——写了 {@code disable-model-invocation: true}
 * 的技能只有这一个入口。展开发生在服务端，所以终端和手机敲的是同一条路。
 */
public final class SkillCommand {

    private static final Pattern SLASH = Pattern.compile("^/([A-Za-z0-9][A-Za-z0-9_.:-]*)(?:\\s+([\\s\\S]*))?$");

    /**
     * @param reminder 拼给模型的那段：技能正文和怎么用
     */
    public record Expansion(Skill skill, String arguments, String reminder) {
    }

    private SkillCommand() {
    }

    /** 这句话是不是在点名一份技能。不是（不以斜杠开头、名字不认得、不让用户点）就是 empty。 */
    public static Optional<Expansion> expand(SkillLibrary library, String text) {
        Matcher m = SLASH.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<Skill> found = library.find(m.group(1)).filter(Skill::userInvocable);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Skill skill = found.get();
        String args = m.group(2) == null ? "" : m.group(2).strip();
        String reminder = "用户用 /" + skill.id() + " 点名了这份技能，照它做。"
                + (args.isEmpty() ? "" : "用户给的参数就是下面那句话里技能名后面的部分。")
                + "\n\n" + SkillTool.render(skill);
        return Optional.of(new Expansion(skill, args, reminder));
    }
}
