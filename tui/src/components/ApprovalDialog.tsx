/**
 * 危险动作确认框。
 *
 * 这个框出现的时候，后端的 agent 线程正卡在 approve() 上等回答——
 * 所以它必须能被回答，也必须能被中断（Esc 按拒绝），不能有第三种出路。
 */

import { Box, Text, useInput } from 'ink'
import { truncate } from '../format.ts'
import type { ApprovalDecision } from '../protocol.ts'
import type { Approval } from '../store.ts'
import { useTheme } from '../theme.ts'

type Props = {
  approval: Approval
  columns: number
  onDecide: (decision: ApprovalDecision) => void
}

export function ApprovalDialog({ approval, columns, onDecide }: Props) {
  const theme = useTheme()

  useInput((input, key) => {
    const answer = input.toLowerCase()
    if (answer === 'y') onDecide('yes')
    else if (answer === 'n' || key.escape) onDecide('no')
    else if (answer === 'a') onDecide('always')
  })

  const inner = Math.max(20, columns - 6)

  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={theme.approvalBorder}
      paddingX={1}
    >
      <Text color={theme.approvalBorder} bold>
        需要确认
      </Text>
      <Box marginTop={1}>
        <Text color={theme.text} bold>
          {approval.action}:{' '}
        </Text>
        <Text color={theme.text}>{truncate(approval.detail, inner - approval.action.length)}</Text>
      </Box>
      <Text color={theme.subtle}>{truncate(`原因: ${approval.reason}`, inner)}</Text>
      <Box marginTop={1}>
        <Text color={theme.success}>[y]</Text>
        <Text color={theme.subtle}> 同意   </Text>
        <Text color={theme.error}>[n]</Text>
        <Text color={theme.subtle}> 拒绝（Esc 同）   </Text>
        <Text color={theme.warning}>[a]</Text>
        <Text color={theme.subtle}> 本会话都同意</Text>
      </Box>
    </Box>
  )
}
