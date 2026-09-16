/**
 * Markdown → Ink 元素。
 *
 * 模型的回答里大量是代码块、列表和加粗，纯文本模式下 `**` 和 ``` 满屏都是，
 * 可读性损失最大的就是这里。但终端不是浏览器，全量 CommonMark 没有意义——
 * 只做模型输出里实际高频出现的子集：
 *
 *   块级: 围栏代码块 / 标题 / 无序列表 / 有序列表 / 引用 / 分隔线 / 段落
 *   行内: **粗体** / *斜体* / `行内代码`
 *
 * 解析（纯函数，可测试）和渲染（Ink 组件）分开：解析产出 token 树，
 * 渲染只是把树映射成 <Text>。流式场景下每来一段增量就整体重新解析——
 * 单条消息几 KB 的量级，解析是微秒级的，不值得做增量解析。
 * 没闭合的代码围栏按「一直开到结尾」处理，这正是流式中途的样子。
 */

import { Box, Text } from 'ink'
import type { ReactNode } from 'react'
import { useTheme, type Theme } from './theme.ts'

// ------------------------------------------------------------------ 解析

export type Inline =
  | { t: 'text'; text: string }
  | { t: 'bold'; text: string }
  | { t: 'italic'; text: string }
  | { t: 'code'; text: string }

export type Block =
  | { t: 'paragraph'; spans: Inline[] }
  | { t: 'heading'; level: number; spans: Inline[] }
  | { t: 'bullet'; spans: Inline[]; indent: number }
  | { t: 'ordered'; marker: string; spans: Inline[]; indent: number }
  | { t: 'quote'; spans: Inline[] }
  | { t: 'code'; lang: string; lines: string[] }
  | { t: 'hr' }
  | { t: 'blank' }

/**
 * 行内标记。一遍扫描，遇到开标记就找对应的闭标记，找不到就当普通文字——
 * 宁可少高亮，不能把没配对的星号吃掉（用户的原文里真的会出现 `2 * 3`）。
 */
export function parseInline(text: string): Inline[] {
  const spans: Inline[] = []
  let plain = ''
  let i = 0

  const flush = () => {
    if (plain) {
      spans.push({ t: 'text', text: plain })
      plain = ''
    }
  }

  while (i < text.length) {
    // `code`
    if (text[i] === '`') {
      const end = text.indexOf('`', i + 1)
      if (end > i) {
        flush()
        spans.push({ t: 'code', text: text.slice(i + 1, end) })
        i = end + 1
        continue
      }
    }
    // **bold**
    if (text.startsWith('**', i)) {
      const end = text.indexOf('**', i + 2)
      if (end > i + 1) {
        flush()
        spans.push({ t: 'bold', text: text.slice(i + 2, end) })
        i = end + 2
        continue
      }
    }
    // *italic* —— 排除 "2 * 3" 这种：紧跟开星号的不能是空格
    if (text[i] === '*' && text[i + 1] !== ' ' && text[i + 1] !== '*' && text[i + 1] !== undefined) {
      const end = text.indexOf('*', i + 1)
      if (end > i + 1 && text[end - 1] !== ' ') {
        flush()
        spans.push({ t: 'italic', text: text.slice(i + 1, end) })
        i = end + 1
        continue
      }
    }
    plain += text[i]
    i++
  }
  flush()
  return spans
}

const FENCE = /^\s*```(.*)$/
const HEADING = /^(#{1,6})\s+(.*)$/
const BULLET = /^(\s*)[-*]\s+(.*)$/
const ORDERED = /^(\s*)(\d+)[.)]\s+(.*)$/
const QUOTE = /^\s*>\s?(.*)$/
const HR = /^\s*(-{3,}|\*{3,}|_{3,})\s*$/

export function parseBlocks(markdown: string): Block[] {
  const blocks: Block[] = []
  const lines = markdown.split('\n')
  let i = 0

  while (i < lines.length) {
    const line = lines[i]!

    const fence = FENCE.exec(line)
    if (fence) {
      // 收集到闭合围栏；流式中途没闭合就吃到结尾，正是渲染中间态想要的样子
      const lang = (fence[1] ?? '').trim()
      const body: string[] = []
      i++
      while (i < lines.length && !FENCE.test(lines[i]!)) {
        body.push(lines[i]!)
        i++
      }
      i++ // 跳过闭合围栏（越界也无妨）
      blocks.push({ t: 'code', lang, lines: body })
      continue
    }

    if (line.trim() === '') {
      blocks.push({ t: 'blank' })
      i++
      continue
    }
    const heading = HEADING.exec(line)
    if (heading) {
      blocks.push({ t: 'heading', level: heading[1]!.length, spans: parseInline(heading[2]!) })
      i++
      continue
    }
    if (HR.test(line)) {
      blocks.push({ t: 'hr' })
      i++
      continue
    }
    const bullet = BULLET.exec(line)
    if (bullet) {
      blocks.push({
        t: 'bullet',
        indent: Math.floor(bullet[1]!.length / 2),
        spans: parseInline(bullet[2]!),
      })
      i++
      continue
    }
    const ordered = ORDERED.exec(line)
    if (ordered) {
      blocks.push({
        t: 'ordered',
        indent: Math.floor(ordered[1]!.length / 2),
        marker: `${ordered[2]}.`,
        spans: parseInline(ordered[3]!),
      })
      i++
      continue
    }
    const quote = QUOTE.exec(line)
    if (quote) {
      blocks.push({ t: 'quote', spans: parseInline(quote[1]!) })
      i++
      continue
    }
    blocks.push({ t: 'paragraph', spans: parseInline(line) })
    i++
  }

  // 连续空行压成一个：模型爱输出双空行，终端里空一行就够了
  const out: Block[] = []
  for (const block of blocks) {
    if (block.t === 'blank' && out[out.length - 1]?.t === 'blank') continue
    out.push(block)
  }
  // 首尾空行没有意义
  while (out[0]?.t === 'blank') out.shift()
  while (out[out.length - 1]?.t === 'blank') out.pop()
  return out
}

// ------------------------------------------------------------------ 渲染

function renderSpans(spans: Inline[], theme: Theme, base: string): ReactNode[] {
  return spans.map((span, i) => {
    switch (span.t) {
      case 'bold':
        return (
          <Text key={i} color={base} bold>
            {span.text}
          </Text>
        )
      case 'italic':
        return (
          <Text key={i} color={base} italic>
            {span.text}
          </Text>
        )
      case 'code':
        return (
          <Text key={i} color={theme.inlineCode}>
            {span.text}
          </Text>
        )
      default:
        return (
          <Text key={i} color={base}>
            {span.text}
          </Text>
        )
    }
  })
}

type Props = {
  markdown: string
  /** 正文基础色。thought 之类的场景想统一染成别的颜色。 */
  color?: string
}

export function Markdown({ markdown, color }: Props) {
  const theme = useTheme()
  const base = color ?? theme.text
  const blocks = parseBlocks(markdown)

  return (
    <Box flexDirection="column">
      {blocks.map((block, i) => {
        switch (block.t) {
          case 'blank':
            return <Text key={i}> </Text>
          case 'heading':
            return (
              <Text key={i} color={theme.heading} bold underline={block.level <= 2}>
                {renderSpans(block.spans, theme, theme.heading)}
              </Text>
            )
          case 'hr':
            return (
              <Text key={i} color={theme.inactive}>
                {'─'.repeat(30)}
              </Text>
            )
          case 'bullet':
            return (
              <Box key={i} marginLeft={block.indent * 2}>
                <Text color={theme.subtle}>{'• '}</Text>
                <Box flexGrow={1}>
                  <Text>{renderSpans(block.spans, theme, base)}</Text>
                </Box>
              </Box>
            )
          case 'ordered':
            return (
              <Box key={i} marginLeft={block.indent * 2}>
                <Text color={theme.subtle}>{block.marker} </Text>
                <Box flexGrow={1}>
                  <Text>{renderSpans(block.spans, theme, base)}</Text>
                </Box>
              </Box>
            )
          case 'quote':
            return (
              <Box key={i}>
                <Text color={theme.inactive}>{'┃ '}</Text>
                <Box flexGrow={1}>
                  <Text color={theme.subtle} italic>
                    {renderSpans(block.spans, theme, theme.subtle)}
                  </Text>
                </Box>
              </Box>
            )
          case 'code':
            return (
              <Box key={i} flexDirection="column" marginLeft={2}>
                {block.lang !== '' && (
                  <Text color={theme.inactive}>{block.lang}</Text>
                )}
                {(block.lines.length > 0 ? block.lines : ['']).map((line, j) => (
                  <Text key={j} color={theme.codeBlock}>
                    {line === '' ? ' ' : line}
                  </Text>
                ))}
              </Box>
            )
          default:
            return <Text key={i}>{renderSpans(block.spans, theme, base)}</Text>
        }
      })}
    </Box>
  )
}
