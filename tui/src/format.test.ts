import { describe, expect, test } from 'bun:test'
import {
  LOGO_LINES,
  LOGO_WIDTH,
  formatBytes,
  formatDuration,
  formatRelativeTime,
  formatTokens,
  summarizeTools,
  truncate,
} from './format.ts'
import type { ToolCall } from './store.ts'

function call(tool: string, args = '{}'): ToolCall {
  return { id: 1, tool, args, running: false, ok: true }
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

  test('检索只调一次时显示模式本身——「搜索 1 次」几乎不含信息', () => {
    expect(summarizeTools([call('grep', '{"pattern":"AgentRole"}')]))
      .toBe('搜索 AgentRole')
    expect(summarizeTools([call('glob', '{"pattern":"**/*Test.java"}')]))
      .toBe('找文件 **/*Test.java')
  })

  test('检索调了好几次就退回计数，不然一行会被一串模式撑爆', () => {
    expect(summarizeTools([
      call('grep', '{"pattern":"a"}'),
      call('grep', '{"pattern":"b"}'),
    ])).toBe('搜索 2 次')
  })

  test('过长的模式要截断，这一行还要和别的摘要并排放', () => {
    const long = 'x'.repeat(80)
    const out = summarizeTools([call('grep', JSON.stringify({ pattern: long }))])
    expect(out.length).toBeLessThan(45)
    expect(out.endsWith('…')).toBe(true)
  })

  test('参数不是合法 JSON 时退回计数，绝不抛', () => {
    // 参数是模型生成的，什么都可能来。摘要是装饰，不该有能力让界面崩掉
    expect(summarizeTools([call('grep', '{截断的半截')])).toBe('搜索 1 次')
    expect(summarizeTools([call('grep', '{}')])).toBe('搜索 1 次')
  })

  test('检索和别的工具混在一起时说法各自成立', () => {
    expect(summarizeTools([
      call('grep', '{"pattern":"foo"}'),
      call('read_file'),
      call('read_file'),
    ])).toBe('搜索 foo · 读取 2 个文件')
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

describe("相对时间", () => {
  const at = (y: number, mo: number, d: number, h: number, mi: number) =>
    new Date(y, mo - 1, d, h, mi).getTime()
  const now = at(2026, 9, 18, 14, 0)

  test("一分钟内说「刚刚」", () => {
    expect(formatRelativeTime(now, now)).toBe("刚刚")
    expect(formatRelativeTime(now - 59_000, now)).toBe("刚刚")
  })

  test("一小时内报分钟，六小时内报小时", () => {
    expect(formatRelativeTime(now - 60_000, now)).toBe("1 分钟前")
    expect(formatRelativeTime(now - 4 * 60_000, now)).toBe("4 分钟前")
    expect(formatRelativeTime(now - 3600_000, now)).toBe("1 小时前")
    expect(formatRelativeTime(now - 5 * 3600_000, now)).toBe("5 小时前")
  })

  test("超过六小时改报时刻——「237 小时前」谁也换算不出是哪天", () => {
    expect(formatRelativeTime(at(2026, 9, 18, 6, 0), now)).toBe("今天 06:00")
    expect(formatRelativeTime(at(2026, 9, 17, 22, 30), now)).toBe("昨天 22:30")
    expect(formatRelativeTime(at(2026, 9, 16, 9, 15), now)).toBe("9-16 09:15")
    expect(formatRelativeTime(at(2025, 9, 16, 9, 15), now)).toBe("2025-9-16")
  })

  test("时钟回拨、存档从别的机器拷过来，也不能打出「-3 小时前」", () => {
    expect(formatRelativeTime(now + 3 * 3600_000, now)).toBe("刚刚")
  })

  test("没有时间就说没有，不打 1970 年", () => {
    expect(formatRelativeTime(0, now)).toBe("时间不明")
  })
})

describe("存档大小", () => {
  test("按量级换单位，一位小数就够", () => {
    expect(formatBytes(0)).toBe("0B")
    expect(formatBytes(512)).toBe("512B")
    expect(formatBytes(34_211)).toBe("33.4KB")
    expect(formatBytes(12_900_000)).toBe("12.3MB")
  })
})
