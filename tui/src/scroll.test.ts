import { describe, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import {
  DEFAULT_SCROLL_SPEED,
  formatSpeed,
  loadScrollSettings,
  resolveScrollSpeed,
  saveScrollSpeed,
  stepSpeed,
  WheelScroll,
} from "./scroll.ts"

describe("滚轮一格滚多远", () => {
  test("慢慢一格一格滚：每格三行，不是 OpenTUI 默认的一行", () => {
    const wheel = new WheelScroll()
    // 间隔超过连击窗口（150ms），每一格都算新的一次
    expect(wheel.tick(1000)).toBe(3)
    expect(wheel.tick(2000)).toBe(3)
  })

  test("连续猛搓会加速，慢下来回到基础速度；关掉加速就恒定", () => {
    const fastSpin = (wheel: WheelScroll) => {
      let t = 1000
      wheel.tick(t)
      let last = 0
      for (let i = 0; i < 6; i++) last = wheel.tick((t += 20))
      return { last, t }
    }
    const wheel = new WheelScroll()
    const { last, t } = fastSpin(wheel)
    expect(last).toBeGreaterThan(3)
    expect(wheel.tick(t + 1000)).toBe(3)

    expect(fastSpin(new WheelScroll(3, false)).last).toBe(3)
  })

  test("速度改了当场生效——弹窗开着时滚一下就是新手感", () => {
    const wheel = new WheelScroll()
    wheel.speed = 7
    expect(wheel.tick(1000)).toBe(7)
  })
})

describe("速度从哪来", () => {
  test("环境变量压过保存的值，保存的压过默认", () => {
    expect(resolveScrollSpeed("5", { scrollSpeed: 2 })).toEqual({ speed: 5, source: "env" })
    expect(resolveScrollSpeed(undefined, { scrollSpeed: 2 })).toEqual({ speed: 2, source: "saved" })
    expect(resolveScrollSpeed(undefined, {})).toEqual({ speed: DEFAULT_SCROLL_SPEED, source: "default" })
  })

  test("环境变量封顶 20，可以是小数；乱填就当没写", () => {
    expect(resolveScrollSpeed("99", {}).speed).toBe(20)
    expect(resolveScrollSpeed("0.25", {}).speed).toBe(0.25)
    for (const bad of ["", "abc", "0", "-2"]) {
      expect(resolveScrollSpeed(bad, { scrollSpeed: 4 })).toEqual({ speed: 4, source: "saved" })
    }
  })

  test("文件里存了坏值也不炸，回到默认", () => {
    expect(resolveScrollSpeed(undefined, { scrollSpeed: -1 }).source).toBe("default")
    expect(resolveScrollSpeed(undefined, { scrollSpeed: "x" as never }).source).toBe("default")
  })
})

describe("←→ 走档", () => {
  test("1 以上走整数到 10，1 以下走四分之一档，两头停住", () => {
    expect(stepSpeed(3, 1)).toBe(4)
    expect(stepSpeed(1, -1)).toBe(0.75)
    expect(stepSpeed(0.25, -1)).toBe(0.25)
    expect(stepSpeed(10, 1)).toBe(10)
  })

  test("不在档位上的值（环境变量给的 2.5）走到相邻那档", () => {
    expect(stepSpeed(2.5, 1)).toBe(3)
    expect(stepSpeed(2.5, -1)).toBe(2)
    expect(stepSpeed(15, -1)).toBe(10)
  })

  test("显示：整数不带小数点", () => {
    expect(formatSpeed(3)).toBe("3")
    expect(formatSpeed(0.25)).toBe("0.25")
  })
})

describe("存取 tui.json", () => {
  const dir = mkdtempSync(join(tmpdir(), "myagent-scroll-"))

  test("只改 scrollSpeed，别的键原样留着", () => {
    const file = join(dir, "a", "tui.json")
    expect(saveScrollSpeed(5, file)).toBe(true)
    writeFileSync(file, JSON.stringify({ scrollSpeed: 5, wheelAcceleration: false }))
    expect(saveScrollSpeed(0.5, file)).toBe(true)
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual({ scrollSpeed: 0.5, wheelAcceleration: false })
  })

  test("没有文件、文件坏了都当空设置", () => {
    expect(loadScrollSettings(join(dir, "none.json"))).toEqual({})
    const broken = join(dir, "broken.json")
    writeFileSync(broken, "{不是 json")
    expect(loadScrollSettings(broken)).toEqual({})
  })
})
