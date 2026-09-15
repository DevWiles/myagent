package dev.wiles.myagent.tool.safety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件写入的风险评估，和 {@link CommandGuard} 对称。
 *
 * <h3>为什么不是「写文件就确认」</h3>
 * agent 干活时写文件是家常便饭，每次都弹框，用户三次之后就会条件反射按 y，
 * 那这道防线等于没有。所以按「会不会毁掉已有东西」来分档：
 * <ul>
 *   <li>新建文件 —— 放行。写错了删掉就是，没有损失</li>
 *   <li>覆盖已有文件 —— 确认。这是唯一会造成数据丢失的情形</li>
 *   <li>动版本库内部 —— 拒绝。改坏 .git 连 git 都救不回来，
 *       而那正是用户最后的后悔药</li>
 * </ul>
 */
public final class FileWriteGuard {

    /** 这些目录是工具自己的数据库，被改写会导致不可恢复的损坏。 */
    private static final List<String> PROTECTED_DIRS = List.of(".git", ".svn", ".hg");

    /**
     * @param displayPath 给人看的相对路径
     * @param resolved    已经过沙箱校验的绝对路径
     * @param newContent  即将写入的内容，用来在确认信息里说明变化幅度
     */
    public CommandRisk assess(String displayPath, Path resolved, String newContent) {
        for (String segment : displayPath.split("[/\\\\]")) {
            if (PROTECTED_DIRS.contains(segment)) {
                return CommandRisk.forbidden(
                        "禁止直接改写 " + segment + " 目录里的文件，那会损坏版本库本身。"
                                + "需要操作版本库请用 git 命令。");
            }
        }
        if (!Files.exists(resolved)) {
            return CommandRisk.safe();       // 新建文件没有损失，不打扰用户
        }
        return CommandRisk.approval("将覆盖已有文件 " + displayPath + describeChange(resolved, newContent));
    }

    /** 把「原来多大、要变成多大」讲清楚，用户才有判断依据。 */
    private String describeChange(Path existing, String newContent) {
        try {
            String old = Files.readString(existing, StandardCharsets.UTF_8);
            return "（原 " + countLines(old) + " 行 → 新 " + countLines(newContent) + " 行）";
        } catch (IOException e) {
            return "（原文件无法读取，可能是二进制文件）";
        }
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n", -1).length;
    }
}
