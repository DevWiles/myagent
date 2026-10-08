import { describe, expect, test } from "bun:test"
import { buildEpilogue } from "./epilogue.ts"
import type { Entry, SessionState } from "./store.ts"

/** 只填测试关心的字段，其余给个能用的默认值。 */
function stateOf(partial: {
  entries?: Entry[]
  tokens?: number
  sentTokens?: number
  auditLog?: string
}): SessionState {
  return {
    ready: partial.auditLog
      ? {
          version: "0.2.0",
          workspace: "/tmp/x",
          model: "deepseek-chat",
          planMode: false,
          hasApiKey: true,
          tools: [],
          roles: [],
          features: [],
          auditLog: partial.auditLog,
        }
      : null,
    entries: partial.entries ?? [],
    approvalStage: { step: 'ask' as const, index: 0 },
    mode: 'normal' as const,
    activePlanKey: null,
    activeTeamKey: null,
    expanded: new Set(),
    busy: false,
    step: 0,
    approval: null,
    planConfirm: null,
    tokens: partial.tokens ?? 0,
    sentTokens: partial.sentTokens ?? 0,
    contextLimit: 1_000_000,
    lastDurationMs: null,
    startedAt: null,
    link: { phase: 'online' as const },
    closed: null,
    bootStatus: '连接后端中…',
    sessionPicker: null,
    interrupted: null,
  }
}

const user = (text: string): Entry => ({ kind: "user", key: `u${text}`, text })
const tools = (count: number): Entry => ({
  kind: "tools",
  key: `t${count}`,
  calls: Array.from({ length: count }, (_, i) => ({
    id: i,
    tool: "read_file",
    args: "{}",
    running: false,
    ok: true,
  })),
})

describe("buildEpilogue", () => {
  test("一句话都没说过就退出——什么都不打", () => {
    expect(buildEpilogue(stateOf({}), 3200)).toEqual([])
  })

  test("只有 notice 没有对话也不打——连不上后端时会留一条 notice，那不算一场会话", () => {
    const notice: Entry = { kind: "notice", key: "n1", level: "error", text: "后端起不来" }
    expect(buildEpilogue(stateOf({ entries: [notice] }), 3200)).toEqual([])
  })

  test("有对话就打一行摘要", () => {
    const lines = buildEpilogue(stateOf({ entries: [user("你好")], tokens: 1234 }), 34100)
    expect(lines).toEqual(["myagent 会话结束 · 1 轮对话 · 1.2k tokens · 34.1s"])
  })

  test("没调过工具时不出现「次工具调用」那一段", () => {
    const lines = buildEpilogue(stateOf({ entries: [user("你好")], tokens: 100 }), 1000)
    expect(lines[0]).not.toContain("工具调用")
  })

  test("多组工具调用要累加，不是数组数", () => {
    const entries = [user("a"), tools(3), user("b"), tools(2)]
    const lines = buildEpilogue(stateOf({ entries, tokens: 4200 }), 138000)
    expect(lines[0]).toBe("myagent 会话结束 · 2 轮对话 · 5 次工具调用 · 4.2k tokens · 2m18s")
  })

  test("token 为 0 时不打 tokens 段——没跑起来就别显摆这个数", () => {
    const lines = buildEpilogue(stateOf({ entries: [user("你好")] }), 1000)
    expect(lines[0]).not.toContain("tokens")
  })

  test("有审计日志就多打一行，并把家目录缩成 ~", () => {
    const home = process.env["HOME"] ?? ""
    const lines = buildEpilogue(
      stateOf({ entries: [user("你好")], auditLog: `${home}/.myagent/logs/run-x.jsonl` }),
      1000,
    )
    expect(lines).toHaveLength(2)
    expect(lines[1]).toBe("审计日志：~/.myagent/logs/run-x.jsonl")
  })

  test("没有审计日志字段时只有一行（老后端连上来也不该崩）", () => {
    const lines = buildEpilogue(stateOf({ entries: [user("你好")] }), 1000)
    expect(lines).toHaveLength(1)
  })
})

describe("退出摘要报的是发出量，不是窗口占用", () => {
  test("有累计就报累计——这几行的本意是「刚才烧了多少」", () => {
    const line = buildEpilogue(stateOf({ entries: [user("问")], tokens: 3_200, sentTokens: 9_800 }), 1000)[0]

    expect(line).toContain("发出 9.8k tokens")
    expect(line).not.toContain("3.2k")
  })

  test("后端没给累计时退回老行为，不至于什么都不显示", () => {
    const line = buildEpilogue(stateOf({ entries: [user("问")], tokens: 3_200 }), 1000)[0]

    expect(line).toContain("3.2k tokens")
  })
})
