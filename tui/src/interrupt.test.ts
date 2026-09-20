import { describe, expect, test } from "bun:test"
import { armedAfter, decideCtrlC, type CtrlCContext } from "./interrupt.ts"

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
