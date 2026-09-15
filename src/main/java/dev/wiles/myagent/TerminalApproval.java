package dev.wiles.myagent;

import dev.wiles.myagent.tool.safety.ApprovalPolicy;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * 在终端里问用户要不要放行。
 *
 * 注意它必须和 REPL 共用同一个 BufferedReader：
 * 各建各的 reader 会各自缓冲 System.in，输入会莫名其妙丢失。
 */
public final class TerminalApproval implements ApprovalPolicy {

    private final BufferedReader in;
    /** 用户选了「本会话都同意」之后就不再打扰他。 */
    private boolean approveEverything = false;

    public TerminalApproval(BufferedReader in) {
        this.in = in;
    }

    @Override
    public boolean approve(String action, String detail, String reason) {
        if (approveEverything) {
            return true;
        }
        System.out.println();
        System.out.println("┌─ 需要你确认 ────────────────────────────");
        System.out.println("│ " + action + ": " + detail);
        System.out.println("│ 原因: " + reason);
        System.out.println("└─ [y] 同意   [n] 拒绝   [a] 本会话都同意");
        System.out.print("> ");
        System.out.flush();

        try {
            String answer = in.readLine();
            if (answer == null) {
                // 管道输入等非交互场景读到 EOF，按拒绝处理
                System.out.println("(无法读取输入，按拒绝处理)");
                return false;
            }
            answer = answer.trim().toLowerCase();
            if ("a".equals(answer)) {
                approveEverything = true;
                System.out.println("(本会话后续不再询问)");
                return true;
            }
            return "y".equals(answer) || "yes".equals(answer);
        } catch (IOException e) {
            return false;   // 读不到就拒绝，永远往安全的方向倒
        }
    }
}
