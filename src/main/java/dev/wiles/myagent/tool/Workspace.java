package dev.wiles.myagent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区沙箱：所有文件工具都必须经过它解析路径。
 *
 * 为什么需要它——模型是会犯错的，也可能被用户输入里的内容诱导（提示词注入）。
 * 一旦它生成 {"path": "../../../.ssh/id_rsa"} 或 {"path": "/etc/passwd"}，
 * 而我们直接 Files.readString(path)，那就是把整台机器交出去了。
 *
 * 所以规则只有一条：**解析后的真实路径必须仍在 root 之内，否则拒绝。**
 */
public final class Workspace {

    private final Path root;

    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 以当前进程的工作目录作为沙箱根。 */
    public static Workspace ofCurrentDir() {
        return new Workspace(Path.of(""));
    }

    public Path root() {
        return root;
    }

    /**
     * 把模型给的相对路径解析成绝对路径，并确保没有逃出沙箱。
     *
     * 挡得住的：
     *   "../../etc/passwd"  -> normalize 后不再以 root 开头
     *   "/etc/passwd"       -> 绝对路径会直接覆盖 root，同样不以 root 开头
     *   "a/../../b"         -> 绕一圈也会被 normalize 拆穿
     *
     * @throws IllegalArgumentException 越界时抛出，由调用方转成 ToolResult.error 交给模型
     */
    public Path resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        // resolve 遇到绝对路径会直接返回它本身，所以下面的 startsWith 检查同时挡住了两种情况
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "路径越界，只能访问工作区 " + root + " 内的文件: " + path);
        }
        // normalize 只做字符串层面的 .. 消解，挡不住符号链接指向外部，
        // 所以对已存在的路径再用真实路径复核一次
        if (Files.exists(resolved)) {
            try {
                Path real = resolved.toRealPath();
                if (!real.startsWith(root.toRealPath())) {
                    throw new IllegalArgumentException(
                            "路径通过符号链接指向了工作区之外: " + path);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("无法解析路径: " + path + " (" + e.getMessage() + ")");
            }
        }
        return resolved;
    }

    /** 转成相对于 root 的展示用路径，避免把机器上的绝对路径泄露给模型。 */
    public String display(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        return abs.startsWith(root) ? root.relativize(abs).toString() : abs.toString();
    }
}
