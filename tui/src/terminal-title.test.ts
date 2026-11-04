import { describe, expect, test } from "bun:test"
import { BUSY_FRAMES, ERROR_ICON, IDLE_ICON, WAITING_ICON, tabStatus, tabTitle } from "./terminal-title.ts"

describe("标题怎么拼", () => {
  test("空闲：蓝宝石 + 摘要；还没摘要就写 myagent", () => {
    expect(tabTitle("排查登录 500", "idle")).toBe(`${IDLE_ICON} 排查登录 500`)
    expect(tabTitle(null, "idle")).toBe(`${IDLE_ICON} myagent`)
    expect(tabTitle("   ", "idle")).toBe(`${IDLE_ICON} myagent`)
  })

  test("正在跑：按帧呼吸，一圈之后回到开头", () => {
    const icons = BUSY_FRAMES.map((_, i) => tabTitle("x", "busy", i).split(" ")[0])
    expect(icons).toEqual(BUSY_FRAMES)
    expect(tabTitle("x", "busy", BUSY_FRAMES.length)).toBe(tabTitle("x", "busy", 0))
  })

  test("等你是橙色，出错是红色，帧数不影响它们", () => {
    expect(tabTitle("x", "waiting", 3)).toBe(`${WAITING_ICON} x`)
    expect(tabTitle("x", "error", 3)).toBe(`${ERROR_ICON} x`)
  })

  test("摘要里的控制字符去掉：混进标题序列会把终端搞乱", () => {
    expect(tabTitle("a\u0007b\u001b]0;evil", "idle")).toBe(`${IDLE_ICON} a b ]0;evil`)
  })
})

describe("该亮哪种", () => {
  const base = {
    busy: false,
    approval: null,
    planConfirm: null,
    link: { phase: "online" as const },
    closed: null,
    interrupted: null,
  }
  const cut = (reason: string) => ({ reason, prompt: "p", steps: 1, at: 0 })

  test("平时：空闲 / 正在跑", () => {
    expect(tabStatus(base)).toBe("idle")
    expect(tabStatus({ ...base, busy: true })).toBe("busy")
  })

  test("卡在审批或计划确认上就是在等你，哪怕这一轮还算在跑", () => {
    expect(tabStatus({ ...base, busy: true, approval: {} as never })).toBe("waiting")
    expect(tabStatus({ ...base, busy: true, planConfirm: { id: 1, ask: "exit", plan: "", choice: 0, feedback: null } })).toBe("waiting")
  })

  test("断线、后端退了、上一轮失败：出错，而且压过等你", () => {
    expect(tabStatus({ ...base, link: { phase: "reconnecting", attempt: 1, reason: "x", restarting: false } })).toBe("error")
    expect(tabStatus({ ...base, closed: "后端退出" })).toBe("error")
    expect(tabStatus({ ...base, interrupted: cut("网络错误") })).toBe("error")
    expect(tabStatus({ ...base, closed: "x", approval: {} as never })).toBe("error")
  })

  test("自己按 Esc 叫停的不算出错；刚启动还在连也不算", () => {
    expect(tabStatus({ ...base, interrupted: cut("已取消") })).toBe("idle")
    expect(tabStatus({ ...base, link: { phase: "connecting" } })).toBe("idle")
  })

  test("接着说下一句（busy 了），上一轮的失败就不再亮红", () => {
    expect(tabStatus({ ...base, busy: true, interrupted: cut("网络错误") })).toBe("busy")
  })
})
