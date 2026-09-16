import { describe, expect, test } from 'bun:test'
import { LOGO_LINES, LOGO_WIDTH, formatDuration, formatTokens, summarizeTools, truncate } from './format.ts'
import type { ToolCall } from './store.ts'

function call(tool: string): ToolCall {
  return { id: 1, tool, args: '{}', running: false, ok: true }
}

describe('工具摘要', () => {
  test('按调用顺序合并计数，说法和 Java 版一致', () => {
    expect(summarizeTools([call('read_file'), call('read_file'), call('bash')]))
      .toBe('读取 2 个文件 · 执行 1 条命令')
  })

  test('顺序按首次出现，不排序——用户脑子里的顺序就是发生顺序', () => {
    expect(summarizeTools([call('bash'), call('read_file')]))
      .toBe('执行 1 条命令 · 读取 1 个文件')
  })

  test('没登记过的工具退回通用说法，而不是显示空白', () => {
    expect(summarizeTools([call('grep_repo')])).toBe('调用 1 次 grep_repo')
  })

  test('空列表返回空串', () => {
    expect(summarizeTools([])).toBe('')
  })
})

describe('截断', () => {
  test('不超长就原样返回', () => {
    expect(truncate('abc', 10)).toBe('abc')
  })

  test('超长用 … 收尾，且总宽度不超过上限', () => {
    const out = truncate('abcdefghij', 5)
    expect(out).toBe('abcd…')
    expect([...out]).toHaveLength(5)
  })

  test('按 code point 切，不把 emoji 劈成两半', () => {
    // '👍' 是 UTF-16 里的两个 code unit，按下标切会切出乱码
    expect(truncate('👍👍👍', 2)).toBe('👍…')
  })
})

describe('格式化', () => {
  test('耗时：毫秒 / 秒 / 分秒', () => {
    expect(formatDuration(850)).toBe('850ms')
    expect(formatDuration(4210)).toBe('4.2s')
    expect(formatDuration(95_000)).toBe('1m35s')
  })

  test('token 数上千用 k，读起来省事', () => {
    expect(formatTokens(918)).toBe('918')
    expect(formatTokens(64_000)).toBe('64.0k')
  })
})

describe('招牌', () => {
  test('每一行宽度必须完全相同，差一个字符整幅图就错位', () => {
    for (const line of LOGO_LINES) {
      expect([...line]).toHaveLength(LOGO_WIDTH)
    }
  })
})
