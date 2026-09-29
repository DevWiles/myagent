#!/usr/bin/env python3
"""上下文压缩实测：把窗口调小（MYAGENT_CONTEXT_TOKENS），让一个多文件阅读任务反复触发三档压缩，记录
tokensBefore→tokensAfter、是否有卡顿（有 compacting 进度帧 = 同步压缩），然后第二轮不读文件只凭记忆问"最初目标 + 改过的文件"，
核对钉住块是否生效。

用法：python3 bench/compact_test.py [--window 12000] [--files 14]
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
    ap.add_argument("--window", type=int, default=12000)
    ap.add_argument("--files", type=int, default=14)
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--force-compact", action="store_true", help="第一轮结束后显式发 compact，逼出摘要路径")
    args = ap.parse_args()
    run_dir = os.path.join(BENCH, "results", f"compact-w{args.window}")
    shutil.rmtree(run_dir, ignore_errors=True)
    work = os.path.join(run_dir, "work")
    shutil.copytree(os.path.join(BENCH, "compact-work"), work)
    for f in sorted(os.listdir(work))[args.files:]:
        os.remove(os.path.join(work, f))
    files = sorted(f for f in os.listdir(work) if f.endswith(".md"))
    os.chmod(work, 0o777)
    home = os.path.join(run_dir, "home")
    os.makedirs(os.path.join(home, ".myagent"))
    cfg = json.load(open(os.path.expanduser("~/.myagent/config.json")))
    json.dump({"apiKey": cfg["apiKey"], "baseUrl": cfg.get("baseUrl", "https://api.deepseek.com"), "model": cfg.get("model", "deepseek-chat"), **({"thinking": cfg["thinking"]} if "thinking" in cfg else {})},
              open(os.path.join(home, ".myagent", "config.json"), "w"))
    json.dump({"version": 1, "allow": [], "deny": []}, open(os.path.join(home, ".myagent", "policy.json"), "w"))
    name = f"compact-bench-{uuid.uuid4().hex[:8]}"
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
    print("contextLimit:", ready.get("contextLimit"), "| features:", [f for f in ready.get("features", []) if "compact" in f])

    goal = ("目标：依次用 read_file 完整阅读 " + "、".join(files) +
            " 这 %d 个文件，每读完一个就把该文件的一句话摘要追加到 summary.md（用 write_file 重写整个文件，保留之前的行）。"
            "全部读完并写完后回复“完成”。不要跳过任何文件，不要用 bash 读文件。" % len(files))
    events = []
    log = {"window": args.window, "files": files, "turns": []}

    def run_turn(msg):
        ws.send(json.dumps({"t": "prompt", "text": msg}))
        t0 = time.time()
        rec = {"prompt": msg[:80], "compactions": [], "compacting_frames": 0, "steps": [], "text": "", "tools": []}
        last_step_ts = t0
        while time.time() - t0 < args.timeout:
            ws.settimeout(15)
            try:
                raw = ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            ev = json.loads(raw)
            ev["ts"] = round(time.time() - t0, 2)
            events.append(ev)
            t = ev["t"]
            if t == "approval_request":
                ws.send(json.dumps({"t": "approval_reply", "id": ev["id"], "decision": "yes"}))
            elif t == "thinking":
                rec["steps"].append({"step": ev.get("step"), "ts": ev["ts"], "gap_s": round(time.time() - last_step_ts, 2)})
                last_step_ts = time.time()
            elif t == "tool_started":
                rec["tools"].append(ev.get("tool"))
            elif t == "compacting":
                rec["compacting_frames"] += 1
            elif t == "compacted":
                rec["compactions"].append({"ts": ev["ts"], "before": ev.get("tokensBefore"), "after": ev.get("tokensAfter"),
                                           "summarized": ev.get("summarized"), "synchronous": rec["compacting_frames"] > 0})
                rec["compacting_frames"] = 0
            elif t == "text_delta":
                rec["text"] += ev.get("text", "")
            elif t == "busy" and ev.get("busy") is False:
                rec["final_tokens"] = ev.get("tokens")
                rec["sent_tokens"] = ev.get("sentTokens")
                rec["duration_ms"] = ev.get("durationMs")
                break
            elif t == "failed":
                rec["failed"] = ev.get("reason")   # 不 break：还要等 busy:false，否则下一条命令会被"正在处理中"拒掉
        rec["elapsed_s"] = round(time.time() - t0, 1)
        log["turns"].append(rec)
        return rec

    r1 = run_turn(goal)
    print("turn1: steps", len(r1["steps"]), "| tools", len(r1["tools"]), "| compactions", len(r1["compactions"]), "| elapsed", r1["elapsed_s"], "s | failed:", r1.get("failed"))
    for c in r1["compactions"]:
        print("   compacted @%ss: %s -> %s (%.0f%%) summarized=%s %s" % (c["ts"], c["before"], c["after"], 100 * (1 - (c["after"] or 0) / max(1, c["before"] or 1)), c["summarized"], "SYNC(有进度帧=卡顿)" if c["synchronous"] else "silent(后台现货)"))
    summary_path = os.path.join(work, "summary.md")
    lines = [l for l in open(summary_path, encoding="utf-8").read().splitlines() if l.strip()] if os.path.exists(summary_path) else []
    print("summary.md lines:", len(lines), "/ expected", len(files))

    if args.force_compact:
        # 显式 /compact：这是会发 compacting 进度帧、用户等得到的同步摘要路径
        ws.send(json.dumps({"t": "compact"}))
        t0 = time.time()
        frames = 0
        while time.time() - t0 < 300:
            ws.settimeout(15)
            try:
                ev = json.loads(ws.recv())
            except websocket.WebSocketTimeoutException:
                continue
            ev["ts"] = round(time.time() - t0, 2)
            events.append(ev)
            if ev["t"] == "compacting":
                frames += 1
            elif ev["t"] == "compacted":
                log["forced_compact"] = {"before": ev.get("tokensBefore"), "after": ev.get("tokensAfter"), "summarized": ev.get("summarized"), "frames": frames}
            elif ev["t"] == "busy" and ev.get("busy") is False:
                log["forced_compact"] = dict(log.get("forced_compact") or {}, elapsed_s=round(time.time() - t0, 1), tokens_after_busy=ev.get("tokens"))
                break
            elif ev["t"] == "error":
                log["forced_compact"] = {"error": ev.get("message")}
                break
        fc = log.get("forced_compact") or {}
        print("forced /compact:", fc.get("before"), "->", fc.get("after"), "summarized=%s frames=%s elapsed=%ss" % (fc.get("summarized"), fc.get("frames"), fc.get("elapsed_s")), fc.get("error") or "")

    r2 = run_turn("不要读任何文件、不要调用工具，只凭你现在记得的内容回答两个问题：1）我们最初的目标是什么？2）到目前为止你写过或改过哪些文件？请逐条列出文件名。")
    txt = r2["text"]
    mentioned = [f for f in files if f in txt]
    print("turn2 tools used:", r2["tools"], "| mentions summary.md:", "summary.md" in txt, "| goal keywords:", all(k in txt for k in ("摘要", "summary.md")), "| files mentioned:", len(mentioned), "/", len(files))
    print("turn2 answer:", txt[:500].replace("\n", " "))
    log["summary_lines"] = len(lines)
    log["turn2_mentions"] = {"summary_md": "summary.md" in txt, "files": len(mentioned)}
    json.dump({"log": log, "events": events}, open(os.path.join(run_dir, "result.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    ws.close()
    sh(["docker", "rm", "-f", name])


if __name__ == "__main__":
    main()
