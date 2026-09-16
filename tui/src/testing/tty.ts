/**
 * 假终端。让测试能把**真的 Ink 渲染管线**（含 Yoga 排版、折行、测量 effect）
 * 跑起来，然后逐行断言画出来的帧。
 *
 * 为什么不用现成的:
 * - `ink-testing-library` 的假流**没有 rows**，而这里被测的恰恰是「内容和 rows
 *   的关系」，结构上就测不了；而且它也不是现成依赖。
 * - `renderToString` 只接受 `{columns}`，同样没有 rows；并且它自己的文档写明
 *   effect 驱动的状态更新不会反映到输出里 —— 自己量高的视口会永远停在 seed 值。
 *
 * 所以自己写，25 行，零新依赖。
 */

import { EventEmitter } from 'node:events'

/** 假 stdout。必须继承 EventEmitter：`useWindowSize` 会 `stdout.on('resize')`。 */
export class FakeStdout extends EventEmitter {
  readonly isTTY = true
  chunks: string[] = []

  constructor(
    public columns: number,
    public rows: number,
  ) {
    super()
  }

  write(data: string): boolean {
    this.chunks.push(data)
    return true
  }

  resize(columns: number, rows: number): void {
    this.columns = columns
    this.rows = rows
    this.emit('resize')
  }
}

/** 假 stdin。Ink 的 App 会碰这些方法，缺一个就崩。 */
export class FakeStdin extends EventEmitter {
  readonly isTTY = true
  setRawMode(): this {
    return this
  }
  setEncoding(): this {
    return this
  }
  resume(): this {
    return this
  }
  pause(): this {
    return this
  }
  read(): null {
    return null
  }
  ref(): void {}
  unref(): void {}
}

const ESC = String.fromCharCode(27)
// 用 fromCharCode 拼而不是写字面转义：源码里的裸转义符对各种工具都不友好
const ANSI = new RegExp(ESC + '\\[[0-9;?]*[a-zA-Z]', 'g')
const OSC = new RegExp(ESC + '\\][^\\u0007]*\\u0007', 'g')

/** 从写入流里取出最后一帧的可见文本，按屏幕行切好。 */
export function lastFrame(out: FakeStdout): string[] {
  // 带正文的那一次写入就是帧。控制序列单独成块（比如藏光标、同步输出开关），
  // 从后往前找第一个含换行的块。
  for (let i = out.chunks.length - 1; i >= 0; i--) {
    const chunk = out.chunks[i]
    if (chunk === undefined || !chunk.includes('\n')) continue
    return chunk.replace(OSC, '').replace(ANSI, '').replace(/\r/g, '').split('\n')
  }
  return []
}

/**
 * 输入框所在的屏幕行号。找不到返回 -1。
 *
 * **从下往上找**：`›` 同时也是 EntryView 给用户消息用的行首标记，从上往下找会
 * 命中对话里的用户消息（提交过一次 prompt 之后就会）。输入框永远是最下面那个。
 */
export function promptRow(lines: readonly string[]): number {
  const marker = String.fromCharCode(0x203a) // '›'
  for (let i = lines.length - 1; i >= 0; i--) {
    if ((lines[i] ?? '').startsWith(marker)) return i
  }
  return -1
}

/** 最后一个非空行的行号。钉底断言看的是这个，不是总行数。 */
export function lastNonEmptyRow(lines: readonly string[]): number {
  for (let i = lines.length - 1; i >= 0; i--) {
    if ((lines[i] ?? '').trim() !== '') return i
  }
  return -1
}
