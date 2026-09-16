/**
 * 计划面板：一份计划从「刚规划出来」到「跑完收尾」的全程展示。
 *
 * 它是 transcript 里的一条 entry，不是固定头部——执行期间被 store 原地更新
 * （✓/✗ 逐个点亮），往上滚随时能看到进度，和其余消息一个待遇。
 *
 * 布局照抄后端 CLI 的 PlanView：按 `layers` 分层，同层标出「N 个任务互不依赖」。
 * layers 是服务端算好的拓扑分层，这里**只照着画，不自己按 dependsOn 推**——
 * 那等于把同一套图算法在两种语言里各实现一次，迟早有一边算错（docs/protocol.md）。
 *
 * 动态长出来的子任务（plan_tasks_added）不在 layers 里，按 parentId 缩进挂在
 * 父任务底下。
 */

import { Box, Text } from 'ink'
import { firstLine, truncate } from '../format.ts'
import type { PlanData, PlanTask } from '../store.ts'
import { useTheme, type Theme } from '../theme.ts'

type Props = {
  plan: PlanData
  columns: number
}

/** 类型的中文短标签，和后端 PlanView 保持同一套词。 */
function typeLabel(type: string): string {
  switch (type) {
    case 'PLANNING':
      return '规划'
    case 'FILE_READ':
      return '读取'
    case 'FILE_WRITE':
      return '写入'
    case 'COMMAND':
      return '命令'
    case 'ANALYSIS':
      return '分析'
    case 'VERIFICATION':
      return '验证'
    default:
      return type // 未来新增的类型原样显示，别让面板画不出来
  }
}

function mark(status: string, theme: Theme): { char: string; color: string } {
  switch (status) {
    case 'RUNNING':
      return { char: '◐', color: theme.spinner }
    case 'SUCCEEDED':
      return { char: '✓', color: theme.success }
    case 'FAILED':
      return { char: '✗', color: theme.error }
    case 'SKIPPED':
      return { char: '⊘', color: theme.warning }
    default:
      return { char: '○', color: theme.subtle }
  }
}

export function PlanPanel({ plan, columns }: Props) {
  const theme = useTheme()
  const byId = new Map(plan.tasks.map(task => [task.id, task]))
  /** 父任务 id → 它拆出来的子任务（保持数组顺序）。 */
  const children = new Map<string, PlanTask[]>()
  for (const task of plan.tasks) {
    if (task.parentId === null) continue
    const siblings = children.get(task.parentId) ?? []
    siblings.push(task)
    children.set(task.parentId, siblings)
  }

  return (
    <Box marginTop={1} flexDirection="column">
      <Header plan={plan} theme={theme} columns={columns} />
      {plan.layers.map((layer, index) => (
        <Box key={`layer-${index}`} flexDirection="column">
          <Text color={theme.subtle} wrap="truncate">
            {'  第 '}
            {index + 1}
            {' 步'}
            {layer.length > 1 ? `（${layer.length} 个任务互不依赖）` : ''}
          </Text>
          {layer.map(id => {
            const task = byId.get(id)
            if (!task) return null
            return (
              <Box key={id} flexDirection="column">
                <TaskLine task={task} columns={columns} theme={theme} indent={4} />
                {(children.get(id) ?? []).map(sub => (
                  <TaskLine key={sub.id} task={sub} columns={columns} theme={theme} indent={6} sub />
                ))}
              </Box>
            )
          })}
        </Box>
      ))}
      {plan.summary && <Summary plan={plan} theme={theme} />}
    </Box>
  )
}

function Header({ plan, theme, columns }: { plan: PlanData; theme: Theme; columns: number }) {
  const title =
    plan.status === 'COMPLETED' ? (
      <Text color={theme.success} bold>
        计划完成
      </Text>
    ) : plan.status === 'FAILED' ? (
      <Text color={theme.error} bold>
        计划未完成
      </Text>
    ) : plan.status === 'CANCELLED' ? (
      <Text color={theme.warning} bold>
        计划已取消
      </Text>
    ) : (
      <Text color={theme.brand} bold>
        执行计划
      </Text>
    )
  return (
    <Box flexDirection="column">
      <Text wrap="truncate">
        {title}
        <Text color={theme.subtle}>
          {'  '}
          {plan.tasks.length}
          {' 个任务'}
        </Text>
      </Text>
      {/* goal 是模型复述的理解，要显眼：这句不对，下面拆得再漂亮也是白搭 */}
      <Text wrap="truncate">
        <Text color={theme.user}>{'  目标: '}</Text>
        <Text color={theme.text}>{truncate(plan.goal, Math.max(8, columns - 8))}</Text>
      </Text>
    </Box>
  )
}

function TaskLine({
  task,
  columns,
  theme,
  indent,
  sub = false,
}: {
  task: PlanTask
  columns: number
  theme: Theme
  indent: number
  sub?: boolean
}) {
  const m = mark(task.status, theme)
  const tag = `[${typeLabel(task.type)} ${task.id}]`
  const deps = task.dependsOn.length > 0 ? `  ← ${task.dependsOn.join(', ')}` : ''
  const suffix =
    task.status === 'SUCCEEDED' && task.durationMs !== undefined
      ? `  ${Math.round(task.durationMs / 1000)}s`
      : ''
  // 失败/跳过的原因即便一行也要露出来：只给一个叉等于把问题藏起来
  const error =
    task.error && (task.status === 'FAILED' || task.status === 'SKIPPED')
      ? firstLine(task.error, Math.max(10, columns - indent - 4))
      : null

  const budget = Math.max(
    6,
    columns - indent - 2 - tag.length - deps.length - suffix.length - (sub ? 2 : 0),
  )

  return (
    <Box flexDirection="column">
      <Text wrap="truncate">
        {' '.repeat(indent)}
        {sub && <Text color={theme.subtle}>{'↳ '}</Text>}
        <Text color={m.color}>{m.char} </Text>
        <Text color={theme.subtle}>{tag} </Text>
        <Text color={theme.text}>{truncate(task.description, budget)}</Text>
        {deps && <Text color={theme.subtle}>{deps}</Text>}
        {suffix && <Text color={theme.subtle}>{suffix}</Text>}
      </Text>
      {error && (
        <Text wrap="truncate">
          {' '.repeat(indent + 2)}
          <Text color={task.status === 'FAILED' ? theme.error : theme.warning}>{error}</Text>
        </Text>
      )}
    </Box>
  )
}

function Summary({ plan, theme }: { plan: PlanData; theme: Theme }) {
  const s = plan.summary
  if (!s) return null
  return (
    <Text wrap="truncate">
      <Text color={theme.subtle}>{'  ✻ 成功 '}</Text>
      <Text color={theme.success}>{s.succeeded}</Text>
      {s.failed > 0 && (
        <>
          <Text color={theme.subtle}>{' · 失败 '}</Text>
          <Text color={theme.error}>{s.failed}</Text>
        </>
      )}
      {s.skipped > 0 && (
        <>
          <Text color={theme.subtle}>{' · 跳过 '}</Text>
          <Text color={theme.warning}>{s.skipped}</Text>
        </>
      )}
      <Text color={theme.subtle}>{` · 用时 ${Math.round(s.durationMs / 1000)}s`}</Text>
    </Text>
  )
}
