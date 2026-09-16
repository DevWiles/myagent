/**
 * 对话记录（inline 版）。
 *
 * 和之前那个 TranscriptView 的区别：**没有视口、没有自绘滚动**。
 * inline 模式下内容直接往主屏下方铺，往上翻是终端 scrollback 的事
 * （滚轮 / fn+↑↓ / Shift-PgUp），我们不该抢这件事 ——
 * 抢了就得自己实现滚动、命中测试、选区……最后还不如终端原生的好用。
 *
 * 招牌作为第一项跟着一起往上走，所以退出后往上翻还能看到它。
 */

import { Box } from 'ink'
import type { Entry } from '../store.ts'
import { EntryView } from './EntryView.tsx'

type Props = {
  entries: readonly Entry[]
  expanded: ReadonlySet<string>
  columns: number
  header?: React.ReactNode
}

export function Transcript({ entries, expanded, columns, header }: Props) {
  return (
    <Box flexDirection="column">
      {header}
      {entries.map(entry => (
        <EntryView
          key={entry.key}
          entry={entry}
          expanded={expanded.has(entry.key)}
          columns={columns}
        />
      ))}
    </Box>
  )
}
