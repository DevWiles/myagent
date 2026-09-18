import { describe, expect, test } from "bun:test"
import {
  filterCommands,
  helpLines,
  listCommands,
  looksLikeCommand,
  matchCommand,
  resolve,
  type CommandContext,
} from "./commands.ts"

/** 后端啥都支持、存档也都有的情况。 */
const full: CommandContext = {
  features: ["plan", "replan", "resume", "resume_plan", "auto_mode", "session", "sessions"],
  hasContinuable: true,
  hasResumablePlan: true,
}

/** 刚连上、什么存档都没有。 */
const fresh: CommandContext = { ...full, hasContinuable: false, hasResumablePlan: false }

describe("看起来是不是命令", () => {
  test("/ 开头的单个词算", () => {
    expect(looksLikeCommand("/help")).toBe(true)
    expect(looksLikeCommand("  /cont")).toBe(true)
    expect(looksLikeCommand("/plan 把 README 补全")).toBe(true)
  })

  test("绝对路径不算——贴个路径进来问问题是常事", () => {
    expect(looksLikeCommand("/Users/me/proj/pom.xml 看看这个")).toBe(false)
    expect(looksLikeCommand("/etc/hosts 里有什么")).toBe(false)
  })

  test("不以 / 开头的都不算", () => {
    expect(looksLikeCommand("继续")).toBe(false)
    expect(looksLikeCommand("帮我看下 a/b/c")).toBe(false)
  })
})

describe("边打边筛", () => {
  test("空的 / 列出全部", () => {
    expect(filterCommands("/", full).map((c) => c.name)).toEqual([
      "/help",
      "/plan",
      "/continue",
      "/resume",
      "/resume-plan",
      "/clear",
    ])
  })

  test("按前缀收窄", () => {
    expect(filterCommands("/c", full).map((c) => c.name)).toEqual(["/continue", "/clear"])
    expect(filterCommands("/cl", full).map((c) => c.name)).toEqual(["/clear"])
    expect(filterCommands("/re", full).map((c) => c.name)).toEqual(["/resume", "/resume-plan"])
  })

  test("大小写不敏感——敲 /Help 的人要的是 /help", () => {
    expect(filterCommands("/HEL", full).map((c) => c.name)).toEqual(["/help"])
  })

  test("开始打参数之后菜单只剩那一条，不再挡视线", () => {
    expect(filterCommands("/plan 把 README 补全", full).map((c) => c.name)).toEqual(["/plan"])
  })

  test("对不上就什么都不显示", () => {
    expect(filterCommands("/zzz", full)).toEqual([])
    expect(filterCommands("继续", full)).toEqual([])
  })
})

describe("高亮用的匹配", () => {
  test("精确匹配", () => {
    expect(matchCommand("/continue", full)?.id).toBe("continue")
  })

  test("唯一前缀也算——终端版的 Tab 补全就是这条规矩", () => {
    expect(matchCommand("/cont", full)?.id).toBe("continue")
  })

  test("前缀不唯一时不算，不能替用户猜", () => {
    expect(matchCommand("/c", full)).toBeNull()
  })

  test("带参数时看首词", () => {
    expect(matchCommand("/plan 把 README 补全", full)?.id).toBe("plan")
  })
})

describe("按回车怎么处置", () => {
  test("普通一句话就是普通一句话", () => {
    expect(resolve("继续", full)).toEqual({ kind: "prompt", text: "继续" })
  })

  test("路径开头的提问不会被当成命令拦掉", () => {
    const r = resolve("/Users/me/proj/pom.xml 看看这个", full)
    expect(r.kind).toBe("prompt")
  })

  test("认出来的命令带上参数", () => {
    const r = resolve("/plan 把 README 补全", full)
    expect(r.kind).toBe("command")
    if (r.kind === "command") {
      expect(r.entry.id).toBe("plan")
      expect(r.args).toBe("把 README 补全")
    }
  })

  test("没这条命令 —— 必须拦住，不能发给模型", () => {
    expect(resolve("/zzz", full)).toEqual({ kind: "unknown", typed: "/zzz" })
    expect(resolve("/繼續", full).kind).toBe("prompt")
  })

  test("/resume 精确匹配自己，不会被前缀规则变成「续跑计划」", () => {
    const r = resolve("/resume", full)
    expect(r.kind).toBe("command")
    if (r.kind === "command") {
      expect(r.entry.id).toBe("resume")
    }
  })

  test("没有存档时 /resume 照样能开——面板自己会说这个工作区还没有会话", () => {
    expect(resolve("/resume", fresh).kind).toBe("command")
  })

  test("/res 前缀不唯一，不替用户在挑会话和续计划之间猜", () => {
    expect(matchCommand("/res", full)).toBeNull()
  })

  test("要参数却没给", () => {
    const r = resolve("/plan", full)
    expect(r.kind).toBe("needs-args")
  })

  test("没存档时 /continue 用不了，而且要说清为什么", () => {
    const r = resolve("/continue", fresh)
    expect(r.kind).toBe("unavailable")
    if (r.kind === "unavailable") {
      expect(r.entry.reason).toBe("这个工作区还没有存下来的会话")
    }
  })

  test("后端没宣告 plan 能力时 /plan 用不了", () => {
    const noPlan: CommandContext = { ...full, features: ["session"] }
    const r = resolve("/plan 干活", noPlan)
    expect(r.kind).toBe("unavailable")
  })

  test("老后端只宣告 resume（没有 resume_plan）也照样能用", () => {
    const old: CommandContext = { ...full, features: ["plan", "resume", "session"] }
    expect(resolve("/resume-plan", old).kind).toBe("command")
  })
})

describe("列表和帮助", () => {
  test("用不了的命令也列出来，灰着比消失好懂", () => {
    const entries = listCommands(fresh)
    expect(entries).toHaveLength(6)
    const cont = entries.find((e) => e.id === "continue")!
    expect(cont.available).toBe(false)
    expect(cont.reason).toBeTruthy()
  })

  test("/help 每条都带一句说明，用不了的写明原因", () => {
    const lines = helpLines(fresh)
    expect(lines).toHaveLength(6)
    expect(lines.some((l) => l.includes("/plan <目标>"))).toBe(true)
    expect(lines.some((l) => l.includes("现在不行"))).toBe(true)
  })
})
