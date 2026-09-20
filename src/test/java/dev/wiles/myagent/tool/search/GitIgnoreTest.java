package dev.wiles.myagent.tool.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitIgnoreTest {

    private static GitIgnore rules(String... lines) {
        return GitIgnore.parse(List.of(lines));
    }

    @Test
    @DisplayName("最朴素的一条：一个名字挡掉任意层级上的同名文件")
    void plainNameMatchesAtAnyDepth() {
        GitIgnore ignore = rules("secrets.txt");

        assertTrue(ignore.ignores("secrets.txt", false));
        assertTrue(ignore.ignores("src/secrets.txt", false));
        assertTrue(ignore.ignores("a/b/c/secrets.txt", false));
        assertFalse(ignore.ignores("other.txt", false));
    }

    @Test
    @DisplayName("带斜杠的锚定在本目录，不再匹配深处的同名条目")
    void slashAnchorsToThisDirectory() {
        GitIgnore ignore = rules("/target");

        assertTrue(ignore.ignores("target", true));
        // 这是「带不带斜杠」唯一的实际区别，写错了要么挡不住要么挡太多
        assertFalse(ignore.ignores("sub/target", true));
    }

    @Test
    @DisplayName("尾斜杠只挡目录，不能误伤同名的文件")
    void trailingSlashMeansDirectoryOnly() {
        GitIgnore ignore = rules("build/");

        assertTrue(ignore.ignores("build", true));
        assertFalse(ignore.ignores("build", false), "build 这个文件不该被 build/ 挡掉");
    }

    @Test
    @DisplayName("尾斜杠不等于锚定——build/ 要挡住任意层级的 build 目录")
    void trailingSlashStillMatchesAnyDepth() {
        // 去掉尾斜杠之后串里就没有斜杠了，所以它是「任意层级」而不是「只在根下」。
        // 判定顺序写反的话，src/build 会漏网，而写这行的人想挡的是全部
        GitIgnore ignore = rules("build/");

        assertTrue(ignore.ignores("build", true));
        assertTrue(ignore.ignores("src/build", true));
        assertTrue(ignore.ignores("a/b/build", true));
    }

    @Test
    @DisplayName("后面的规则覆盖前面的，! 才有意义")
    void laterRuleWins() {
        GitIgnore ignore = rules("*.log", "!important.log");

        assertTrue(ignore.ignores("debug.log", false));
        assertFalse(ignore.ignores("important.log", false), "! 应该把它放回来");
    }

    @Test
    @DisplayName("顺序反过来，! 就不起作用了——这正是 git 的语义")
    void orderMatters() {
        GitIgnore ignore = rules("!important.log", "*.log");

        assertTrue(ignore.ignores("important.log", false),
                "取反写在前面会被后面的通配规则重新挡掉");
    }

    @Test
    @DisplayName("单星不跨目录，双星跨")
    void starDoesNotCrossDirectories() {
        GitIgnore single = rules("src/*.java");
        assertTrue(single.ignores("src/Main.java", false));
        assertFalse(single.ignores("src/deep/Main.java", false),
                "单星跨了目录的话，src/*.java 会把整棵子树挡掉");

        GitIgnore doubled = rules("src/**/*.java");
        assertTrue(doubled.ignores("src/deep/Main.java", false));
        assertTrue(doubled.ignores("src/Main.java", false),
                "a/**/b 里的 ** 要能匹配零层目录");
    }

    @Test
    @DisplayName("注释和空行跳过，# 要能转义成字面量")
    void commentsAndBlanks() {
        GitIgnore ignore = rules("", "  ", "# 这是注释", "*.tmp", "\\#literal");

        assertTrue(ignore.ignores("a.tmp", false));
        assertTrue(ignore.ignores("#literal", false));
        assertFalse(ignore.ignores("这是注释", false));
    }

    @Test
    @DisplayName("match 要分得开「明确放行」和「一条都没命中」")
    void matchIsTriState() {
        GitIgnore ignore = rules("*.log", "!keep.log");

        assertEquals(Boolean.TRUE, ignore.match("a.log", false));
        assertEquals(Boolean.FALSE, ignore.match("keep.log", false));
        // 这一条是嵌套 .gitignore 能不能推翻父目录的前提：
        // 「没意见」必须和「明确放行」长得不一样
        assertNull(ignore.match("Main.java", false));
    }

    @Test
    @DisplayName("字符组要认，glob 的 ! 取反要翻译成正则的 ^")
    void characterClasses() {
        GitIgnore ignore = rules("log[0-9].txt");
        assertTrue(ignore.ignores("log3.txt", false));
        assertFalse(ignore.ignores("logx.txt", false));

        GitIgnore negated = rules("log[!0-9].txt");
        assertTrue(negated.ignores("logx.txt", false));
        assertFalse(negated.ignores("log3.txt", false));
    }

    @Test
    @DisplayName("正则元字符要当字面量，不能被当成正则")
    void regexMetacharactersAreLiteral() {
        // .gitignore 里的 . 就是个点。当成正则的「任意字符」的话，
        // a.txt 会把 axtxt 也挡掉——这种错不会报，只会莫名其妙少文件
        GitIgnore ignore = rules("a.txt");

        assertTrue(ignore.ignores("a.txt", false));
        assertFalse(ignore.ignores("axtxt", false));
    }

    @Test
    @DisplayName("行尾空白不算数，但转义过的空格要留着")
    void trailingWhitespace() {
        assertTrue(rules("*.log   ").ignores("a.log", false));
        assertTrue(rules("trail\\ ").ignores("trail ", false));
    }

    @Test
    @DisplayName("编译不成正则的规则跳过，不能让整个文件失效")
    void brokenRuleIsSkipped() {
        GitIgnore ignore = rules("*.log", "[[[[bad", "*.tmp");

        assertTrue(ignore.ignores("a.log", false));
        assertTrue(ignore.ignores("a.tmp", false), "坏规则不该连累它后面的");
    }

    @Test
    @DisplayName("规则条数有上限，生成出来的巨型 .gitignore 不能拖垮遍历")
    void ruleCountIsCapped() {
        String[] many = new String[GitIgnore.MAX_RULES + 50];
        for (int i = 0; i < many.length; i++) {
            many[i] = "pattern" + i + ".txt";
        }
        GitIgnore ignore = GitIgnore.parse(List.of(many));

        assertTrue(ignore.ignores("pattern0.txt", false));
        assertFalse(ignore.ignores("pattern" + (GitIgnore.MAX_RULES + 10) + ".txt", false),
                "超过上限的规则应该被丢掉");
    }

    @Test
    @DisplayName("没有 .gitignore 的目录返回 EMPTY，调用方不用到处判空")
    void missingFileIsEmpty(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        GitIgnore ignore = GitIgnore.of(dir);

        assertTrue(ignore.isEmpty());
        assertFalse(ignore.ignores("anything", false));
        assertNull(ignore.match("anything", false));
    }
}
