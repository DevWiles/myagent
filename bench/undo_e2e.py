#!/usr/bin/env python3
"""端到端验证 /undo 与 /rewind：起一个后端、让 agent 改/建/删三个文件、发 undo 核对全部回来且用户的 git
干干净净；再跑一轮、列检查点、rewind 到 #1 的 both（文件和对话都回）。

用法：python3 bench/undo_e2e.py <一个临时目录>
需要 ~/.myagent/config.json 里的 key 可用；shell 里的 DEEPSEEK_API_KEY 会覆盖配置文件，
不想用它就 env -u DEEPSEEK_API_KEY 再跑。
"""
import json, subprocess, sys, time, os, websocket
import functools
print = functools.partial(print, flush=True)

root = os.path.abspath(sys.argv[1]); ws_dir = os.path.join(root, "ws")
jar = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "target", "myagent.jar")
os.makedirs(ws_dir, exist_ok=True)
subprocess.run("git init -q && printf '# demo\\n原始第一行\\n' > README.md && printf 'keep\\n' > keep.txt && git add -A && git -c user.name=t -c user.email=t@t commit -q -m init", shell=True, cwd=ws_dir, check=True)
p = subprocess.Popen(["java", "-jar", jar, "--serve", "-d", ws_dir], stdout=subprocess.PIPE, stderr=open(os.path.join(os.path.dirname(ws_dir), "server.log"), "w"), text=True)
hello = json.loads(p.stdout.readline())
ws = websocket.create_connection(f"ws://127.0.0.1:{hello['port']}/?token={hello['token']}", timeout=120, suppress_origin=True)
ready = json.loads(ws.recv()); print("features has undo:", "undo" in ready.get("features", []))
def turn(cmd, until):
    ws.send(json.dumps(cmd)); seen = []
    while True:
        ev = json.loads(ws.recv()); seen.append(ev["t"])
        if ev["t"] == "approval_request": ws.send(json.dumps({"t": "approval_reply", "id": ev["id"], "decision": "yes"}))
        if ev["t"] == "error": print("ERROR:", ev.get("message")); return ev
        if until == "busy":
            if ev["t"] == "busy" and ev.get("busy") is False: return ev
        elif ev["t"] == until:
            # 目标帧后面还跟着一条 busy:false，得吃掉，否则它会被下一轮当成「跑完了」
            while True:
                nxt = json.loads(ws.recv())
                if nxt["t"] == "busy" and nxt.get("busy") is False: return ev
def show(tag):
    print(f"--- {tag}:", sorted(os.listdir(ws_dir)), "| README:", open(os.path.join(ws_dir, "README.md")).read().strip().replace("\n", "⏎"))
show("before")
turn({"t": "prompt", "text": "把 README.md 的第二行改成「已被 agent 改过」，再新建 hello.txt 内容为 hi，然后删除 keep.txt。不要问我，直接做。"}, "busy")
show("after prompt")
print("git status in user repo:", subprocess.run(["git", "status", "--porcelain"], cwd=ws_dir, capture_output=True, text=True).stdout.strip().replace("\n", " | "))
ev = turn({"t": "undo"}, "rewound"); print("undo →", json.dumps({k: ev.get(k) for k in ("t", "id", "scope", "restored", "remaining", "message")}, ensure_ascii=False))
turn({"t": "noop"}, "busy") if False else None
time.sleep(0.5); show("after undo")
print("git status after undo:", repr(subprocess.run(["git", "status", "--porcelain"], cwd=ws_dir, capture_output=True, text=True).stdout.strip()))
# 第二轮：再改一次，然后回到检查点 #1 的「都回」——文件回、对话也截回
turn({"t": "prompt", "text": "在 README.md 末尾追加一行「第二轮」。不要问我，直接做。"}, "busy")
show("after 2nd prompt")
ws.send(json.dumps({"t": "checkpoints"}))
while True:
    ev = json.loads(ws.recv())
    if ev["t"] == "checkpoint_list":
        print("checkpoints:", [(c["id"], c["turn"], c["label"][:12]) for c in ev["items"]]); break
ev = turn({"t": "rewind", "id": 1, "scope": "both"}, "rewound"); print("rewound:", json.dumps({k: ev.get(k) for k in ("id", "scope", "restored", "userTurn", "remaining")}, ensure_ascii=False))
time.sleep(0.5); show("after rewind both")
print("git status after rewind:", repr(subprocess.run(["git", "status", "--porcelain"], cwd=ws_dir, capture_output=True, text=True).stdout.strip()))
ws.close(); p.terminate()
