#!/usr/bin/env python3
"""
备用屏的字节流验收。

为什么不是「跑一下看看」：这个项目有过好几次「我说修好了、其实没修好」。
终端状态是不可见的 —— 独占没生效、退出没还回来、模式漏在外面，肉眼都很难当场
看出来，但字节流里写得一清二楚。所以验收标准全部是可断言的字节序。

必须用真 PTY 并显式 TIOCSWINSZ：
  - `script -q` 在非 tty 父进程下把 rows/cols 报成 0，根本构造不出全屏帧
    （我用它测过一轮，得到「三种情况都正常」的假结论）。
  - 管道里 stdout.isTTY 为假，Ink 直接不进备用屏。

跑：  python3 tui/test/verify_altscreen.py

（文件名刻意不叫 `pty.py` —— 那会把标准库的 `pty` 模块遮住，`pty.fork` 直接消失。）
"""

import os
import pty
import re
import select
import signal
import struct
import sys
import termios
import fcntl
import time
import tempfile

ESC = b"\x1b"
HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE = os.path.join(HERE, "fixture.tsx")

SEQ = {
    "1049h": ESC + b"[?1049h",
    "1049l": ESC + b"[?1049l",
    "1007s": ESC + b"[?1007s",
    "1007h": ESC + b"[?1007h",
    "1007r": ESC + b"[?1007r",
    "25l": ESC + b"[?25l",
    "25h": ESC + b"[?25h",
    "3J": ESC + b"[3J",
}
ANSI = re.compile(ESC + rb"\[[0-9;?]*[a-zA-Z]")

failures = []
checks = [0]


def check(ok, label, detail=""):
    checks[0] += 1
    if not ok:
        failures.append("%s %s" % (label, detail))
        print("  FAIL  %s %s" % (label, detail))
    else:
        print("  ok    %s" % label)


def run(argv, rows=24, cols=90, env=None, send=b"", boot=2.5, after=2.0, kill_sig=None):
    """在真 PTY 里跑一个命令，返回它写出来的全部字节。"""
    full_env = dict(os.environ)
    full_env["TERM"] = "xterm-256color"
    full_env.pop("CI", None)
    full_env.pop("CONTINUOUS_INTEGRATION", None)
    if env:
        full_env.update(env)

    pid, fd = pty.fork()
    if pid == 0:
        os.environ.clear()
        os.environ.update(full_env)
        os.execvp(argv[0], argv)

    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    buf = bytearray()

    def pump(seconds):
        end = time.time() + seconds
        while time.time() < end:
            r, _, _ = select.select([fd], [], [], 0.1)
            if not r:
                continue
            try:
                chunk = os.read(fd, 65536)
            except OSError:
                return False  # 子进程没了
            if not chunk:
                return False
            buf.extend(chunk)
        return True

    alive = pump(boot)
    if send and alive:
        # 一定要把正文和回车**分两次**发。整块到达时 Ink 会把 "/exit\r" 作为一个
        # 输入事件交给 PromptInput，而它的兜底分支会把 \r 换成空格当正文吃掉，
        # 于是永远不提交、程序不退出，最后被 SIGKILL —— 表现就是「没有 1049l」。
        for i, part in enumerate(send.split(b"\r")):
            if part:
                os.write(fd, part)
                pump(0.4)
            if i < len(send.split(b"\r")) - 1:
                os.write(fd, b"\r")
                pump(0.4)
    if kill_sig and alive:
        try:
            os.kill(pid, kill_sig)
        except ProcessLookupError:
            pass
    pump(after)
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    try:
        os.waitpid(pid, 0)
    except ChildProcessError:
        pass
    os.close(fd)
    return bytes(buf)


def count(b, name):
    return len(re.findall(re.escape(SEQ[name]), b))


def first(b, name):
    return b.find(SEQ[name])


def assert_fullscreen_lifecycle(b, label, expect_modes=True):
    """每条退出路径都要满足的那几条。"""
    print("\n[%s]  %d bytes" % (label, len(b)))

    check(count(b, "1049h") == 1, "1049h 恰好 1 次", "(got %d)" % count(b, "1049h"))
    check(first(b, "1049h") == 0, "1049h 位于第 0 字节", "(at %d)" % first(b, "1049h"))

    l = first(b, "1049l")
    check(l >= 0, "退出时还了主屏（有 1049l）")
    if l < 0:
        return

    if expect_modes:
        check(count(b, "1007s") == count(b, "1007r") == 1,
              "Alt Scroll 开关一一配对",
              "(s=%d r=%d)" % (count(b, "1007s"), count(b, "1007r")))
        r = first(b, "1007r")
        check(0 <= r < l, "模式在还主屏之前就关掉了", "(1007r@%d < 1049l@%d)" % (r, l))

    tail = b[l + len(SEQ["1049l"]):]
    check(SEQ["3J"] not in tail, "第一个 1049l 之后没有 3J（没去清用户的 scrollback）")
    visible = ANSI.sub(b"", tail).strip()
    crash_markers = (b"BOOM", b"crash", "崩溃".encode("utf-8"), "完整日志".encode("utf-8"))
    is_crash_text = any(m in visible for m in crash_markers)
    check(visible == b"" or is_crash_text,
          "第一个 1049l 之后没有残帧内容（只允许崩溃堆栈）", "(got %r)" % visible[:80])
    check(count(b, "25h") >= 1, "光标还回来了")


def last_frame_lines(b):
    parts = b.rsplit(ESC + b"[?2026h", 1)
    seg = parts[-1].split(ESC + b"[?2026l", 1)[0]
    txt = ANSI.sub(b"", seg).decode("utf8", "replace").replace("\r", "")
    return txt.split("\n")


def main():
    bun = os.popen("command -v bun").read().strip()
    if not bun:
        print("需要 bun")
        return 1
    argv = [bun, FIXTURE]

    # --- 1. 正常退出：/exit ---
    assert_fullscreen_lifecycle(run(argv, send=b"/exit\r"), "/exit")

    # --- 2. Ctrl-C ---
    assert_fullscreen_lifecycle(run(argv, send=b"\x03"), "Ctrl-C")

    # --- 3. Ctrl-D（空输入） ---
    assert_fullscreen_lifecycle(run(argv, send=b"\x04"), "Ctrl-D")

    # --- 4. 三个信号 ---
    for name, sig in (("SIGINT", signal.SIGINT), ("SIGTERM", signal.SIGTERM), ("SIGHUP", signal.SIGHUP)):
        assert_fullscreen_lifecycle(run(argv, kill_sig=sig), name)

    # --- 5. 未捕获异常 / 未处理 rejection ---
    #
    # 这两条是**最重要**的：signal-exit 在 Bun 下不接这两种情况，全靠
    # process.on('exit') + 显式 handler 兜。之前我用 `bun -e` 拼的夹具没能
    # 真的触发到这条路径（抛与不抛抓到的字节完全一样），所以这里用固定夹具重验。
    for mode, marker in (("throw", b"FIXTURE-BOOM-THROW"), ("reject", b"FIXTURE-BOOM-REJECT")):
        log = os.path.join(tempfile.mkdtemp(), "crash.log")
        b = run(argv, env={"FIXTURE_CRASH": mode, "MYAGENT_CRASH_LOG": log}, boot=3.0, after=2.0)
        assert_fullscreen_lifecycle(b, "未处理的 %s" % mode)
        l = first(b, "1049l")
        idx = b.find(marker)
        check(idx >= 0, "%s：堆栈出现在输出里" % mode)
        check(idx > l if idx >= 0 and l >= 0 else False,
              "%s：堆栈打在主屏上（在 1049l 之后，用户看得见）" % mode,
              "(stack@%d vs 1049l@%d)" % (idx, l))
        check(os.path.exists(log) and os.path.getsize(log) > 0,
              "%s：崩溃日志落盘了" % mode)

    # --- 6. 关闭态：不进备用屏，也不许碰 scrollback ---
    b = run(argv, env={"FIXTURE_FULLSCREEN": "0"}, send=b"/exit\r")
    print("\n[--no-fullscreen]  %d bytes" % len(b))
    check(count(b, "1049h") == 0, "没有进备用屏")
    check(count(b, "1007h") == 0, "没有开 Alt Scroll")
    check(count(b, "3J") == 0, "**没有 3J** —— 不清用户的 scrollback")

    # --- 7. 稳态不闪：内容更新不会每帧全清 ---
    b = run(argv, send=b"hello", boot=3.0, after=1.5)
    print("\n[稳态重绘]  %d bytes" % len(b))
    l = first(b, "1049l")
    body = b[:l] if l > 0 else b
    n2j = len(re.findall(re.escape(ESC + b"[2J"), body))
    check(n2j <= 2, "备用屏内 ESC[2J 次数很少（不是每帧全清）", "(got %d)" % n2j)

    # --- 8. 输入框钉在最后一行（真终端里再验一遍） ---
    for rows, cols in ((24, 90), (14, 20), (40, 120)):
        b = run(argv, rows=rows, cols=cols, boot=3.0, after=0.3)
        lines = last_frame_lines(b)
        prompt = -1
        for i in range(len(lines) - 1, -1, -1):
            if lines[i].startswith("›"):
                prompt = i
                break
        last_non_empty = max((i for i, l in enumerate(lines) if l.strip()), default=-1)
        print("\n[钉底 %dx%d]  帧 %d 行，输入框在第 %d 行，最后非空行 %d"
              % (cols, rows, len(lines), prompt, last_non_empty))
        check(len(lines) == rows, "帧高 == rows", "(got %d)" % len(lines))
        check(prompt >= 0, "找到了输入框")
        check(last_non_empty == rows - 1, "底部贴到最后一行", "(got %d)" % last_non_empty)

    print("\n%d 项断言，%d 项失败" % (checks[0], len(failures)))
    for f in failures:
        print("  - %s" % f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
