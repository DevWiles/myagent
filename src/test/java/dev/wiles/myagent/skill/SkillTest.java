package dev.wiles.myagent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTest {

    private static Path skill(Path root, String id, String content) throws IOException {
        Path dir = Files.createDirectories(root.resolve(id));
        Files.writeString(dir.resolve("SKILL.md"), content);
        return dir;
    }

    @Test
    @DisplayName("读一份标准 SKILL.md：头里的字段和正文各归各位")
    void readsAStandardSkill(@TempDir Path root) throws IOException {
        Path dir = skill(root, "research-report", """
                ---
                name: research-report
                description: 调研某个主题并生成中文报告。
                ---

                # 调研报告生成

                先联网检索，再写进 Obsidian。
                """);

        Skill skill = Skill.read(dir);

        assertEquals("research-report", skill.id());
        assertEquals("调研某个主题并生成中文报告。", skill.description());
        assertTrue(skill.body().startsWith("# 调研报告生成"));
        assertFalse(skill.body().contains("---"), "头不该漏进正文");
        assertEquals(dir, skill.dir());
    }

    @Test
    @DisplayName("身份是目录名，不是 name 字段——两份技能写了同名 name 不该互相盖掉")
    void theDirectoryNameIsTheIdentity(@TempDir Path root) throws IOException {
        Path dir = skill(root, "my-deploy", """
                ---
                name: 部署
                description: 部署到生产。
                ---
                照着做。
                """);

        Skill skill = Skill.read(dir);

        assertEquals("my-deploy", skill.id(), "id 取目录名");
        assertEquals("部署", skill.title(), "name 只影响显示");
    }

    @Test
    @DisplayName("所有头字段都可选：没有头、只有正文的 SKILL.md 也是合法的")
    void frontMatterIsEntirelyOptional(@TempDir Path root) throws IOException {
        Path dir = skill(root, "bare", "就这一句话，没有头。\n");

        Skill skill = Skill.read(dir);

        assertEquals("bare", skill.id());
        assertEquals("bare", skill.title());
        assertEquals("", skill.description());
        assertEquals("就这一句话，没有头。", skill.body());
    }

    @Test
    @DisplayName("描述里的冒号不能把值切断——「当用户说：xxx」这种写法太常见了")
    void colonsInsideValuesSurvive(@TempDir Path root) throws IOException {
        Path dir = skill(root, "x", """
                ---
                description: 当用户说：调研、研究时使用。Use when: researching.
                ---
                正文
                """);

        assertEquals("当用户说：调研、研究时使用。Use when: researching.",
                Skill.read(dir).description());
    }

    @Test
    @DisplayName("换行续写的描述按 YAML 折叠成一行——长描述几乎一定会换行")
    void wrappedValuesAreFolded(@TempDir Path root) throws IOException {
        Path dir = skill(root, "x", """
                ---
                description: 第一行
                  第二行
                  第三行
                ---
                正文
                """);

        assertEquals("第一行 第二行 第三行", Skill.read(dir).description());
    }

    @Test
    @DisplayName("清单里的描述封顶截断——每一个字都是每一轮的固定开销")
    void descriptionIsCapped(@TempDir Path root) throws IOException {
        Path dir = skill(root, "x", "---\ndescription: " + "长".repeat(3000) + "\n---\n正文\n");

        String description = Skill.read(dir).description();

        assertEquals(Skill.MAX_DESCRIPTION + 1, description.length(), "多的那一位是省略号");
        assertTrue(description.endsWith("…"));
    }

    @Test
    @DisplayName("description 和 when_to_use 合成一句——一个说干什么，一个说什么时候用")
    void whenToUseIsAppended(@TempDir Path root) throws IOException {
        Path dir = skill(root, "x", """
                ---
                description: 把 md 转成 pdf。
                when_to_use: 用户说「转成 pdf」时。
                ---
                正文
                """);

        assertEquals("把 md 转成 pdf。 用户说「转成 pdf」时。", Skill.read(dir).description());
    }

    @Test
    @DisplayName("没有 SKILL.md 的目录不是技能，静默跳过——技能目录旁边放别的文件夹很正常")
    void directoriesWithoutSkillMdAreNotSkills(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("just-a-folder"));

        assertNull(Skill.read(dir));
    }

    @Test
    @DisplayName("写了 disable-model-invocation 的不进模型清单")
    void modelInvocationCanBeTurnedOff(@TempDir Path root) throws IOException {
        Path off = skill(root, "manual", """
                ---
                description: 只能人点。
                disable-model-invocation: true
                ---
                正文
                """);
        Path on = skill(root, "auto", "---\ndescription: 谁都能点。\n---\n正文\n");

        assertFalse(Skill.read(off).modelInvocable());
        assertTrue(Skill.read(on).modelInvocable());
    }
}
