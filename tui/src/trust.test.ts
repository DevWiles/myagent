import { describe, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { isTrusted, loadTrusted, rememberTrusted, riskOf, shouldRemember } from "./trust.ts"

const scratch = () => mkdtempSync(join(tmpdir(), "myagent-trust-"))

describe("信任清单", () => {
  test("信任过的目录和它的子目录都不再问", () => {
    const root = scratch()
    expect(isTrusted(root, [root])).toBe(true)
    expect(isTrusted(join(root, "a", "b"), [root])).toBe(true)
  })

  test("只是名字开头一样的兄弟目录不算——/a/bc 不在 /a/b 下面", () => {
    const root = scratch()
    expect(isTrusted(root + "x", [root])).toBe(false)
  })

  test("上级目录不因为子目录被信任而被信任", () => {
    const root = scratch()
    expect(isTrusted(root, [join(root, "child")])).toBe(false)
  })

  test("Windows 上路径不分大小写", () => {
    expect(isTrusted("C:\\Projects\\App", ["c:\\projects"], "win32")).toBe(process.platform === "win32")
  })

  test("记下来、读回来，同一个目录不重复记", () => {
    const file = join(scratch(), "nested", "trusted.json")
    const dir = scratch()
    rememberTrusted(dir, file)
    rememberTrusted(dir, file)
    expect(loadTrusted(file)).toEqual([dir])
  })

  test("文件不在、坏了、形状不对都当空清单——最坏是多问一次，不能崩", () => {
    const dir = scratch()
    expect(loadTrusted(join(dir, "missing.json"))).toEqual([])
    writeFileSync(join(dir, "bad.json"), "{not json")
    expect(loadTrusted(join(dir, "bad.json"))).toEqual([])
    writeFileSync(join(dir, "shape.json"), JSON.stringify({ directories: "x" }))
    expect(loadTrusted(join(dir, "shape.json"))).toEqual([])
  })

  test("家目录只信这一次：记住它等于信任整台机器", () => {
    const home = scratch()
    expect(shouldRemember(home, home)).toBe(false)
    expect(shouldRemember(join(home, "proj"), home)).toBe(true)
  })

  test.if(process.platform === "win32")("C:\\ 这类系统目录也只信这一次——信任往下继承，记住它就连家目录一起信了", () => {
    const home = scratch()
    expect(shouldRemember("C:\\", home, "win32")).toBe(false)
    expect(shouldRemember("C:\\Users", home, "win32")).toBe(false)
    expect(shouldRemember("D:\\Program Files", home, "win32")).toBe(false)
    expect(shouldRemember("E:\\projects\\myagent", home, "win32")).toBe(true)
  })

  test.if(process.platform !== "win32")("类 Unix 的系统目录也只信这一次", () => {
    const home = scratch()
    expect(shouldRemember("/", home, process.platform)).toBe(false)
    expect(shouldRemember("/usr", home, process.platform)).toBe(false)
  })
})

describe("风险提示", () => {
  test("普通项目目录没有额外风险", () => {
    const home = scratch()
    const proj = join(home, "proj")
    mkdirSync(proj)
    expect(riskOf(proj, home)).toBeNull()
  })

  test("家目录、个人文件夹要写明", () => {
    const home = scratch()
    expect(riskOf(home, home)).toContain("家目录")
    mkdirSync(join(home, "Documents"))
    expect(riskOf(join(home, "Documents"), home)).toContain("Documents")
  })

  test("下面装着多个仓库的父目录（往下看两层）", () => {
    const home = scratch()
    const parent = join(home, "work")
    mkdirSync(join(parent, "group", "a", ".git"), { recursive: true })
    mkdirSync(join(parent, "group", "b", ".git"), { recursive: true })
    expect(riskOf(parent, home)).toContain("多个独立的代码仓库")
    expect(riskOf(join(parent, "group", "a"), home)).toBeNull()
  })

  test.if(process.platform === "win32")("Windows 的盘符根目录和系统目录", () => {
    const home = scratch()
    expect(riskOf("C:\\", home, "win32")).toContain("系统目录")
    expect(riskOf("C:\\Windows", home, "win32")).toContain("系统目录")
    expect(riskOf("D:\\Program Files", home, "win32")).toContain("系统目录")
  })

  test.if(process.platform !== "win32")("类 Unix 的系统目录", () => {
    const home = scratch()
    expect(riskOf("/", home, process.platform)).toContain("系统目录")
    expect(riskOf("/usr", home, process.platform)).toContain("系统目录")
  })
})
