#!/usr/bin/env bun
/**
 * PTY 验收测试的被测程序。
 *
 * 走的是和真程序**同一条** `runTui()` 路径（备用屏、终端模式、各条退出路径的
 * 恢复全在里面），但后端是个 stub —— 不起 java、不连 WebSocket、不调模型。
 * 否则这套测试既慢又不可复现，也就没人会跑。
 *
 * 用环境变量控制要验哪条路：
 *   FIXTURE_FULLSCREEN=0|1   传给 runTui 的 fullscreen
 *   FIXTURE_CRASH=throw      首帧之后抛未捕获异常
 *   FIXTURE_CRASH=reject     首帧之后产生未处理 rejection
 *   FIXTURE_CRASH_DELAY_MS   多久之后（默认 1200）
 *   FIXTURE_CRASHLOG         崩溃日志落在哪（测试会断言它变大了）
 */

import type { Backend } from '../src/backend.ts'
import { runTui } from '../src/main.tsx'
import { SessionStore } from '../src/store.ts'
import { THEMES } from '../src/theme.ts'

const store = new SessionStore()
store.apply({
  t: 'ready',
  protocol: 1,
  version: '0.0.0-fixture',
  model: 'fixture-model',
  workspace: process.cwd(),
  planMode: false,
  hasApiKey: true,
  busy: false,
  tools: [{ name: 'read_file', description: '读文件' }],
  contextLimit: 64000,
})
// 灌一些内容，让帧里真的有东西可看（也顺便验证内容变长不影响输入框位置）
for (let i = 0; i < 40; i++) {
  store.apply({ t: 'text_delta', text: `fixture 第 ${i} 行 ${'-'.repeat(30)}\n` })
}

const backend: Backend = {
  send() {},
  onEvent() {
    return () => {}
  },
  onClose() {
    return () => {}
  },
  dispose() {},
}

const crash = process.env['FIXTURE_CRASH']
const delay = Number(process.env['FIXTURE_CRASH_DELAY_MS'] ?? '1200')
if (crash === 'throw') {
  setTimeout(() => {
    throw new Error('FIXTURE-BOOM-THROW')
  }, delay)
} else if (crash === 'reject') {
  setTimeout(() => {
    void Promise.reject(new Error('FIXTURE-BOOM-REJECT'))
  }, delay)
}

const FS = process.env['FIXTURE_FULLSCREEN'] !== '0'

await runTui({
  store,
  backend,
  theme: THEMES.dark,
  fullscreen: FS,
})
