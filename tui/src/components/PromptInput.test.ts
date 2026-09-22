import { describe, expect, test } from "bun:test"
import { isNewlineKey, promptKeyBindings, rowsFor } from "./PromptInput.tsx"

/**
 * 输入框的两条纪律，都是从 textarea 的**默认值反过来**的，所以特别值得钉死：
 * 回车是提交（不是换行），高度按**折行后**的行数长（不是逻辑行数）。
 *
 * <p>控件本身的折行是 Zig 那层干的，这里测不了也不该测。
 * 测的是我们自己定的那两条规矩——它们破了，症状分别是
 * 「打字打不出换行 / 每敲一次回车就发出去一句」和「框又不长高了」。
 */
describe("回车键位", () => {
  const find = (want: string) =>
    promptKeyBindings.filter((b) => b.action === want).map((b) => b.name)

  test("光秃秃的回车是提交——欢迎页上写着的就是这条，不能改", () => {
    const submit = promptKeyBindings.find(
      (b) => b.name === "return" && !("shift" in b && b.shift) && !("meta" in b && b.meta),
    )
    expect(submit?.action).toBe("submit")
  })

  test("小键盘回车和主键盘一致——两个键长得一样，行为不一样最气人", () => {
    expect(find("submit")).toContain("kpenter")
  })

  test("换行给了三条路，终端对 Shift+Enter 的支持不一致", () => {
    // shift（新终端认得）、meta（Option+Enter）、Ctrl-J（万能，它就是个 LF 字节）
    expect(isNewlineKey({ name: "return", shift: true })).toBe(true)
    expect(isNewlineKey({ name: "return", meta: true })).toBe(true)
    expect(isNewlineKey({ name: "j", ctrl: true })).toBe(true)
  })

  test("光秃秃的回车不算换行——App 靠这个判断要不要拦下它去选菜单", () => {
    expect(isNewlineKey({ name: "return" })).toBe(false)
    expect(isNewlineKey({ name: "kpenter" })).toBe(false)
  })

  test("别的键一概不是换行", () => {
    expect(isNewlineKey({ name: "tab", shift: true })).toBe(false)
    expect(isNewlineKey({ name: "j" })).toBe(false)
  })
})

describe("高度跟着折行走", () => {
  test("空的时候一行，不留一块空白", () => {
    expect(rowsFor(0)).toBe(1)
    expect(rowsFor(1)).toBe(1)
  })

  test("折出几行就长几行——这正是这次要修的：以前长高了但字还挤在一行", () => {
    expect(rowsFor(3)).toBe(3)
    expect(rowsFor(8)).toBe(8)
  })

  test("到顶就不再长，改成内部滚动——输入框不该吃掉半个屏幕", () => {
    expect(rowsFor(9)).toBe(8)
    expect(rowsFor(400)).toBe(8)
  })
})
