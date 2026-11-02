/**
 * 滚轮一格滚多远。形式照 Claude Code 的全屏渲染器：基础速度 × 快搓加速。
 *
 * OpenTUI 的 scrollbox 默认一格一行：解析器给每个滚轮事件的 delta 写死 1，
 * 默认的 LinearScrollAccel 又恒返回 1。而终端每滚一格只报一个事件——
 * 系统设置里的「每次滚动多少行」管的是窗口自己的滚动，报给程序的事件数不变。
 * 于是别处一格三行，这里一格一行，调系统灵敏度也没用。Claude Code 文档里写的是同一个坑，
 * 它也没法探测终端属于哪一种，所以给一个可调的倍率。
 *
 * 速度从哪来，前面的压过后面的：
 *   1. `MYAGENT_SCROLL_SPEED`（对应 `CLAUDE_CODE_SCROLL_SPEED`，上限 20）
 *   2. `/scroll-speed` 存进 {@link SETTINGS_FILE} 的值
 *   3. 默认 3（vim 的默认，也是 Windows「每次滚三行」的默认）
 *
 * 加速叠在上面：一格一格慢慢滚就是基础速度，猛搓一串才放大。
 * 在 {@link SETTINGS_FILE} 里写 `"wheelAcceleration": false` 关掉，每格恒定。
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { dirname, join } from "node:path"
import { MacOSScrollAccel, type ScrollAcceleration } from "@opentui/core"

/** 界面自己的设置。不放进 config.json：那个文件归后端读，两边各管各的。 */
export const SETTINGS_FILE = join(homedir(), ".myagent", "tui.json")

export const DEFAULT_SCROLL_SPEED = 3

/** 环境变量的上限，照 Claude Code。 */
export const MAX_ENV_SCROLL_SPEED = 20

/**
 * `/scroll-speed` 里 ←→ 能走到的档位。照 Claude Code：1 以上走整数到 10，
 * 1 以下走四分之一档——给已经自带放大的终端（触控板、开了加速的终端）往慢里调。
 */
export const SPEED_STEPS = [0.25, 0.5, 0.75, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10]

export type ScrollSettings = {
  scrollSpeed?: number
  wheelAcceleration?: boolean
}

export type ResolvedSpeed = {
  speed: number
  /** 速度从哪来。弹窗要据此提醒「环境变量压着，存了也不生效」。 */
  source: "env" | "saved" | "default"
}

function positive(value: unknown, max: number): number | null {
  const n = typeof value === "string" ? (value.trim() === "" ? NaN : Number(value)) : value
  return typeof n === "number" && Number.isFinite(n) && n > 0 ? Math.min(n, max) : null
}

export function resolveScrollSpeed(env: string | undefined, settings: ScrollSettings): ResolvedSpeed {
  const fromEnv = positive(env, MAX_ENV_SCROLL_SPEED)
  if (fromEnv !== null) return { speed: fromEnv, source: "env" }
  const saved = positive(settings.scrollSpeed, SPEED_STEPS[SPEED_STEPS.length - 1])
  if (saved !== null) return { speed: saved, source: "saved" }
  return { speed: DEFAULT_SCROLL_SPEED, source: "default" }
}

/** ←（-1）/ →（+1）走一档。当前值不在档位上（环境变量给的 2.5）就走到相邻的那档。 */
export function stepSpeed(current: number, dir: -1 | 1): number {
  if (dir > 0) return SPEED_STEPS.find((s) => s > current) ?? SPEED_STEPS[SPEED_STEPS.length - 1]
  return [...SPEED_STEPS].reverse().find((s) => s < current) ?? SPEED_STEPS[0]
}

/** 读不到、坏了都当空设置——滚动设置不值得让界面起不来。 */
export function loadScrollSettings(file = SETTINGS_FILE): ScrollSettings {
  try {
    if (!existsSync(file)) return {}
    const data = JSON.parse(readFileSync(file, "utf8")) as unknown
    return data && typeof data === "object" ? (data as ScrollSettings) : {}
  } catch {
    return {}
  }
}

/** 只改 scrollSpeed，别的键原样留着。存不进去返回 false，由调用方告诉用户。 */
export function saveScrollSpeed(speed: number, file = SETTINGS_FILE): boolean {
  try {
    const current = loadScrollSettings(file) as Record<string, unknown>
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, JSON.stringify({ ...current, scrollSpeed: speed }, null, 2) + "\n")
    return true
  } catch {
    return false
  }
}

/**
 * 交给 scrollbox 的 scrollAcceleration。速度和加速开关都能在运行中改：
 * `/scroll-speed` 弹窗开着时一改就生效，用户滚一下就知道手感。
 */
export class WheelScroll implements ScrollAcceleration {
  private readonly accel = new MacOSScrollAccel()

  constructor(
    public speed: number = DEFAULT_SCROLL_SPEED,
    public accelerate = true,
  ) {}

  tick(now?: number): number {
    const boost = this.accelerate ? this.accel.tick(now) : 1
    return this.speed * boost
  }

  reset(): void {
    this.accel.reset()
  }
}

/** 弹窗里显示的数：整数不带小数点，四分之一档保留到两位。 */
export function formatSpeed(speed: number): string {
  return Number.isInteger(speed) ? String(speed) : String(Number(speed.toFixed(2)))
}
