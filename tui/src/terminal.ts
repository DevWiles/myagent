/**
 * 终端占用与归还。
 *
 * 这个文件是**唯一**允许直接往 stdout 写控制序列的地方（`terminal.ts` 之外
 * 任何 `stdout.write` 都是 bug：Ink 的帧是「相对光标」增删的，外人插一句
 * 挪光标的序列就会让它算错要擦几行）。
 *
 * 独占终端本身交给 Ink 的 `alternateScreen` 选项，不自己发 `1049h`——
 * 实测它把顺序管得比手写的对：`1049h` 落在第 0 字节、配 `?25l` 藏光标，
 * 卸载时先还光标再还主屏，连 Ctrl-Z 挂起/恢复都跟着进出备用屏。
 *
 * 我们自己只管 Ink 不管的那些 DEC 私有模式（滚轮、将来的鼠标追踪），
 * 所以它们必须登记在册——否则崩溃退出后用户的终端会留着开关没关，
 * 而这种「终端坏了但看不出为什么」是最难自救的故障。
 */

import fs from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

// ---------------------------------------------------------------------------
// 一、要不要独占终端
// ---------------------------------------------------------------------------

export type FullscreenDecision = {
  fullscreen: boolean
  /** 为什么是这个结论。给 `--help` 之外的诊断用，也让测试断言更有意义。 */
  reason: string
}

const TRUTHY = new Set(['1', 'true', 'on', 'yes'])
const FALSY = new Set(['0', 'false', 'off', 'no'])

export type FullscreenInput = {
  /** 命令行开关。undefined = 没写；同一行里后写的赢（由 parseArgs 保证）。 */
  flag?: boolean
  env: Record<string, string | undefined>
  isTTY: boolean
}

/**
 * 默认独占，可关。先匹配先生效。
 *
 * 纯函数，不读 process——整张优先级表都能在单测里跑完。
 */
export function resolveFullscreen({ flag, env, isTTY }: FullscreenInput): FullscreenDecision {
  if (!isTTY) return { fullscreen: false, reason: '输出不是终端' }

  if (flag !== undefined) {
    return { fullscreen: flag, reason: flag ? '--fullscreen' : '--no-fullscreen' }
  }

  const raw = env['MYAGENT_FULLSCREEN']?.trim().toLowerCase()
  if (raw !== undefined && raw !== '') {
    if (TRUTHY.has(raw)) return { fullscreen: true, reason: 'MYAGENT_FULLSCREEN' }
    if (FALSY.has(raw)) return { fullscreen: false, reason: 'MYAGENT_FULLSCREEN' }
    // 认不出来的值不当开关用，往下走默认路径——比静默当成 false 好
  }

  const term = env['TERM']
  if (term === undefined || term === '' || term === 'dumb') {
    return { fullscreen: false, reason: 'TERM 不支持（未设置或 dumb）' }
  }

  // 和 is-in-ci 的判定保持一致：设了就算，除非显式是 0/false。
  // 必须和 Ink 内部一致，否则会出现「我们按全屏布局、Ink 却没进备用屏」的错配。
  for (const key of ['CI', 'CONTINUOUS_INTEGRATION']) {
    const value = env[key]
    if (value !== undefined && value !== '0' && value !== 'false') {
      return { fullscreen: false, reason: `${key} 已设置` }
    }
  }

  return { fullscreen: true, reason: '默认' }
}

// ---------------------------------------------------------------------------
// 二、我们自己开的终端模式（Ink 不管这些）
// ---------------------------------------------------------------------------

/** Alternate Scroll：让终端把滚轮翻译成方向键。接管鼠标后要换成 SGR，两者互斥。 */
export const MODE_ALT_SCROLL = '1007'

/** 开着的模式，按开启顺序。关的时候要反序。 */
let enabled: string[] = []
/** 预先算好的恢复串：崩溃路径上一次 writeSync 写完，不做任何分配。 */
let resetCache = ''
/** 是否进过备用屏。没进过就不该发 1049l（虽然无害，但没必要）。 */
let ownsAltScreen = false

const SHOW_CURSOR = '\x1b[?25h'
const LEAVE_ALT = '\x1b[?1049l'

function recomputeReset(): void {
  const parts = enabled.map(mode => `\x1b[?${mode}r`).reverse()
  if (ownsAltScreen) parts.push(LEAVE_ALT)
  parts.push(SHOW_CURSOR)
  resetCache = parts.join('')
}

/** 告诉本模块「Ink 已经把我们送进备用屏了」，这样兜底恢复才知道要不要还主屏。 */
export function markAltScreen(owned: boolean): void {
  ownsAltScreen = owned
  recomputeReset()
}

/**
 * 开启若干终端模式。
 *
 * 每个模式先 `?Ns`（XTSAVE 存旧值）再 `?Nh`，退出时用 `?Nr`（XTRESTORE）还原，
 * 而不是盲发 `?Nl`——用户可能本来就把某个模式开着，我们无权替他关掉。
 * 终端不认 s/r 的话后果也无害：这些模式只在备用屏里有意义，而那时我们已经出来了。
 */
export function enableModes(...modes: string[]): void {
  const fresh = modes.filter(mode => !enabled.includes(mode))
  if (fresh.length === 0) return
  enabled = [...enabled, ...fresh]
  recomputeReset()
  write(fresh.map(mode => `\x1b[?${mode}s\x1b[?${mode}h`).join(''))
}

/** 反序关掉所有登记的模式。必须在离开备用屏**之前**调用（这些是全局终端状态）。 */
export function disableModes(): void {
  if (enabled.length === 0) return
  const sequence = [...enabled].reverse().map(mode => `\x1b[?${mode}r`).join('')
  enabled = []
  recomputeReset()
  write(sequence)
}

/** 完整恢复串：关模式 + 还主屏 + 还光标。给崩溃路径用。 */
export function resetSequence(): string {
  return resetCache
}

/** 只给测试看的内部状态。 */
export function enabledModes(): readonly string[] {
  return enabled
}

// ---------------------------------------------------------------------------
// 三、无论怎么退出都要把终端还回去
// ---------------------------------------------------------------------------

export type TeardownOptions = {
  /** 拿到 Ink 实例。用回调而不是直接传，因为 installTeardown 要在 render 之前装。 */
  getInstance: () => { unmount: () => void } | null
  /**
   * 崩溃日志落在哪。默认 `~/.myagent/tui-crash.log`（Java 那边的配置也在这个目录）。
   * 也可以用 `MYAGENT_CRASH_LOG` 覆盖 —— PTY 验收测试靠它把日志写到临时目录，
   * 免得把开发者真正的 `~/.myagent` 当成测试垃圾场。
   */
  crashLog?: string
}

let installed = false
let torndown = false

/**
 * 装好各条退出路径的恢复钩子。
 *
 * **刻意不注册 `SIGINT`/`SIGTERM`/`SIGHUP`**：Ink 用 signal-exit 接这三个，而
 * signal-exit 只在「该信号的监听器数量 == 它自己装的数量」时才动手。我们多注册
 * 一个就把它禁用了。零监听器时这三个信号实测都能正确恢复。
 * （之前 `main.tsx` 那个 SIGTERM handler 正是这个毛病，只是因为它自己调了
 * `process.exit(0)` 才侥幸没出事。）
 *
 * 反过来，`process.on('exit')` 是**必须**的：Bun 下 signal-exit 在未捕获异常和
 * 未处理 rejection 时不触发，那两条路径只能靠它和下面的显式 handler 兜。
 */
export function installTeardown({ getInstance, crashLog }: TeardownOptions): void {
  if (installed) return
  installed = true

  const logPath =
    crashLog ?? process.env['MYAGENT_CRASH_LOG'] ?? join(homedir(), '.myagent', 'tui-crash.log')

  const teardown = (): void => {
    if (torndown) return
    torndown = true

    // 顺序是有讲究的，反了会把用户的屏幕和 scrollback 一起毁掉：
    //   1. 先关我们开的模式——此时还在备用屏里，什么都看不见
    //   2. 再让 Ink 卸载——它会同步吐 `?25h` `?1049l`，把主屏还回来
    //   3. 最后补一发兜底，应对「实例是 null」或「unmount 抛了」
    // 若把 3 提到 2 前面，Ink 的收尾重绘（含 `ESC[2J ESC[3J`）就会落在**主屏**上，
    // 连用户的命令历史一起清掉。
    try {
      disableModes()
    } catch {
      // 流可能已经废了，继续往下走，别让恢复链断在这
    }
    try {
      getInstance()?.unmount()
    } catch {
      // 同上
    }
    try {
      fs.writeSync(1, ownsAltScreen ? LEAVE_ALT + SHOW_CURSOR : SHOW_CURSOR)
    } catch {
      // 没救了，但至少不要用一个新异常盖掉原来的崩溃原因
    }
  }

  const fatal = (error: unknown): never => {
    teardown()
    const stack = error instanceof Error ? (error.stack ?? error.message) : String(error)
    // 必须用 writeSync 而不是 console.error：Ink 的 patchConsole 会把 console
    // 输出重定向进它的帧里，而此时那个帧连同备用屏已经没了，用户什么都看不到。
    try {
      fs.writeSync(2, `\nmyagent-tui 崩溃了：\n${stack}\n`)
    } catch {
      /* stderr 也废了的话没别的办法 */
    }
    try {
      fs.mkdirSync(dirname(logPath), { recursive: true })
      fs.appendFileSync(logPath, `[${new Date().toISOString()}] ${stack}\n`)
      fs.writeSync(2, `完整日志：${logPath}\n`)
    } catch {
      // 落盘失败不重要，堆栈已经打到 stderr 了
    }
    process.exit(1)
  }

  process.on('exit', teardown)
  process.on('uncaughtException', fatal)
  process.on('unhandledRejection', fatal)
}

/**
 * 只给测试用：把模块状态清回初始，并把输出改道到一个数组。
 *
 * 不改道的话单测会把 `?1002h` 之类真的写到跑测试的那个终端上——既刷屏，
 * 又可能让终端留着模式没关（正是这个文件存在的理由，别自己犯）。
 */
export function resetForTest(sink?: string[]): void {
  enabled = []
  ownsAltScreen = false
  torndown = false
  writer = sink ? (data: string) => void sink.push(data) : defaultWriter
  recomputeReset()
}

function defaultWriter(data: string): void {
  try {
    process.stdout.write(data)
  } catch {
    // 退出过程中流可能已经关了
  }
}

let writer: (data: string) => void = defaultWriter

function write(data: string): void {
  writer(data)
}

recomputeReset()
