#!/usr/bin/env python3
"""端到端验证「中断 → 关进程 → 接回会话 → 说继续」：

1. 起后端，交代一件要好几步的活，第一个工具跑完就发 cancel；
   核对 failed 带 interrupted、会话存档里写着断点；
2. 杀掉进程（模拟关终端），重启，发 continue；
   核对 session_restored 带 interrupted；
3. 说「继续」，跑完后核对：发给模型的那句前面拼了 <turn_interrupted>，界面广播的仍是原话，
   活接着干完了，存档里的断点清掉了。

用法：env -u DEEPSEEK_API_KEY python3 bench/resume_e2e.py <一个临时目录> [jar]
需要 ~/.myagent/config.json 里的 key 可用。
"""
import functools
import glob
import json
import os
import subprocess
import sys

import websocket

print = functools.partial(print, flush=True)

root = os.path.abspath(sys.argv[1])
ws_dir = os.path.join(root, "ws")
jar = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "target", "myagent.jar")
os.makedirs(ws_dir, exist_ok=True)
for name, body in [("a.txt", "苹果 3 个\n"), ("b.txt", "香蕉 5 根\n"), ("c.txt", "橙子 2 个\n")]:
    with open(os.path.join(ws_dir, name), "w") as f:
        f.write(body)

failures = []


def check(ok, what):
    print(("  ✓ " if ok else "  ✗ ") + what)
    if not ok:
        failures.append(what)


def start():
    p = subprocess.Popen(["java", "-jar", jar, "--serve", "-d", ws_dir], stdout=subprocess.PIPE,
                         stderr=open(os.path.join(root, "server.log"), "a"), text=True)
    hello = json.loads(p.stdout.readline())
    ws = websocket.create_connection(f"ws://127.0.0.1:{hello['port']}/?token={hello['token']}",
                                     timeout=180, suppress_origin=True)
    return p, ws, json.loads(ws.recv())


def until_idle(ws, on_event=None):
    """收事件直到 busy:false，返回这期间的全部事件。"""
    seen = []
    while True:
        ev = json.loads(ws.recv())
        seen.append(ev)
        if on_event:
            on_event(ev, ws)
        if ev["t"] == "approval_request":
            ws.send(json.dumps({"t": "approval_reply", "id": ev["id"], "decision": "yes"}))
        if ev["t"] == "busy" and ev.get("busy") is False:
            return seen


def archive():
    """这个工作区最新那份会话存档。"""
    files = []
    for path in glob.glob(os.path.expanduser("~/.myagent/sessions/*/*.json")):
        try:
            with open(path) as f:
                snap = json.load(f)
        except (OSError, ValueError):
            continue
        if snap.get("workspace") == os.path.realpath(ws_dir) or snap.get("workspace") == ws_dir:
            files.append((os.path.getmtime(path), snap))
    return max(files, key=lambda x: x[0])[1] if files else None


# ---------- 1. 跑到一半按停 ----------
print("== 1. 交代活，第一个工具跑完就按停")
p, ws, ready = start()
cancelled = {"sent": False}


def cancel_after_first_tool(ev, sock):
    if ev["t"] == "tool_finished" and not cancelled["sent"]:
        cancelled["sent"] = True
        sock.send(json.dumps({"t": "cancel"}))


ws.send(json.dumps({"t": "prompt", "text": "依次读 a.txt、b.txt、c.txt，每次只读一个文件，"
                    "读完三个后新建 summary.md，写一行水果总数。不要问我，直接做。"}))
events = until_idle(ws, cancel_after_first_tool)
failed = next((e for e in events if e["t"] == "failed"), None)
check(cancelled["sent"], "第一个工具跑完后发出了 cancel")
check(failed is not None and "interrupted" in failed, f"failed 带 interrupted：{failed}")
snap = archive()
check(snap is not None and snap.get("interrupted") is not None,
      f"存档里写着断点：{snap and snap.get('interrupted')}")
check(not os.path.exists(os.path.join(ws_dir, "summary.md")), "活确实没干完（summary.md 还不存在）")

# ---------- 2. 关进程，重启，接回会话 ----------
print("== 2. 杀进程（关终端），重启，continue")
ws.close()
p.kill()
p.wait()
p, ws, ready = start()
check("continuable" in ready, "首帧有 continuable")
check("interrupted" not in ready, "首帧没有 interrupted（还没接回会话，眼前是空的新会话）")
ws.send(json.dumps({"t": "continue"}))
restored = None
while restored is None:
    ev = json.loads(ws.recv())
    if ev["t"] == "session_restored":
        restored = ev
    elif ev["t"] == "error":
        print("ERROR:", ev)
        break
check(restored is not None and "interrupted" in restored,
      f"session_restored 带 interrupted：{restored and restored.get('interrupted')}")

# ---------- 3. 说「继续」 ----------
print("== 3. 说「继续」")
ws.send(json.dumps({"t": "prompt", "text": "继续", "cid": "e2e"}))
events = until_idle(ws)
echo = next((e for e in events if e["t"] == "user_message"), None)
done = next((e for e in events if e["t"] == "done"), None)
check(echo is not None and echo.get("text") == "继续", f"界面广播的是原话：{echo and echo.get('text')}")
check(done is not None, f"这一轮正常说完：{done and done.get('text', '')[:80]}")
summary = os.path.join(ws_dir, "summary.md")
check(os.path.exists(summary), "summary.md 写出来了（接着把活干完）")
if os.path.exists(summary):
    print("     summary.md:", open(summary).read().strip())
reads = [e for e in events if e["t"] == "tool_started" and e["tool"] == "read_file"]
print("     继续之后读了：", [json.loads(e["arguments"]).get("path") for e in reads])
snap = archive()
users = [m for m in snap["messages"] if m.get("role") == "user"]
check(users[-1]["content"].startswith("<turn_interrupted>") and users[-1]["content"].endswith("继续"),
      "发给模型的那句前面拼了中断说明")
print("     ---- 发给模型的最后一句 ----")
print("     " + users[-1]["content"].replace("\n", "\n     "))
check(snap.get("interrupted") is None, "说完之后存档里的断点清掉了")

ws.close()
p.terminate()
print()
print("全部通过" if not failures else f"{len(failures)} 项没过：{failures}")
sys.exit(1 if failures else 0)
