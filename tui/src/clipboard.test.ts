import { describe, expect, test } from "bun:test"
import { copyToClipboard } from "./clipboard.ts"

/**
 * 剪贴板这条路能测的和不能测的，界限很清楚：
 * **能测**「文字真的到了剪贴板」（本机有 pbcopy 就读回来比一比），
 * **不能测** OSC 52——它是写给终端看的，转义序列没有回执，
 * 测试进程里也没有终端在听。所以那条路只测「不崩、不往 stdout 里吐东西」。
 */
describe("copyToClipboard", () => {
  const hasPbcopy = process.platform === "darwin"

  test("空字符串直接返回 false，不去起进程", async () => {
    expect(await copyToClipboard("")).toBe(false)
  })

  test.skipIf(!hasPbcopy)("文字真的进了系统剪贴板（读回来比一比）", async () => {
    // 带中文和换行：base64 和管道这两条路都可能在多字节上翻车
    const text = `并发测试 ${Date.now()}\n第二行\ttab`
    expect(await copyToClipboard(text)).toBe(true)

    const pasted = await new Promise<string>((resolve, reject) => {
      const child = Bun.spawn(["pbpaste"], { stdout: "pipe" })
      new Response(child.stdout).text().then(resolve, reject)
    })
    expect(pasted).toBe(text)
  })

  test.skipIf(!hasPbcopy)("超长文本也能进去——上限只卡 OSC 52 那条路，本机命令不受限", async () => {
    const text = "x".repeat(200_000)
    expect(await copyToClipboard(text)).toBe(true)
  })

  test("非 TTY 下没有本机命令时如实返回 false，而不是假装成功", async () => {
    // 造一个平台上必然没有复制命令的场景：把 PATH 清空，本机命令一个都起不来；
    // 测试进程的 stdout 不是 TTY，OSC 52 那条路也会自己放弃
    const savedPath = process.env["PATH"]
    process.env["PATH"] = "/nonexistent"
    try {
      expect(await copyToClipboard("随便什么")).toBe(false)
    } finally {
      process.env["PATH"] = savedPath
    }
  })
})
