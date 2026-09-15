package dev.wiles.myagent;

import dev.wiles.myagent.cli.Ansi;
import dev.wiles.myagent.cli.LineReader;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;

/**
 * 在终端里问用户要不要放行。
 *
 * 必须和 REPL 共用同一个 LineReader：各读各的会各自缓冲标准输入，
 * 按键会莫名其妙丢失。
 */
public final class TerminalApproval implements ApprovalPolicy {

    private final LineReader reader;
    /** 用户选了「本会话都同意」之后就不再打扰他。 */
    private boolean approveEverything = false;

    public TerminalApproval(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public boolean approve(String action, String detail, String reason) {
        if (approveEverything) {
            return true;
        }
        System.out.println();
        System.out.println(Ansi.yellow("┌─ 需要确认 ─────────────────────────────"));
        System.out.println(Ansi.yellow("│ ") + Ansi.bold(action + ": ") + detail);
        System.out.println(Ansi.yellow("│ ") + Ansi.dim("原因: " + reason));
        System.out.println(Ansi.yellow("└─ ") + Ansi.green("[y]") + " 同意   "
                + Ansi.red("[n]") + " 拒绝   " + Ansi.dim("[a] 本会话都同意"));

        String answer = reader.readLine(Ansi.yellow("? "));
        if (answer == null) {
            // 管道输入等非交互场景读到结尾，按拒绝处理
            System.out.println(Ansi.dim("(读不到输入，按拒绝处理)"));
            return false;
        }
        answer = answer.trim().toLowerCase();
        if ("a".equals(answer)) {
            approveEverything = true;
            System.out.println(Ansi.dim("(本会话后续不再询问)"));
            return true;
        }
        return "y".equals(answer) || "yes".equals(answer);
    }
}
