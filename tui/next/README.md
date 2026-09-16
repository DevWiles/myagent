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

在你想让 agent 干活的目录里敲：

```bash
~/Workspace/projects/myagent/myagent-next
```

参数和 `./myagent-tui` 一致：`-d ~/proj`、`-m 模型`、`--readonly`、`--unsafe`、`--rebuild`。
Ctrl-C 退出，Esc 中断当前这一轮。

只调界面不想拉后端的话可以直接跑，但**必须在这个目录下**（原因见下一节）：

```bash
cd tui/next && bun run dev
```

## 为什么单独一个包

Solid 的 JSX 要靠 `bunfig.toml` 里的 `preload = ["@opentui/solid/preload"]` 做转换，
而那个 Bun 插件会**无差别地转换目录下所有 `.tsx`**——
和 Ink 的 React JSX 放同一个包里会互相打架。

拆成子包后两套界面各有各的 `bunfig.toml`，互不干扰，可以并排跑着对比。
等组件全部搬完，把 `next/` 的内容提上去、删掉 Ink 版即可。

⚠️ **bun 是按 cwd 找 `bunfig.toml` 的**，不是按脚本位置。
所以在仓库根目录跑 `bun tui/next/src/main.tsx`，那份 bunfig 根本不会加载，
JSX 不转换，界面起来一片空白然后立刻退出，**而且没有任何报错**。
`myagent-next` 脚本为此先存下 cwd、`cd` 进本目录、再用 `-d` 把工作区传回去。

跨包共用的只有纯 TS 模块（`../src/format.ts` 的字模、`../src/protocol.ts` 的协议类型）——
它们是 `.ts` 不是 `.tsx`，不经过 Solid 转换，所以引用是安全的。

## 迁移进度

- [x] 骨架：renderer 初始化、退出还原终端、主题层
- [x] Banner（蓝色 MYAGENT，实心块 / 描边线分色）
- [x] 首屏欢迎框（Claude Code 式双栏，按宽度三档退化）
- [x] 输入框、状态栏
- [x] 接 WebSocket（`docs/protocol.md`），复用 Ink 版的 backend.ts / store.ts
- [x] EntryView：对话流（七种 entry 都画了，但都画得朴素）
- [x] ApprovalDialog、PlanConfirmDialog
- [ ] 工具调用详情的展开/折叠（Ctrl-O）
- [ ] Spinner（等模型时的转圈）
- [ ] PlanPanel：计划任务图
- [ ] Markdown 渲染（可以换成 OpenTUI 内置的 `<markdown>`，省掉自己解析）
- [ ] 键位系统（可以引 `@opentui/keymap`，支持 leader 键）
