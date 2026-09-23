/**
 * 重连的三条纯规则：退避多久、什么时候放弃、哪些事件是补发时重复的。
 *
 * 全部不碰 I/O，所以能用断言测。真正开 WebSocket、spawn 进程的那些在 backend.ts，
 * 那边只负责在对的时刻调这里的函数。
 *
 * 背景：原来 TUI 对「断开」只有一种理解——死了，于是 WebSocket 一关就整个退出。
 * Claude Code 的做法是把连接状态当成一等状态：断了就退避重试、一直等到它回来，
 * 期间界面照常活着。这份文件是那套行为的规则部分。
 */

/**
 * 第 attempt 次重试（从 1 起）前等多久：1s 起翻倍，30s 封顶。
 *
 * 封顶是必须的：翻到几分钟之后，网络其实早就好了，人还在对着「重连中」发呆。
 * 30s 和后端 RelayLink 那头的退避是同一个数，两边体感一致。
 */
export function backoffMs(attempt: number): number {
  const n = Math.max(1, Math.floor(attempt))
  return Math.min(30_000, 1000 * 2 ** (n - 1))
}

/**
 * 崩溃循环判定：窗口期内崩到第 limit 次就该放弃重启。
 *
 * 没有它会怎样：jar 坏了、或者 API key 配错让后端起来就退，
 * 「自动重启」会变成一秒一次的无限循环，而屏幕上永远是「重启中」，
 * 用户看不到真正的报错。放弃之后才把 stderr 尾巴打出来，那才是线索。
 */
export class CrashCounter {
  private readonly times: number[] = []

  constructor(
    private readonly limit = 3,
    private readonly windowMs = 60_000,
  ) {}

  /** 记一次崩溃。返回 true 表示已经是循环，别再重启了。 */
  record(now: number): boolean {
    this.times.push(now)
    while (this.times.length > 0 && now - this.times[0]! > this.windowMs) this.times.shift()
    return this.times.length >= this.limit
  }

  /** 窗口内崩了几次。放弃时报给用户看。 */
  get count(): number {
    return this.times.length
  }
}

/**
 * 按 `seq` 去重（协议 1.2）。
 *
 * 重连时服务端先补发 `since` 之后的留底，此刻的实时广播还在继续，
 * 同一条事件可能补发里有一遍、直播又来一遍。规则只有一条：
 * **小于等于已见最大 seq 的直接丢**。没编号的（`ready`、`error` 这类定向回执）一律放行。
 */
export class SeqTracker {
  private last = 0

  /** 这条事件该不该交给上层。顺带记下它的号。 */
  accept(event: object): boolean {
    const seq = (event as { seq?: unknown }).seq
    if (typeof seq !== 'number') return true
    if (seq <= this.last) return false
    this.last = seq
    return true
  }

  /** 重连时带的 `since`：见过的最大号。 */
  get lastSeq(): number {
    return this.last
  }

  /**
   * 后端换了进程（本地重启、或远端 `ready.lastSeq` 比我们记的还小）。
   * 老号作废，不清的话新进程从 1 起编的事件会被全部当成重复丢掉。
   */
  reset(): void {
    this.last = 0
  }
}
