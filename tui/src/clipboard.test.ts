import { describe, expect, test } from 'bun:test'
import { getClipboardPath } from './clipboard.ts'

describe('剪贴板通路选择', () => {
  const saved = { ...process.env }
  const reset = () => {
    for (const k of ['SSH_CONNECTION', 'SSH_TTY', 'TMUX', 'DISPLAY', 'WAYLAND_DISPLAY']) {
      delete process.env[k]
    }
  }

  test('本机 macOS 走 native', () => {
    reset()
    if (process.platform === 'darwin') expect(getClipboardPath()).toBe('native')
  })

  test('SSH 里不走 native（远程 pbcopy 写的是远程剪贴板）', () => {
    reset()
    process.env['SSH_CONNECTION'] = '1.2.3.4 22 5.6.7.8 22'
    expect(getClipboardPath()).not.toBe('native')
  })

  test('只有 SSH_TTY 不算远程——tmux pane 会永久继承它，本地重连后仍在', () => {
    reset()
    process.env['SSH_TTY'] = '/dev/ttys001'
    if (process.platform === 'darwin') expect(getClipboardPath()).toBe('native')
  })

  test('SSH + tmux 走 tmux-buffer', () => {
    reset()
    process.env['SSH_CONNECTION'] = 'x'
    process.env['TMUX'] = '/tmp/tmux-501/default,1,0'
    expect(getClipboardPath()).toBe('tmux-buffer')
  })

  test('SSH 无 tmux 退到 osc52', () => {
    reset()
    process.env['SSH_CONNECTION'] = 'x'
    expect(getClipboardPath()).toBe('osc52')
  })

  test('恢复环境', () => {
    reset()
    Object.assign(process.env, saved)
    expect(true).toBe(true)
  })
})
