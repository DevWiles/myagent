package dev.wiles.myagent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 用户敲 /技能名 点名一份技能。 */
class SkillCommandTest {

    private static SkillLibrary library(Path tmp) throws IOException {
        write(tmp, "deploy", "---\ndescription: 发版。\ndisable-model-invocation: true\n---\n先跑 make check。\n");
        write(tmp, "internal", "---\ndescription: 只给模型用。\nuser-invocable: false\n---\n内部步骤\n");
        return SkillLibrary.load(List.of(tmp.resolve("skills")));
    }

    private static void write(Path tmp, String id, String content) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("skills").resolve(id));
        Files.writeString(dir.resolve("SKILL.md"), content);
    }

    @Test
    @DisplayName("/名字 参数：展开成技能正文，参数留在原话里；只许人点的技能这是唯一入口")
    void expandsSlashCommand(@TempDir Path tmp) throws IOException {
        SkillCommand.Expansion e = SkillCommand.expand(library(tmp), "  /deploy 生产环境\n第二行  ").orElseThrow();

        assertEquals("deploy", e.skill().id());
        assertEquals("生产环境\n第二行", e.arguments());
        assertTrue(e.reminder().contains("先跑 make check。"));
        assertTrue(e.reminder().contains("/deploy"));
    }

    @Test
    @DisplayName("不是在点技能：普通的话、不认得的名字、写了 user-invocable: false 的，都原样放过")
    void otherInputPassesThrough(@TempDir Path tmp) throws IOException {
        SkillLibrary lib = library(tmp);
        assertTrue(SkillCommand.expand(lib, "帮我 /deploy 一下").isEmpty(), "斜杠不在开头不算");
        assertTrue(SkillCommand.expand(lib, "/nope").isEmpty());
        assertTrue(SkillCommand.expand(lib, "/internal").isEmpty());
        assertTrue(SkillCommand.expand(lib, "/").isEmpty());
        assertEquals(List.of("deploy"), lib.userInvocable().stream().map(Skill::id).toList());
    }
}
