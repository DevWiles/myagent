import { describe, expect, test } from "bun:test"
import {
  clampSelection,
  describeFilter,
  filterSessions,
  windowAround,
  type PickerFilter,
  type SessionRow,
} from "./session-picker.ts"

const row = (id: string, title: string, branch: string | null, savedAt = 0): SessionRow => ({
  id,
  title,
  turns: 3,
  model: "deepseek-chat",
  branch,
  startedAt: savedAt - 1000,
  savedAt,
  bytes: 1024,
})

const rows: SessionRow[] = [
  row("a", "加上多会话功能", "dev", 300),
  row("b", "调试 tmux 终端输出和 markdown 格式", "main", 200),
  row("c", "找开源 Deepseek TUI 命令行工具", "dev", 100),
  row("d", "看一下 pom.xml 的 artifactId", null, 50),
]

const base: PickerFilter = { query: "", branchOnly: false, currentBranch: "dev" }

describe("筛会话", () => {
  test("不筛的时候原样返回，顺序不动（后端已经按时间倒序了）", () => {
    expect(filterSessions(rows, base).map((r) => r.id)).toEqual(["a", "b", "c", "d"])
  })

  test("搜标题的子串", () => {
    expect(filterSessions(rows, { ...base, query: "tmux" }).map((r) => r.id)).toEqual(["b"])
    expect(filterSessions(rows, { ...base, query: "会话" }).map((r) => r.id)).toEqual(["a"])
  })

  test("搜索也匹配分支名——想找「那段在 main 上聊的」直接打 main 最顺手", () => {
    expect(filterSessions(rows, { ...base, query: "main" }).map((r) => r.id)).toEqual(["b"])
  })

  test("大小写不敏感：分支名常带大写，没人会为了搜到它去按 shift", () => {
    const mixed = [row("x", "Fix The Thing", "Feature/Picker")]
    expect(filterSessions(mixed, { ...base, query: "feature/pick" })).toHaveLength(1)
    expect(filterSessions(mixed, { ...base, query: "THE THING" })).toHaveLength(1)
  })

  test("只看当前分支", () => {
    expect(filterSessions(rows, { ...base, branchOnly: true }).map((r) => r.id)).toEqual(["a", "c"])
  })

  test("不在 git 仓库里时，只看当前分支不生效——否则按一下就清空，像是会话全丢了", () => {
    const filter = { query: "", branchOnly: true, currentBranch: null }
    expect(filterSessions(rows, filter)).toHaveLength(4)
  })

  test("搜索和分支过滤叠加", () => {
    const filter = { query: "找开源", branchOnly: true, currentBranch: "dev" }
    expect(filterSessions(rows, filter).map((r) => r.id)).toEqual(["c"])
  })

  test("什么都没匹配上就是空的", () => {
    expect(filterSessions(rows, { ...base, query: "不存在的东西" })).toEqual([])
  })
})

describe("选中项", () => {
  test("夹回列表里——按 Enter 不能接一段屏幕上没有的会话", () => {
    expect(clampSelection(5, 3)).toBe(2)
    expect(clampSelection(-1, 3)).toBe(0)
    expect(clampSelection(1, 3)).toBe(1)
  })

  test("列表空的时候是 0，不是 -1", () => {
    expect(clampSelection(3, 0)).toBe(0)
  })
})

describe("标题右边那句话", () => {
  test("没筛的时候只报总数", () => {
    expect(describeFilter(4, 4, base)).toBe("4 段")
  })

  test("筛掉了就要说清为什么——否则分不清「只有 1 段」和「有 20 段被滤掉了」", () => {
    expect(describeFilter(4, 1, { ...base, query: "tmux" })).toBe("1/4 段 · 搜「tmux」")
    expect(describeFilter(4, 2, { ...base, branchOnly: true })).toBe("2/4 段 · 只看 dev")
    expect(describeFilter(4, 1, { query: "x", branchOnly: true, currentBranch: "dev" })).toBe(
      "1/4 段 · 搜「x」 · 只看 dev",
    )
  })
})

describe("窗口滚动", () => {
  test("装得下就全显示", () => {
    expect(windowAround(3, 0, 10)).toEqual({ start: 0, end: 3 })
  })

  test("装不下时窗口跟着选中项走——不跟的话会接一段屏幕外的会话", () => {
    expect(windowAround(20, 0, 5)).toEqual({ start: 0, end: 5 })
    expect(windowAround(20, 4, 5)).toEqual({ start: 0, end: 5 })
    expect(windowAround(20, 5, 5)).toEqual({ start: 1, end: 6 })
    expect(windowAround(20, 19, 5)).toEqual({ start: 15, end: 20 })
  })

  test("选中项往回走时窗口也跟着回来", () => {
    expect(windowAround(20, 2, 5)).toEqual({ start: 0, end: 5 })
  })

  test("空列表和零高度都不炸", () => {
    expect(windowAround(0, 0, 5)).toEqual({ start: 0, end: 0 })
    expect(windowAround(10, 3, 0)).toEqual({ start: 0, end: 0 })
  })
})
