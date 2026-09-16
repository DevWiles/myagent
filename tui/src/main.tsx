#!/usr/bin/env bun
/**
 * 入口：解析参数 → 拉起后端 → 渲染界面。
 *
 * 这里刻意不做任何 agent 逻辑。界面进程只负责画和收键盘，
 * 模型调用、工具执行、上下文管理全在 Java 后端里——它才是这个项目的本体。
 */

import { render } from 'ink'
import { createElement } from 'react'
import { resolve } from 'node:path'
import { startBackend, type Backend } from './backend.ts'
import { App } from './components/App.tsx'
import { SessionStore } from './store.ts'
import { THEMES, ThemeProvider, pickTheme } from './theme.ts'

const USAGE = `myagent-tui —— myagent 的终端界面（React + Ink）

用法:
  myagent-tui [选项]

选项:
  -d, --dir <路径>     工作区目录（默认当前目录）
  -m, --model <名称>   覆盖配置里的模型
  -p, --plan           只读模式：不给它改文件和执行命令的工具
      --unsafe         跳过工作区安全检查（危险）
      --theme <名称>   dark | light | ansi（默认按终端能力自动选）
      --jar <路径>     指定后端 jar（默认 ../target/myagent.jar，也可用 MYAGENT_JAR）
  -h, --help           显示本帮助

界面自己不跑 agent：它会拉起 \`java -jar myagent.jar --serve\`，
通过本机 WebSocket 驱动它。后端逻辑一行都没有改。`

type Args = {
  workspace: string
  model?: string
  planMode: boolean
  unsafe: boolean
  theme?: string
  jar?: string
  help: boolean
}

function parseArgs(argv: string[]): Args {
  const args: Args = { workspace: process.cwd(), planMode: false, unsafe: false, help: false }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    const value = () => {
      const next = argv[++i]
      if (next === undefined) throw new Error(`${arg} 后面要跟一个值`)
      return next
    }
    switch (arg) {
      case '-h':
      case '--help':
        args.help = true
        break
      case '-p':
      case '--plan':
        args.planMode = true
        break
      case '--unsafe':
        args.unsafe = true
        break
      case '-d':
      case '--dir':
        args.workspace = resolve(value())
        break
      case '-m':
      case '--model':
        args.model = value()
        break
      case '--theme':
        args.theme = value()
        break
      case '--jar':
        args.jar = value()
        break
      default:
        throw new Error(`未知参数: ${arg}（用 --help 查看用法）`)
    }
  }
  return args
}

async function main(): Promise<void> {
  let args: Args
  try {
    args = parseArgs(process.argv.slice(2))
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exit(2)
  }

  if (args.help) {
    console.log(USAGE)
    return
  }

  // 全屏 TUI 在管道里毫无意义，而且会把控制序列当正文吐出去
  if (!process.stdout.isTTY) {
    console.error('myagent-tui 需要在真正的终端里运行。非交互场景请直接用 `myagent "任务描述"`。')
    process.exit(2)
  }

  const backend = await startBackend({
    workspace: args.workspace,
    model: args.model,
    planMode: args.planMode,
    unsafe: args.unsafe,
    jar: args.jar,
  })

  const store = new SessionStore()
  // 等首帧到了再开画。否则第一帧会画出一个没有招牌的空输入框，
  // 下一帧再被 ready 顶掉——用户看到的就是启动时闪一下。
  await primeReady(backend, store)

  const theme = THEMES[pickTheme(args.theme)]

  // 独占终端：进备用屏幕（alternate screen）。
  //
  // 这才是终端 agent 的形态（Claude Code 亦然）：运行时占满整屏，退出时主屏
  // **原样恢复** —— 所以退出后你能看到启动前的命令历史（`% ./myagent-tui` 那行
  // 还在），但和 agent 的对话不留在 scrollback 里，随备用屏一起消失。
  //
  //   1049h  切备用屏（退出一条 1049l 把主屏还回来）
  //   2J H   清屏 + 光标归左上，从 (0,0) 开画
  //
  // 刻意不开鼠标追踪：那会夺走终端原生的拖拽选中。滚动/选中/复制全交给终端。
  //
  // **重要**：Terminal.app 的「允许应用程序使用备用屏幕」若没勾选，1049 会被
  // 直接忽略，程序就退化成在主屏往下打印（表现为「没有独占」）。所以下面用
  // DECRQM 查询终端是否真的接受了，没接受就明确告知用户怎么开，而不是静默失败。
  const ENTER_ALT = '\x1b[?1049h\x1b[2J\x1b[H'
  const EXIT_ALT = '\x1b[?1049l'

  process.stdout.write(ENTER_ALT)
  const altOk = await probeAltScreen()
  if (!altOk) {
    process.stdout.write(EXIT_ALT)
    console.error(
      '\n无法独占终端：你的终端忽略了备用屏幕（alternate screen）。\n' +
        'macOS Terminal.app 请开启：\n' +
        '  设置 → 描述文件 → 选中当前描述文件 → 高级 →\n' +
        '  勾选「允许应用程序使用备用屏幕缓冲区」，然后**新开一个窗口**。\n' +
        '（iTerm2 / Ghostty 默认已开启。tmux 需要 set -g alternate-screen on）\n',
    )
    process.exit(3)
  }

  let restored = false
  const restore = () => {
    if (restored) return
    restored = true
    process.stdout.write(EXIT_ALT)
  }
  process.on('exit', restore)
  process.on('SIGTERM', () => {
    restore()
    process.exit(0)
  })

  const instance = render(
    createElement(ThemeProvider, { value: theme }, createElement(App, { store, backend })),
    {
      // Ctrl-C 自己处理：正在跑的时候它应该是「中断这一轮」而不是「退出程序」
      exitOnCtrlC: false,
    },
  )

  try {
    await instance.waitUntilExit()
  } finally {
    backend.dispose()
  }
}

/**
 * 问终端：1049（备用屏）现在是什么状态？
 *
 * DECRQM：发 `ESC [ ? 1049 $ p`，终端回 `ESC [ ? 1049 ; <v> $ y`。
 *   v=1 已开启（我们刚发的 1049h 生效了）  v=2 未开启（被忽略）
 *   v=0 不支持这个模式
 * 不回应的终端也存在（不支持 DECRQM），那按「支持」处理——不能因为查不到
 * 就把能用的终端也挡掉。
 */
function probeAltScreen(): Promise<boolean> {
  return new Promise(resolve => {
    if (!process.stdin.isTTY) return resolve(true)
    let buf = ''
    let settled = false
    const finish = (ok: boolean) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      process.stdin.off('data', onData)
      process.stdin.setRawMode?.(false)
      resolve(ok)
    }
    const onData = (chunk: Buffer) => {
      buf += chunk.toString('binary')
      const m = /\x1b\[\?1049;(\d+)\$y/.exec(buf)
      if (m) finish(m[1] === '1' || m[1] === '3')
    }
    process.stdin.setRawMode?.(true)
    process.stdin.on('data', onData)
    process.stdout.write('\x1b[?1049$p')
    // 没回应 = 终端不支持状态查询，按能用处理
    const timer = setTimeout(() => finish(true), 300)
  })
}

/**
 * 把后端的首帧灌进 store，然后才返回。
 *
 * 超时不算失败：ready 收不到界面照样能起来（顶多少一块招牌），
 * 但连不上的错误此时早就在 startBackend 里抛过了。
 */
function primeReady(backend: Backend, store: SessionStore): Promise<void> {
  return new Promise(resolvePromise => {
    const timer = setTimeout(finish, 3000)
    const off = backend.onEvent(event => {
      store.apply(event)
      if (event.t === 'ready') finish()
    })
    function finish() {
      clearTimeout(timer)
      off()
      resolvePromise()
    }
  })
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error))
  process.exit(1)
})
