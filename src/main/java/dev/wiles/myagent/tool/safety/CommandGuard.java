package dev.wiles.myagent.tool.safety;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * shell 命令风险评估。
 *
 * <h3>先说清楚它做不到什么</h3>
 * shell 太灵活，静态检查**不可能**做到万无一失。
 * {@code eval $(echo cm0gLXJmIC8K | base64 -d)} 这种就绕过去了，
 * 变量拼接、别名、脚本文件同理。所以这个类的定位是
 * <b>挡住明显危险和手滑，不是对抗蓄意攻击</b>。
 * 真正的防线是后面那道人工确认——重要动作总有人过目。
 *
 * <h3>三档判定</h3>
 * <ul>
 *   <li>{@link RiskLevel#FORBIDDEN} 直接拒绝，用户同意也不执行：删根目录、提权、
 *       格式化、关机、读私钥、管道到 shell</li>
 *   <li>{@link RiskLevel#NEEDS_APPROVAL} 要人点头：删改文件、联网、装包、改权限</li>
 *   <li>{@link RiskLevel#SAFE} 直接放行：只读查看、编译测试等</li>
 * </ul>
 * <b>未知命令一律归入 NEEDS_APPROVAL</b>——白名单没列到的东西，宁可多问一句。
 *
 * <h3>为什么要按分隔符拆开逐段检查</h3>
 * {@code ls && rm -rf ~} 只看第一个词是 ls 就放行，等于没防。
 * 所以 {@code ; && || |} 和子命令 {@code $() ``} 都要当作分界，每一段单独判，取最严的那档。
 */
public final class CommandGuard {

    /**
     * 这些命令<b>规划阶段就该避开</b>，别等执行时才被拦。
     *
     * <p>排过序，因为它会被拼进规划提示词——顺序不稳定的话，
     * 同一个工作区每次规划的提示词都不一样，缓存命不中，出了问题也不好复现。
     *
     * @return 禁止命令的名字，已排序
     */
    public static List<String> forbiddenCommands() {
        return FORBIDDEN_COMMANDS.stream().sorted().toList();
    }

    /** 无论如何都不执行。 */
    private static final Set<String> FORBIDDEN_COMMANDS = Set.of(
            "sudo", "su", "doas",                                   // 提权
            "mkfs", "fdisk", "diskutil", "parted", "shred", "srm",   // 抹盘
            "shutdown", "reboot", "halt", "poweroff", "init",        // 关机
            "launchctl", "systemctl", "service", "crontab",          // 系统服务与定时任务
            "useradd", "userdel", "usermod", "passwd", "visudo", "chpasswd");

    /** 要人点头才执行。 */
    private static final Set<String> APPROVAL_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "dd", "truncate", "tee", "ln",       // 能毁掉已有文件
            "chmod", "chown", "chgrp",                                // 改权限
            "curl", "wget", "nc", "ncat", "telnet", "ssh", "scp",     // 出网
            "sftp", "rsync", "ftp",
            "kill", "killall", "pkill",                               // 动别的进程
            "npm", "pnpm", "yarn", "pip", "pip3", "brew",             // 装包会执行第三方脚本
            "apt", "apt-get", "yum", "dnf", "gem", "composer",
            "docker", "podman", "kubectl",                            // 容器与集群
            "eval", "exec", "source", "xargs",                        // 能把任意字符串变成命令
            "osascript", "open", "defaults");

    /** 只读或影响限于工作区，直接放行。 */
    private static final Set<String> SAFE_COMMANDS = Set.of(
            "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "rg", "ag",
            "find", "fd", "wc", "echo", "printf", "pwd", "cd", "which", "whereis",
            "type", "file", "stat", "du", "df", "tree", "realpath", "readlink",
            "diff", "sort", "uniq", "cut", "tr", "jq", "basename", "dirname",
            "date", "sleep", "true", "false", "test", "env", "uname", "whoami",
            "hostname", "seq", "yes", "touch", "mkdir", "cp", "awk", "sed",
            "java", "javac", "jar", "mvn", "gradle", "node", "npx", "deno",
            "python", "python3", "go", "rustc", "cargo", "make", "cmake", "dotnet");

    /** 带后缀的变体也算，比如 mkfs.ext4、mkfs.xfs。 */
    private static final Set<String> FORBIDDEN_PREFIXES = Set.of("mkfs", "newfs");

    /** 包管理器里这些子命令只是跑脚本或查信息，不装东西。 */
    private static final Set<String> PACKAGE_MANAGERS = Set.of("npm", "pnpm", "yarn");
    private static final Set<String> SAFE_PACKAGE_SUBCOMMANDS = Set.of(
            "test", "run", "start", "ls", "list", "outdated", "view", "why", "version");

    /** git 的只读子命令。 */
    private static final Set<String> SAFE_GIT_SUBCOMMANDS = Set.of(
            "status", "log", "diff", "show", "branch", "ls-files", "rev-parse",
            "describe", "blame", "shortlog", "reflog", "cat-file", "remote");

    /** 出现这些片段就认定在碰凭据，一律拒绝——agent 没有任何理由读它们。 */
    private static final List<String> SECRET_PATHS = List.of(
            "/.ssh", "id_rsa", "id_ed25519", "id_dsa", "/.aws", "/.gnupg",
            "/.kube/config", "/etc/shadow", "/etc/sudoers", "/etc/passwd",
            ".pem", ".p12", "keychain");

    /** 系统目录，配合写操作就是 FORBIDDEN。 */
    private static final List<String> SYSTEM_PATHS = List.of(
            "/etc/", "/usr/", "/bin/", "/sbin/", "/System/", "/Library/", "/boot/", "/var/");

    /**
     * 会毁掉已有数据的命令。它们的目标只要指向工作区外，一律拒绝——
     * 这是防「把电脑删干净」的主力规则，比逐条列黑名单可靠。
     */
    private static final Set<String> DESTRUCTIVE_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "dd", "truncate", "shred", "srm", "tee", "ln",
            "chmod", "chown", "chgrp");

    /**
     * 这条命令的参数是不是「会被毁掉的东西」。
     *
     * <p>给 {@link RuleSuggester} 用，用来决定一条授权规则能不能带「后面还可以跟参数」
     * 那个通配。区别在于多出来的参数是什么性质：
     * <pre>
     *   mvn test -X        多出来的是选项    → 放宽无妨
     *   rm -rf target src  多出来的是<b>又一个删除目标</b> → 放宽等于多授权了一个目标
     * </pre>
     * 用这张现成的表而不是另列一份：它定义的正是「参数指向会被销毁的东西」，
     * 和这里要问的是同一件事。两份表迟早会分叉，而分叉处就是意外授权。
     */
    public static boolean destroysItsArguments(String commandName) {
        return commandName != null && DESTRUCTIVE_COMMANDS.contains(commandName.toLowerCase(Locale.ROOT));
    }

    /** 取一段命令里真正的命令名。{@link RuleSuggester} 要和这边认的是同一个。 */
    public static String commandNameOf(String segment) {
        return commandName(segment);
    }

    /** find 带这些参数就不再是只读命令了。 */
    private static final List<String> FIND_DESTRUCTIVE_FLAGS =
            List.of("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fls", "-fprint");

    /** 参数里出现这些，说明目标在工作区之外。 */
    private static final Pattern OUTSIDE_TARGET = Pattern.compile(
            "(?:^|\\s)(~|\\$HOME|\\$\\{HOME\\}|/[^\\s]|\\.\\.(?:/|\\s|$))");

    /** 重定向写到工作区外（/dev/null 除外，它无害且常用）。 */
    private static final Pattern REDIRECT_OUTSIDE = Pattern.compile(
            ">>?\\s*(~|\\$HOME|/(?!dev/null))");

    /** 能改文件的命令，用来判断上面那些系统路径是被读还是被写。 */
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "cp", "dd", "tee", "truncate", "ln",
            "chmod", "chown", "chgrp", "touch", "mkdir");

    /** 在原始命令上整体匹配的致命模式——拆段之后就看不出来了，所以必须先查。 */
    private static final List<Pattern> FATAL_PATTERNS = List.of(
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{.*\\|.*&"),               // fork bomb
            Pattern.compile("\\|\\s*(sudo\\s+)?(sh|bash|zsh|python\\d?)\\b"), // curl … | sh
            Pattern.compile(">\\s*/dev/(sd|disk|nvme|hd)"),                   // 直接写裸设备
            Pattern.compile("\\bof=/dev/"),                                   // dd of=/dev/…
            Pattern.compile("\\brm\\s+(-\\S+\\s+)*[~/]\\s*$"),                // rm … / 或 rm … ~
            Pattern.compile("\\brm\\s+(-\\S+\\s+)*/\\s+"),
            Pattern.compile("\\b(mv|cp)\\s+\\S+\\s+/dev/null\\b"));           // 把文件搬进黑洞

    /** rm 带这些参数时特别危险。 */
    private static final Pattern RM_RECURSIVE = Pattern.compile("\\brm\\s+(-\\S*[rR]\\S*\\s+)");
    /** 任何命令碰到家目录都要提醒一句。 */
    private static final Pattern HOME_REFERENCE = Pattern.compile("(?:^|\\s)(~|\\$HOME|\\$\\{HOME\\})");

    /** 会就地改文件的 sed。 */
    private static final Pattern SED_IN_PLACE = Pattern.compile("\\bsed\\s+(-\\S+\\s+)*-i\\b|\\bsed\\s+-\\S*i");

    public CommandRisk assess(String command) {
        if (command == null || command.isBlank()) {
            return CommandRisk.forbidden("命令为空");
        }
        String lower = command.toLowerCase(Locale.ROOT);

        // ---- 0. 重定向写到工作区外，等同于破坏外部文件 ----
        if (REDIRECT_OUTSIDE.matcher(lower).find()) {
            return CommandRisk.forbidden("命令试图把内容写到工作区之外的路径");
        }

        // ---- 1. 整体致命模式：拆段后就看不出来了，必须先查 ----
        for (Pattern p : FATAL_PATTERNS) {
            if (p.matcher(lower).find()) {
                return CommandRisk.forbidden("命令匹配到高危模式（删根目录 / 管道执行下载内容 / 写裸设备等）");
            }
        }
        for (String secret : SECRET_PATHS) {
            if (lower.contains(secret)) {
                return CommandRisk.forbidden("命令涉及密钥或系统凭据文件: " + secret);
            }
        }

        // ---- 2. 逐段判定，取最严的一档 ----
        RiskLevel worst = RiskLevel.SAFE;
        String reason = "";
        for (String segment : splitSegments(command)) {
            CommandRisk risk = assessSegment(segment);
            if (risk.isForbidden()) {
                return risk;                       // 有一段是禁止的，整条就禁止
            }
            if (risk.needsApproval() && worst == RiskLevel.SAFE) {
                worst = RiskLevel.NEEDS_APPROVAL;
                reason = risk.reason();
            }
        }
        return worst == RiskLevel.SAFE ? CommandRisk.safe() : CommandRisk.approval(reason);
    }

    private CommandRisk assessSegment(String segment) {
        String name = commandName(segment);
        if (name.isEmpty()) {
            return CommandRisk.safe();
        }
        String lower = segment.toLowerCase(Locale.ROOT);

        if (FORBIDDEN_COMMANDS.contains(name)) {
            return CommandRisk.forbidden(name + " 属于禁止执行的命令（提权 / 系统级操作）");
        }
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (name.startsWith(prefix)) {
                return CommandRisk.forbidden(name + " 会格式化磁盘");
            }
        }

        // 写操作碰系统目录，直接拒绝
        if (WRITE_COMMANDS.contains(name)) {
            for (String path : SYSTEM_PATHS) {
                if (lower.contains(path)) {
                    return CommandRisk.forbidden(name + " 试图修改系统目录 " + path);
                }
            }
        }

        // 破坏性命令的目标只要指向工作区外，一律拒绝。
        // 这条比逐个列黑名单有效得多：不管是 rm -rf ~、mv ~/Documents /tmp
        // 还是 chmod -R 000 ~，统统落在这里。
        if (DESTRUCTIVE_COMMANDS.contains(name) && targetsOutside(segment, name)) {
            return CommandRisk.forbidden(name + " 的目标超出了工作区，只允许操作工作区内的文件");
        }

        // find 默认是只读的，但 -delete / -exec 让它变成删除或执行任意命令的工具
        if ("find".equals(name)) {
            boolean destructive = FIND_DESTRUCTIVE_FLAGS.stream().anyMatch(lower::contains);
            if (destructive && targetsOutside(segment, name)) {
                return CommandRisk.forbidden("find 带 -delete/-exec 且目标超出工作区");
            }
            if (destructive) {
                return CommandRisk.approval("find 带 -delete/-exec，会删除文件或执行任意命令");
            }
            if (targetsOutside(segment, name)) {
                return CommandRisk.approval("find 的搜索范围在工作区之外");
            }
            return CommandRisk.safe();
        }

        // 任何命令只要碰到家目录，都先问一句——那里全是个人数据
        if (HOME_REFERENCE.matcher(segment).find()) {
            return CommandRisk.approval(name + " 的参数指向家目录，可能影响工作区之外的个人文件");
        }

        if ("git".equals(name)) {
            return bySubcommand(segment, SAFE_GIT_SUBCOMMANDS, "git", "会改动仓库状态或与远端交互");
        }
        if (PACKAGE_MANAGERS.contains(name)) {
            // npm test / npm run build 是日常操作，npm install 才会下载并执行第三方脚本
            return bySubcommand(segment, SAFE_PACKAGE_SUBCOMMANDS, name, "会安装或发布依赖包");
        }
        if ("sed".equals(name) && SED_IN_PLACE.matcher(lower).find()) {
            return CommandRisk.approval("sed -i 会就地改写文件");
        }
        if (APPROVAL_COMMANDS.contains(name)) {
            return CommandRisk.approval(name + " 可能修改文件、访问网络或执行外部代码");
        }
        if (SAFE_COMMANDS.contains(name)) {
            return CommandRisk.safe();
        }
        // 白名单没列到的一律多问一句
        return CommandRisk.approval("未知命令 " + name + "，无法判断其影响");
    }

    /** 判断参数（不含命令名本身）里是否出现了工作区之外的路径。 */
    private static boolean targetsOutside(String segment, String name) {
        String args = stripCommandName(segment, name);
        return OUTSIDE_TARGET.matcher(args).find();
    }

    /** 去掉命令名，避免 /bin/rm 这种写法把命令自身的路径当成目标。 */
    private static String stripCommandName(String segment, String name) {
        String trimmed = segment.trim();
        int index = trimmed.toLowerCase(Locale.ROOT).indexOf(name);
        return index < 0 ? trimmed : trimmed.substring(index + name.length());
    }

    /**
     * 按子命令细分风险。像 git、npm 这类「一个命令干很多事」的工具，
     * 一刀切成危险会把 git status、npm test 这种日常操作也拦下来，
     * 用户被问烦了就会一路按 y，那道防线也就废了。
     */
    private CommandRisk bySubcommand(String segment, Set<String> safeSubcommands,
                                     String name, String reason) {
        for (String token : segment.trim().split("\\s+")) {
            if (token.isEmpty() || token.startsWith("-") || token.endsWith(name)) {
                continue;                          // 跳过命令自身和它的选项
            }
            String sub = token.toLowerCase(Locale.ROOT);
            return safeSubcommands.contains(sub)
                    ? CommandRisk.safe()
                    : CommandRisk.approval(name + " " + sub + " " + reason);
        }
        return CommandRisk.safe();                 // 光一个命令名，没带子命令
    }

    /**
     * 后台执行的单个 {@code &}。
     *
     * <p>{@code 2>&1}、{@code &>out} 里的 {@code &} 不是分隔符，切开会得到
     * {@code 2>} 这种「未知命令」，于是每条带重定向的命令都要问一次人——
     * 而被问烦了的用户会一路按同意，那才是真正的损失。
     */
    private static final Pattern BACKGROUND_AMP = Pattern.compile("(?<![<>&])&(?![>&0-9])");

    /**
     * 按分隔符拆段。
     *
     * <p>故意不解析引号：{@code echo "a; rm -rf /"} 会被切成两段，
     * 于是引号里的内容也按命令判一次。这会产生误报，但方向是安全的——
     * 宁可让模型换个写法，也不能放过真的危险命令。
     *
     * <h3>为什么是 public</h3>
     * {@link ApprovalRules} 判断「这条命令在不在放行名单里」时，<b>必须用同一个拆段器</b>。
     * 两边各拆各的，它们之间的差值就是绕过这套系统的入口：
     * 风险评估拆成两段、看见了危险的那段，放行名单不拆、只看见前缀像 {@code mvn test}，
     * 于是危险的那段被前缀规则顺手批了。
     *
     * <p>共用一份不是为了少写几行，是为了让这个差值<b>恒等于零</b>。
     */
    public static List<String> splitSegments(String command) {
        String normalized = command
                .replace("&&", "\n").replace("||", "\n")
                .replace("$(", "\n").replace("`", "\n")
                .replace(";", "\n").replace("|", "\n")
                .replace(")", "\n");
        // 单个 & 也是分隔符。漏掉它的后果实测过：mvn test & rm -rf build
        // 整条被判成 SAFE——因为 commandName 只看第一个 token，看到 mvn 就收工了
        normalized = BACKGROUND_AMP.matcher(normalized).replaceAll("\n");
        return List.of(normalized.split("\n"));
    }

    /** 取一段里真正的命令名：跳过 FOO=bar 这类前缀赋值，并去掉路径。 */
    private static String commandName(String segment) {
        for (String token : segment.trim().split("\\s+")) {
            if (token.isEmpty() || (token.contains("=") && !token.startsWith("-"))) {
                continue;
            }
            int slash = token.lastIndexOf('/');
            String name = slash >= 0 ? token.substring(slash + 1) : token;
            return name.toLowerCase(Locale.ROOT);
        }
        return "";
    }
}
