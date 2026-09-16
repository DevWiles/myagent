/**
 * 危险动作确认框。
 *
 * 这个框出现的时候，后端的 agent 线程正卡在 `approve()` 上等回答——
 * 所以它必须能被回答，也必须能被中断（Esc 按拒绝），不能有第三种出路。
 *
 * **按键不在这里处理**，而在 `App` 里。理由正是上面那句：终端矮到连这个框都
 * 放不下时（`planBottomStack` 会返回 `approval: 'none'`），这个组件根本不渲染，
 * 可后端还在等。按键要是跟着组件一起消失，会话就永久挂死了。
 * 所以这里只负责画，`y/n/a` 由 App 无条件接住。
 *
 * 高度是**约定死的**：`APPROVAL_ROWS` / `APPROVAL_COMPACT_ROWS` 由本文件和
 * `layout.ts` 共用。以前 App 里写死一个 8、这边想长多高长多高，结果 `cols=44`
 * 时按钮行折行，把「动作: 详情」那一行整个挤没了 —— 那是线上 bug，不是隐患。
 */

import { Box, Text } from 'ink'
import { APPROVAL_COMPACT_ROWS, APPROVAL_ROWS } from '../layout.ts'
import { truncate } from '../format.ts'
import type { Approval } from '../store.ts'
import { useTheme } from '../theme.ts'

type Props = {
  approval: Approval
  columns: number
  /** 'compact' 去掉边框和空行，3 行塞完，给矮终端用。 */
  variant?: 'full' | 'compact'
}

export function ApprovalDialog({ approval, columns, variant = 'full' }: Props) {
  const theme = useTheme()
  const compact = variant === 'compact'
  // 边框 + paddingX 各吃掉 2 列
  const inner = Math.max(12, columns - (compact ? 0 : 4))

  const action = (
    <Text wrap="truncate">
      <Text color={theme.text} bold>
        {approval.action}:{' '}
      </Text>
      <Text color={theme.text}>
        {truncate(approval.detail, Math.max(4, inner - approval.action.length - 2))}
      </Text>
    </Text>
  )
  const reason = (
    <Text color={theme.subtle} wrap="truncate">
      {truncate(`原因: ${approval.reason}`, inner)}
    </Text>
  )
  // 按钮压成**一个** Text：拆成多个 Text 时窄屏会折行，框就长高一行，
  // 把约定的高度撑破。窄屏换短文案，但仍然是一行。
  const buttons =
    inner >= 40 ? (
      <Text wrap="truncate">
        <Text color={theme.success}>[y]</Text>
        <Text color={theme.subtle}> 同意   </Text>
        <Text color={theme.error}>[n]</Text>
        <Text color={theme.subtle}> 拒绝（Esc 同）   </Text>
        <Text color={theme.warning}>[a]</Text>
        <Text color={theme.subtle}> 本会话都同意</Text>
      </Text>
    ) : (
      <Text wrap="truncate">
        <Text color={theme.success}>[y]</Text>
        <Text color={theme.subtle}>同意 </Text>
        <Text color={theme.error}>[n]</Text>
        <Text color={theme.subtle}>拒绝 </Text>
        <Text color={theme.warning}>[a]</Text>
        <Text color={theme.subtle}>全同意</Text>
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
        {action}
        {reason}
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
      borderColor={theme.approvalBorder}
      paddingX={1}
    >
      <Text color={theme.approvalBorder} bold wrap="truncate">
        需要确认
      </Text>
      <Box marginTop={1} flexShrink={0}>
        {action}
      </Box>
      {reason}
      <Box marginTop={1} flexShrink={0}>
        {buttons}
      </Box>
    </Box>
  )
}
