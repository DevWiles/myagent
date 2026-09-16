/**
 * 危险动作确认框。
 *
 * 这个框出现的时候，后端的 agent 线程正卡在 `approve()` 上等回答——
 * 所以它必须能被回答，也必须能被中断（Esc 按拒绝），不能有第三种出路。
 *
 * **按键不在这里处理**，在 App 里（`useApprovalKeys`）。理由是：
 * 终端矮到放不下这个框时组件可能根本不渲染，可后端还在等。
 * 按键要是跟着组件一起消失，会话就永久挂死了。这条纪律从 Ink 版原样继承。
 *
 * 按钮行压成**一行**：窄屏折行会把框撑高，把上面的对话内容顶掉。
 */

import { TextAttributes } from "@opentui/core"
import { createMemo } from "solid-js"
import type { Approval } from "../../../src/store.ts"
import { truncateEnd, width } from "../path.ts"
import { useTheme } from "../theme.ts"

export type ApprovalDialogProps = {
  approval: Approval
  columns: number
}

export function ApprovalDialog(props: ApprovalDialogProps) {
  const theme = useTheme()
  // 边框 2 列 + 左右内边距 2 列
  const inner = () => Math.max(12, props.columns - 4)

  // 预算要按**显示宽度**减，不能用 .length：
  // 「执行命令」是 4 个字符但占 8 列，按字符数算会少扣 4 列，
  // 长命令就正好溢出一点点，把动作行折成两行，框被撑高、按钮行顶出可视区。
  const detail = createMemo(() =>
    truncateEnd(props.approval.detail, Math.max(4, inner() - width(props.approval.action) - 2)),
  )
  const reason = createMemo(() => truncateEnd(`原因: ${props.approval.reason}`, inner()))

  return (
    <box
      flexDirection="column"
      flexShrink={0}
      border
      borderColor={theme.approvalBorder}
      paddingLeft={1}
      paddingRight={1}
    >
      <text fg={theme.approvalBorder} attributes={TextAttributes.BOLD}>
        需要确认
      </text>

      <box flexDirection="row" paddingTop={1}>
        <text fg={theme.text} attributes={TextAttributes.BOLD}>
          {props.approval.action}:{" "}
        </text>
        <text fg={theme.text}>{detail()}</text>
      </box>
      <text fg={theme.subtle}>{reason()}</text>

      <box flexDirection="row" paddingTop={1}>
        <text fg={theme.success}>[y]</text>
        <text fg={theme.subtle}>{inner() >= 40 ? " 同意   " : "同意 "}</text>
        <text fg={theme.error}>[n]</text>
        <text fg={theme.subtle}>{inner() >= 40 ? " 拒绝（Esc 同）   " : "拒绝 "}</text>
        <text fg={theme.warning}>[a]</text>
        <text fg={theme.subtle}>{inner() >= 40 ? " 本会话都同意" : "全同意"}</text>
      </box>
    </box>
  )
}
