#!/usr/bin/env bun
/**
 * 入口：解析参数 → 拉起后端 → 渲染界面。
 *
 * 这里刻意不做任何 agent 逻辑。界面进程只负责画和收键盘，
 * 模型调用、工具执行、上下文管理全在 Java 后端里——它才是这个项目的本体。
 */

import { render, type Instance } from 'ink'
import { createElement } from 'react'
import { resolve } from 'node:path'
import { startBackend, type Backend } from './backend.ts'
import { App } from './components/App.tsx'
import { SessionStore } from './store.ts'
import { installTeardown, markAltScreen, resolveFullscreen } from './terminal.ts'
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
      --no-fullscreen  不独占终端（对话留在终端 scrollback 里，可用 Cmd-F 搜）
      --fullscreen     强制独占终端（默认就是，一般用不到）
  -h, --help           显示本帮助

独占终端默认开启：运行时占满整屏、输入框钉在最后一行，退出后主屏原样恢复
（你启动前的命令历史都还在，但和 agent 的这段对话不留在 scrollback 里）。
也可以用环境变量 MYAGENT_FULLSCREEN=0 关掉。

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
  /** undefined = 命令行没写，交给 resolveFullscreen 的后续规则。 */
  fullscreen?: boolean
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
      // 后写的赢：`--fullscreen --no-fullscreen` 以最后那个为准
      case '--fullscreen':
        args.fullscreen = true
        break
      case '--no-fullscreen':
        args.fullscreen = false
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

  const { fullscreen } = resolveFullscreen({
    flag: args.fullscreen,
    env: process.env,
    isTTY: true, // 上面已经挡掉非 TTY 了
  })

  await runTui({ store, backend, theme, fullscreen })
}

export type RunTuiOptions = {
  store: SessionStore
  backend: Backend
  theme: (typeof THEMES)[keyof typeof THEMES]
  fullscreen: boolean
}

/**
 * 装恢复钩子 → 起 Ink → 等退出。
 *
 * 单独导出是为了让 PTY 验收测试能驱动**同一条**渲染/退出路径，而不用真去起
 * 一个 java 后端、连 WebSocket、调真模型 —— 那样的测试既慢又不可复现。
 * 测试传一个 stub backend 进来就行（`Backend` 就 4 个方法）。
 */
export async function runTui({ store, backend, theme, fullscreen }: RunTuiOptions): Promise<void> {
  // 恢复钩子要在 render **之前**装：render 一旦返回屏幕就已经切走了，
  // 这之间抛异常的话得有人把终端还回来。
  let instance: Instance | null = null
  installTeardown({ getInstance: () => instance })

  // 独占终端交给 Ink 自己：`alternateScreen: true` 会在第 0 字节发 `1049h`、
  // 配 `?25l` 藏光标，卸载时先 `?25h` 再 `1049l`，挂起/恢复也跟着进出备用屏。
  // 手写这套转义反而更容易错——原来那版就压根没还光标。
  //
  // 顺带纠正一条曾经写在这里的错话：并没有「Terminal.app 要勾选允许备用屏」
  // 这个开关（`defaults read com.apple.Terminal` 里只有颜色/字体/bell 之类）。
  // 备用屏在 Terminal.app 里本来就无条件可用，vim 就是这么跑的。原来那段 DECRQM
  // 探测是在测一个不存在的问题，代价是白等 300ms 并且吞掉用户开头敲的按键。
  //
  // `interactive: true` 是必需的：Ink 的 alternateScreen 要求 interactive，
  // 而它的自动判定在 CI 有值时为假——实测 `CI=1` 时 `1049h` 计数为 0，加上这个
  // 才为 1。真正该关的情形已经由 resolveFullscreen 挡掉了，所以这里强制打开
  // 不会和「该不该全屏」的判断打架。
  //
  // **不开 `incrementalRendering`**：它走的 `createIncremental` 会逐行跳过
  // 「和上一帧相同」的行。省字节，但接管鼠标那一期要靠「拦下完整帧」做选区，
  // 增量重绘会让那条路直接走不通。闪烁本来已经由同步输出（`?2026`）兜住了。
  instance = render(
    createElement(
      ThemeProvider,
      { value: theme },
      createElement(App, { store, backend, fullscreen }),
    ),
    {
      // Ctrl-C 自己处理：正在跑的时候它应该是「中断这一轮」而不是「退出程序」
      exitOnCtrlC: false,
      alternateScreen: fullscreen,
      ...(fullscreen ? { interactive: true as const } : {}),
    },
  )
  markAltScreen(fullscreen)

  // 终端模式（Alternate Scroll 等）由 App 的 effect 开关，不在这里 ——
  // React 的 cleanup 跑在 Ink 还主屏之前，那才是「先关模式、再还主屏」的正确接缝。

  try {
    await instance.waitUntilExit()
  } finally {
    backend.dispose()
  }
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

// 只有「被当成命令直接跑」的时候才执行 main()。
//
// 少了这个判断，任何 `import` 这个文件的人都会顺带把整个程序跑起来 ——
// PTY 验收测试 import `runTui` 时就踩到了：它同时起了第二个 App（两个实例往同一个
// stdout 画），还顺手 spawn 了一个真的 java 后端。表现是「--no-fullscreen 却发了
// Alternate Scroll」，查了半天才定位到这里。
if (import.meta.main) {
  main().catch((error: unknown) => {
    console.error(error instanceof Error ? error.message : String(error))
    process.exit(1)
  })
}
