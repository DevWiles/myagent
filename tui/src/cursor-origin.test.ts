import { describe, expect, test } from "bun:test"
import { EventEmitter } from "node:events"
import { queryCursorOrigin } from "./cursor-origin.ts"

/**
 * 假的 stdin。只实现 queryCursorOrigin 真正用到的那几样，
 * 并且把「有没有被 pause 掉」记下来——那是这一层最容易出事的地方，
 * 实测过一次：在不回话的终端上顺手 pause()，等于把键盘整个关掉。
 */
class FakeStdin extends EventEmitter {
  isTTY = true
  isRaw = false
  paused: boolean
  rawCalls: boolean[] = []

  constructor(startPaused = true) {
    super()
    this.paused = startPaused
  }

  setRawMode(on: boolean): this {
    this.rawCalls.push(on)
    this.isRaw = on
    return this
  }

  isPaused(): boolean {
    return this.paused
  }

  resume(): this {
    this.paused = false
    return this
  }

  pause(): this {
    this.paused = true
    return this
  }
}

const tty = { isTTY: true } as NodeJS.WriteStream

function ask(stdin: FakeStdin, out: NodeJS.WriteStream = tty) {
  const sent: string[] = []
  const promise = queryCursorOrigin(
    stdin as unknown as NodeJS.ReadStream,
    out,
    (text) => sent.push(text),
  )
  return { promise, sent }
}

describe("问一次光标在哪", () => {
  test("认得终端回的 ESC[行;列R", async () => {
    const stdin = new FakeStdin()
    const { promise, sent } = ask(stdin)
    stdin.emit("data", Buffer.from("\x1b[12;34R"))

    expect(await promise).toEqual({ row: 12, column: 34 })
    expect(sent).toEqual(["\x1b[6n"])
  })

  test("回答分几次到也拼得起来", async () => {
    const stdin = new FakeStdin()
    const { promise } = ask(stdin)
    // 真终端的回答被 TCP/pty 切成两半是常事
    stdin.emit("data", Buffer.from("\x1b[7"))
    stdin.emit("data", Buffer.from(";1R"))

    expect(await promise).toEqual({ row: 7, column: 1 })
  })

  test("终端不回话就超时返回 null，不能把启动卡在这儿", async () => {
    const stdin = new FakeStdin()
    const { promise } = ask(stdin)

    expect(await promise).toBeNull()
  }, 2000)

  test("不回话那条路**不许**把 stdin 停掉——那等于把键盘关了", async () => {
    // 这条是实测撞出来的：顺手 pause() 之后界面照常画出来，但一个键都不响应，
    // 连 Ctrl-C 都退不掉。而它只在「终端不支持 CPR」的机器上发作
    const stdin = new FakeStdin(false)          // 本来就是流动的
    const { promise } = ask(stdin)

    expect(await promise).toBeNull()
    expect(stdin.paused).toBe(false)
  }, 2000)

  test("本来就停着的，问完还得停回去", async () => {
    const stdin = new FakeStdin(true)
    const { promise } = ask(stdin)
    stdin.emit("data", Buffer.from("\x1b[3;5R"))

    await promise
    expect(stdin.paused).toBe(true)
  })

  test("raw 模式借完就还", async () => {
    const stdin = new FakeStdin()
    const { promise } = ask(stdin)
    stdin.emit("data", Buffer.from("\x1b[1;1R"))

    await promise
    expect(stdin.rawCalls).toEqual([true, false])
    expect(stdin.isRaw).toBe(false)
  })

  test("不是 TTY 就别问，一个字节都不发", async () => {
    const stdin = new FakeStdin()
    stdin.isTTY = false
    const { promise, sent } = ask(stdin)

    expect(await promise).toBeNull()
    expect(sent).toEqual([])
  })

  test("输出被重定向时也别问", async () => {
    const stdin = new FakeStdin()
    const { promise, sent } = ask(stdin, { isTTY: false } as NodeJS.WriteStream)

    expect(await promise).toBeNull()
    expect(sent).toEqual([])
  })
})
