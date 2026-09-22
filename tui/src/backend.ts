/**
 * 后端进程的生命周期 + WebSocket 连接。
 *
 * 界面永远不自己跑 agent 逻辑：这里拉起 `java -jar myagent.jar --serve`，
 * 从它 stdout 的第一行握手信息里拿到端口和一次性 token，然后连上去。
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
}

export type Backend = {
  /**
   * 手机配对用的完整 URL。两种来源，**中继优先**：
   * 中继那个到哪儿都能用，局域网那个出了门就废。
   * 两种都没有（既没 --relay 也没 --lan）时是 null，二维码入口就不该出现。
   */
  pairingUrl: string | null
  /** 这个配对地址是中继的还是局域网的。界面据此说清它能用在什么网络下。 */
  pairingKind: 'relay' | 'lan' | null
  send(command: ClientCommand): void
  onEvent(handler: (event: ServerEvent) => void): () => void
  /** 连接断了（后端挂了或主动停了）。参数是原因，正常退出时为 null。 */
  onClose(handler: (reason: string | null) => void): () => void
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
 * 拉起后端并连上。
 *
 * `signal` 用来处理一个很窄但必现的窗口：java 起来要一两秒，这期间用户完全可能
 * 已经按了 Ctrl-C。而此刻子进程句柄只活在本函数的作用域里——调用方拿到的 Promise
 * 还没 resolve，它手上什么都没有。不在这儿杀，就会留下一个还攥着端口的 java。
 */
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

export async function startBackend(
  options: BackendOptions,
  signal?: AbortSignal,
  /** 启动过程走到哪了（build 中/启动中），给状态栏看。没人关心就不传。 */
  onStatus?: (text: string) => void,
): Promise<Backend> {
  const jar = await ensureJar(options, signal, onStatus)
  if (signal?.aborted) throw new Error('已取消')
  onStatus?.('正在启动后端…')
  const args = [...javaOptions(), '-jar', jar, '--serve', '--port', '0', '-d', options.workspace]
  if (options.model) args.push('-m', options.model)
  if (options.planMode) args.push('--plan')
  if (options.unsafe) args.push('--unsafe')
  if (options.lan) args.push('--lan')
  if (options.relay) args.push('--relay', options.relay)
  if (options.relayReset) args.push('--relay-reset')

  const child = spawn('java', args, { stdio: ['ignore', 'pipe', 'pipe'] })

  // AbortSignal 的监听器是**同步**派发的，所以 abort() 之后 child.kill() 一定在
  // 紧随其后的 process.exit() 之前跑完。换成「设个标志位、等 then 里再补杀」不行——
  // 那个 then 永远等不到。
  const onAbort = (): void => {
    child.kill()
  }
  signal?.addEventListener('abort', onAbort, { once: true })
  if (signal?.aborted) onAbort()

  // Java 的日志走 stderr。平时不打扰用户，但后端启动失败时它就是唯一的线索，
  // 所以留一个滚动窗口，报错时一起抛出去。
  const stderrTail: string[] = []
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', (chunk: string) => {
    stderrTail.push(chunk)
    if (stderrTail.length > 40) stderrTail.shift()
  })

  const listening = await readListeningLine(child, stderrTail)
  if (listening.protocol !== PROTOCOL_VERSION) {
    child.kill()
    throw new Error(
      `协议版本对不上：界面是 ${PROTOCOL_VERSION}，后端是 ${listening.protocol}。` +
        `重新 build 一次 jar，或更新界面。`,
    )
  }

  const socket = await connect(listening)
  // 连上了就不再需要这个应急通道，后面交给 backend.dispose()
  signal?.removeEventListener('abort', onAbort)
  // 中继优先：它在任何网络下都能用；局域网那个只在同一屋檐下有效
  const pairingUrl = listening.pair
    ?? (listening.lan ? `ws://${listening.lan}:${listening.port}/?token=${listening.token}` : null)
  const pairingKind = listening.pair ? 'relay' : listening.lan ? 'lan' : null
  return wire(child, socket, pairingUrl, pairingKind)
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

function connect(listening: ListeningLine): Promise<WebSocket> {
  return new Promise((resolvePromise, rejectPromise) => {
    const socket = new WebSocket(
      `ws://127.0.0.1:${listening.port}/?token=${listening.token}`,
    )
    const timer = setTimeout(() => {
      socket.close()
      rejectPromise(new Error('连接后端超时'))
    }, 10_000)

    socket.addEventListener('open', () => {
      clearTimeout(timer)
      resolvePromise(socket)
    }, { once: true })

    socket.addEventListener('error', () => {
      clearTimeout(timer)
      rejectPromise(new Error('连不上后端'))
    }, { once: true })
  })
}

function wire(
  child: BackendProcess,
  socket: WebSocket,
  pairingUrl: string | null,
  pairingKind: 'relay' | 'lan' | null,
): Backend {
  const eventHandlers = new Set<(event: ServerEvent) => void>()
  const closeHandlers = new Set<(reason: string | null) => void>()
  let disposed = false

  socket.addEventListener('message', message => {
    const event = parseEvent(String(message.data))
    if (event) for (const handler of eventHandlers) handler(event)
  })

  socket.addEventListener('close', () => {
    if (!disposed) for (const handler of closeHandlers) handler('后端断开了连接')
  })

  child.on('exit', code => {
    if (!disposed) {
      for (const handler of closeHandlers) {
        handler(code === 0 ? null : `后端进程退出（退出码 ${code}）`)
      }
    }
  })

  return {
    pairingUrl,
    pairingKind,
    send(command) {
      if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(command))
    },
    onEvent(handler) {
      eventHandlers.add(handler)
      return () => eventHandlers.delete(handler)
    },
    onClose(handler) {
      closeHandlers.add(handler)
      return () => closeHandlers.delete(handler)
    },
    dispose() {
      // 顺序有讲究：先置位再关，否则 close 回调会把「正常退出」报成「后端断开了」
      disposed = true
      try {
        socket.close()
      } catch {
        /* 已经关了就算了 */
      }
      child.kill()
    },
  }
}
