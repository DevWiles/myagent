#!/usr/bin/env python3
"""从 Exercism python 题库生成 50 个任务（与 make_tasks.py 的 16 个手写任务并列，目录同为 bench/tasks/）。

两类：
  feature（25）：给说明 + 桩文件 + 约 40% 的测试用例（可见），其余测试用例隐藏；agent 要按说明实现。
  bugfix（25）：把参考解拷进桩文件位置，再用 AST 注入一个变异（改运算符/比较/常量/布尔），完整测试可见且初始失败；
                隐藏测试 = 同一份测试（成功标准仍是全过）。变异在沙盒里验证：原解全过、变异解至少挂一个且能导入。
用法：python3 bench/make_exercism_tasks.py --repo <exercism-python 目录> [--seed 7]
需要 Docker 镜像 myagent-sandbox:v2 来验证变异。
"""
import argparse
import ast
import json
import os
import random
import shutil
import subprocess
import sys
import textwrap

BENCH = os.path.dirname(os.path.abspath(__file__))
TASKS = os.path.join(BENCH, "tasks")


# ---------------------------------------------------------------- 变异
class Mutator(ast.NodeTransformer):
    CMP = {ast.Lt: ast.LtE, ast.LtE: ast.Lt, ast.Gt: ast.GtE, ast.GtE: ast.Gt, ast.Eq: ast.NotEq, ast.NotEq: ast.Eq}
    BIN = {ast.Add: ast.Sub, ast.Sub: ast.Add, ast.Mult: ast.FloorDiv, ast.Mod: ast.FloorDiv}
    BOOL = {ast.And: ast.Or, ast.Or: ast.And}

    def __init__(self, target_index, rng):
        self.sites = []
        self.target = target_index
        self.i = -1
        self.rng = rng
        self.applied = None

    def _hit(self):
        self.i += 1
        return self.i == self.target

    def visit_Compare(self, node):
        self.generic_visit(node)
        if len(node.ops) == 1 and type(node.ops[0]) in self.CMP and self._hit():
            node.ops = [self.CMP[type(node.ops[0])]()]
            self.applied = "compare"
        return node

    def visit_BinOp(self, node):
        self.generic_visit(node)
        if type(node.op) in self.BIN and self._hit():
            node.op = self.BIN[type(node.op)]()
            self.applied = "binop"
        return node

    def visit_BoolOp(self, node):
        self.generic_visit(node)
        if type(node.op) in self.BOOL and self._hit():
            node.op = self.BOOL[type(node.op)]()
            self.applied = "boolop"
        return node

    def visit_Constant(self, node):
        if isinstance(node.value, bool):
            if self._hit():
                node.value = not node.value
                self.applied = "bool"
        elif isinstance(node.value, int) and not isinstance(node.value, bool) and 0 <= node.value <= 10:
            if self._hit():
                node.value = node.value + 1
                self.applied = "int+1"
        return node

    def visit_UnaryOp(self, node):
        self.generic_visit(node)
        if isinstance(node.op, ast.Not) and self._hit():
            self.applied = "drop-not"
            return node.operand
        return node


def count_sites(src):
    m = Mutator(-1, None)
    m.visit(ast.parse(src))
    return m.i + 1


def mutate(src, index):
    tree = ast.parse(src)
    m = Mutator(index, None)
    tree = m.visit(tree)
    ast.fix_missing_locations(tree)
    return ast.unparse(tree), m.applied


# ---------------------------------------------------------------- 测试拆分
def split_tests(test_src, visible_ratio, rng):
    """把 unittest 类里的 test 方法按比例拆成可见/隐藏两份源码。"""
    tree = ast.parse(test_src)
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef))
    tests = [n for n in cls.body if isinstance(n, ast.FunctionDef) and n.name.startswith("test")]
    others = [n for n in cls.body if n not in tests]
    n_vis = max(2, int(round(len(tests) * visible_ratio)))
    n_vis = min(n_vis, len(tests) - 2)
    idx = list(range(len(tests)))
    rng.shuffle(idx)
    vis = sorted(idx[:n_vis])
    hid = sorted(idx[n_vis:])
    header = [n for n in tree.body if n is not cls]

    def build(sel, suffix):
        c = ast.ClassDef(name=cls.name + suffix, bases=cls.bases, keywords=cls.keywords,
                         body=others + [tests[i] for i in sel], decorator_list=[])
        mod = ast.Module(body=header + [c], type_ignores=[])
        ast.fix_missing_locations(mod)
        return ast.unparse(mod) + "\n"

    return build(vis, ""), build(hid, "Hidden"), len(vis), len(hid)


def read(p):
    return open(p, encoding="utf-8").read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--n-feature", type=int, default=25)
    ap.add_argument("--n-bugfix", type=int, default=25)
    ap.add_argument("--usable", default=os.path.join(os.path.dirname(os.path.abspath(sys.argv[0])), "..", "..", "scratch-unused"))
    args = ap.parse_args()
    rng = random.Random(args.seed)
    usable = json.load(open(os.path.join(os.path.dirname(args.repo.rstrip("/")), "exercism_usable.json")))
    # 难度 2~7 里挑，避开最简单的 1（太容易）和 8/9（样本太少）
    pool = [u for u in usable if 2 <= u["difficulty"] <= 7]
    rng.shuffle(pool)
    pool.sort(key=lambda u: u["difficulty"])

    # 交错取，保证两类难度分布相近
    feature, bugfix = [], []
    for i, u in enumerate(pool):
        (feature if i % 2 == 0 else bugfix).append(u)

    index_path = os.path.join(TASKS, "index.json")
    index = json.load(open(index_path)) if os.path.exists(index_path) else []
    index = [t for t in index if not t["name"].startswith("ex_")]
    for d in os.listdir(TASKS):
        if d.startswith("ex_"):
            shutil.rmtree(os.path.join(TASKS, d))

    # ---- feature
    made_f = 0
    for u in feature:
        if made_f >= args.n_feature:
            break
        d = os.path.join(args.repo, "exercises", "practice", u["slug"])
        test_src = read(os.path.join(d, u["test"]))
        try:
            vis, hid, nv, nh = split_tests(test_src, 0.4, rng)
        except Exception:
            continue
        name = "ex_feat_" + u["slug"].replace("-", "_")
        out = os.path.join(TASKS, name)
        os.makedirs(os.path.join(out, "hidden"), exist_ok=True)
        shutil.copy(os.path.join(d, u["solution"]), os.path.join(out, u["solution"]))
        open(os.path.join(out, u["test"]), "w", encoding="utf-8").write(vis)
        open(os.path.join(out, "hidden", u["test"].replace("_test.py", "_hidden_test.py")), "w", encoding="utf-8").write(hid)
        docs = ""
        for fn in ("introduction.md", "instructions.md", "instructions.append.md"):
            p = os.path.join(d, ".docs", fn)
            if os.path.exists(p):
                docs += read(p).strip() + "\n\n"
        prompt = (f"在 {u['solution']} 中实现下面这道题（保持文件里已有的函数/类签名）。{u['test']} 是部分验收测试，通过它只是必要条件，"
                  f"验收时还会用更多按同一说明写的用例；不要改测试文件。\n\n" + docs.strip())
        open(os.path.join(out, "task.md"), "w", encoding="utf-8").write(prompt + "\n")
        index.append({"name": name, "kind": "feature", "difficulty": u["difficulty"], "source": "exercism", "slug": u["slug"],
                      "visible_tests": [u["test"]], "visible_count": nv, "hidden_count": nh})
        made_f += 1

    # ---- bugfix：先生成候选变异，再在沙盒里批量验证
    cand_dir = os.path.join(BENCH, "results", "_mutants")
    shutil.rmtree(cand_dir, ignore_errors=True)
    cands = []
    for u in bugfix:
        d = os.path.join(args.repo, "exercises", "practice", u["slug"])
        ex_src = read(os.path.join(d, u["example"]))
        try:
            n = count_sites(ex_src)
        except Exception:
            continue
        if n == 0:
            continue
        sites = list(range(n))
        rng.shuffle(sites)
        for k, s in enumerate(sites[:4]):
            try:
                msrc, kind = mutate(ex_src, s)
            except Exception:
                continue
            if not kind or msrc.strip() == ast.unparse(ast.parse(ex_src)).strip():
                continue
            cd = os.path.join(cand_dir, f"{u['slug']}__{k}")
            os.makedirs(cd, exist_ok=True)
            open(os.path.join(cd, u["solution"]), "w", encoding="utf-8").write(msrc + "\n")
            open(os.path.join(cd, "original_" + u["solution"]), "w", encoding="utf-8").write(ex_src)
            shutil.copy(os.path.join(d, u["test"]), os.path.join(cd, u["test"]))
            cands.append({"u": u, "dir": cd, "kind": kind, "site": s})
    # 沙盒验证脚本：原解全过 && 变异解失败(非导入错误)
    script = textwrap.dedent("""
        import os, subprocess, sys, json, shutil
        res = {}
        for name in sorted(os.listdir('/m')):
            d = os.path.join('/m', name)
            if not os.path.isdir(d):
                continue
            sol = [f for f in os.listdir(d) if f.endswith('.py') and not f.endswith('_test.py') and not f.startswith('original_')][0]
            test = [f for f in os.listdir(d) if f.endswith('_test.py')][0]
            def run():
                try:
                    r = subprocess.run([sys.executable, '-m', 'pytest', '-q', '-x', '--no-header', '-p', 'no:cacheprovider', test], cwd=d, capture_output=True, text=True, timeout=45)
                    return r.returncode, r.stdout[-400:]
                except subprocess.TimeoutExpired:
                    return 99, 'TIMEOUT'
            mrc, mout = run()
            shutil.copy(os.path.join(d, sol), os.path.join(d, sol + '.mut'))
            shutil.copy(os.path.join(d, 'original_' + sol), os.path.join(d, sol))
            orc, oout = run()
            shutil.copy(os.path.join(d, sol + '.mut'), os.path.join(d, sol))
            res[name] = {'orig_rc': orc, 'mut_rc': mrc, 'timeout': orc == 99 or mrc == 99, 'mut_import_error': ('ImportError' in mout or 'SyntaxError' in mout or 'collected 0' in mout or 'error' in mout.lower() and 'failed' not in mout), 'mut_tail': mout[-160:]}
            json.dump(res, open('/m/_verify.json', 'w'))
        json.dump(res, open('/m/_verify.json', 'w'))
    """)
    open(os.path.join(cand_dir, "_verify.py"), "w").write(script)
    subprocess.run(["docker", "run", "--rm", "-v", f"{cand_dir}:/m", "-w", "/m", "myagent-sandbox:v2", "python3", "/m/_verify.py"], check=True, timeout=1800)
    verify = json.load(open(os.path.join(cand_dir, "_verify.json")))

    made_b = 0
    used = set()
    for c in cands:
        if made_b >= args.n_bugfix:
            break
        u = c["u"]
        if u["slug"] in used:
            continue
        v = verify.get(os.path.basename(c["dir"]))
        if not v or v["orig_rc"] != 0 or v["mut_rc"] == 0 or v["mut_import_error"] or v.get("timeout"):
            continue
        used.add(u["slug"])
        name = "ex_bug_" + u["slug"].replace("-", "_")
        out = os.path.join(TASKS, name)
        os.makedirs(os.path.join(out, "hidden"), exist_ok=True)
        shutil.copy(os.path.join(c["dir"], u["solution"]), os.path.join(out, u["solution"]))
        d = os.path.join(args.repo, "exercises", "practice", u["slug"])
        shutil.copy(os.path.join(d, u["test"]), os.path.join(out, u["test"]))
        shutil.copy(os.path.join(d, u["test"]), os.path.join(out, "hidden", u["test"].replace("_test.py", "_hidden_test.py")))
        docs = ""
        p = os.path.join(d, ".docs", "instructions.md")
        if os.path.exists(p):
            docs = read(p).strip()
        prompt = (f"{u['test']} 里有测试失败了。{u['solution']} 是这道题的实现，里面有一处 bug，找到并修复它，让全部测试通过；不要改测试文件。\n\n"
                  f"题目说明：\n\n" + docs)
        open(os.path.join(out, "task.md"), "w", encoding="utf-8").write(prompt + "\n")
        index.append({"name": name, "kind": "bugfix", "difficulty": u["difficulty"], "source": "exercism", "slug": u["slug"],
                      "visible_tests": [u["test"]], "mutation": c["kind"], "visible_count": u["tests"], "hidden_count": u["tests"]})
        made_b += 1

    json.dump(index, open(index_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"feature {made_f}, bugfix {made_b}（候选变异 {len(cands)}，验证通过 {sum(1 for v in verify.values() if v['orig_rc']==0 and v['mut_rc']!=0 and not v['mut_import_error'])}）；index 共 {len(index)} 个任务")


if __name__ == "__main__":
    main()
