import { describe, expect, test } from 'bun:test'
import { backoffMs, CrashCounter, SeqTracker } from './link.ts'

describe('退避', () => {
  test('1s 起翻倍', () => {
    expect(backoffMs(1)).toBe(1000)
    expect(backoffMs(2)).toBe(2000)
    expect(backoffMs(3)).toBe(4000)
    expect(backoffMs(5)).toBe(16_000)
  })

  test('30s 封顶，再多次也不再涨', () => {
    expect(backoffMs(6)).toBe(30_000)
    expect(backoffMs(40)).toBe(30_000)
  })

  test('小于 1 的次数当第 1 次', () => {
    expect(backoffMs(0)).toBe(1000)
  })
})

describe('崩溃循环', () => {
  test('一分钟内第 3 次就放弃', () => {
    const counter = new CrashCounter(3, 60_000)
    expect(counter.record(0)).toBe(false)
    expect(counter.record(10_000)).toBe(false)
    expect(counter.record(20_000)).toBe(true)
    expect(counter.count).toBe(3)
  })

  test('隔得久的崩溃不算在一起：偶尔崩一次照样重启', () => {
    const counter = new CrashCounter(3, 60_000)
    counter.record(0)
    counter.record(30_000)
    // 第三次在 90s，第一次已经滑出窗口
    expect(counter.record(90_000)).toBe(false)
    expect(counter.count).toBe(2)
  })
})

describe('seq 去重', () => {
  test('小于等于已见最大号的丢掉', () => {
    const tracker = new SeqTracker()
    expect(tracker.accept({ seq: 1 })).toBe(true)
    expect(tracker.accept({ seq: 2 })).toBe(true)
    expect(tracker.accept({ seq: 2 })).toBe(false)
    expect(tracker.accept({ seq: 1 })).toBe(false)
    expect(tracker.accept({ seq: 3 })).toBe(true)
    expect(tracker.lastSeq).toBe(3)
  })

  test('没编号的定向回执一律放行', () => {
    const tracker = new SeqTracker()
    tracker.accept({ seq: 5 })
    expect(tracker.accept({})).toBe(true)
    expect(tracker.accept({ seq: undefined })).toBe(true)
    expect(tracker.lastSeq).toBe(5)
  })

  test('后端换了进程要 reset，否则新号全被当重复', () => {
    const tracker = new SeqTracker()
    tracker.accept({ seq: 100 })
    expect(tracker.accept({ seq: 1 })).toBe(false)
    tracker.reset()
    expect(tracker.accept({ seq: 1 })).toBe(true)
  })
})
