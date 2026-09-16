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
  /**
   * 上报对话内容（不含 header）的真实行数。
   * App 用它决定「历史是否已铺满屏幕」，从而让固定在顶部的招牌让位。
   * 之所以要排除 header：招牌显示与否会改变可用高度，若把它算进来，
   * 「显示招牌 → 高度变小 → 判定溢出 → 隐藏招牌 → 高度变大 → 判定不溢出」
   * 会来回抖。只看内容本身，这个量和招牌是否显示无关。
   */
  onEntriesHeight?: (rows: number) => void
  /**
   * 变一次就重新测量一遍布局。resize 后 Yoga 的折行结果全变了，
   * 必须重测，否则 hitTest 用的还是旧行坐标（点击会点偏）。
   * 用 prop 而不是给本组件换 key：换 key 会让父组件持有的 ref 失效。
   */
  repaintToken?: number
}

/** 一个工具组在视口里占据的行区间（含 y0，不含 y1）。 */
type HitSpan = { key: string; y0: number; y1: number }

export function TranscriptView({
  entries,
  expanded,
  height,
  columns,
  header,
  handleRef,
  onEntriesHeight,
  repaintToken,
}: Props) {
  const theme = useTheme()
  const contentRef = useRef<DOMElement>(null)
  const headerRef = useRef<DOMElement>(null)
  /** key → 该工具组的 wrapper 元素，用于 measureElement。 */
  const rowRefs = useRef(new Map<string, DOMElement>())
  const hitMap = useRef<HitSpan[]>([])
  /** 上次上报给父组件的内容行数。只有真的变了才通知，避免无谓的 setState。 */
  const lastReported = useRef(-1)

  const [offset, setOffset] = useState(0)
  const [contentHeight, setContentHeight] = useState(0)

  // 尺寸变了：回到底部。折行全变了，旧的 offset 对应不到原来的位置，
  // 与其停在一个错位的地方，不如落到「最新内容」这个确定的锚点。
  useEffect(() => {
    setOffset(0)
  }, [repaintToken, columns, height])

  // 每次 commit 后：量内容总高，并重建 hitMap（每个工具组的视口行区间）。
  // 依赖数组留空是刻意的——折行随宽度变，内容没变宽度变了高度也变，每次都量。
  //
  // 这个 effect 每次 commit 都跑，所以**任何 setState 都必须先判等再调**。
  // 否则就是「渲染 → 测量 → setState → 渲染」的自激循环：流式文本一秒几十次
  // 更新，叠上这个循环会直接撞穿 React 的嵌套更新上限
  // （Maximum update depth exceeded），进程崩掉。
  useEffect(() => {
    if (contentRef.current) {
      const measured = measureElement(contentRef.current).height
      if (measured !== contentHeight) setContentHeight(measured)
    }
    // 内容坐标：header 高度打头，随后按 entries 顺序累加各元素高度
    const spans: HitSpan[] = []
    const headerH = headerRef.current ? measureElement(headerRef.current).height : 0
    let y = headerH
    for (const entry of entries) {
      const el = rowRefs.current.get(entry.key)
      const h = el ? measureElement(el).height : 0
      if (entry.kind === 'tools') {
        spans.push({ key: entry.key, y0: y, y1: y + h })
      }
      y += h
    }
    hitMap.current = spans

    // 判等后再上报：父组件拿它做 setState，每帧无条件调用会把上面说的
    // 自激循环坐实。数值没变就什么都不做。
    const entriesHeight = y - headerH
    if (entriesHeight !== lastReported.current) {
      lastReported.current = entriesHeight
      onEntriesHeight?.(entriesHeight)
    }
  })

  const maxOffset = Math.max(0, contentHeight - height)
  const clamped = Math.min(offset, maxOffset)
  const hiddenAbove = Math.max(0, contentHeight - height - clamped)
  // 内容不够高时，剩下的行要用空格铺满。
  // 空的 Box 不产生任何字符，Ink 就什么都不写，那些格子上一帧的字符会留着 ——
  // 这正是缩放后出现残影的根源之一。铺上空格，画面就自带擦除能力。
  const blankRows = Math.max(0, height - contentHeight)

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
      {blankRows > 0 &&
        Array.from({ length: blankRows }, (_, i) => (
          <Text key={`blank-${i}`}>{' '.repeat(Math.max(1, columns))}</Text>
        ))}
      {clamped > 0 && (
        <Box position="absolute" marginTop={height - 1} width={columns} justifyContent="flex-end">
          <Text color={theme.warning}> ↓ 还有新内容 · End 回到底部 </Text>
        </Box>
      )}
    </Box>
  )
}
