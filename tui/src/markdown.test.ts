/**
 * 只测解析（纯函数）。渲染部分是 token → <Text> 的直译，没有逻辑可错。
 * 重点盯两类边界：流式中途的不完整输入、正文里长得像标记的普通字符。
 */

import { describe, expect, test } from 'bun:test'
import { parseBlocks, parseInline } from './markdown.tsx'

describe('行内标记', () => {
  test('粗体 / 行内代码 / 斜体', () => {
    expect(parseInline('用 **mvn** 跑 `test` 目标')).toEqual([
      { t: 'text', text: '用 ' },
      { t: 'bold', text: 'mvn' },
      { t: 'text', text: ' 跑 ' },
      { t: 'code', text: 'test' },
      { t: 'text', text: ' 目标' },
    ])
  })

  test('没配对的星号是普通文字，不能被吃掉', () => {
    expect(parseInline('2 * 3 = 6')).toEqual([{ t: 'text', text: '2 * 3 = 6' }])
    expect(parseInline('结尾一个 **')).toEqual([{ t: 'text', text: '结尾一个 **' }])
  })

  test('没闭合的反引号原样保留（流式中途的常态）', () => {
    expect(parseInline('看 `pom.xml')).toEqual([{ t: 'text', text: '看 `pom.xml' }])
  })

  test('空的行内代码 `` 不产生空 token', () => {
    // indexOf 从 i+1 找到的就是自己旁边那个反引号 → 空内容，当普通文字更稳
    const spans = parseInline('a `` b')
    expect(spans.map(s => s.t)).toContain('text')
  })
})

describe('块级结构', () => {
  test('围栏代码块带语言名，正文原样保留（包括缩进和星号）', () => {
    const blocks = parseBlocks('前文\n```java\nint a = 2 * 3;\n  return a;\n```\n后文')
    expect(blocks).toEqual([
      { t: 'paragraph', spans: [{ t: 'text', text: '前文' }] },
      { t: 'code', lang: 'java', lines: ['int a = 2 * 3;', '  return a;'] },
      { t: 'paragraph', spans: [{ t: 'text', text: '后文' }] },
    ])
  })

  test('没闭合的围栏吃到结尾——流式中途就是这个样子', () => {
    const blocks = parseBlocks('```python\nprint(1)\nprint(2)')
    expect(blocks).toEqual([{ t: 'code', lang: 'python', lines: ['print(1)', 'print(2)'] }])
  })

  test('代码块里的 # 和 - 不能被当成标题和列表', () => {
    const blocks = parseBlocks('```\n# 注释\n- 不是列表\n```')
    expect(blocks[0]?.t).toBe('code')
    expect(blocks).toHaveLength(1)
  })

  test('标题带级别', () => {
    expect(parseBlocks('## 核心能力')[0]).toMatchObject({ t: 'heading', level: 2 })
  })

  test('无序 / 有序列表，缩进折算层级', () => {
    const blocks = parseBlocks('- 第一条\n  - 子项\n1. 有序的')
    expect(blocks[0]).toMatchObject({ t: 'bullet', indent: 0 })
    expect(blocks[1]).toMatchObject({ t: 'bullet', indent: 1 })
    expect(blocks[2]).toMatchObject({ t: 'ordered', marker: '1.', indent: 0 })
  })

  test('分隔线和引用', () => {
    expect(parseBlocks('---')[0]).toEqual({ t: 'hr' })
    expect(parseBlocks('> 引用一句')[0]).toMatchObject({ t: 'quote' })
  })

  test('连续空行压成一个，首尾空行去掉', () => {
    const blocks = parseBlocks('\n\na\n\n\n\nb\n\n')
    expect(blocks.map(b => b.t)).toEqual(['paragraph', 'blank', 'paragraph'])
  })

  test('纯文本一行一个段落，一个字都不丢', () => {
    const blocks = parseBlocks('第一行\n第二行')
    expect(blocks).toHaveLength(2)
    expect(blocks[0]).toMatchObject({ t: 'paragraph', spans: [{ t: 'text', text: '第一行' }] })
  })
})
