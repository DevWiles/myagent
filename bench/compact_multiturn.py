#!/usr/bin/env python3
"""多轮对话把窗口撑满，逼出摘要档（summarized=true）：每轮让 agent 读一个文件并用不少于 N 字复述——
复述在 assistant 消息里，裁剪工具输出去不掉它，只能靠 map/reduce 摘要。记录每次 compacted 的 before/after/summarized、
是否出现 compacting 进度帧（= 同步压缩、用户会等）、每轮首 token 延迟；最后不读文件只凭记忆核对目标与改过的文件。

用法：python3 bench/compact_multiturn.py [--window 6000] [--turns 6] [--words 500]
"""
import argparse
import json
import os
import shutil
import subprocess
import time
import uuid

import websocket

BENCH = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(BENCH)
JAR = os.path.join(PROJECT, "target", "myagent.jar")
IMAGE = "myagent-sandbox:v2"


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--window", type=int, default=6000)
    ap.add_argument("--turns", type=int, default=6)
    ap.add_argument("--words", type=int, default=500)
    args = ap.parse_args()
    run_dir = os.path.join(BENCH, "results", f"compact-multiturn-w{args.window}")
    shutil.rmtree(run_dir, ignore_errors=True)
    work = os.path.join(run_dir, "work")
    shutil.copytree(os.path.join(BENCH, "compact-work"), work)
    files = sorted(f for f in os.listdir(work) if f.endswith(".md"))[: args.turns]
    os.chmod(work, 0o777)
    home = os.path.join(run_dir, "home")
    os.makedirs(os.path.join(home, ".myagent"))
    cfg = json.load(open(os.path.expanduser("~/.myagent/config.json")))
    json.dump({"apiKey": cfg["apiKey"], "baseUrl": cfg.get("baseUrl", "https://api.deepseek.com"), "model": cfg.get("model", "deepseek-chat"), **({"thinking": cfg["thinking"]} if "thinking" in cfg else {})},
              open(os.path.join(home, ".myagent", "config.json"), "w"))
    json.dump({"version": 1, "allow": [], "deny": []}, open(os.path.join(home, ".myagent", "policy.json"), "w"))
    name = f"compact-mt-{uuid.uuid4().hex[:8]}"
    r = sh(["docker", "run", "-d", "--rm", "--name", name, "-p", "127.0.0.1::7777",
            "-v", f"{JAR}:/opt/myagent.jar:ro", "-v", f"{work}:/work", "-v", f"{home}:/home/bench",
            "-e", "HOME=/home/bench", "-e", "MYAGENT_AUTO_PLAN=0", "-e", f"MYAGENT_CONTEXT_TOKENS={args.window}",
            "-w", "/work", IMAGE, "java", "-jar", "/opt/myagent.jar", "--serve", "--lan", "--port", "7777", "-d", "/work"])
    assert r.returncode == 0, r.stderr
    token = port = None
    for _ in range(60):
        for line in sh(["docker", "logs", name]).stdout.splitlines():
            if line.startswith("{") and '"listening"' in line:
                token = json.loads(line)["token"]
                port = sh(["docker", "port", name, "7777"]).stdout.strip().split(":")[-1]
        if token:
            break
        time.sleep(0.5)
    ws = websocket.create_connection(f"ws://127.0.0.1:{port}/?token={token}", timeout=60, suppress_origin=True)
    ready = json.loads(ws.recv())
    print("contextLimit:", ready.get("contextLimit"))
    events = []
    turns = []

    def run_turn(msg):
        ws.send(json.dumps({"t": "prompt", "text": msg}))
        t0 = time.time()
        rec = {"prompt": msg[:60], "compactions": [], "frames": 0, "text": "", "tools": [], "first_token_s": None}
        while time.time() - t0 < 600:
            ws.settimeout(15)
            try:
                ev = json.loads(ws.recv())
            except websocket.WebSocketTimeoutException:
                continue
            ev["ts"] = round(time.time() - t0, 2)
            events.append(ev)
            t = ev["t"]
            if t == "approval_request":
                ws.send(json.dumps({"t": "approval_reply", "id": ev["id"], "decision": "yes"}))
            elif t == "tool_started":
                rec["tools"].append(ev.get("tool"))
            elif t == "compacting":
                rec["frames"] += 1
            elif t == "compacted":
                rec["compactions"].append({"ts": ev["ts"], "before": ev.get("tokensBefore"), "after": ev.get("tokensAfter"), "summarized": ev.get("summarized"), "sync": rec["frames"] > 0})
                rec["frames"] = 0
            elif t == "text_delta":
                if rec["first_token_s"] is None:
                    rec["first_token_s"] = ev["ts"]
                rec["text"] += ev.get("text", "")
            elif t == "failed":
                rec["failed"] = ev.get("reason")
            elif t == "busy" and ev.get("busy") is False:
                rec["tokens"] = ev.get("tokens")
                break
        rec["elapsed_s"] = round(time.time() - t0, 1)
        turns.append(rec)
        return rec

    for i, f in enumerate(files):
        act = "，然后把要点追加写进 notes.md（用 write_file 重写整个文件、保留已有内容）" if i % 2 == 1 else ""
        rec = run_turn(f"读 {f}，用不少于 {args.words} 字复述它的主要内容{act}。")
        print(f"turn{i+1} {f}: tokens_after={rec.get('tokens')} first_token={rec['first_token_s']}s elapsed={rec['elapsed_s']}s "
              f"compactions={[(c['before'], c['after'], c['summarized'], 'SYNC' if c['sync'] else 'silent') for c in rec['compactions']]} {rec.get('failed') or ''}")
    rec = run_turn("不要读任何文件、不要调用工具，只凭记忆回答：1）我们最初的目标/第一句话是什么？2）到目前为止你写过或改过哪些文件？3）你读过哪些文件？逐条列出。")
    txt = rec["text"]
    print("recall: notes.md mentioned:", "notes.md" in txt, "| read files mentioned:", sum(1 for f in files if f in txt), "/", len(files), "| tools:", rec["tools"])
    print("answer:", txt[:400].replace("\n", " "))
    json.dump({"turns": turns, "events": events}, open(os.path.join(run_dir, "result.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    ws.close()
    sh(["docker", "rm", "-f", name])


if __name__ == "__main__":
    main()
