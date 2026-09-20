/**
 * 记下启动那一刻光标在哪，退出时原样放回去。
 *
 * ## 为什么需要它：备用屏还回来的光标位置是错的
 *
 * OpenTUI 在进备用屏**之前**要量一次字符宽度，那段探测长这样（真机抓包）：
 *
 * ```
 * ESC[H          ← 光标挪到左上角
 * ESC]66;w=1; ESC\
 * ESC[6n
 * ESC[H          ← 又挪到左上角
 * ESC]66;s=2; ESC\
 * ESC[6n
 * ESC[u          ← 用 CSI u（SCORC）把光标还回去
 * ESC[s
 * ESC[?1049h     ← 进备用屏：终端在这一刻把光标位置存下来
 * ```
 *
 * `CSI s` / `CSI u` 是 ANSI.SYS 风格的存取光标，**macOS Terminal.app 不认**
 * （它只认 `ESC 7` / `ESC 8`）。于是 `ESC[u` 是个空操作，光标停在 (1,1)，
 * `?1049h` 把这个错误位置存了下来，退出时 `?1049l` 又忠实地还回来。
 *
 * 后果不是「屏幕被清空」——内容一直都在，终端还原得好好的——而是**从第 1 行
 * 开始被覆盖写**：收尾那两行盖掉第 1、2 行，shell 的提示符盖掉第 3 行。
 * 用户跑 myagent 之前敲的那几条命令，正好就在那几行。
 *
 * tmux、iTerm2 实现了 `CSI s`/`CSI u`，所以在那儿一切正常——这也是为什么
 * 这个 bug 在 tmux 里怎么都复现不出来。
 *
 * ## 为什么用「问一次绝对坐标」而不是自己存取
 *
 * 想过用 `ESC 7`/`ESC 8`（DECSC/DECRC，Terminal.app 认）自己包一层，不行：
 * `?1049h`/`?1049l` 用的**就是同一个**保存槽位，我们存进去的值会被它覆盖掉。
 *
 * 问绝对坐标（`ESC[6n` → `ESC[行;列R`）没有这个问题：拿到的是两个数字，
 * 存在我们自己的变量里，谁也动不了。还原时用 `ESC[行;列H`——
 * 这条序列没有任何终端不支持。
 *
 * ## 问不到就当没有
 *
 * 超时、不是 TTY、回的东西看不懂，一律返回 null，退出路径原样跳过这一步，
 * 行为退回修之前。**为了一个光标位置把启动卡住是本末倒置。**
 */

import { writeSync } from "node:fs"

export type CursorOrigin = { row: number; column: number }

/** 等终端回话最多这么久。它不回的话，多半是压根不支持，接着等没有意义。 */
const REPLY_TIMEOUT_MS = 300

/** 终端对 `ESC[6n` 的回答：`ESC[行;列R`。 */
const CPR = /\x1b\[(\d+);(\d+)R/

/**
 * 问终端「光标现在在哪」。
 *
 * <p>必须在 OpenTUI 接管 stdin <b>之前</b>调——它一接管就自己读键盘了，
 * 这个回答会被它当成一串按键吃掉。
 */
export async function queryCursorOrigin(
  stdin: NodeJS.ReadStream = process.stdin,
  stdout: NodeJS.WriteStream = process.stdout,
  // 注入的口子只为测试：真跑起来永远是往 fd 1 写。
  // 不注入的话，测试会把 ESC[6n 打到跑测试的那个终端上，
  // 而它会老老实实回一个光标报告——喂进测试进程的 stdin 里
  write: (text: string) => void = (text) => writeSync(1, text),
): Promise<CursorOrigin | null> {
  if (!stdin.isTTY || !stdout.isTTY) return null

  const wasRaw = stdin.isRaw
  // stdin 本来是不是停着的。问完必须原样还回去：
  // 我们要读回答就得让它流动起来，而读完顺手 pause() 的话，
  // 在「终端压根不回话」那条路上就等于**把键盘关了**——
  // 界面照常画出来，但一个键都不响应（实测过，Ctrl-C 都退不掉）
  const wasPaused = stdin.isPaused()
  try {
    // 不进 raw 模式的话，终端的回答会被行缓冲扣着，等到用户按回车才到——
    // 而那时候我们早就超时了
    stdin.setRawMode(true)
    return await new Promise<CursorOrigin | null>((resolve) => {
      let seen = ""
      let settled = false

      const finish = (value: CursorOrigin | null): void => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        stdin.off("data", onData)
        if (wasPaused) stdin.pause()
        resolve(value)
      }

      const onData = (chunk: Buffer): void => {
        // latin1：这里只关心 ASCII 的控制序列，按字节解码不会把多字节字符切坏
        seen += chunk.toString("latin1")
        const match = CPR.exec(seen)
        if (match) finish({ row: Number(match[1]), column: Number(match[2]) })
      }

      const timer = setTimeout(() => finish(null), REPLY_TIMEOUT_MS)
      stdin.on("data", onData)
      stdin.resume()
      write("\x1b[6n")
    })
  } catch {
    return null        // setRawMode 在某些环境下会抛，那就当问不到
  } finally {
    try {
      stdin.setRawMode(wasRaw)
    } catch {
      // 还不回去也没办法，总不能因此让程序起不来
    }
  }
}

/**
 * 把光标放回启动时的位置。
 *
 * <p>只该在<b>离开备用屏之后</b>调——在那之前调，动的是备用屏的光标，
 * 而那块屏马上就要被丢掉了。
 */
export function restoreCursorOrigin(origin: CursorOrigin | null): void {
  if (!origin) return
  writeSync(1, `\x1b[${origin.row};${origin.column}H`)
}
