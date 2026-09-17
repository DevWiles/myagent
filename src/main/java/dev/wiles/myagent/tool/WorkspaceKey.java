package dev.wiles.myagent.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 把一个工作区路径压成一个能当文件名用的标识，形如 {@code myagent-a1b2c3}。
 *
 * <h3>为什么前半截是目录名、后半截是哈希</h3>
 * 两半各有各的用处，缺一个都不行：
 * <ul>
 *   <li><b>目录名给人看</b>。{@code ~/.myagent/} 下躺着十几个文件，
 *       光有哈希的话用户想手工删掉某个项目的存档，得一个个打开看。</li>
 *   <li><b>哈希保证不撞车</b>。光用目录名的话，{@code ~/a/demo} 和 {@code ~/b/demo}
 *       会互相覆盖——用户在两个项目间来回切，存档悄悄串了，
 *       现象是「续跑续出了别的项目的步骤」。</li>
 * </ul>
 *
 * <h3>为什么单独成类</h3>
 * 计划存档（{@code ~/.myagent/plans/}）和长期记忆（{@code ~/.myagent/memory/}）
 * 都要按工作区分片。这套算法一旦在两处各写一遍，将来改了一处没改另一处，
 * 后果是<b>某一类存档集体失联</b>——文件还在盘上，但新算法算出来的名字找不到它们。
 * 放一处，两边都从这里取。
 *
 * <p><b>改这个类等于让所有已存的档失联。</b>真要改，得同时写一段迁移。
 */
public final class WorkspaceKey {

    /** 目录名部分的长度上限。太长的名字在某些文件系统上会顶到限制。 */
    private static final int MAX_LABEL = 40;
    /** 取哈希的前几个字节。6 字节 = 12 个十六进制字符，撞车概率可以忽略。 */
    private static final int HASH_BYTES = 6;

    private WorkspaceKey() {
    }

    /** 算出这个工作区的标识。传 null 得到 {@code unknown}，不抛。 */
    public static String of(Path workspace) {
        if (workspace == null) {
            return "unknown";
        }
        Path normalized = workspace.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        String label = name == null ? "root" : sanitize(name.toString());
        return label + "-" + hash(normalized.toString());
    }

    /** 文件名里只留安全字符：目录名可能带空格、中文、斜杠之外的怪东西。 */
    private static String sanitize(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > MAX_LABEL ? cleaned.substring(0, MAX_LABEL) : cleaned;
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, HASH_BYTES);
        } catch (Exception e) {
            // SHA-256 不可能没有；真没有的话退化成 hashCode，撞车概率大些但不至于不能用
            return Integer.toHexString(text.hashCode());
        }
    }
}
