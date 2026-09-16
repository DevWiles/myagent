import { describe, expect, test, beforeEach } from 'bun:test'
import {
  MODE_ALT_SCROLL,
  disableModes,
  enableModes,
  enabledModes,
  markAltScreen,
  resetForTest,
  resetSequence,
  resolveFullscreen,
} from './terminal.ts'

const base = { env: {} as Record<string, string | undefined>, isTTY: true }

describe('resolveFullscreen 优先级', () => {
  test('默认独占', () => {
    expect(resolveFullscreen({ ...base, env: { TERM: 'xterm-256color' } }).fullscreen).toBe(true)
  })

  test('非 TTY 一律关闭，且优先于一切', () => {
    const d = resolveFullscreen({ flag: true, env: { MYAGENT_FULLSCREEN: '1' }, isTTY: false })
    expect(d.fullscreen).toBe(false)
  })

  test('命令行开关压过环境变量', () => {
    const env = { TERM: 'xterm', MYAGENT_FULLSCREEN: '1' }
    expect(resolveFullscreen({ flag: false, env, isTTY: true }).fullscreen).toBe(false)
    const env2 = { TERM: 'xterm', MYAGENT_FULLSCREEN: '0' }
    expect(resolveFullscreen({ flag: true, env: env2, isTTY: true }).fullscreen).toBe(true)
  })

  test('环境变量认 0/false/off/no 和 1/true/on/yes，大小写无关', () => {
    for (const off of ['0', 'false', 'OFF', 'No']) {
      expect(resolveFullscreen({ ...base, env: { TERM: 'xterm', MYAGENT_FULLSCREEN: off } }).fullscreen).toBe(false)
    }
    for (const on of ['1', 'true', 'ON', 'Yes']) {
      expect(resolveFullscreen({ ...base, env: { TERM: 'xterm', MYAGENT_FULLSCREEN: on } }).fullscreen).toBe(true)
    }
  })

  test('认不出来的环境变量值不当开关用，往下走默认', () => {
    const d = resolveFullscreen({ ...base, env: { TERM: 'xterm', MYAGENT_FULLSCREEN: '毛线' } })
    expect(d.fullscreen).toBe(true)
    expect(d.reason).toBe('默认')
  })

  test('TERM 未设 / 空 / dumb 都关闭', () => {
    for (const term of [undefined, '', 'dumb']) {
      expect(resolveFullscreen({ ...base, env: { TERM: term } }).fullscreen).toBe(false)
    }
  })

  test('CI 关闭，但 CI=0/false 不算开着（要和 is-in-ci 一致）', () => {
    expect(resolveFullscreen({ ...base, env: { TERM: 'xterm', CI: 'true' } }).fullscreen).toBe(false)
    expect(resolveFullscreen({ ...base, env: { TERM: 'xterm', CONTINUOUS_INTEGRATION: '1' } }).fullscreen).toBe(false)
    // 这两个必须仍然是全屏：Ink 内部用的就是这个判据，不一致会导致
    // 「我们按全屏布局、Ink 却没进备用屏」的错配
    expect(resolveFullscreen({ ...base, env: { TERM: 'xterm', CI: '0' } }).fullscreen).toBe(true)
    expect(resolveFullscreen({ ...base, env: { TERM: 'xterm', CI: 'false' } }).fullscreen).toBe(true)
  })

  test('reason 说明了是谁决定的', () => {
    expect(resolveFullscreen({ flag: false, ...base }).reason).toBe('--no-fullscreen')
    expect(resolveFullscreen({ ...base, isTTY: false }).reason).toBe('输出不是终端')
    expect(resolveFullscreen({ ...base, env: { TERM: 'xterm', CI: '1' } }).reason).toBe('CI 已设置')
  })
})

describe('终端模式登记表', () => {
  /** 把控制序列收进数组，不要真写到跑测试的终端上。 */
  let written: string[] = []
  beforeEach(() => {
    written = []
    resetForTest(written)
  })

  test('开了才登记，重复开不会登记两次（也不会重复发字节）', () => {
    enableModes(MODE_ALT_SCROLL)
    enableModes(MODE_ALT_SCROLL)
    expect(enabledModes()).toEqual([MODE_ALT_SCROLL])
    expect(written).toEqual(['\x1b[?1007s\x1b[?1007h'])
  })

  test('开启时先 XTSAVE 再置位——退出用 XTRESTORE 还原，不盲发 ?Nl 把用户自己的设置关掉', () => {
    enableModes('1002')
    expect(written.join('')).toBe('\x1b[?1002s\x1b[?1002h')
  })

  test('关闭串是开启顺序的严格逆序', () => {
    enableModes('1006', '1002')
    expect(resetSequence()).toStartWith('\x1b[?1002r\x1b[?1006r')
  })

  test('恢复串以「还主屏 + 还光标」收尾——崩溃路径就靠这一发', () => {
    markAltScreen(true)
    enableModes(MODE_ALT_SCROLL)
    expect(resetSequence()).toBe('\x1b[?1007r\x1b[?1049l\x1b[?25h')
  })

  test('没进过备用屏就不发 1049l', () => {
    markAltScreen(false)
    enableModes(MODE_ALT_SCROLL)
    expect(resetSequence()).toBe('\x1b[?1007r\x1b[?25h')
  })

  test('disableModes 是幂等的，第二次一个字节都不发', () => {
    enableModes(MODE_ALT_SCROLL)
    written.length = 0
    disableModes()
    expect(enabledModes()).toEqual([])
    expect(written).toEqual(['\x1b[?1007r'])
    written.length = 0
    disableModes()
    expect(written).toEqual([])
  })

  test('一个模式都没开时，恢复串仍然是合法的还原序列', () => {
    markAltScreen(true)
    expect(resetSequence()).toBe('\x1b[?1049l\x1b[?25h')
  })
})
