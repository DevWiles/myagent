/**
 * 「执行这份计划?」确认框。
 *
 * 计划本身在对话流里，这个框只负责问一句，所以比审批框还简单，不用复述内容。
 * 和 ApprovalDialog 同一套纪律：**按键不在这里处理**，在 App 里——
 * 后端此刻卡在 confirm() 上等回答，按键必须无条件活着。
 *
 * 刻意**没有「本会话都同意」**：每份计划都不一样，一次性授权掉后面所有计划
 * 是危险的。审批有 [a]、这里没有，是有意的差别（见 docs/protocol.md）。
 */

import { TextAttributes } from "@opentui/core"
import { useTheme } from "../theme.ts"

export function PlanConfirmDialog() {
  const theme = useTheme()

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
        执行这份计划？
      </text>

      <box paddingTop={1}>
        <text fg={theme.subtle}>计划在上方——往上滚可以完整过一遍再决定</text>
      </box>

      <box flexDirection="row" paddingTop={1}>
        <text fg={theme.success}>[y]</text>
        <text fg={theme.subtle}> 执行（Enter 同）   </text>
        <text fg={theme.error}>[n]</text>
        <text fg={theme.subtle}> 放弃（Esc 同）</text>
      </box>
    </box>
  )
}
