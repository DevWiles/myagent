#!/usr/bin/env python3
"""端到端验证 /undo：起一个后端、让 agent 改/建/删三个文件、发 undo、核对全部回来且用户的 git 干干净净。

用法：python3 bench/undo_e2e.py <一个临时目录>   （目录里会 git init 一个小仓库；需要 ~/.myagent/config.json 里的 key 可用，
注意 shell 里的 DEEPSEEK_API_KEY 会覆盖配置文件——不想用它就 env -u DEEPSEEK_API_KEY 再跑）
"""
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
        elif ev["t"] == until: return ev
def show(tag):
    print(f"--- {tag}:", sorted(os.listdir(ws_dir)), "| README:", open(os.path.join(ws_dir, "README.md")).read().strip().replace("\n", "⏎"))
show("before")
turn({"t": "prompt", "text": "把 README.md 的第二行改成「已被 agent 改过」，再新建 hello.txt 内容为 hi，然后删除 keep.txt。不要问我，直接做。"}, "busy")
show("after prompt")
print("git status in user repo:", subprocess.run(["git", "status", "--porcelain"], cwd=ws_dir, capture_output=True, text=True).stdout.strip().replace("\n", " | "))
ev = turn({"t": "undo"}, "undone"); print("undone:", json.dumps(ev, ensure_ascii=False))
turn({"t": "noop"}, "busy") if False else None
time.sleep(0.5); show("after undo")
print("git status after undo:", repr(subprocess.run(["git", "status", "--porcelain"], cwd=ws_dir, capture_output=True, text=True).stdout.strip()))
ev = turn({"t": "undo"}, "undone"); print("second undo:", ev.get("t"), ev.get("message", ""))
ws.close(); p.terminate()
