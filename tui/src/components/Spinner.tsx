/**
 * 等模型响应时的活着信号。
 *
 * 一次 run 可能跑十几轮、花上一分钟，期间屏幕如果一个字都不动，
 * 用户会以为程序卡死了。所以这里必须动，而且要带上已经花了多久。
 *
 * 高度恒定 1 行：以前这是个不受约束的 Box，窄屏下
 * 「⠋ 正在思考 · 第 3 轮 · 12.3s · Esc 中断」会折成两行，
 * 而 App 只给它记了 1 行，于是底部整体被顶偏。
 */

import { Box, Text } from 'ink'
import { useEffect, useState } from 'react'
import { SPINNER_ROWS } from '../layout.ts'
import { SPINNER_VERBS, formatDuration } from '../format.ts'
import { useTheme } from '../theme.ts'

const FRAMES = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏']
const FRAME_MS = 80
/** 动词多久换一个。太快会让人眼花，太慢又显得卡住了。 */
const VERB_EVERY_FRAMES = Math.round(4000 / FRAME_MS)

type Props = {
  step: number
  startedAt: number | null
  columns: number
}

export function Spinner({ step, startedAt, columns }: Props) {
  const theme = useTheme()
  const [tick, setTick] = useState(0)

  useEffect(() => {
    const timer = setInterval(() => setTick(t => t + 1), FRAME_MS)
    return () => clearInterval(timer)
  }, [])

  const frame = FRAMES[tick % FRAMES.length]
  const verb = SPINNER_VERBS[Math.floor(tick / VERB_EVERY_FRAMES) % SPINNER_VERBS.length]
  const elapsed = startedAt === null ? null : Date.now() - startedAt

  return (
    <Box width={columns} height={SPINNER_ROWS} flexShrink={0} overflow="hidden">
      <Text color={theme.spinner} wrap="truncate">{frame} </Text>
      <Text color={theme.text} wrap="truncate">{verb}</Text>
      <Text color={theme.subtle} wrap="truncate">
        {step > 0 ? ` · 第 ${step} 轮` : ''}
        {elapsed !== null ? ` · ${formatDuration(elapsed)}` : ''}
        {' · Esc 中断'}
      </Text>
    </Box>
  )
}
