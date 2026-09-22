package dev.wiles.myagent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLibraryTest {

    private static void skill(Path dir, String id, String description, String body)
            throws IOException {
        Path home = Files.createDirectories(dir.resolve(id));
        Files.writeString(home.resolve("SKILL.md"),
                "---\ndescription: " + description + "\n---\n" + body + "\n");
    }

    @Test
    @DisplayName("四个位置都扫：工作区和家目录下的 .myagent 和 .claude")
    void searchPathCoversBothLayoutsAndBothScopes() {
        List<Path> dirs = SkillLibrary.searchPath(Path.of("/proj"), Path.of("/home/me"));

        assertEquals(List.of(
                Path.of("/proj/.myagent/skills"),
                Path.of("/proj/.claude/skills"),
                Path.of("/home/me/.myagent/skills"),
                Path.of("/home/me/.claude/skills")), dirs);
    }

    @Test
    @DisplayName("撞名时工作区压过家目录——一个仓库得能给自己定制技能")
    void workspaceWinsOverHome(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("proj/.myagent/skills"));
        Path home = Files.createDirectories(tmp.resolve("home/.claude/skills"));
        skill(project, "deploy", "这个仓库的部署办法。", "用 make deploy");
        skill(home, "deploy", "通用部署办法。", "用 scp");

        SkillLibrary library = SkillLibrary.load(List.of(project, home));

        assertEquals(1, library.all().size(), "同名只该留一份");
        assertEquals("这个仓库的部署办法。", library.find("deploy").orElseThrow().description());
    }

    @Test
    @DisplayName("两处的技能会合在一起，不是二选一")
    void skillsFromEveryDirectoryAreMerged(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("proj/.myagent/skills"));
        Path home = Files.createDirectories(tmp.resolve("home/.claude/skills"));
        skill(project, "deploy", "部署。", "x");
        skill(home, "research-report", "调研。", "y");

        SkillLibrary library = SkillLibrary.load(List.of(project, home));

        assertEquals(List.of("deploy", "research-report"),
                library.all().stream().map(Skill::id).toList());
    }

    @Test
    @DisplayName("一份坏的只作废它自己，不拖累别人")
    void oneUnreadableSkillDoesNotSinkTheRest(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("skills"));
        Files.createDirectories(dir.resolve("空目录"));          // 没有 SKILL.md
        skill(dir, "good", "好的那份。", "正文");

        SkillLibrary library = SkillLibrary.load(List.of(dir));

        assertEquals(List.of("good"), library.all().stream().map(Skill::id).toList());
    }

    @Test
    @DisplayName("目录不存在就是空清单——绝大多数人是这个状态，不能报错")
    void missingDirectoriesAreNormal(@TempDir Path tmp) {
        SkillLibrary library = SkillLibrary.load(List.of(tmp.resolve("根本没有这个目录")));

        assertTrue(library.isEmpty());
    }

    @Test
    @DisplayName("清单顺序按目录名固定——顺序一抖，服务端的前缀缓存就白瞎了")
    void listingOrderIsStable(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("skills"));
        for (String id : List.of("zebra", "alpha", "mango")) {
            skill(dir, id, id + " 的说明。", "正文");
        }

        assertEquals(List.of("alpha", "mango", "zebra"),
                SkillLibrary.load(List.of(dir)).all().stream().map(Skill::id).toList());
    }

    @Test
    @DisplayName("不让模型自己选的那份不进清单，但按名字还找得到")
    void manualOnlySkillsAreHiddenFromTheListing(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("skills"));
        Files.writeString(Files.createDirectories(dir.resolve("manual")).resolve("SKILL.md"),
                "---\ndescription: 只能人点。\ndisable-model-invocation: true\n---\n正文\n");
        skill(dir, "auto", "谁都能点。", "正文");

        SkillLibrary library = SkillLibrary.load(List.of(dir));

        assertEquals(List.of("auto"), library.invocable().stream().map(Skill::id).toList());
        assertTrue(library.find("manual").isPresent());
    }
}
