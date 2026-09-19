package dev.wiles.myagent.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 从 classpath 读提示词片段。
 *
 * <h3>为什么提示词是资源文件，不是 Java 的 text block</h3>
 * 提示词的每个字都影响模型行为，所以它会被<b>反复调</b>——而调一次要重新编译、
 * 重新打包、重启，这个来回足以让人放弃「再试一版措辞」。资源文件改完重启就生效，
 * 甚至可以在 jar 外面覆盖一份对照着试。
 *
 * <p>还有一个更实在的好处：提示词从此能被 <b>diff</b> 得清清楚楚。
 * 埋在 Java 字符串里时，改一句话的 diff 混在转义和缩进里，
 * review 的人根本看不出语义变了什么。
 *
 * <h3>缺了就抛，不静默退化</h3>
 * {@link #loadRequired} 找不到资源时直接抛。这类错误只可能是打包漏了文件或者
 * 路径写错，属于「一定会发生在所有用户身上」的那种——
 * 而它的症状（模型少了一段约束，行为微妙地变差）<b>没有任何迹象</b>，
 * 靠观察是发现不了的。宁可启动就炸。
 *
 * <h3>读一次就缓存</h3>
 * 提示词在一次进程生命周期内不会变，而装配发生在<b>每次构造 Agent</b> 时。
 * 不缓存的话每次都要开流读盘——不算贵，但也没有理由付。
 */
public final class PromptRepository implements PromptSource {

    /** 资源根目录。改它等于换一整套提示词。 */
    static final String ROOT = "/prompts/";

    private final Map<String, String> cache = new HashMap<>();

    public static PromptRepository createDefault() {
        return new PromptRepository();
    }

    /**
     * 读一段提示词。
     *
     * @param path 相对 {@code /prompts/} 的路径，如 {@code modes/agent.md}
     * @throws IllegalStateException 找不到或读不出来
     */
    @Override
    public String loadRequired(String path) {
        return cache.computeIfAbsent(path, PromptRepository::read);
    }

    private static String read(String path) {
        String resource = ROOT + path;
        try (InputStream in = PromptRepository.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("提示词资源不存在: " + resource
                        + "（打包漏了文件，或者路径写错了。这类问题不会自己暴露——"
                        + "模型只是少了一段约束，行为微妙地变差，所以这里直接抛）");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源读不出来: " + resource, e);
        }
    }
}
