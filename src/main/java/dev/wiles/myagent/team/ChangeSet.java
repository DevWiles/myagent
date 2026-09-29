package dev.wiles.myagent.team;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 工作区快照与改动清单：验收席拿到的「这一轮到底改了什么」，由程序算出来，不由模型自述。
 *
 * <h3>为什么要这一层</h3>
 * 验收最常见的失效方式是<b>对着施工报告验收</b>：报告说改了 A，验收就去读 A，
 * 报告没提的 B（比如顺手改了测试）永远没人看。open-code-review 的做法是
 * 「确定性工程 × 模型」——该看哪些文件由 diff 说了算，模型只负责判断。
 * 这里照搬这个分工：开工前拍一张快照，收工后逐文件比对，
 * 新增 / 修改 / 删除各列一条，修改的附 unified diff。
 *
 * <h3>边界</h3>
 * 只看文本文件（含 NUL 字节的按二进制跳过），单文件 ≤ {@link #MAX_FILE_BYTES}，
 * 最多 {@link #MAX_FILES} 个；{@code .git}、{@code __pycache__} 这类目录跳过。
 * diff 用最长公共子序列算，两边行数乘积超过 {@link #MAX_DIFF_CELLS} 就不算 diff，
 * 只标「已修改」——验收自己去读原文。
 */
public final class ChangeSet {

    static final int MAX_FILE_BYTES = 256 * 1024;
    static final int MAX_FILES = 2000;
    static final long MAX_DIFF_CELLS = 4_000_000L;
    /** 单个文件 diff 的字数上限，超了截断——验收要的是「改了哪儿」，不是全文。 */
    static final int MAX_DIFF_CHARS = 6_000;
    /** 整份清单的字数上限。 */
    static final int MAX_REPORT_CHARS = 24_000;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".hg", ".svn", "__pycache__", ".pytest_cache", ".mypy_cache",
            "node_modules", "target", "build", "dist", ".venv", "venv", ".myagent",
            ".idea", ".gradle", ".tox");

    private ChangeSet() {
    }

    /** 相对路径 → 文件内容。root 为 null 或不存在时返回空快照。 */
    public static Map<String, String> snapshot(Path root) {
        Map<String, String> out = new LinkedHashMap<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        Path base = root.toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (out.size() >= MAX_FILES) {
                    break;
                }
                if (!Files.isRegularFile(p) || skipped(base.relativize(p))) {
                    continue;
                }
                try {
                    if (Files.size(p) > MAX_FILE_BYTES) {
                        continue;
                    }
                    byte[] bytes = Files.readAllBytes(p);
                    if (isBinary(bytes)) {
                        continue;
                    }
                    out.put(base.relativize(p).toString().replace('\\', '/'),
                            new String(bytes, StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 读不了的文件（权限、正在被写）不进快照——验收材料少一条，好过整轮作废
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return out;
        }
        return out;
    }

    /**
     * 两张快照的差别，写成验收能直接读的清单。没有任何改动时返回空串。
     */
    public static String report(Map<String, String> before, Map<String, String> after) {
        Set<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        StringBuilder sb = new StringBuilder();
        int files = 0;
        for (String path : paths) {
            String old = before.get(path);
            String now = after.get(path);
            if (old == null && now == null || old != null && old.equals(now)) {
                continue;
            }
            files++;
            if (sb.length() > MAX_REPORT_CHARS) {
                sb.append("\n（改动清单已超长，后面的文件只列名）\n");
                sb.append("- ").append(path).append('\n');
                continue;
            }
            if (old == null) {
                sb.append("\n### 新增 ").append(path).append('\n');
                sb.append(fence(clip(now)));
            } else if (now == null) {
                sb.append("\n### 删除 ").append(path).append('\n');
            } else {
                sb.append("\n### 修改 ").append(path).append('\n');
                String diff = unifiedDiff(old, now);
                sb.append(fence(diff == null
                        ? "（文件太大没算 diff，请自己读原文）" : clip(diff)));
            }
        }
        if (files == 0) {
            return "";
        }
        return "共 " + files + " 个文件有改动。\n" + sb;
    }

    private static boolean skipped(Path rel) {
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 8000);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String s) {
        return s.length() <= MAX_DIFF_CHARS ? s
                : s.substring(0, MAX_DIFF_CHARS) + "\n…（截断，共 " + s.length() + " 字）";
    }

    private static String fence(String body) {
        return "```\n" + (body.endsWith("\n") ? body : body + "\n") + "```\n";
    }

    // ── unified diff ────────────────────────────────────────────

    /** 返回 null 表示两边太大，没算。 */
    static String unifiedDiff(String oldText, String newText) {
        String[] a = oldText.split("\n", -1);
        String[] b = newText.split("\n", -1);
        if ((long) a.length * b.length > MAX_DIFF_CELLS) {
            return null;
        }
        // 去掉公共前后缀，DP 只跑中间那段
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix
                && a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix])) {
            suffix++;
        }
        int n = a.length - prefix - suffix;
        int m = b.length - prefix - suffix;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[prefix + i].equals(b[prefix + j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        // 中间段的编辑脚本：' ' 相同、'-' 删、'+' 加
        List<String> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[prefix + i].equals(b[prefix + j])) {
                ops.add(" " + a[prefix + i]);
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add("-" + a[prefix + i]);
                i++;
            } else {
                ops.add("+" + b[prefix + j]);
                j++;
            }
        }
        while (i < n) {
            ops.add("-" + a[prefix + i++]);
        }
        while (j < m) {
            ops.add("+" + b[prefix + j++]);
        }
        // 只输出一个块：前后各带 3 行上下文，行号按 1 起算
        int ctx = 3;
        int fromA = Math.max(0, prefix - ctx);
        int fromB = Math.max(0, prefix - ctx);
        List<String> out = new ArrayList<>();
        for (int k = fromA; k < prefix; k++) {
            out.add(" " + a[k]);
        }
        out.addAll(ops);
        int tail = Math.min(ctx, suffix);
        for (int k = 0; k < tail; k++) {
            out.add(" " + a[a.length - suffix + k]);
        }
        int lenA = (prefix - fromA) + n + tail;
        int lenB = (prefix - fromB) + m + tail;
        StringBuilder sb = new StringBuilder();
        sb.append("@@ -").append(fromA + 1).append(',').append(lenA)
                .append(" +").append(fromB + 1).append(',').append(lenB).append(" @@\n");
        for (String line : out) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
