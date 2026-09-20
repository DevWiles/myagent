package dev.wiles.myagent.tool.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class PathGlobTest {

    @Test
    @DisplayName("不含斜杠的模式匹配文件名，所以 *.java 能搜到任意深度")
    void bareePatternMatchesFileNameAtAnyDepth() {
        // 这是故意偏离标准 glob 的地方。按标准语义 *.java 只匹配根下的文件，
        // 而模型写这个的时候想的永远是「任何地方的 java 文件」——
        // 按标准办，它拿到的是空结果，且分不清是模式写错了还是文件不存在
        Predicate<String> match = PathGlob.of("*.java");

        assertTrue(match.test("Main.java"));
        assertTrue(match.test("src/main/java/dev/wiles/Main.java"));
        assertFalse(match.test("src/main/java/dev/wiles/Main.kt"));
    }

    @Test
    @DisplayName("带斜杠就回到标准语义：锚定相对路径")
    void patternWithSlashAnchorsToRelativePath() {
        Predicate<String> match = PathGlob.of("src/*.ts");

        assertTrue(match.test("src/main.ts"));
        assertFalse(match.test("src/deep/main.ts"), "单星不该跨目录");
        assertFalse(match.test("other/main.ts"));
    }

    @Test
    @DisplayName("** 跨目录")
    void doubleStarCrossesDirectories() {
        Predicate<String> match = PathGlob.of("src/**/*.ts");

        assertTrue(match.test("src/deep/nested/main.ts"));
    }

    @Test
    @DisplayName("开头的 **/ 要能匹配零层目录——真跑一轮就是栽在这上面")
    void leadingDoubleStarMatchesZeroDirectories() {
        // 标准 glob 里 **/ 是「至少一层」。模型问「最近改动最多的测试文件」时写了
        // **/src/test/**/*.java，按标准语义只捞回 demo/src/test/ 下那一个，
        // 顶层 src/test/ 下五十多个全漏了——而且不报错，只是少给
        Predicate<String> match = PathGlob.of("**/src/test/**/*.java");

        assertTrue(match.test("src/test/java/dev/wiles/GrepToolTest.java"),
                "顶层的 src/test 不该因为前面没有目录就漏掉");
        assertTrue(match.test("demo/src/test/java/dev/wiles/GreeterTest.java"));
        assertFalse(match.test("src/main/java/dev/wiles/GrepTool.java"));
    }

    @Test
    @DisplayName("中间的 **/ 同样能匹配零层")
    void middleDoubleStarMatchesZeroDirectories() {
        Predicate<String> match = PathGlob.of("src/**/*.ts");

        assertTrue(match.test("src/deep/nested/main.ts"));
        assertTrue(match.test("src/main.ts"), "src/**/*.ts 该能匹配 src 下直接放着的文件");
    }

    @Test
    @DisplayName("**/ 的语义要和 GitIgnore 那边一致——同一个记号不能有两种意思")
    void doubleStarAgreesWithGitIgnore() {
        // 同一个包里 ** 表示两种意思，是迟早要出事的那种不一致：
        // .gitignore 挡住了的文件，glob 却以为自己没匹配到，两边对不上账
        Predicate<String> glob = PathGlob.of("src/**/*.java");
        GitIgnore ignore = GitIgnore.parse(java.util.List.of("src/**/*.java"));

        for (String path : new String[]{"src/Main.java", "src/a/b/Main.java"}) {
            assertEquals(ignore.ignores(path, false), glob.test(path),
                    path + " 在两边的判定不一致");
        }
    }

    @Test
    @DisplayName("展开的组合数要封顶，别让一个离奇模式生成几千个 matcher")
    void expansionIsCapped() {
        String many = "**/".repeat(10) + "*.java";

        assertTrue(PathGlob.expandDoubleStars(many).size() <= 1 << PathGlob.MAX_DOUBLE_STARS,
                "组合数是 2 的幂，不封顶会炸");
        assertTrue(PathGlob.of(many).test("a/b/c/Main.java"));
    }

    @Test
    @DisplayName("按位置展开，不能用字符串替换——那会一次砍掉所有的 **/")
    void expansionKeepsOccurrencesIndependent() {
        java.util.List<String> variants = PathGlob.expandDoubleStars("**/src/**/*.java");

        // 四种组合：都留、留前、留后、都不留
        assertTrue(variants.contains("**/src/**/*.java"), variants.toString());
        assertTrue(variants.contains("src/**/*.java"), variants.toString());
        assertTrue(variants.contains("**/src/*.java"), variants.toString());
        assertTrue(variants.contains("src/*.java"), variants.toString());
    }

    @Test
    @DisplayName("大括号展开要认——模型很爱写 {ts,tsx}")
    void braceExpansion() {
        Predicate<String> match = PathGlob.of("*.{ts,tsx}");

        assertTrue(match.test("a/b/App.tsx"));
        assertTrue(match.test("a/b/store.ts"));
        assertFalse(match.test("a/b/style.css"));
    }

    @Test
    @DisplayName("前缀式的模式也能用：*Test.java")
    void suffixPattern() {
        Predicate<String> match = PathGlob.of("*Test.java");

        assertTrue(match.test("src/test/java/dev/wiles/GrepToolTest.java"));
        assertFalse(match.test("src/main/java/dev/wiles/GrepTool.java"));
    }

    @Test
    @DisplayName("空模式等于不过滤，省得调用方判空")
    void blankPatternMatchesEverything() {
        assertTrue(PathGlob.of(null).test("anything"));
        assertTrue(PathGlob.of("   ").test("anything"));
        assertTrue(PathGlob.always().test("anything"));
    }

    @Test
    @DisplayName("语法错误要抛出能看懂的话，并给个正确例子")
    void badPatternExplainsItself() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PathGlob.of("*.{unclosed"));

        assertTrue(e.getMessage().contains("*.{unclosed"), e.getMessage());
        assertTrue(e.getMessage().contains("例"), "报错要带例子，模型才知道该怎么改");
    }
}
