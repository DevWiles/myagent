/**
 * 配对二维码：把 ws URL 画成终端里的半块字符图。
 *
 * <h3>为什么是半块字符</h3>
 * 终端的一个字符格大约是 1:2（宽:高），直接一格一模块画出来的码是竖着拉长的，
 * 手机识别率很差。`▀`/`▄`/`█` 能在一格里放上下两个模块，画出来接近正方形。
 *
 * <h3>极性</h3>
 * 二维码规范要的是「亮底暗模块」。终端通常是深底浅字，所以这里**用前景色画底、
 * 用空格画模块**——深色终端里看到的正是标准极性。浅色主题的终端会变成反色码，
 * 主流扫码器（ML Kit / zxing TRY_HARDER）都认，况且码下面永远印着 URL，
 * 扫不出来还能手填。
 *
 * <h3>编码</h3>
 * 编码交给 uqr（零依赖，纯 TS）。这个模块只做「矩阵 → 字符」那一步，
 * 它是纯函数，也是唯一值得测的部分——Reed-Solomon 不该由我们再写一遍。
 */

import { encode } from "uqr"

/** 上下两个模块拼成一个字符。true = 暗模块（QR 语义上的「黑点」）。 */
export function renderHalfBlocks(matrix: boolean[][]): string {
  const size = matrix.length
  const row = (y: number, x: number): boolean => (matrix[y]?.[x] ?? false)
  const lines: string[] = []
  for (let y = 0; y < size; y += 2) {
    let line = ""
    for (let x = 0; x < size; x++) {
      const top = row(y, x)
      const bottom = row(y + 1, x)
      // 前景画的是「亮」：两亮=█，上亮=▀，下亮=▄，两暗=空格
      line += top ? (bottom ? " " : "▄") : bottom ? "▀" : "█"
    }
    lines.push(line)
  }
  return lines.join("\n")
}

/** 把一段文本编成可扫的字符画。`border` 是静区（quiet zone），规范建议至少 2。 */
export function qrText(text: string, border = 2): string {
  const { data } = encode(text, { border, ecc: "M" })
  return renderHalfBlocks(data)
}
