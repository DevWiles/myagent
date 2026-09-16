/**
 * 对话记录的滚动视口 + 鼠标命中测试。
 *
 * 备用屏幕里全部内容都是活的 React 元素，滚动我们自己做：外层固定视口高度 +
 * overflow:hidden，内层整段用负 marginTop 往上平移。内层真实高度、以及每个
 * 工具组占据哪几行，都用 measureElement 在每次 commit 后量出来——文本折行后
 * 的行数只有排版引擎知道，自己数会数错。
 *
 * 命中测试（hitTest）是「鼠标点击展开」的关键：把点击的屏幕行号映射回是哪个
 * 工具组。每个工具组包一层带 ref 的 Box，量出它在视口里的行区间存进 hitMap，
 * 点击时二分查一下就知道点中了谁。只有工具组登记进 hitMap——只有它能展开。
 */

import { Box, Text, measureElement } from 'ink'
import type { DOMElement } from 'ink'
import { useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'
import type { Entry } from '../store.ts'
import { useTheme } from '../theme.ts'
import { EntryView } from './EntryView.tsx'

export type TranscriptHandle = {
  scrollBy(lines: number): void
  scrollToTop(): void
  scrollToBottom(): void
  /** 屏幕行号（0 起，相对视口顶）→ 该行所在的工具组 key，没有则 null。 */
  hitTest(screenRow: number): string | null
}

type Props = {
  entries: readonly Entry[]
  expanded: ReadonlySet<string>
  height: number
  columns: number
  header?: React.ReactNode
  handleRef?: Ref<TranscriptHandle>
}

/** 一个工具组在视口里占据的行区间（含 y0，不含 y1）。 */
type HitSpan = { key: string; y0: number; y1: number }

export function TranscriptView({ entries, expanded, height, columns, header, handleRef }: Props) {
  const theme = useTheme()
  const contentRef = useRef<DOMElement>(null)
  const headerRef = useRef<DOMElement>(null)
  /** key → 该工具组的 wrapper 元素，用于 measureElement。 */
  const rowRefs = useRef(new Map<string, DOMElement>())
  const hitMap = useRef<HitSpan[]>([])

  const [offset, setOffset] = useState(0)
  const [contentHeight, setContentHeight] = useState(0)

  // 每次 commit 后：量内容总高，并重建 hitMap（每个工具组的视口行区间）。
  // 依赖数组留空是刻意的——折行随宽度变，内容没变宽度变了高度也变，每次都量。
  useEffect(() => {
    if (contentRef.current) {
      const measured = measureElement(contentRef.current).height
      if (measured !== contentHeight) setContentHeight(measured)
    }
    // 内容坐标：header 高度打头，随后按 entries 顺序累加各元素高度
    const spans: HitSpan[] = []
    let y = headerRef.current ? measureElement(headerRef.current).height : 0
    for (const entry of entries) {
      const el = rowRefs.current.get(entry.key)
      const h = el ? measureElement(el).height : 0
      if (entry.kind === 'tools') {
        spans.push({ key: entry.key, y0: y, y1: y + h })
      }
      y += h
    }
    hitMap.current = spans
  })

  const maxOffset = Math.max(0, contentHeight - height)
  const clamped = Math.min(offset, maxOffset)
  const hiddenAbove = Math.max(0, contentHeight - height - clamped)

  useImperativeHandle(handleRef, () => ({
    scrollBy(lines) {
      setOffset(cur => Math.max(0, Math.min(maxOffset, cur + lines)))
    },
    scrollToTop() {
      setOffset(maxOffset)
    },
    scrollToBottom() {
      setOffset(0)
    },
    hitTest(screenRow) {
      // 屏幕行 → 内容坐标：加回被平移隐藏的高度
      const contentY = screenRow + hiddenAbove
      for (const span of hitMap.current) {
        if (contentY >= span.y0 && contentY < span.y1) return span.key
      }
      return null
    },
  }), [maxOffset, hiddenAbove])

  return (
    <Box flexDirection="column" height={height} overflow="hidden">
      <Box flexDirection="column" flexShrink={0} marginTop={-hiddenAbove} ref={contentRef}>
        {header && (
          <Box flexShrink={0} ref={headerRef}>
            {header}
          </Box>
        )}
        {entries.map(entry => (
          <Box
            key={entry.key}
            flexShrink={0}
            ref={el => {
              if (el) rowRefs.current.set(entry.key, el)
              else rowRefs.current.delete(entry.key)
            }}
          >
            <EntryView entry={entry} expanded={expanded.has(entry.key)} columns={columns} />
          </Box>
        ))}
      </Box>
      {clamped > 0 && (
        <Box position="absolute" marginTop={height - 1} width={columns} justifyContent="flex-end">
          <Text color={theme.warning}> ↓ 还有新内容 · End 回到底部 </Text>
        </Box>
      )}
    </Box>
  )
}
