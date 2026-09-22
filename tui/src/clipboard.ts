/**
 * 把选中的文字送进系统剪贴板。
 *
 * <h3>为什么非要我们自己做</h3>
 * 界面开着鼠标上报（`useMouse: true`，滚轮和点击都靠它），于是终端把拖选交给了程序，
 * **原生的「拖选 + Cmd-C」不再起作用**。OpenTUI 接手之后自己画选区、自己算选中了什么，
 * 但它只画不copy——用户看到一块高亮，按 Cmd-C 却什么都没有。
 *
 * 这是最让人抓狂的一类交互：它看起来是好的。没有报错、没有异常，
 * 只是粘出来的是上一次复制的东西，而人往往要试上三四次才反应过来。
 *
 * <h3>两条路，先近后远</h3
 * 1. **本机命令**（pbcopy / wl-copy / xclip / clip.exe）——最可靠，但只在本机有用
 * 2. **OSC 52**——一段写给终端的转义序列，能**穿过 SSH**：
 *    远端没有 pbcopy，但终端在你面前，让它自己去写剪贴板
 *
 * 顺序是刻意的：本机有命令就用命令，它不依赖终端支不支持某个转义序列。
 * 两条都不通时如实返回 false，让调用方告诉用户一声——
 * 静默失败等于把「看起来是好的」这个毛病又造了一遍。
 */

import { spawn } from "node:child_process"
import { writeSync } from "node:fs"

/** 一条本机复制命令。 */
type Copier = { command: string; args: string[] }

/**
 * 按平台挑复制命令。
 *
 * <p>Linux 上先 Wayland 后 X11：`WAYLAND_DISPLAY` 在的时候 xclip 多半连不上显示服务器，
 * 反过来 wl-copy 在纯 X11 下也一样。按环境变量分流比挨个试一遍快，也少一次失败日志。
 */
function copiersFor(platform: NodeJS.Platform): Copier[] {
  if (platform === "darwin") return [{ command: "pbcopy", args: [] }]
  if (platform === "win32") return [{ command: "clip.exe", args: [] }]
  const wayland = process.env["WAYLAND_DISPLAY"]
  const x11: Copier[] = [
    { command: "xclip", args: ["-selection", "clipboard"] },
    { command: "xsel", args: ["--clipboard", "--input"] },
  ]
  return wayland ? [{ command: "wl-copy", args: [] }, ...x11] : x11
}

/** 跑一条复制命令，把文字从 stdin 喂进去。命令不存在或非零退出都算没成。 */
function runCopier(copier: Copier, text: string): Promise<boolean> {
  return new Promise((resolve) => {
    let child
    try {
      child = spawn(copier.command, copier.args, { stdio: ["pipe", "ignore", "ignore"] })
    } catch {
      resolve(false)
      return
    }
    // 命令不存在时 spawn 不抛异常，而是异步发 error 事件——两处都得接
    child.on("error", () => resolve(false))
    child.on("close", (code) => resolve(code === 0))
    child.stdin.on("error", () => resolve(false))   // 命令已经死了还往里写会 EPIPE
    child.stdin.end(text)
  })
}

/**
 * OSC 52：请终端自己把这段文字放进剪贴板。
 *
 * <p>格式是 `ESC ] 52 ; c ; <base64> BEL`。它<b>不动光标、不占格子</b>，
 * 所以在一个正在刷帧的全屏界面里写它是安全的——这也是敢直接往 stdout 里塞的原因。
 *
 * <p>不支持的终端会静默忽略整段序列（macOS 自带 Terminal 就是这样），
 * 所以最坏情况是「没复制上」，而不是「屏幕上多了一串乱码」。
 *
 * <p>长度上限是真实存在的：很多终端把 OSC 52 的载荷卡在 100KB 上下，
 * 超了整段丢弃。这里在 64KB 就放弃并如实返回 false——
 * 送一段被截断的文本进剪贴板，比没复制上更糟：用户不会发现。
 */
const OSC52_MAX_BYTES = 64 * 1024

function copyViaOsc52(text: string): boolean {
  if (process.env["MYAGENT_NO_OSC52"] === "1" || !process.stdout.isTTY) return false
  const payload = Buffer.from(text, "utf8")
  if (payload.byteLength > OSC52_MAX_BYTES) return false
  try {
    writeSync(1, `\x1b]52;c;${payload.toString("base64")}\x07`)
    return true
  } catch {
    return false
  }
}

/**
 * 复制。成功返回 true。
 *
 * <p>注意 OSC 52 那条路的 true 是「发出去了」，不是「终端真的收下了」——
 * 转义序列没有回执，这是协议本身的限制。所以本机命令永远排在它前面：
 * 那条路的 true 是退出码说了算的。
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (!text) return false
  for (const copier of copiersFor(process.platform)) {
    if (await runCopier(copier, text)) return true
  }
  return copyViaOsc52(text)
}
