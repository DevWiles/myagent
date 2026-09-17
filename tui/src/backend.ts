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
}

export type Backend = {
  send(command: ClientCommand): void
  onEvent(handler: (event: ServerEvent) => void): () => void
  /** 连接断了（后端挂了或主动停了）。参数是原因，正常退出时为 null。 */
  onClose(handler: (reason: string | null) => void): () => void
  dispose(): void
}

/** 后端 jar 的位置。允许用环境变量覆盖，方便指向另一个 build。 */
export function resolveJar(explicit?: string): string {
  const candidates = [
    explicit,
    process.env['MYAGENT_JAR'],
    // tui/src/ → tui/ → 仓库根 → target/myagent.jar
    join(dirname(dirname(fileURLToPath(new URL('.', import.meta.url)))), 'target', 'myagent.jar'),
  ].filter((p): p is string => typeof p === 'string' && p.length > 0)

  for (const candidate of candidates) {
    const path = resolve(candidate)
    if (existsSync(path)) return path
  }
  throw new Error(
    `找不到后端 jar，试过:\n  ${candidates.map(c => resolve(c)).join('\n  ')}\n` +
      `先在仓库根目录跑一次 \`mvn -DskipTests package\`，或用 MYAGENT_JAR 指定路径。`,
  )
}

/**
 * 拉起后端并连上。
 *
 * `signal` 用来处理一个很窄但必现的窗口：java 起来要一两秒，这期间用户完全可能
 * 已经按了 Ctrl-C。而此刻子进程句柄只活在本函数的作用域里——调用方拿到的 Promise
 * 还没 resolve，它手上什么都没有。不在这儿杀，就会留下一个还攥着端口的 java。
 */
export async function startBackend(
  options: BackendOptions,
  signal?: AbortSignal,
): Promise<Backend> {
  const jar = resolveJar(options.jar)
  const args = ['-jar', jar, '--serve', '--port', '0', '-d', options.workspace]
  if (options.model) args.push('-m', options.model)
  if (options.planMode) args.push('--plan')
  if (options.unsafe) args.push('--unsafe')

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
  return wire(child, socket)
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

function wire(child: BackendProcess, socket: WebSocket): Backend {
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
