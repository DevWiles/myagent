package dev.wiles.myagent.tool.safety;

import java.nio.file.Path;
import java.util.List;

/**
 * 启动位置检查——所有安全规则里最后、也最关键的一道。
 *
 * 前面那些命令级规则都建立在同一个前提上：<b>工作区本身是可以随便折腾的</b>。
 * 所以 {@code rm -rf *} 只算「需确认」——删掉的无非是个项目目录，大不了重新 clone。
 *
 * 但如果 agent 是在家目录里启动的，这个前提就崩了：
 * 此时工作区 = 你的全部个人文件，{@code rm -rf *} 一旦被确认，照片、文档、密钥全没。
 * 而它在规则层面完全「合法」，因为目标确实在工作区内。
 *
 * 所以与其在命令层面打补丁，不如从根上堵住：<b>不许在这些目录里启动。</b>
 */
public final class WorkspaceGuard {

    /** 在这些目录里启动一律拒绝。 */
    private static final List<String> FORBIDDEN_ROOTS = List.of(
            "/", "/Users", "/home", "/System", "/Library", "/Applications",
            "/usr", "/etc", "/var", "/bin", "/sbin", "/opt", "/private", "/tmp",
            "/Volumes", "/Network");

    private WorkspaceGuard() {
    }

    /**
     * @return 拒绝的理由；可以启动则返回 null
     */
    public static String rejectionReason(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        String path = normalized.toString();

        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (normalized.equals(home)) {
            return "这里是你的家目录。工作区内的删除操作只需确认即可执行，"
                    + "在这里启动等于把全部个人文件交给 agent 处置。";
        }
        for (String forbidden : FORBIDDEN_ROOTS) {
            if (path.equals(forbidden)) {
                return "这里是系统目录 " + forbidden + "，不适合作为 agent 的工作区。";
            }
        }
        // 家目录下的这几个目录同样装着大量个人数据
        for (String risky : List.of("Documents", "Desktop", "Downloads", "Pictures", "Movies", "Music")) {
            if (normalized.equals(home.resolve(risky))) {
                return "这里是 " + risky + " 目录，装的是个人文件而不是某个项目。";
            }
        }
        return null;
    }

    public static boolean isSafe(Path root) {
        return rejectionReason(root) == null;
    }
}
