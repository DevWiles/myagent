# myagent-tui-next

myagent 终端界面的 **OpenTUI + SolidJS 版**，正在替换隔壁 `tui/`（React + Ink）那一套。

## 为什么要换

Ink 用 React 的协调器把界面算成字符串再写给终端，绘制全在 JS 里做。
OpenTUI 把绘制推到一个 Zig 写的原生库里（通过 Bun FFI 调用），布局仍然是 Yoga（和 Ink 一样的 flexbox 引擎），
但多出来的是 Ink 给不了的几样东西：

- **原生输入控件**——光标、选区、滚动在原生层实现，不用再自己往字符串里插反色字符冒充光标
- **鼠标**——原生层维护命中网格，点击和拖选是现成的
- **内置代码/Markdown 渲染**，带 TreeSitter 语法高亮
- 顺带还有音频（提示音）

这套技术栈和 opencode 的 TUI 是同一套，剖析见 Obsidian 的《opencode TUI 前端技术栈剖析》。

## 跑起来

```bash
cd tui/next
bun install
bun run dev        # 或 bun run src/main.tsx
```

Ctrl-C 退出。**当前是纯壳**：不连后端，欢迎框里的模型、工具数是写死的占位值，
输入回车只会把内容回显到对话区。接 WebSocket 是下一步。

## 为什么单独一个包

Solid 的 JSX 要靠 `bunfig.toml` 里的 `preload = ["@opentui/solid/preload"]` 做转换，
而那个 Bun 插件会**无差别地转换目录下所有 `.tsx`**——
和 Ink 的 React JSX 放同一个包里会互相打架。

拆成子包后两套界面各有各的 `bunfig.toml`，互不干扰，可以并排跑着对比。
等组件全部搬完，把 `next/` 的内容提上去、删掉 Ink 版即可。

跨包共用的只有纯 TS 模块（`../src/format.ts` 的字模、`../src/protocol.ts` 的协议类型）——
它们是 `.ts` 不是 `.tsx`，不经过 Solid 转换，所以引用是安全的。

## 迁移进度

- [x] 骨架：renderer 初始化、退出还原终端、主题层
- [x] Banner（蓝色 MYAGENT，实心块 / 描边线分色）
- [x] 首屏欢迎框（Claude Code 式双栏，按宽度三档退化）
- [x] 输入框、状态栏
- [ ] 接 WebSocket（`docs/protocol.md`），用 ready 帧填掉写死的占位值
- [ ] TranscriptView / EntryView：对话流
- [ ] Spinner、工具调用展示
- [ ] PlanPanel、PlanConfirmDialog、ApprovalDialog
- [ ] Markdown 渲染（可以换成 OpenTUI 内置的 `<markdown>`，省掉自己解析）
- [ ] 键位系统（可以引 `@opentui/keymap`，支持 leader 键）
