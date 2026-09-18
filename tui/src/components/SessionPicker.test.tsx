import { describe, expect, test } from "bun:test"
import { testRender } from "@opentui/solid"
import { SessionPicker } from "./SessionPicker.tsx"
import type { PickerFilter, SessionRow } from "../session-picker.ts"

const NOW = new Date(2026, 8, 18, 14, 0).getTime()

const row = (
  id: string,
  title: string,
  branch: string | null,
  savedAt: number,
  bytes = 34_211,
): SessionRow => ({ id, title, turns: 5, model: "deepseek-chat", branch, startedAt: savedAt - 1000, savedAt, bytes })

const rows: SessionRow[] = [
  row("a", "加上多会话功能", "dev", NOW - 4 * 60_000),
  row("b", "调试 tmux 终端输出和 markdown 格式", "main", NOW - 22 * 3600_000),
  row("c", "看一下 pom.xml 的 artifactId", null, NOW - 30 * 3600_000, 5_800),
]

const filter: PickerFilter = { query: "", branchOnly: false, currentBranch: "dev" }

async function frame(overrides: Partial<Parameters<typeof SessionPicker>[0]> = {}) {
  const props = {
    rows,
    total: rows.length,
    selected: 0,
    currentId: "b",
    filter,
    columns: 80,
    maxRows: 10,
    now: NOW,
    ...overrides,
  }
  const { renderOnce, captureCharFrame } = await testRender(() => <SessionPicker {...props} />, {
    width: props.columns,
    height: 24,
  })
  await renderOnce()
  return captureCharFrame()
}

describe("会话选择器", () => {
  test("每段两行：标题 + 「时间 · 分支 · 大小 · 轮数」", async () => {
    const f = await frame()
    expect(f).toContain("加上多会话功能")
    expect(f).toContain("4 分钟前 · dev · 33.4KB · 5 轮")
    expect(f).toContain("昨天")          // 22 小时前那段跨了天
    expect(f).toContain("main")
  })

  test("此刻待着的那段标出来——否则用户会挑中自己现在就在的那段", async () => {
    const f = await frame()
    const marked = f.split("\n").filter((l) => l.includes("← 当前"))
    expect(marked).toHaveLength(1)
    expect(marked[0]).toContain("调试 tmux")
  })

  test("老存档没有分支，那一段就整段省掉，不写 null 也不写「无分支」", async () => {
    const f = await frame()
    const meta = f.split("\n").find((l) => l.includes("5.7KB"))!
    expect(meta).toContain("· 5.7KB ·")
    expect(meta).not.toContain("null")
    expect(meta).not.toContain("无分支")
  })

  test("选中的那行有指针，只有一行有", async () => {
    const f = await frame({ selected: 2 })
    const marked = f.split("\n").filter((l) => l.includes("❯"))
    expect(marked).toHaveLength(1)
    expect(marked[0]).toContain("pom.xml")
  })

  test("装不下时说清还剩多少，而且选中项一定在窗口里", async () => {
    const many = Array.from({ length: 12 }, (_, i) =>
      row(`s${i}`, `第 ${i} 段`, "dev", NOW - i * 3600_000),
    )
    const f = await frame({ rows: many, total: 12, selected: 11, maxRows: 3, currentId: "none" })
    expect(f).toContain("…还有 9 段")
    expect(f).toContain("第 11 段")     // 选中的那条必须看得见
    expect(f).not.toContain("第 0 段")
  })

  test("一段都没有时说清是「没有」还是「筛没了」", async () => {
    expect(await frame({ rows: [], total: 0 })).toContain("还没有存下来的会话")
    const filtered = await frame({
      rows: [],
      total: 3,
      filter: { query: "zzz", branchOnly: false, currentBranch: "dev" },
    })
    expect(filtered).toContain("没有匹配的会话")
    expect(filtered).toContain("0/3 段 · 搜「zzz」")
  })

  test("Ctrl-B 那项跟着当前分支变；不在 git 仓库里就不提它", async () => {
    expect(await frame()).toContain("Ctrl-B 只看 dev")
    expect(await frame({ filter: { ...filter, branchOnly: true } })).toContain("Ctrl-B 看全部分支")
    const noGit = await frame({ filter: { query: "", branchOnly: false, currentBranch: null } })
    expect(noGit).not.toContain("Ctrl-B")
    expect(noGit).toContain("Esc 取消")
  })

  test("窄屏下标题和元信息被截断，但一行都不许折行", async () => {
    const f = await frame({ columns: 40 })
    const lines = f.split("\n").filter((l) => l.trim())
    // 上下边框 2 + 标题栏 1 + 三段 × 2 行 + 键位提示 1 = 10。
    // 多出来的行都是折行折出来的，而面板钉在输入框上方，多一行就把输入框往下顶
    expect(lines).toHaveLength(10)
    expect(f).toContain("…")
  })
})
