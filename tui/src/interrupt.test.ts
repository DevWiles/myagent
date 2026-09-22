import { describe, expect, test } from "bun:test"
import {
  armedAfter,
  ARM_TIMEOUT_MS,
  createArmState,
  decideCtrlC,
  type CtrlCContext,
} from "./interrupt.ts"

const idle: CtrlCContext = {
  hasApproval: false,
  hasPlanConfirm: false,
  pickerOpen: false,
  busy: false,
  draft: "",
  armed: false,
}

const ctx = (patch: Partial<CtrlCContext>): CtrlCContext => ({ ...idle, ...patch })

describe("Ctrl-C：先停手，再问要不要走", () => {
  test("跑着的时候中断这一轮，不退出", () => {
    // 这是整件事的起点：原先第一下 Ctrl-C 直接退进程，
    // 一次跑了两分钟的任务，手一抖整个会话就没了
    expect(decideCtrlC(ctx({ busy: true }))).toEqual({ kind: "interrupt" })
  })

  test("空行上连按两次才退，第一次只是亮个提示", () => {
    const first = decideCtrlC(idle)
    expect(first).toEqual({ kind: "arm" })
    expect(decideCtrlC(ctx({ armed: true }))).toEqual({ kind: "exit" })
  })

  test("输入框里有字就清掉这一行——和 shell 一致", () => {
    expect(decideCtrlC(ctx({ draft: "把 README 改一下" }))).toEqual({ kind: "clear-input" })
  })

  test("跑着的时候不动草稿：想停的是那一轮，不是打了一半的下一句", () => {
    expect(decideCtrlC(ctx({ busy: true, draft: "打了一半的下一句" })))
      .toEqual({ kind: "interrupt" })
  })

  test("确认框挂着时接得住——不然用户既答不了也退不出", () => {
    // App 里那个分支会把其余按键全部吞掉，Ctrl-C 要是不在这儿接就是个死胡同
    expect(decideCtrlC(ctx({ hasApproval: true }))).toEqual({ kind: "decline-approval" })
    expect(decideCtrlC(ctx({ hasPlanConfirm: true }))).toEqual({ kind: "decline-plan" })
  })

  test("确认框比 busy 优先：那会儿后端正阻塞着等回答", () => {
    expect(decideCtrlC(ctx({ hasApproval: true, busy: true })))
      .toEqual({ kind: "decline-approval" })
  })

  test("选择器开着就收起来，等同于 Esc", () => {
    expect(decideCtrlC(ctx({ pickerOpen: true, draft: "搜索词" })))
      .toEqual({ kind: "close-picker" })
  })
})

describe("「再按一次就退出」什么时候亮着", () => {
  test("只有 arm 让它亮", () => {
    expect(armedAfter({ kind: "arm" })).toBe(true)
  })

  test("干了别的事就不亮——第二下的意思是「刚才那下没生效？」", () => {
    for (const kind of ["interrupt", "clear-input", "close-picker",
                        "decline-approval", "decline-plan", "exit"] as const) {
      expect(armedAfter({ kind })).toBe(false)
    }
  })

  test("中断一轮之后紧接着按第二下，不会把人踢出去", () => {
    // 这条是上一条的实际后果，单独钉住：中断 → 不武装 → 下一次只是 arm
    const afterInterrupt = decideCtrlC(ctx({ busy: true }))
    expect(armedAfter(afterInterrupt)).toBe(false)
    expect(decideCtrlC(ctx({ armed: false }))).toEqual({ kind: "arm" })
  })
})

describe("武装状态的有效期", () => {
  /** 定时器测试统一用这个刻度：够短跑得快，够长不会被调度抖动冲掉。 */
  const TICK = 20
  const sleep = (ms: number) => new Promise((done) => setTimeout(done, ms))

  test("武装之后没人动，到点自己解除", async () => {
    // 只靠「按了别的键」解除的话，武装能无限期挂着：
    // 人走开再回来想中断点什么，按下去的却是退出
    const seen: boolean[] = []
    const arm = createArmState((on) => seen.push(on), TICK)

    arm.set(true)
    expect(seen).toEqual([true])

    await sleep(TICK * 3)
    expect(seen).toEqual([true, false])
    arm.dispose()
  })

  test("重新武装要重新计时，旧定时器不许掐灭新提示", async () => {
    // 忘了先 clear 的话：第一根定时器在第二次武装期间到点，
    // 把刚亮起来的提示掐灭——表现是「提示闪一下就没了」，肉眼极难复现
    const seen: boolean[] = []
    const arm = createArmState((on) => seen.push(on), TICK * 4)

    arm.set(true)
    await sleep(TICK * 3)     // 第一根还差一点到点
    arm.set(true)             // 重新武装，第一根必须被掐掉
    await sleep(TICK * 3)     // 第一根若还活着，会在这段里到点

    expect(seen).toEqual([true, true])
    arm.dispose()
  })

  test("手动解除之后定时器不再回调——否则会多报一次 false", async () => {
    const seen: boolean[] = []
    const arm = createArmState((on) => seen.push(on), TICK)

    arm.set(true)
    arm.set(false)            // 按了别的键
    await sleep(TICK * 3)

    expect(seen).toEqual([true, false])
    arm.dispose()
  })

  test("dispose 之后彻底安静——界面都没了，别再回调", async () => {
    const seen: boolean[] = []
    const arm = createArmState((on) => seen.push(on), TICK)

    arm.set(true)
    arm.dispose()
    await sleep(TICK * 3)

    expect(seen).toEqual([true])
  })

  test("默认有效期是 4 秒", () => {
    // 「刻意连按两下」和「隔了一会儿又按一下」之间的分界
    expect(ARM_TIMEOUT_MS).toBe(4000)
  })
})
