import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { jarIsStale } from "./build.ts"

/**
 * 只测判断，不测 mvn：buildBackend 是「spawn 一个进程等退出码」，
 * 它的错误路径（找不到 mvn、退出码非零带尾巴）形状简单；
 * 而 jarIsStale 是启动路径上**每次都跑**的判断，判错的两个方向代价都不小——
 * 误报新鲜是拿旧代码跑（最难查的那种错），误报过期是每次启动白等五秒。
 */
describe("jarIsStale", () => {
  let root: string
  let jar: string

  /** 把文件的修改时间设到 now 偏移 sec 秒的位置。 */
  const touchAt = (path: string, sec: number) => {
    const t = new Date(Date.now() + sec * 1000)
    utimesSync(path, t, t)
  }

  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), "myagent-build-"))
    mkdirSync(join(root, "src", "main", "java"), { recursive: true })
    mkdirSync(join(root, "target"), { recursive: true })
    writeFileSync(join(root, "pom.xml"), "<project/>")
    writeFileSync(join(root, "src", "main", "java", "A.java"), "class A {}")
    jar = join(root, "target", "myagent.jar")
    writeFileSync(jar, "jar")
    // 基线：jar 比所有源文件新 100 秒
    touchAt(join(root, "pom.xml"), -200)
    touchAt(join(root, "src", "main", "java", "A.java"), -200)
    touchAt(jar, -100)
  })

  afterEach(() => {
    rmSync(root, { recursive: true, force: true })
  })

  test("jar 不存在就是过期——第一次运行走的就是这条", () => {
    rmSync(jar)
    expect(jarIsStale(jar, root)).toBe(true)
  })

  test("所有源文件都比 jar 旧，就是新鲜的", () => {
    expect(jarIsStale(jar, root)).toBe(false)
  })

  test("深层目录里有一个更新的源文件就算过期", () => {
    const deep = join(root, "src", "main", "java", "dev", "wiles")
    mkdirSync(deep, { recursive: true })
    writeFileSync(join(deep, "B.java"), "class B {}")
    touchAt(join(deep, "B.java"), 10)

    expect(jarIsStale(jar, root)).toBe(true)
  })

  test("pom.xml 变了也算过期——加依赖不改源码，照样得重打", () => {
    touchAt(join(root, "pom.xml"), 10)
    expect(jarIsStale(jar, root)).toBe(true)
  })

  test("src/test 不算数：改测试不用重打 jar", () => {
    // 语义和原来脚本里 find src/main pom.xml 一字不差
    const tests = join(root, "src", "test", "java")
    mkdirSync(tests, { recursive: true })
    writeFileSync(join(tests, "ATest.java"), "class ATest {}")
    touchAt(join(tests, "ATest.java"), 10)

    expect(jarIsStale(jar, root)).toBe(false)
  })
})
