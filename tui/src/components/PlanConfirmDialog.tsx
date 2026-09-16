/**
 * 「执行这份计划?」确认框。
 *
 * 计划本身在 transcript 里（PlanPanel），这个框只负责问一句——所以它比审批框
 * 还简单，不用复述内容。和 ApprovalDialog 同一套纪律：
 *
 * - **按键不在这里处理**，在 App 里。矮终端下这个框可能整个不渲染
 *   （planBottomStack 降到 'none'），但后端正卡在 confirm() 上等回答，
 *   按键必须无条件活着，否则会话挂死。
 * - 高度**借用** APPROVAL_ROWS / APPROVAL_COMPACT_ROWS 的约定：确认框和审批框
 *   永不同时出现（一个在执行前、一个在执行中），共用一套高度就不用给
 *   layout.ts 添第二根降级梯。
 * - 刻意没有「本会话都同意」：每份计划都不一样，一次性授权掉后面所有计划
 *   是危险的——审批有 [a]，这里没有，是有意的差别（docs/protocol.md 第 7 节）。
 */

import { Box, Text } from 'ink'
import { APPROVAL_COMPACT_ROWS, APPROVAL_ROWS } from '../layout.ts'
import { useTheme } from '../theme.ts'

type Props = {
  columns: number
  /** 'compact' 去掉边框和空行，给矮终端用。档位由 planBottomStack 决定。 */
  variant?: 'full' | 'compact'
}

export function PlanConfirmDialog({ columns, variant = 'full' }: Props) {
  const theme = useTheme()
  const compact = variant === 'compact'

  const hint = (
    <Text color={theme.subtle} wrap="truncate">
      计划在上方——往上滚可以完整过一遍再决定
    </Text>
  )
  // 按钮压成一个 Text：拆开的话窄屏折行会把约定的高度撑破（和 ApprovalDialog 同一个坑）
  const buttons = (
    <Text wrap="truncate">
      <Text color={theme.success}>[y]</Text>
      <Text color={theme.subtle}> 执行（Enter 同）   </Text>
      <Text color={theme.error}>[n]</Text>
      <Text color={theme.subtle}> 放弃（Esc 同）</Text>
    </Text>
  )

  if (compact) {
    return (
      <Box
        flexDirection="column"
        width={columns}
        height={APPROVAL_COMPACT_ROWS}
        flexShrink={0}
        overflow="hidden"
      >
        <Text color={theme.brand} bold wrap="truncate">
          执行这份计划?
        </Text>
        {hint}
        {buttons}
      </Box>
    )
  }

  return (
    <Box
      flexDirection="column"
      width={columns}
      height={APPROVAL_ROWS}
      flexShrink={0}
      overflow="hidden"
      borderStyle="round"
      borderColor={theme.brand}
      paddingX={1}
    >
      <Text color={theme.brand} bold wrap="truncate">
        执行这份计划?
      </Text>
      <Box marginTop={1} flexShrink={0}>
        {hint}
      </Box>
      <Text color={theme.subtle} wrap="truncate">
        看不对就放弃——现在毙掉只花一次规划的钱，跑完再发现代价是十倍
      </Text>
      <Box marginTop={1} flexShrink={0}>
        {buttons}
      </Box>
    </Box>
  )
}
