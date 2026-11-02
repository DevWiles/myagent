/**
 * 工作区信任：在一个目录里第一次起 agent 之前，先问用户信不信任它。
 *
 * <h3>为什么问，而不是像以前那样直接拒绝</h3>
 * 以前后端在家目录、系统目录、多仓库的父目录里一律拒绝启动，要绕开只能加 `--unsafe`。
 * 拒绝挡住的往往是用户明知故犯的那一次（就是想在家目录里整理文件），
 * 而真正的风险——目录里的东西会进提示词、命令和改动会落在这里——在普通项目目录里同样存在，
 * 拒绝清单却一个字都没提。照 Claude Code 的做法改成**开门前问一句**：
 * 风险目录照样能进，但确认框里把风险写明白。
 *
 * <h3>为什么在界面里问、在后端起来之前问</h3>
 * 后端一启动就会读工作区里的 `.myagent/skills`、`.claude/skills`，内容直接进提示词。
 * 等后端起来再问，读已经发生了。
 *
 * <h3>记住什么</h3>
 * 同意过的目录记进 `~/.myagent/trusted.json`，它和它下面的子目录以后都不再问。
 * **家目录和系统目录例外，只信这一次、每次都问**：信任是往下继承的，记住 `C:\` 或家目录
 * 就等于信任了它下面的每个目录——包括以后才建出来的、来路不明的那些。
 */

import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { dirname, join, resolve, sep } from "node:path"

export const TRUST_FILE = join(homedir(), ".myagent", "trusted.json")

/** Windows 上路径不分大小写，比较前统一折叠；别处原样。 */
function key(path: string, platform: NodeJS.Platform): string {
  const normalized = resolve(path)
  return platform === "win32" ? normalized.toLowerCase() : normalized
}

/** `dir` 是 `root` 本身或在它下面。按路径段比，`/a/bc` 不算在 `/a/b` 下面。 */
function within(dir: string, root: string, platform: NodeJS.Platform): boolean {
  const d = key(dir, platform)
  const r = key(root, platform)
  if (d === r) return true
  return d.startsWith(r.endsWith(sep) ? r : r + sep)
}

/** 这个目录（或它的某一级上级）是不是已经信任过。 */
export function isTrusted(dir: string, trusted: readonly string[], platform = process.platform): boolean {
  return trusted.some((root) => within(dir, root, platform))
}

/** 同意之后要不要记住。家目录和系统目录只信这一次，理由见文件头。 */
export function shouldRemember(dir: string, home = homedir(), platform = process.platform): boolean {
  return key(dir, platform) !== key(home, platform) && !isSystemDir(dir, platform)
}

/** 盘符根目录、Windows / Program Files / Users，或类 Unix 的系统目录。 */
function isSystemDir(dir: string, platform: NodeJS.Platform): boolean {
  const d = key(dir, platform)
  if (platform !== "win32") return UNIX_SYSTEM_DIRS.includes(d)
  return (
    /^[a-z]:\\?$/.test(d) ||
    ["windows", "program files", "program files (x86)", "users"].some(
      (name) => d === key(join(d.slice(0, 3), name), platform),
    )
  )
}

/** 读信任清单。文件不在、坏了、形状不对，一律当空清单——最坏结果是多问一次。 */
export function loadTrusted(file = TRUST_FILE): string[] {
  try {
    const data = JSON.parse(readFileSync(file, "utf8")) as { directories?: unknown }
    return Array.isArray(data.directories)
      ? data.directories.filter((d): d is string => typeof d === "string")
      : []
  } catch {
    return []
  }
}

/** 把一个目录加进信任清单。写不进去不报错：这次照样进，下次再问一遍而已。 */
export function rememberTrusted(dir: string, file = TRUST_FILE): void {
  const directories = loadTrusted(file)
  if (directories.includes(resolve(dir))) return
  directories.push(resolve(dir))
  try {
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, JSON.stringify({ directories }, null, 2) + "\n")
  } catch {
    // 见上
  }
}

/** 类 Unix 上不该当工作区的系统目录。 */
const UNIX_SYSTEM_DIRS = [
  "/", "/Users", "/home", "/System", "/Library", "/Applications",
  "/usr", "/etc", "/var", "/bin", "/sbin", "/opt", "/private", "/tmp",
  "/Volumes", "/Network",
]

/** 家目录下装个人文件而不是项目的那几个。 */
const PERSONAL_DIRS = ["Documents", "Desktop", "Downloads", "Pictures", "Movies", "Music", "Videos"]

/**
 * 在这里起 agent 有什么特别的风险，确认框里要写出来。没有就是 null。
 *
 * <p>只是提醒，不拦：用户读完照样可以选信任。
 */
export function riskOf(dir: string, home = homedir(), platform = process.platform): string | null {
  const d = key(dir, platform)
  if (d === key(home, platform)) {
    return "这是你的家目录：里面是你的全部个人文件。agent 在工作区内的删除只需确认就会执行。"
  }
  if (isSystemDir(dir, platform)) {
    return "这是系统目录，不是某个项目。"
  }
  for (const name of PERSONAL_DIRS) {
    if (d === key(join(home, name), platform)) {
      return `这是 ${name} 目录，装的是个人文件而不是某个项目。`
    }
  }
  if (countRepositories(dir, 2) >= 2) {
    return "这里下面装着多个独立的代码仓库，是存放项目的父目录。一条 rm -rf * 就能把它们全带走。"
  }
  return null
}

/**
 * 往下两层数独立仓库，数到 2 就停。
 *
 * <p>固定清单永远列不全「重要目录」，装着多个 `.git` 的目录是项目的容器而不是项目本身。
 * 看两层是因为 `~/Workspace/projects/xxx` 这种层次很常见。
 */
function countRepositories(dir: string, depth: number): number {
  if (depth <= 0) return 0
  let found = 0
  let children: string[]
  try {
    children = readdirSync(dir)
  } catch {
    return 0
  }
  for (const name of children) {
    const child = join(dir, name)
    try {
      if (!statSync(child).isDirectory()) continue
    } catch {
      continue
    }
    found += existsSync(join(child, ".git")) ? 1 : countRepositories(child, depth - 1)
    if (found >= 2) return found
  }
  return found
}
