/**
 * 后端连接的生命周期：拉起（或连上）、断了重连、崩了重启。
 *
 * 界面永远不自己跑 agent 逻辑。两种接法，一套重连：
 *
 * - **本地**（默认）：这里拉起 `java -jar myagent.jar --serve`，从它 stdout 的第一行
 *   握手信息里拿到端口和一次性 token，然后连上去。进程崩了就重新拉起，
 *   连上后由上层决定要不要 `continue` 接回会话。
 * - **远程**（`--connect ws://…?token=…`）：不 spawn，直接连给定地址。
 *   连不上就退避重试，一直等到它回来——后端在云上时，网断了是常态不是事故。
 *
 * 原来 WebSocket 一关就整个退出，这版把「连接状态」当成一等状态（见 link.ts）：
 * 断了只是进入 reconnecting，界面照常活着，敲的命令先攒着、连上补发。
 * 只有两种情况才真正放弃：协议版本对不上，以及崩溃循环（一分钟内崩三次）。
 *
 * 端口用 0 让系统分配，而不是写死一个数字——同时开两个 TUI 不该打架，
 * 也不该在端口被占时神秘失败。
 */

import { spawn, type ChildProcessByStdio } from 'node:child_process'
import type { Readable } from 'node:stream'
import { existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildBackend, jarIsStale } from './build.ts'
import { backoffMs, CrashCounter, SeqTracker } from './link.ts'
import {
  PROTOCOL_VERSION,
  parseEvent,
  type ClientCommand,
  type ListeningLine,
  type ServerEvent,
} from './protocol.ts'

/** stdin 关掉、stdout/stderr 收进管道——和下面 spawn 的 stdio 配置一一对应。 */
type BackendProcess = ChildProcessByStdio<null, Readable, Readable>

export type BackendOptions = {
  workspace: string
  model?: string
  planMode: boolean
  unsafe: boolean
  jar?: string
  /** 不管新鲜不新鲜都重 build 一遍（--rebuild）。只对仓库自带的 jar 有意义。 */
  forceRebuild?: boolean
  /** 局域网模式：后端绑 0.0.0.0，手机扫码可连（协议 1.1 节）。 */
  lan?: boolean
  /** 中继地址（wss://…/ws）。给了就往外拨，手机在任何网络都能连（协议 1.3 节）。 */
  relay?: string
  /** 换一套房间号和配对密钥，手机要重新扫码。 */
  relayReset?: boolean
  /**
   * 远程后端地址（`ws://host:port/?token=…`）。给了就不 spawn java，直接连。
   * 后端搬到云服务器上时用这个；那台机器上的后端要用 `--lan` 起。
   */
  connect?: string
}

/** 连接状态的变化。上层据此画状态栏、决定要不要接回会话。 */
export type LinkEvent =
  | {
      kind: 'online'
      /** 不是第一次连上（中间断过）。 */
      reconnected: boolean
      /** 对面是个新进程：本地 java 重启了，或远端的 seq 从头开始了。老会话要靠 `continue` 接回。 */
      restarted: boolean
    }
  | {
      kind: 'reconnecting'
      /** 第几次重试（从 1 起），状态栏上给人看。 */
      attempt: number
      reason: string
      /** 这一次是在重启进程（本地模式），不只是重连。 */
      restarting: boolean
    }

export type Backend = {
  /**
   * 手机配对用的完整 URL 和它的种类。两种来源，**中继优先**：
   * 中继那个到哪儿都能用，局域网那个出了门就废。
   * 两种都没有（既没 --relay 也没 --lan，或者是远程模式）时是 null，二维码入口就不该出现。
   *
   * 是函数不是字段：后端重启一次 token 就换一个，配对地址跟着变。
   */
  pairing(): { url: string; kind: 'relay' | 'lan' } | null
  /** 发一条命令。没连着时先攒着，连上按序补发。 */
  send(command: ClientCommand): void
  onEvent(handler: (event: ServerEvent) => void): () => void
  onLink(handler: (event: LinkEvent) => void): () => void
  /** 彻底放弃了（协议不兼容、崩溃循环）。参数是原因。之后不会再有任何事件。 */
  onClose(handler: (reason: string) => void): () => void
  dispose(): void
}

/** tui/src/ → tui/ → 仓库根。 */
function repoRoot(): string {
  return dirname(dirname(fileURLToPath(new URL('.', import.meta.url))))
}

/**
 * 该用哪个 jar，以及它归不归我们管。
 *
 * <p>两种来源，责任完全不同：显式指定（参数或 MYAGENT_JAR）的是用户
 * **钉死的某个 build**——它不存在就报错，绝不替人家重编译；
 * 仓库自带的 target/myagent.jar 归我们管——没有或者过期就现场 build。
 */
export function resolveJar(explicit?: string): { path: string; managed: boolean } {
  const pinned = explicit ?? process.env['MYAGENT_JAR']
  if (pinned && pinned.length > 0) {
    const path = resolve(pinned)
    if (!existsSync(path)) {
      throw new Error(`指定的后端 jar 不存在: ${path}\n检查 MYAGENT_JAR 或 jar 参数。`)
    }
    return { path, managed: false }
  }
  return { path: join(repoRoot(), 'target', 'myagent.jar'), managed: true }
}

/**
 * 保证 jar 可用：仓库自管的 jar 缺了或旧了就地 build。
 *
 * <p>这一步以前在 myagent-tui 脚本里、界面起来**之前**同步做，
 * 用户对着一行裸文本等 mvn。现在它发生在界面之后：进度经 `onStatus`
 * 打到状态栏，输入框也活着——搬家的全部理由见 build.ts 开头。
 */
async function ensureJar(
  options: BackendOptions,
  signal?: AbortSignal,
  onStatus?: (text: string) => void,
): Promise<string> {
  const jar = resolveJar(options.jar)
  if (jar.managed && (options.forceRebuild || jarIsStale(jar.path, repoRoot()))) {
    onStatus?.(existsSync(jar.path) ? '后端有改动，正在 build…' : '第一次运行，正在 build 后端…')
    await buildBackend(repoRoot(), signal)
  }
  return jar.path
}

/**
 * `MYAGENT_JAVA_OPTS` 里的东西原样塞给 java，放在 `-jar` 前面。
 *
 * 存在的理由只有一个：**调后端时把日志捞出来**。无头模式的日志走 stderr，
 * 而这边把 stderr 收进一个只在启动失败时才露面的内存环（见下面的 stderrTail），
 * 于是正常跑起来之后，后端日志对使用者是完全不可见的。
 *
 * 想看的时候这样起：
 * ```
 * MYAGENT_JAVA_OPTS="-Dorg.slf4j.simpleLogger.logFile=/tmp/be.log \
 *                    -Dorg.slf4j.simpleLogger.defaultLogLevel=debug" ./myagent-tui
 * ```
 * 不设就是空数组，命令行和以前一字不差。
 */
function javaOptions(): string[] {
  return (process.env.MYAGENT_JAVA_OPTS ?? '').split(' ').filter((s) => s.length > 0)
}

/** 连接一次最多等多久。超了就当这次失败，走退避重试。 */
const CONNECT_TIMEOUT_MS = 10_000

/**
 * 拉起后端并保持连着。
 *
 * **同步返回**：启动过程（build、spawn、连接）在里面异步跑，进度经 `onStatus` 报给状态栏。
 * 原来是 async 的、resolve 之后调用方才拿到句柄——那有个必现的窗口：java 起来要一两秒，
 * 这期间用户按 Ctrl-C，子进程句柄只活在函数作用域里，谁也杀不到它。
 * 现在句柄一直在这个闭包里，`dispose()` 任何时刻都够得着。
 */
export function startBackend(
  options: BackendOptions,
  /** 启动过程走到哪了（build 中/启动中），给状态栏看。没人关心就不传。 */
  onStatus?: (text: string) => void,
): Backend {
  const eventHandlers = new Set<(event: ServerEvent) => void>()
  const linkHandlers = new Set<(event: LinkEvent) => void>()
  const closeHandlers = new Set<(reason: string) => void>()

  /** 远程模式：不 spawn，连这个地址。 */
  const remote = options.connect ?? null

  let disposed = false
  let child: BackendProcess | null = null
  let listening: ListeningLine | null = null
  let socket: WebSocket | null = null
  let stderrTail: string[] = []
  let retryTimer: ReturnType<typeof setTimeout> | undefined
  /** build 还在跑时退出，靠它掐掉 mvn。 */
  const aborter = new AbortController()

  const seq = new SeqTracker()
  const crashes = new CrashCounter()
  /** 没连着时敲的命令。连上那一刻按序补发，一条都不该静默丢。 */
  const outbox: ClientCommand[] = []

  let attempt = 0
  /** 已经成功连上过至少一次。之后的连上都算「重连」，要带 `since` 补发。 */
  let everConnected = false
  /** 对面换了进程。下一次 online 时告诉上层，然后清掉。 */
  let restarted = false

  const emitLink = (event: LinkEvent): void => {
    for (const handler of linkHandlers) handler(event)
  }

  /** 彻底放弃。之后一切静默。 */
  const fatal = (reason: string): void => {
    if (disposed) return
    teardown()
    for (const handler of closeHandlers) handler(reason)
  }

  const teardown = (): void => {
    disposed = true
    clearTimeout(retryTimer)
    aborter.abort()
    const ws = socket
    socket = null
    try {
      ws?.close()
    } catch {
      /* 已经关了就算了 */
    }
    child?.kill()
    child = null
  }

  const stderrText = (): string => stderrTail.join('').trim()

  // ------------------------------------------------------------ 进程

  /** 拉起一个 java 进程，等到它的握手行。失败抛错，由调用方决定算不算崩溃。 */
  async function spawnChild(): Promise<void> {
    const jar = await ensureJar(options, aborter.signal, onStatus)
    if (disposed) return
    onStatus?.('正在启动后端…')
    const args = [...javaOptions(), '-jar', jar, '--serve', '--port', '0', '-d', options.workspace]
    if (options.model) args.push('-m', options.model)
    if (options.planMode) args.push('--plan')
    if (options.unsafe) args.push('--unsafe')
    if (options.lan) args.push('--lan')
    if (options.relay) args.push('--relay', options.relay)
    if (options.relayReset) args.push('--relay-reset')

    const proc = spawn('java', args, { stdio: ['ignore', 'pipe', 'pipe'] })
    child = proc
    // Java 的日志走 stderr。平时不打扰用户，但后端起不来/崩了时它就是唯一的线索，
    // 所以留一个滚动窗口，报错时一起抛出去。
    const tail: string[] = []
    stderrTail = tail
    proc.stderr.setEncoding('utf8')
    proc.stderr.on('data', (chunk: string) => {
      tail.push(chunk)
      if (tail.length > 40) tail.shift()
    })

    const line = await readListeningLine(proc, tail)
    if (disposed) {
      proc.kill()
      return
    }
    if (line.protocol !== PROTOCOL_VERSION) {
      proc.kill()
      throw new ProtocolMismatch(line.protocol)
    }
    listening = line
    // 握手之后才挂 exit 监听：握手期间的退出由 readListeningLine 报成启动失败
    proc.on('exit', (code) => onChildExit(proc, code))
  }

  /** 进程走了。不是我们杀的就算崩溃：重启，或者放弃。 */
  function onChildExit(proc: BackendProcess, code: number | null): void {
    if (disposed || proc !== child) return
    child = null
    listening = null
    // 连接肯定跟着断了；主动关掉，别让 close 回调再走一遍重连
    const ws = socket
    socket = null
    ws?.close()
    handleCrash(`后端进程退出（退出码 ${code}）`)
  }

  /** 崩溃循环判定 + 安排重启。 */
  function handleCrash(reason: string): void {
    if (crashes.record(Date.now())) {
      const detail = stderrText()
      fatal(
        `${reason}\n一分钟内后端崩了 ${crashes.count} 次，不再重启。` +
          (detail ? `\n\n后端输出:\n${detail}` : ''),
      )
      return
    }
    // 新进程新 token 新 seq：老号作废，下一次连上要告诉上层这是重启
    seq.reset()
    restarted = true
    // 进程死的时候 socket 的 close 往往先到一步、已经排了一次「重连」；
    // 那一次是白排的，这里从头计数，别让第一次重启白等 2 秒
    attempt = 0
    scheduleRetry(reason, true)
  }

  // ------------------------------------------------------------ 连接

  function socketUrl(): string {
    const base = remote ?? `ws://127.0.0.1:${listening!.port}/?token=${listening!.token}`
    // 第一次连不带 since（协议 1.2：不补发）；重启后的新进程也不带——老号对它没意义。
    // 断线重连才带，把漏掉的那段补回来
    if (!everConnected || restarted) return base
    return `${base}${base.includes('?') ? '&' : '?'}since=${seq.lastSeq}`
  }

  function openSocket(): void {
    if (disposed) return
    const ws = new WebSocket(socketUrl())
    socket = ws
    let finished = false

    /** 这一条连接结束了（没连上、或连上又断了）。每条连接只走一次。 */
    const finish = (reason: string): void => {
      if (finished) return
      finished = true
      clearTimeout(timer)
      if (disposed || socket !== ws) return
      socket = null
      // 本地模式下进程已经没了：重启那条路（onChildExit）在管，这里别再排一次
      if (!remote && !child) return
      scheduleRetry(reason, false)
    }

    const timer = setTimeout(() => {
      try {
        ws.close()
      } catch {
        /* 忽略 */
      }
      finish('连接后端超时')
    }, CONNECT_TIMEOUT_MS)

    ws.addEventListener('message', (message) => {
      if (socket !== ws) return
      const event = parseEvent(String(message.data))
      if (!event) return
      if (event.t === 'ready') {
        // 连上了，超时那根弦撤掉——不撤的话它会在 10 秒后把好好的连接掐断，然后重连
        clearTimeout(timer)
        onReady(event)
        return
      }
      if (!seq.accept(event)) return
      for (const handler of eventHandlers) handler(event)
    })
    ws.addEventListener('close', () => finish(everConnected ? '后端断开了连接' : '连不上后端'))
    ws.addEventListener('error', () => finish('连不上后端'))
  }

  /** 首帧到了才算真正连上：握手能过不等于对面认我们。 */
  function onReady(event: Extract<ServerEvent, { t: 'ready' }>): void {
    if (event.protocol !== PROTOCOL_VERSION) {
      fatal(
        `协议版本对不上：界面是 ${PROTOCOL_VERSION}，后端是 ${event.protocol}。` +
          `重新 build 一次 jar，或更新界面。`,
      )
      return
    }
    // 远端的号比我们记的还小 = 那头换过进程（云上重启了）。
    // 不 reset 的话它从 1 起编的事件会被全部当成重复丢掉
    if (typeof event.lastSeq === 'number' && event.lastSeq < seq.lastSeq) {
      seq.reset()
      restarted = true
    }
    for (const handler of eventHandlers) handler(event)

    const reconnected = everConnected
    const wasRestart = restarted
    everConnected = true
    restarted = false
    attempt = 0
    emitLink({ kind: 'online', reconnected, restarted: wasRestart })
    // 上层在 online 回调里可能先发了 continue，攒下的命令排在它后面——
    // 服务端按到达顺序处理，会话先接回来、再跑用户敲的话
    for (const command of outbox.splice(0)) socket?.send(JSON.stringify(command))
  }

  /** 退避之后再来一次：本地模式下进程没了就先拉起。 */
  function scheduleRetry(reason: string, restarting: boolean): void {
    if (disposed) return
    clearTimeout(retryTimer)
    attempt += 1
    emitLink({ kind: 'reconnecting', attempt, reason, restarting })
    retryTimer = setTimeout(() => {
      void (async () => {
        if (disposed) return
        try {
          if (!remote && !child) await spawnChild()
          openSocket()
        } catch (error) {
          if (error instanceof ProtocolMismatch) {
            fatal(
              `协议版本对不上：界面是 ${PROTOCOL_VERSION}，后端是 ${error.version}。` +
                `重新 build 一次 jar，或更新界面。`,
            )
            return
          }
          // 起不来（握手超时、起来就退）也算一次崩溃：连崩三次就放弃并把原因摆出来
          handleCrash(error instanceof Error ? error.message : String(error))
        }
      })()
    }, backoffMs(attempt))
  }

  // ------------------------------------------------------------ 启动

  void (async () => {
    try {
      if (!remote) await spawnChild()
      openSocket()
    } catch (error) {
      // 第一次起不来是常见情况（jar 没 build、没装 java、协议不对），
      // 直接报出去而不是进重试循环——原因摆在眼前，重试三次也不会变好
      if (error instanceof ProtocolMismatch) {
        fatal(
          `协议版本对不上：界面是 ${PROTOCOL_VERSION}，后端是 ${error.version}。` +
            `重新 build 一次 jar，或更新界面。`,
        )
        return
      }
      fatal(error instanceof Error ? error.message : String(error))
    }
  })()

  return {
    pairing() {
      if (!listening) return null
      // 中继优先：它在任何网络下都能用；局域网那个只在同一屋檐下有效
      if (listening.pair) return { url: listening.pair, kind: 'relay' }
      if (listening.lan) {
        return {
          url: `ws://${listening.lan}:${listening.port}/?token=${listening.token}`,
          kind: 'lan',
        }
      }
      return null
    },
    send(command) {
      if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(command))
      else outbox.push(command)
    },
    onEvent(handler) {
      eventHandlers.add(handler)
      return () => eventHandlers.delete(handler)
    },
    onLink(handler) {
      linkHandlers.add(handler)
      return () => linkHandlers.delete(handler)
    },
    onClose(handler) {
      closeHandlers.add(handler)
      return () => closeHandlers.delete(handler)
    },
    dispose() {
      if (disposed) return
      // 先置位再关（在 teardown 里）：否则 close 回调会把「正常退出」当成断线去重连
      teardown()
    },
  }
}

class ProtocolMismatch extends Error {
  constructor(readonly version: number) {
    super(`协议版本 ${version}`)
  }
}

/** 等 stdout 上那一行握手信息。后端在这之前挂掉就直接报错，别让界面干等。 */
function readListeningLine(
  child: BackendProcess,
  stderrTail: string[],
): Promise<ListeningLine> {
  return new Promise((resolvePromise, rejectPromise) => {
    let buffer = ''
    let settled = false

    const fail = (message: string) => {
      if (settled) return
      settled = true
      cleanup()
      child.kill()
      const detail = stderrTail.join('').trim()
      rejectPromise(new Error(detail ? `${message}\n\n后端输出:\n${detail}` : message))
    }

    const onData = (chunk: string) => {
      buffer += chunk
      const newline = buffer.indexOf('\n')
      if (newline < 0) return
      const line = buffer.slice(0, newline).trim()
      try {
        const parsed = JSON.parse(line) as ListeningLine
        if (parsed.t !== 'listening') throw new Error('不是握手行')
        settled = true
        cleanup()
        resolvePromise(parsed)
      } catch {
        fail(`后端第一行输出看不懂: ${line}`)
      }
    }

    const onExit = (code: number | null) => fail(`后端启动失败（退出码 ${code}）`)
    const cleanup = () => {
      child.stdout.off('data', onData)
      child.off('exit', onExit)
      clearTimeout(timer)
    }

    const timer = setTimeout(() => fail('等后端启动超时（15 秒）'), 15_000)
    child.stdout.setEncoding('utf8')
    child.stdout.on('data', onData)
    child.on('exit', onExit)
  })
}
