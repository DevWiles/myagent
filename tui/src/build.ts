/**
 * 后端 jar 的按需构建。原来住在 myagent-tui 脚本里，搬进来的理由是**时机**：
 *
 * 脚本里的 build 是同步的——mvn 跑完界面才起来，改过一行 Java 之后的每次启动，
 * 用户都对着一行裸文本干等五秒起步。搬到这里之后顺序反过来：界面先起（亚秒级），
 * build 在 startBackend 里进行，进度打在状态栏上，输入框也是活的——
 * 配合 session 那边的发件队列，用户可以在 build 期间就把第一句话敲完。
 * **总耗时一秒都没少，等待的体验完全不同**：看得见进度的等待才叫等待，
 * 看不见的叫卡死。
 *
 * 新鲜度判断和脚本里那版语义一字不差：src/main 或 pom.xml 里有任何文件
 * 比 jar 新就重 build。判断只对**仓库自带的那个 jar** 做——
 * MYAGENT_JAR 指过来的是用户钉死的某个 build，替人家重编译是帮倒忙。
 */

import { spawn } from 'node:child_process'
import { existsSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

/**
 * jar 需不需要重 build。
 *
 * <p>和脚本版一样用「有没有更新的文件」而不是记时间戳文件：
 * 少一份需要自己维护的状态。整棵 src/main 也就几百个文件，
 * stat 一遍毫秒级，而且找到第一个更新的就短路。
 */
export function jarIsStale(jar: string, repoRoot: string): boolean {
  if (!existsSync(jar)) return true
  const jarMtime = statSync(jar).mtimeMs
  const pom = join(repoRoot, 'pom.xml')
  if (existsSync(pom) && statSync(pom).mtimeMs > jarMtime) return true
  return anyNewer(join(repoRoot, 'src', 'main'), jarMtime)
}

function anyNewer(dir: string, mtime: number): boolean {
  if (!existsSync(dir)) return false
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    try {
      if (entry.isDirectory()) {
        if (anyNewer(path, mtime)) return true
      } else if (statSync(path).mtimeMs > mtime) {
        return true
      }
    } catch {
      // 比较的半路上文件没了（编辑器的临时文件）——没了的文件不构成「有改动」
    }
  }
  return false
}

/**
 * 跑一次 `mvn -DskipTests package`，成功了安静返回，失败把输出尾巴带在错误里抛出来。
 *
 * <p>输出只留尾巴不留全量：mvn 一次能吐几百行，而报错时人要看的是最后那几十行。
 * 平时一行都不展示——界面在 altscreen 里，这些字没地方去，失败才是它们的舞台。
 */
export function buildBackend(repoRoot: string, signal?: AbortSignal): Promise<void> {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(
      'mvn',
      ['-q', '-f', join(repoRoot, 'pom.xml'), '-DskipTests', 'package'],
      { stdio: ['ignore', 'pipe', 'pipe'] },
    )

    // 和 startBackend 里杀 java 的道理相同：build 要几秒，这期间按 Ctrl-C 的话，
    // 调用方手上还没有任何句柄，abort 是唯一能杀到 mvn 的手
    const onAbort = (): void => {
      child.kill()
    }
    signal?.addEventListener('abort', onAbort, { once: true })
    if (signal?.aborted) onAbort()

    const tail: string[] = []
    const keep = (chunk: string) => {
      tail.push(chunk)
      if (tail.length > 60) tail.shift()
    }
    child.stdout.setEncoding('utf8')
    child.stderr.setEncoding('utf8')
    child.stdout.on('data', keep)
    child.stderr.on('data', keep)

    child.on('error', (error: NodeJS.ErrnoException) => {
      signal?.removeEventListener('abort', onAbort)
      rejectPromise(error.code === 'ENOENT'
        ? new Error('后端源码有改动但找不到 mvn。装一个 Maven，'
            + '或自己跑 mvn -DskipTests package 后重启。')
        : error)
    })

    child.on('exit', (code) => {
      signal?.removeEventListener('abort', onAbort)
      if (code === 0) {
        resolvePromise()
      } else {
        const detail = tail.join('').trim()
        rejectPromise(new Error(`后端 build 失败（退出码 ${code}）`
          + (detail ? `\n\nmvn 输出:\n${detail}` : '')))
      }
    })
  })
}
