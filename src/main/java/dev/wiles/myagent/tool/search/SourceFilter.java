package dev.wiles.myagent.tool.search;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 哪些文件值得搜——{@code grep} 和 {@code glob} 共用的那套判定。
 *
 * <h3>为什么共用一套</h3>
 * 因为两个工具会被<b>连着用</b>：模型先 {@code glob} 看有哪些文件，再 {@code grep}
 * 搜里面的内容。两边的取舍要是不一样，就会出现「glob 列得出来、grep 搜不到」，
 * 而模型对这种矛盾的反应是反复重试同一件事——它没有别的办法判断到底哪边错了。
 * 这和自带终端界面那次教训是同一类：<b>同一个概念在两处行为不一致，
 * 比任何一种行为本身都更糟</b>。
 *
 * <h3>三道闸，管的是三件不同的事</h3>
 * <ul>
 *   <li>{@link #SKIP_DIRS}——<b>硬黑名单</b>。产物和依赖目录，永远不进，
 *       不受 .gitignore 有没有写、写没写对的影响。</li>
 *   <li>{@link #MAX_FILE_BYTES}——<b>大小闸</b>。超过这个的多半不是人写的代码，
 *       而是数据、日志、压缩产物。</li>
 *   <li>{@link #looksBinary}——<b>二进制探测</b>。png/jar/class 拿正则去搜纯属浪费，
 *       而且真匹配上了，输出的乱码会把上下文喂脏。</li>
 * </ul>
 *
 * <h3>为什么目录用黑名单，而角色的工具用白名单</h3>
 * 取舍方向相反，因为<b>漏一个的代价不一样</b>：角色那边漏一个是安全缺口，
 * 这边漏一个只是多几行噪音。源码目录的名字千奇百怪（src/lib/app/cmd/internal…），
 * 白名单必然漏掉真正该搜的东西。同 {@link dev.wiles.myagent.tool.WorkspaceDigest}。
 */
public final class SourceFilter {

    /**
     * 永远不进的目录。
     *
     * <p>比 {@code WorkspaceDigest.SKIP_DIRS} 多几个，因为那份是给规划器看目录树用的，
     * 只要三层；这份要走到底，会撞上更多东西。
     *
     * <p>{@code .git} 尤其要挡：它<b>不会</b>出现在任何 .gitignore 里（git 自己不忽略它，
     * 它只是不跟踪），但里面有成千上万个松散对象，全是二进制，且名字看起来像哈希。
     * 少了这一条，在任何真实仓库上搜索都会慢一个数量级。
     */
    public static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".hg", ".svn",
            ".idea", ".vscode", ".mvn", ".gradle",
            "target", "build", "out", "dist", "bin", "obj",
            "node_modules", "bower_components", "vendor",
            "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache",
            ".venv", "venv", ".tox", ".eggs",
            ".next", ".nuxt", ".svelte-kit", ".turbo", ".parcel-cache",
            ".cache", ".gradle-cache", "coverage", ".nyc_output",
            "Pods", "DerivedData");

    /**
     * 单个文件的大小上限，超过就不读。
     *
     * <p>2MB 在源码里是个<b>极端</b>值——这个项目最大的 Java 文件不到 40KB。
     * 能到 2MB 的基本只有三类：日志、导出的数据、以及压缩打包过的 js。
     * 三类都不是「搜代码」想要的东西，而读一个它们的代价，够读一百个真源文件。
     */
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** 二进制探测读多少字节。8KB 足够——真二进制文件的 NUL 字节几乎都在文件头。 */
    static final int SNIFF_BYTES = 8192;

    private SourceFilter() {
    }

    /** 这个目录要不要进去。 */
    public static boolean shouldEnter(String dirName) {
        return !SKIP_DIRS.contains(dirName);
    }

    /**
     * 这个文件值不值得读进来搜。
     *
     * <p>只看大小，不看扩展名——<b>扩展名白名单是个陷阱</b>：
     * {@code Dockerfile}、{@code Makefile}、{@code .gitignore}、{@code pom.xml} 之外，
     * 每个生态还有自己的一套（{@code .gradle.kts}、{@code .tsx}、{@code .zig}、
     * {@code .astro}…），列不全，而漏掉的那个对用户来说就是「搜不到」。
     * 真正要挡的是二进制，那件事 {@link #looksBinary} 直接看内容，比猜扩展名准。
     */
    public static boolean worthReading(long size) {
        return size > 0 && size <= MAX_FILE_BYTES;
    }

    /**
     * 看起来是不是二进制：<b>头 8KB 里有没有 NUL 字节</b>。
     *
     * <p>这是 git 和 grep 用了几十年的判据，粗糙但极其有效：文本编码里
     * NUL 不会自然出现，而几乎所有二进制格式的文件头都有。
     *
     * <p>读不动就当二进制（返回 true）——跳过一个读不出的文件，
     * 好过让它把一次搜索搞挂。
     */
    public static boolean looksBinary(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = new byte[SNIFF_BYTES];
            int read = in.readNBytes(head, 0, head.length);
            for (int i = 0; i < read; i++) {
                if (head[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }
}
