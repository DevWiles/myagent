import { describe, expect, test } from "bun:test"
import {
  acceptSelected,
  argsOf,
  filterCommands,
  helpLines,
  listCommands,
  looksLikeCommand,
  matchCommand,
  resolve,
  statusLines,
  type CommandContext,
} from "./commands.ts"

/** 后端啥都支持、存档也都有的情况。 */
const full: CommandContext = {
  features: ["plan_mode", "skills", "session",
    "sessions", "compact", "memory", "undo", "rewind"],
  hasContinuable: true,
  skills: [],
}

/** 刚连上、什么存档都没有。 */
const fresh: CommandContext = { ...full, hasContinuable: false }

describe("看起来是不是命令", () => {
  test("/ 开头的单个词算", () => {
    expect(looksLikeCommand("/help")).toBe(true)
    expect(looksLikeCommand("  /cont")).toBe(true)
    expect(looksLikeCommand("/memory undo")).toBe(true)
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
      "/continue",
      "/resume",
      "/compact",
      "/undo",
      "/rewind",
      "/memory",
      "/clear",
      "/qr",
      "/status",
      "/scroll-speed",
    ])
  })

  test("按前缀收窄", () => {
    expect(filterCommands("/c", full).map((c) => c.name)).toEqual([
      "/continue",
      "/compact",
      "/clear",
    ])
    expect(filterCommands("/co", full).map((c) => c.name)).toEqual(["/continue", "/compact"])
    expect(filterCommands("/com", full).map((c) => c.name)).toEqual(["/compact"])
    expect(filterCommands("/cl", full).map((c) => c.name)).toEqual(["/clear"])
    expect(filterCommands("/re", full).map((c) => c.name)).toEqual(["/resume", "/rewind"])
    expect(filterCommands("/rew", full).map((c) => c.name)).toEqual(["/rewind"])
  })

  test("大小写不敏感——敲 /Help 的人要的是 /help", () => {
    expect(filterCommands("/HEL", full).map((c) => c.name)).toEqual(["/help"])
  })

  test("开始打参数之后菜单只剩那一条，不再挡视线", () => {
    expect(filterCommands("/memory undo", full).map((c) => c.name)).toEqual(["/memory"])
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
    expect(matchCommand("/memory undo", full)?.id).toBe("memory")
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
    const r = resolve("/memory undo", full)
    expect(r.kind).toBe("command")
    if (r.kind === "command") {
      expect(r.entry.id).toBe("memory")
      expect(r.args).toBe("undo")
    }
  })

  test("没这条命令 —— 必须拦住，不能发给模型", () => {
    expect(resolve("/zzz", full)).toEqual({ kind: "unknown", typed: "/zzz" })
    expect(resolve("/繼續", full).kind).toBe("prompt")
  })

  test("/resume 精确匹配自己", () => {
    const r = resolve("/resume", full)
    expect(r.kind).toBe("command")
    if (r.kind === "command") {
      expect(r.entry.id).toBe("resume")
    }
  })

  test("没有存档时 /resume 照样能开——面板自己会说这个工作区还没有会话", () => {
    expect(resolve("/resume", fresh).kind).toBe("command")
  })

  test("/re 前缀不唯一，不替用户在挑会话和回检查点之间猜", () => {
    expect(matchCommand("/re", full)).toBeNull()
  })

  // 「要参数却没给」这条分支暂时测不了：/plan 和 /team 改成模式（Shift+Tab）之后，
  // **没有任何内置命令再声明 args**。分支本身留着（见 commands.ts 里那段说明），
  // 下一条带参数的命令加进来时，这个用例跟着一起补回来。

  test("没存档时 /continue 用不了，而且要说清为什么", () => {
    const r = resolve("/continue", fresh)
    expect(r.kind).toBe("unavailable")
    if (r.kind === "unavailable") {
      expect(r.entry.reason).toBe("这个工作区还没有存下来的会话")
    }
  })

  test("后端没宣告 compact 能力时 /compact 用不了", () => {
    const noCompact: CommandContext = { ...full, features: ["session"] }
    const r = resolve("/compact", noCompact)
    expect(r.kind).toBe("unavailable")
  })

  test("/resume-plan 没了：DAG 计划下线之后它是个不存在的命令，不能当提示词发出去", () => {
    expect(resolve("/resume-plan", full)).toEqual({ kind: "unknown", typed: "/resume-plan" })
  })
})

describe("列表和帮助", () => {
  test("用不了的命令也列出来，灰着比消失好懂", () => {
    const entries = listCommands(fresh)
    expect(entries).toHaveLength(11)
    const cont = entries.find((e) => e.id === "continue")!
    expect(cont.available).toBe(false)
    expect(cont.reason).toBeTruthy()
  })

  test("/help 每条都带一句说明，用不了的写明原因", () => {
    const lines = helpLines(fresh)
    expect(lines).toHaveLength(11)
    expect(lines.some((l) => l.includes("/compact"))).toBe(true)
    expect(lines.every((l) => !l.includes("/plan ") && !l.includes("/team")),
    ).toBe(true)
    expect(lines.some((l) => l.includes("现在不行"))).toBe(true)
  })
})

describe("参数部分", () => {
  test("首词之后的都是参数", () => {
    expect(argsOf("/memory clear-auto")).toBe("clear-auto")
    expect(argsOf("  /memory   clear-auto  ")).toBe("clear-auto")
  })

  test("没有参数就是空串", () => {
    expect(argsOf("/memory")).toBe("")
    expect(argsOf("")).toBe("")
  })

  test("打了一半的命令也能取出参数——高亮的是 /memory，参数是后面那句", () => {
    expect(argsOf("/mem undo")).toBe("undo")
  })
})

describe("菜单开着时按回车", () => {
  const menu = (query: string, ctx = full) => filterCommands(query, ctx)

  test("执行高亮那条，而不是解析敲了一半的字——/c 同时匹配三条命令", () => {
    const rows = menu("/c")          // /continue、/compact、/clear
    expect(rows).toHaveLength(3)
    const r = acceptSelected(rows, 2, "/c")
    expect(r.kind).toBe("run")
    if (r.kind === "run") expect(r.entry.id).toBe("clear")
  })

  test("参数从用户实际敲的字里取，不是从高亮那条里取", () => {
    const r = acceptSelected(menu("/mem undo"), 0, "/mem undo")
    expect(r.kind).toBe("run")
    if (r.kind === "run") {
      expect(r.entry.id).toBe("memory")
      expect(r.args).toBe("undo")
    }
  })

  test("命令还没打全：补完整，别报错——他本来就在打", () => {
    const r = acceptSelected(menu("/mem"), 0, "/mem")
    expect(r).toEqual({ kind: "run", entry: menu("/mem")[0], args: "" })
  })

  test("用不了的那条按回车只是说一句，不执行", () => {
    const rows = menu("/continue", fresh)
    const r = acceptSelected(rows, 0, "/continue")
    expect(r.kind).toBe("refuse")
  })

  test("列表空的时候按回车什么都不做", () => {
    expect(acceptSelected([], 0, "/zzz")).toEqual({ kind: "none" })
    expect(acceptSelected(menu("/c"), 9, "/c")).toEqual({ kind: "none" })
  })
})


describe("/status", () => {
  test("模型、baseUrl、打过码的 key 都列出来", () => {
    const lines = statusLines({
      model: "deepseek-flash",
      hasApiKey: true,
      baseUrl: "https://api.deepseek.com",
      apiKeyMasked: "sk-****abcd",
    })
    expect(lines[0]).toContain("deepseek-flash")
    expect(lines[1]).toContain("https://api.deepseek.com")
    expect(lines[2]).toContain("sk-****abcd")
  })

  test("没配 key 就说没配", () => {
    expect(statusLines({ model: "m", hasApiKey: false, baseUrl: "u", apiKeyMasked: null })[2]).toContain("未配置")
  })

  test("老后端没发的字段照实说，不拿默认值冒充", () => {
    const lines = statusLines({ model: "m", hasApiKey: true })
    expect(lines[1]).toContain("后端没发")
    expect(lines[2]).toContain("后端没发")
  })
})

describe("技能（/名字 点一份技能）", () => {
  const withSkills: CommandContext = {
    ...full,
    skills: [
      { id: "baguwen", description: "记八股文" },
      { id: "mg-video", description: "做成带配音的视频" },
      // 和内置命令撞名：内置的赢，技能不再列一遍
      { id: "help", description: "别的 help" },
    ],
  }

  test("技能排在内置命令后面，撞名的不列", () => {
    const names = listCommands(withSkills).map((c) => c.name)
    expect(names.slice(-2)).toEqual(["/baguwen", "/mg-video"])
    expect(names.filter((n) => n === "/help")).toHaveLength(1)
  })

  test("边打边筛照样认技能", () => {
    expect(filterCommands("/mg", withSkills).map((c) => c.name)).toEqual(["/mg-video"])
    expect(filterCommands("/b", withSkills).map((c) => c.name)).toEqual(["/baguwen"])
  })

  test("回车：认成一条 skill 命令，参数原样带上，由界面整句发给后端", () => {
    const r = resolve("/baguwen 加一道 HashMap 扩容", withSkills)
    expect(r.kind).toBe("command")
    if (r.kind === "command") {
      expect(r.entry.id).toBe("skill")
      expect(r.entry.name).toBe("/baguwen")
      expect(r.args).toBe("加一道 HashMap 扩容")
    }
  })

  test("唯一前缀也认，发出去的用全名", () => {
    const r = resolve("/bag", withSkills)
    expect(r.kind === "command" && r.entry.name).toBe("/baguwen")
  })

  test("菜单里高亮一份技能按回车：run，参数取用户实际敲的", () => {
    const entries = filterCommands("/mg 这篇", withSkills)
    expect(acceptSelected(entries, 0, "/mg 这篇")).toMatchObject({ kind: "run", entry: { id: "skill" }, args: "这篇" })
  })

  test("没装技能时 / 菜单还是那几条内置命令", () => {
    expect(listCommands(full).every((c) => c.id !== "skill")).toBe(true)
  })
})
