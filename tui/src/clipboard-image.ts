/**
 * 把截图贴进对话：从系统剪贴板读图片，或者认出拖进终端的图片文件路径。
 *
 * <h3>为什么要自己读剪贴板</h3>
 * 终端的粘贴只会把<b>文字</b>交给程序。剪贴板里是一张截图时，按 Ctrl+V / Cmd+V，
 * 程序什么都收不到。所以得像 Claude Code 那样，用一个单独的快捷键让程序自己去问系统要图：
 *
 * <ul>
 *   <li>Windows：PowerShell 走 .NET 的剪贴板 API（要 -STA，剪贴板只认单线程套间）</li>
 *   <li>macOS：系统自带的 osascript，`the clipboard as «class PNGf»` 吐出 PNG 的十六进制</li>
 *   <li>Linux：先 Wayland（wl-paste）后 X11（xclip），和 clipboard.ts 复制那边同一个分流</li>
 * </ul>
 *
 * <h3>拖文件进来</h3>
 * 从访达、资源管理器把图片拖进终端，终端粘贴进来的是那个文件的路径（可能带引号、
 * 空格被转义）。认出这种「整段粘贴就是一个图片文件路径」的情况，直接当图附上。
 */

import { spawn } from "node:child_process"
import { existsSync, readFileSync, statSync } from "node:fs"

export type ClipImage = { mime: string; data: string }

/** 后端只认这几种，见 PromptImages.java。 */
const MAGIC: [string, number[]][] = [
  ["image/png", [0x89, 0x50, 0x4e, 0x47]],
  ["image/jpeg", [0xff, 0xd8, 0xff]],
  ["image/gif", [0x47, 0x49, 0x46, 0x38]],
  ["image/webp", [0x52, 0x49, 0x46, 0x46]],   // RIFF....WEBP，后面再验一次
]

/** 按文件头认格式，不信扩展名。认不出返回 null。 */
export function sniffMime(bytes: Uint8Array): string | null {
  for (const [mime, sig] of MAGIC) {
    if (sig.every((b, i) => bytes[i] === b)) {
      if (mime === "image/webp" && String.fromCharCode(...bytes.slice(8, 12)) !== "WEBP") continue
      return mime
    }
  }
  return null
}

/** 和后端一致的单张上限。超了在这边就拦下，不用等后端回一条错误 */
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024

export type ImageResult = { ok: true; image: ClipImage } | { ok: false; reason: string }

function fromBytes(bytes: Uint8Array): ImageResult {
  const mime = sniffMime(bytes)
  if (!mime) return { ok: false, reason: "不是 png / jpeg / gif / webp 图片" }
  if (bytes.length > MAX_IMAGE_BYTES) {
    return { ok: false, reason: `图片有 ${(bytes.length / 1024 / 1024).toFixed(1)} MB，超过 10 MB，先缩小一点再贴` }
  }
  return { ok: true, image: { mime, data: Buffer.from(bytes).toString("base64") } }
}

/** 跑一条命令，拿 stdout 的原始字节。命令不存在、非零退出、超时都算没拿到。 */
function capture(command: string, args: string[], timeoutMs = 10_000): Promise<Buffer | null> {
  return new Promise((resolve) => {
    let child
    try {
      child = spawn(command, args, { stdio: ["ignore", "pipe", "ignore"], windowsHide: true })
    } catch {
      resolve(null)
      return
    }
    const chunks: Buffer[] = []
    const timer = setTimeout(() => child.kill(), timeoutMs)
    child.stdout.on("data", (c: Buffer) => chunks.push(c))
    child.on("error", () => (clearTimeout(timer), resolve(null)))
    child.on("close", (code) => (clearTimeout(timer), resolve(code === 0 ? Buffer.concat(chunks) : null)))
  })
}

/**
 * 剪贴板里没有图时，Windows 这段脚本什么都不打印、正常退出。
 * 每条语句都以分号结尾再拼成一行：-Command 收的是一整行，少一个分号就是语法错误，
 * 而那时 PowerShell 照样以非零码退出、什么都不打印，看上去和「没有图」一模一样。
 */
export const WINDOWS_SCRIPT = [
  "Add-Type -AssemblyName System.Windows.Forms, System.Drawing;",
  "$img = [System.Windows.Forms.Clipboard]::GetImage();",
  "if ($img) {",
  "$ms = New-Object System.IO.MemoryStream;",
  "$img.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png);",
  "[Console]::Out.Write([Convert]::ToBase64String($ms.ToArray()));",
  "}",
].join(" ")

/** osascript 把 PNG 打印成 `«data PNGf89504E47…»`，这里把十六进制拆出来 */
export function parseOsascriptPng(out: string): Uint8Array | null {
  const m = out.match(/«data PNGf([0-9A-Fa-f]+)»/)
  return m ? Uint8Array.from(Buffer.from(m[1]!, "hex")) : null
}

export async function readClipboardImage(platform: NodeJS.Platform = process.platform): Promise<ImageResult> {
  const none: ImageResult = { ok: false, reason: "剪贴板里没有图片。先截图或复制一张图，再按这个键" }
  if (platform === "win32") {
    const out = await capture("powershell.exe", ["-NoProfile", "-NonInteractive", "-STA", "-Command", WINDOWS_SCRIPT])
    const b64 = out?.toString("ascii").trim()
    return b64 ? fromBytes(Buffer.from(b64, "base64")) : none
  }
  if (platform === "darwin") {
    const out = await capture("osascript", ["-e", "try", "-e", "the clipboard as «class PNGf»", "-e", "end try"])
    const bytes = out ? parseOsascriptPng(out.toString("utf8")) : null
    return bytes ? fromBytes(bytes) : none
  }
  const wayland = process.env["WAYLAND_DISPLAY"]
  const tries: [string, string[]][] = [
    ...(wayland ? [["wl-paste", ["--no-newline", "--type", "image/png"]] as [string, string[]]] : []),
    ["xclip", ["-selection", "clipboard", "-t", "image/png", "-o"]],
  ]
  for (const [cmd, args] of tries) {
    const out = await capture(cmd, args)
    if (out && out.length > 0) return fromBytes(out)
  }
  return { ok: false, reason: "剪贴板里没有图片，或者没装 wl-paste / xclip" }
}

/**
 * 粘贴进来的这段文字是不是一个图片文件的路径（拖文件进终端就是这样）。是就读出来。
 *
 * <p>不是图片路径时返回 null，调用方照常把它当文字插进去——
 * 只认「整段就是一个存在的、看得出是图片的文件」，别的一律不碰。
 */
export function imageFromPastedPath(text: string): ImageResult | null {
  let p = text.trim()
  if (!p || p.includes("\n") || p.length > 1024) return null
  if ((p.startsWith('"') && p.endsWith('"')) || (p.startsWith("'") && p.endsWith("'"))) p = p.slice(1, -1)
  if (p.startsWith("file://")) {
    try {
      p = decodeURIComponent(new URL(p).pathname).replace(/^\/([A-Za-z]:)/, "$1")
    } catch {
      return null
    }
  }
  p = p.replace(/\\([ ()'&])/g, "$1")   // macOS 终端把路径里的空格、括号转义成 `\ `
  if (!/\.(png|jpe?g|gif|webp)$/i.test(p)) return null
  try {
    if (!existsSync(p) || !statSync(p).isFile()) return null
    return fromBytes(readFileSync(p))
  } catch {
    return null
  }
}

/** 这个键是不是「贴图」。Windows 上 Ctrl+V 被终端拿去贴文字了，所以那边用 Alt+V；两种都认 */
export function isImagePasteKey(key: { name?: string; ctrl?: boolean; meta?: boolean; shift?: boolean }): boolean {
  return key.name === "v" && !key.shift && (Boolean(key.ctrl) || Boolean(key.meta))
}

/** 给人看的快捷键写法 */
export function imagePasteHint(platform: NodeJS.Platform = process.platform): string {
  return platform === "win32" ? "Alt+V" : "Ctrl+V"
}

/**
 * 一句话里贴进来的图。输入框里只有占位符 `[图片 #n]`，图本身在这儿。
 *
 * 提交时只带上占位符还完整留在文字里的那几张，顺序按它们在文字里出现的先后——
 * 和粘贴折叠一个规矩：占位符删了就是不要这张了。占位符本身照样留在发给模型的文字里，
 * 模型才知道「这张图」指的是哪张。
 */
export class ImageStore {
  private seq = 0
  private readonly images = new Map<string, ClipImage>()

  add(image: ClipImage): string {
    const placeholder = `[图片 #${++this.seq}]`
    this.images.set(placeholder, image)
    return placeholder
  }

  collect(value: string): ClipImage[] {
    return [...this.images.entries()]
      .map(([placeholder, image]) => ({ at: value.indexOf(placeholder), image }))
      .filter((x) => x.at >= 0)
      .sort((a, b) => a.at - b.at)
      .map((x) => x.image)
  }

  clear(): void {
    this.seq = 0
    this.images.clear()
  }
}
