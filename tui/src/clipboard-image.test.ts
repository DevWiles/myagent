import { describe, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { pathToFileURL } from "node:url"
import {
  ImageStore,
  MAX_IMAGE_BYTES,
  imageFromPastedPath,
  imagePasteHint,
  isImagePasteKey,
  parseOsascriptPng,
  sniffMime,
} from "./clipboard-image.ts"

const PNG = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])
const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 0])
const WEBP = Uint8Array.from([...Buffer.from("RIFF"), 0, 0, 0, 0, ...Buffer.from("WEBP")])

describe("认格式", () => {
  test("按文件头认 png / jpeg / gif / webp", () => {
    expect(sniffMime(PNG)).toBe("image/png")
    expect(sniffMime(JPEG)).toBe("image/jpeg")
    expect(sniffMime(Uint8Array.from(Buffer.from("GIF89a")))).toBe("image/gif")
    expect(sniffMime(WEBP)).toBe("image/webp")
  })

  test("RIFF 但不是 WEBP（比如 wav）、随便的字节：不认", () => {
    expect(sniffMime(Uint8Array.from([...Buffer.from("RIFF"), 0, 0, 0, 0, ...Buffer.from("WAVE")]))).toBeNull()
    expect(sniffMime(Uint8Array.from(Buffer.from("hello")))).toBeNull()
  })
})

describe("macOS：osascript 的输出", () => {
  test("把 «data PNGf…» 里的十六进制还原成字节", () => {
    const out = "«data PNGf" + Buffer.from(PNG).toString("hex").toUpperCase() + "»\n"
    expect(parseOsascriptPng(out)).toEqual(PNG)
  })

  test("剪贴板里没图时 try 吞掉了错误、什么都没打印：返回 null", () => {
    expect(parseOsascriptPng("")).toBeNull()
  })
})

describe("拖文件进终端", () => {
  const dir = mkdtempSync(join(tmpdir(), "myagent-img-"))
  const plain = join(dir, "shot.png")
  const spaced = join(dir, "my shot (1).png")
  writeFileSync(plain, PNG)
  writeFileSync(spaced, PNG)

  test("裸路径、带引号（Windows 资源管理器）、file:// URL 都认", () => {
    for (const pasted of [plain, `"${plain}"`, `'${plain}'`, pathToFileURL(plain).href]) {
      const r = imageFromPastedPath(pasted)
      expect(r?.ok).toBe(true)
      if (r?.ok) expect(r.image.mime).toBe("image/png")
    }
  })

  test("macOS 终端把空格和括号转义成 `\\ `：还原后照样认", () => {
    const escaped = spaced.replace(/([ ()])/g, "\\$1")
    expect(imageFromPastedPath(escaped)?.ok).toBe(true)
  })

  test("不是图片路径的就不碰：普通文字、不存在的文件、多行、不是图片的扩展名", () => {
    writeFileSync(join(dir, "notes.txt"), "x")
    for (const pasted of ["看下这个报错", join(dir, "nope.png"), `${plain}\n${plain}`, join(dir, "notes.txt")]) {
      expect(imageFromPastedPath(pasted)).toBeNull()
    }
  })

  test("扩展名是 .png 但内容不是图：说清楚，不当图发", () => {
    const fake = join(dir, "fake.png")
    writeFileSync(fake, "其实是文字")
    const r = imageFromPastedPath(fake)
    expect(r?.ok).toBe(false)
  })

  test("超过上限：拦下并说明多大", () => {
    const big = join(dir, "big.png")
    writeFileSync(big, Buffer.concat([Buffer.from(PNG), Buffer.alloc(MAX_IMAGE_BYTES)]))
    const r = imageFromPastedPath(big)
    expect(r?.ok).toBe(false)
    if (r && !r.ok) expect(r.reason).toContain("MB")
  })
})

describe("占位符", () => {
  const img = (n: number) => ({ mime: "image/png", data: String(n) })

  test("按在文字里出现的先后收图，删掉占位符的那张不带", () => {
    const store = new ImageStore()
    const a = store.add(img(1))
    const b = store.add(img(2))
    const c = store.add(img(3))
    expect([a, b, c]).toEqual(["[图片 #1]", "[图片 #2]", "[图片 #3]"])
    expect(store.collect(`先看 ${c}，再对比 ${a}`).map((i) => i.data)).toEqual(["3", "1"])
  })

  test("清空之后编号从头来", () => {
    const store = new ImageStore()
    store.add(img(1))
    store.clear()
    expect(store.collect("[图片 #1]")).toEqual([])
    expect(store.add(img(2))).toBe("[图片 #1]")
  })
})

describe("快捷键", () => {
  test("Ctrl+V 和 Alt+V 都算；普通 v、Ctrl+Shift+V 不算", () => {
    expect(isImagePasteKey({ name: "v", ctrl: true })).toBe(true)
    expect(isImagePasteKey({ name: "v", meta: true })).toBe(true)
    expect(isImagePasteKey({ name: "v" })).toBe(false)
    expect(isImagePasteKey({ name: "v", ctrl: true, shift: true })).toBe(false)
  })

  test("提示写法：Windows 是 Alt+V，Mac、Linux 是 Ctrl+V", () => {
    expect(imagePasteHint("win32")).toBe("Alt+V")
    expect(imagePasteHint("darwin")).toBe("Ctrl+V")
    expect(imagePasteHint("linux")).toBe("Ctrl+V")
  })
})

describe("Windows：读剪贴板的 PowerShell 脚本", () => {
  // 脚本有语法错误时 PowerShell 非零退出、什么都不打印，和「剪贴板里没图」一模一样——
  // 真出过一次（少了个分号）。在 Windows 上直接拿 PowerShell 的解析器验一遍
  test.skipIf(process.platform !== "win32")("能被 PowerShell 解析，没有语法错误", async () => {
    const { WINDOWS_SCRIPT } = await import("./clipboard-image.ts")
    const check = `$e = $null; [void][System.Management.Automation.Language.Parser]::ParseInput($env:SCRIPT, [ref]$null, [ref]$e); $e.Count`
    const proc = Bun.spawnSync(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", check], {
      env: { ...process.env, SCRIPT: WINDOWS_SCRIPT },
    })
    expect(proc.stdout.toString().trim()).toBe("0")
  }, 30000)
})
