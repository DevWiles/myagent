/**
 * 写系统剪贴板。
 *
 * 自研选中之后，"选完能粘贴出去" 才算闭环——否则用户选了个高亮但拿不走。
 * 三条通路按可靠性降级（和那份泄露的 Claude Code 源码同一套策略）：
 *
 *   native      本机 pbcopy / xclip / clip.exe，最可靠
 *   tmux-buffer 在 tmux 里：load-buffer，prefix+] 可粘贴
 *   osc52       裸 OSC 52 转义序列，best-effort（iTerm2 默认关闭这个功能）
 *
 * 判 SSH 用 SSH_CONNECTION 而不是 SSH_TTY：tmux 的 pane 会永久继承 SSH_TTY，
 * 本地重新 attach 之后它还在，会误判成远程；SSH_CONNECTION 在 tmux 的
 * update-environment 默认列表里，重连时会被清掉，所以它才是可靠信号。
 */

import { spawn } from 'node:child_process'

export type ClipboardPath = 'native' | 'tmux-buffer' | 'osc52'

export function getClipboardPath(): ClipboardPath {
  const isRemote = Boolean(process.env['SSH_CONNECTION'])
  if (!isRemote) {
    if (process.platform === 'darwin') return 'native'
    if (process.platform === 'win32') return 'native'
    // Linux 本机要有 xclip/xsel 才算 native，探测成本高，交给 tmux/osc52 兜
    if (process.env['DISPLAY'] || process.env['WAYLAND_DISPLAY']) return 'native'
  }
  if (process.env['TMUX']) return 'tmux-buffer'
  return 'osc52'
}

function nativeCommand(): { cmd: string; args: string[] } | null {
  if (process.platform === 'darwin') return { cmd: 'pbcopy', args: [] }
  if (process.platform === 'win32') return { cmd: 'clip', args: [] }
  if (process.env['WAYLAND_DISPLAY']) return { cmd: 'wl-copy', args: [] }
  if (process.env['DISPLAY']) return { cmd: 'xclip', args: ['-selection', 'clipboard'] }
  return null
}

/** 把文本喂给一个读 stdin 的命令。失败只返回 false，不抛——复制失败不该搞崩界面。 */
function pipeToCommand(cmd: string, args: string[], text: string): Promise<boolean> {
  return new Promise(resolve => {
    try {
      const child = spawn(cmd, args, { stdio: ['pipe', 'ignore', 'ignore'] })
      child.on('error', () => resolve(false))
      child.on('close', code => resolve(code === 0))
      child.stdin.end(text)
    } catch {
      resolve(false)
    }
  })
}

/** OSC 52：把 base64 后的内容交给终端，由终端写入系统剪贴板。 */
function osc52Sequence(text: string): string {
  const b64 = Buffer.from(text, 'utf8').toString('base64')
  return `\x1b]52;c;${b64}\x07`
}

/**
 * tmux 的 DCS 透传：ESC P tmux ; <内容> ESC \
 * tmux 会把内容原样转发给外层终端，绕过自己的解析器。内层 ESC 必须加倍。
 * 需要 tmux 配了 `set -g allow-passthrough on`。
 */
function tmuxPassthrough(payload: string): string {
  return `\x1bPtmux;${payload.replace(/\x1b/g, '\x1b\x1b')}\x1b\\`
}

/**
 * 写剪贴板。返回实际走通的通路，全都失败返回 null。
 *
 * 注意：OSC 52 得写到 stdout，而我们正处在备用屏幕里。这没问题——
 * 转义序列不会显示成字符，终端会把它当指令吃掉。
 */
export async function writeClipboard(text: string): Promise<ClipboardPath | null> {
  if (!text) return null
  const path = getClipboardPath()

  if (path === 'native') {
    const native = nativeCommand()
    if (native && (await pipeToCommand(native.cmd, native.args, text))) return 'native'
    // native 失败就继续往下降级，别直接放弃
  }

  if (path === 'tmux-buffer' || process.env['TMUX']) {
    if (await pipeToCommand('tmux', ['load-buffer', '-'], text)) {
      // 顺带透传一次 OSC 52，让外层终端的系统剪贴板也拿到
      try {
        process.stdout.write(tmuxPassthrough(osc52Sequence(text)))
      } catch {
        /* 透传失败不影响 tmux buffer 本身 */
      }
      return 'tmux-buffer'
    }
  }

  try {
    process.stdout.write(osc52Sequence(text))
    return 'osc52'
  } catch {
    return null
  }
}
