#!/usr/bin/env python3
"""生成任务成功率评测用的任务集：bench/tasks/<name>/{task.md, src/, tests/, hidden/}

- task.md   给 agent 的任务描述（即发给后端的 prompt / goal）
- src/      初始代码（有 bug 或缺实现）
- tests/    agent 可见的测试（初始状态应当失败）
- hidden/   验收用的隐藏测试，运行时才拷进容器；成功 = tests + hidden 全部通过
每个任务标注 kind（bugfix / feature / refactor / multi）和 difficulty（1~3），便于分组统计。
"""
import json
import os
import shutil
import textwrap

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tasks")

T = {}


def task(name, kind, difficulty, prompt, files):
    T[name] = dict(kind=kind, difficulty=difficulty, prompt=textwrap.dedent(prompt).strip(), files={k: textwrap.dedent(v).lstrip("\n") for k, v in files.items()})


# ---------------------------------------------------------------- 1 bugfix: 分页 off-by-one
task("paginate_offbyone", "bugfix", 1, """
    tests/test_paginate.py 里有测试挂了。修复 src/paginate.py 里的分页函数，让全部测试通过。不要改测试。
""", {
    "src/__init__.py": "",
    "src/paginate.py": """
    def paginate(items, page, page_size):
        \"\"\"返回第 page 页（从 1 开始）的元素列表；page 超出范围返回空列表。\"\"\"
        if page < 1 or page_size < 1:
            raise ValueError("page and page_size must be >= 1")
        start = page * page_size
        end = start + page_size
        return items[start:end]


    def total_pages(n_items, page_size):
        if page_size < 1:
            raise ValueError("page_size must be >= 1")
        return n_items // page_size
    """,
    "tests/test_paginate.py": """
    from src.paginate import paginate, total_pages

    def test_first_page():
        assert paginate(list(range(10)), 1, 3) == [0, 1, 2]

    def test_last_partial_page():
        assert paginate(list(range(10)), 4, 3) == [9]

    def test_total_pages():
        assert total_pages(10, 3) == 4
        assert total_pages(9, 3) == 3
    """,
    "hidden/test_hidden.py": """
    import pytest
    from src.paginate import paginate, total_pages

    def test_out_of_range():
        assert paginate([1, 2, 3], 5, 2) == []

    def test_zero_items():
        assert total_pages(0, 5) == 0
        assert paginate([], 1, 5) == []

    def test_invalid():
        with pytest.raises(ValueError):
            paginate([1], 0, 1)
    """,
})

# ---------------------------------------------------------------- 2 feature: LRU cache
task("lru_cache", "feature", 2, """
    在 src/lru.py 里实现 LRUCache 类：构造参数 capacity；get(key) 命中返回值并把 key 标记为最近使用，未命中返回 None；
    put(key, value) 写入，超出容量时淘汰最久未使用的键；len(cache) 返回当前条目数；支持 `key in cache`（不影响新旧顺序）。
    要求 get/put 均为 O(1)。tests/test_lru.py 是验收测试，不要改测试。
""", {
    "src/__init__.py": "",
    "src/lru.py": """
    class LRUCache:
        \"\"\"TODO: 实现\"\"\"

        def __init__(self, capacity: int):
            raise NotImplementedError
    """,
    "tests/test_lru.py": """
    from src.lru import LRUCache

    def test_basic():
        c = LRUCache(2)
        c.put("a", 1); c.put("b", 2)
        assert c.get("a") == 1
        c.put("c", 3)  # 淘汰 b
        assert c.get("b") is None
        assert c.get("c") == 3
        assert len(c) == 2

    def test_update_moves_to_recent():
        c = LRUCache(2)
        c.put("a", 1); c.put("b", 2); c.put("a", 9)
        c.put("c", 3)  # 淘汰 b
        assert c.get("a") == 9 and c.get("b") is None
    """,
    "hidden/test_hidden.py": """
    import time
    from src.lru import LRUCache

    def test_contains_does_not_touch_order():
        c = LRUCache(2)
        c.put("a", 1); c.put("b", 2)
        assert "a" in c
        c.put("c", 3)  # a 仍是最久未用，应被淘汰
        assert c.get("a") is None and c.get("b") == 2

    def test_capacity_one():
        c = LRUCache(1)
        c.put("a", 1); c.put("b", 2)
        assert c.get("a") is None and c.get("b") == 2 and len(c) == 1

    def test_o1_ish():
        c = LRUCache(10000)
        t = time.time()
        for i in range(200000):
            c.put(i % 20000, i)
            c.get((i * 7) % 20000)
        assert time.time() - t < 3.0
    """,
})

# ---------------------------------------------------------------- 3 bugfix: 配置解析
task("config_parse", "bugfix", 2, """
    src/config.py 的 parse_config 解析 KEY=VALUE 格式的配置文本，现有测试有失败。修复它：要正确处理注释行、空行、值里包含 '=' 的情况、
    键两侧的空白，以及 true/false/整数的类型转换。不要改测试。
""", {
    "src/__init__.py": "",
    "src/config.py": """
    def _convert(value):
        if value.isdigit():
            return int(value)
        if value == "true":
            return True
        if value == "false":
            return False
        return value


    def parse_config(text):
        result = {}
        for line in text.splitlines():
            if line.startswith("#"):
                continue
            key, value = line.split("=")
            result[key] = _convert(value)
        return result
    """,
    "tests/test_config.py": """
    from src.config import parse_config

    def test_basic_and_comments():
        cfg = parse_config("# comment\\nname = app\\n\\nport=8080\\ndebug=true\\n")
        assert cfg == {"name": "app", "port": 8080, "debug": True}

    def test_value_with_equals():
        assert parse_config("url=http://x/?a=1&b=2")["url"] == "http://x/?a=1&b=2"

    def test_whitespace_and_inline_comment_not_stripped():
        assert parse_config("  key  =  value  ") == {"key": "value"}
    """,
    "hidden/test_hidden.py": """
    from src.config import parse_config

    def test_negative_and_false():
        cfg = parse_config("n=-5\\nflag=false\\nz=0")
        assert cfg == {"n": -5, "flag": False, "z": 0}

    def test_blank_lines_and_crlf():
        assert parse_config("a=1\\r\\n\\r\\nb=2\\r\\n") == {"a": 1, "b": 2}

    def test_comment_with_leading_space():
        assert parse_config("   # c\\nk=v") == {"k": "v"}
    """,
})

# ---------------------------------------------------------------- 4 feature: 令牌桶限流
task("token_bucket", "feature", 2, """
    在 src/ratelimit.py 实现 TokenBucket(rate, capacity, clock=time.monotonic)：令牌以 rate 个/秒匀速补充、上限 capacity，
    初始满桶；allow(n=1) 在桶里有 >= n 个令牌时扣除并返回 True，否则返回 False 且不扣。
    clock 参数用于测试注入可控时间。tests/test_ratelimit.py 是验收测试，不要改测试。
""", {
    "src/__init__.py": "",
    "src/ratelimit.py": """
    import time


    class TokenBucket:
        def __init__(self, rate, capacity, clock=time.monotonic):
            raise NotImplementedError

        def allow(self, n=1):
            raise NotImplementedError
    """,
    "tests/test_ratelimit.py": """
    from src.ratelimit import TokenBucket

    class Clock:
        def __init__(self): self.t = 0.0
        def __call__(self): return self.t

    def test_burst_then_block():
        clk = Clock(); b = TokenBucket(rate=1, capacity=3, clock=clk)
        assert [b.allow() for _ in range(4)] == [True, True, True, False]

    def test_refill():
        clk = Clock(); b = TokenBucket(rate=2, capacity=2, clock=clk)
        assert b.allow(2)
        clk.t = 0.5
        assert b.allow() is True and b.allow() is False
    """,
    "hidden/test_hidden.py": """
    from src.ratelimit import TokenBucket

    class Clock:
        def __init__(self): self.t = 0.0
        def __call__(self): return self.t

    def test_cap_not_exceeded():
        clk = Clock(); b = TokenBucket(rate=10, capacity=2, clock=clk)
        clk.t = 100
        assert b.allow(2) and not b.allow()

    def test_partial_refill_no_deduct_on_reject():
        clk = Clock(); b = TokenBucket(rate=1, capacity=5, clock=clk)
        assert b.allow(5)
        clk.t = 1.5
        assert b.allow(2) is False   # 只有 1.5 个
        assert b.allow(1) is True    # 拒绝时不应扣掉
    """,
})

# ---------------------------------------------------------------- 5 bugfix: 递归深度
task("flatten_recursion", "bugfix", 1, """
    src/flatten.py 的 flatten 在深层嵌套时会 RecursionError（tests 里有个 5000 层的用例）。改成不依赖递归深度的实现，保持输出顺序不变。不要改测试。
""", {
    "src/__init__.py": "",
    "src/flatten.py": """
    def flatten(nested):
        \"\"\"把任意深度嵌套的 list/tuple 展平成一个 list，保持从左到右的顺序。\"\"\"
        out = []
        for x in nested:
            if isinstance(x, (list, tuple)):
                out.extend(flatten(x))
            else:
                out.append(x)
        return out
    """,
    "tests/test_flatten.py": """
    from src.flatten import flatten

    def test_basic():
        assert flatten([1, [2, (3, [4])], 5]) == [1, 2, 3, 4, 5]

    def test_deep():
        x = 0
        for i in range(5000):
            x = [i, x]
        out = flatten(x)
        assert len(out) == 5001 and out[0] == 4999 and out[-1] == 0
    """,
    "hidden/test_hidden.py": """
    from src.flatten import flatten

    def test_empty_and_strings():
        assert flatten([]) == []
        assert flatten([[], [[]], "ab", ["cd"]]) == ["ab", "cd"]

    def test_order_wide():
        assert flatten([[1, 2], [3, [4, 5]], 6]) == [1, 2, 3, 4, 5, 6]
    """,
})

# ---------------------------------------------------------------- 6 feature: 重试装饰器
task("retry_decorator", "feature", 2, """
    在 src/retry.py 实现装饰器 retry(times, exceptions=(Exception,), backoff=0.0, sleep=time.sleep)：
    被装饰函数抛出 exceptions 里的异常时最多重试 times 次（总调用次数 times+1），每次重试前调用 sleep(backoff * 2**(attempt-1))，
    attempt 从 1 开始；不在 exceptions 里的异常直接抛出；重试耗尽后抛出最后一次的异常。用 functools.wraps 保留函数名。
    tests/test_retry.py 是验收测试，不要改测试。
""", {
    "src/__init__.py": "",
    "src/retry.py": """
    import time


    def retry(times, exceptions=(Exception,), backoff=0.0, sleep=time.sleep):
        raise NotImplementedError
    """,
    "tests/test_retry.py": """
    import pytest
    from src.retry import retry

    def test_eventually_succeeds():
        calls = []
        @retry(times=3)
        def f():
            calls.append(1)
            if len(calls) < 3:
                raise ValueError("x")
            return "ok"
        assert f() == "ok" and len(calls) == 3

    def test_exhausted_raises_last():
        @retry(times=2, exceptions=(KeyError,))
        def f():
            raise KeyError("k")
        with pytest.raises(KeyError):
            f()

    def test_backoff_schedule():
        slept = []
        @retry(times=3, backoff=0.1, sleep=slept.append)
        def f():
            raise RuntimeError
        with pytest.raises(RuntimeError):
            f()
        assert [round(s, 3) for s in slept] == [0.1, 0.2, 0.4]
    """,
    "hidden/test_hidden.py": """
    import pytest
    from src.retry import retry

    def test_unlisted_exception_not_retried():
        calls = []
        @retry(times=5, exceptions=(ValueError,))
        def f():
            calls.append(1); raise TypeError
        with pytest.raises(TypeError):
            f()
        assert len(calls) == 1

    def test_wraps_and_args():
        @retry(times=1)
        def add(a, b=1):
            \"\"\"doc\"\"\"
            return a + b
        assert add.__name__ == "add" and add.__doc__ == "doc" and add(2, b=3) == 5

    def test_zero_times():
        calls = []
        @retry(times=0)
        def f():
            calls.append(1); raise ValueError
        with pytest.raises(ValueError):
            f()
        assert len(calls) == 1
    """,
})

# ---------------------------------------------------------------- 7 bugfix: 路径穿越
task("safe_path", "bugfix", 2, """
    src/files.py 的 resolve_under(root, user_path) 应该保证返回的路径一定在 root 目录内，否则抛 PermissionError。
    现有实现能被 '../' 和绝对路径绕过，tests 里有失败用例。修复它，注意符号链接以外的情况都要覆盖（'..'、绝对路径、'./'、重复斜杠、以 root 名为前缀的兄弟目录）。不要改测试。
""", {
    "src/__init__.py": "",
    "src/files.py": """
    import os


    def resolve_under(root, user_path):
        root = os.path.abspath(root)
        full = os.path.join(root, user_path)
        if not full.startswith(root):
            raise PermissionError(user_path)
        return full
    """,
    "tests/test_files.py": """
    import os, pytest
    from src.files import resolve_under

    ROOT = "/srv/data"

    def test_ok():
        assert resolve_under(ROOT, "a/b.txt") == "/srv/data/a/b.txt"

    def test_dotdot():
        with pytest.raises(PermissionError):
            resolve_under(ROOT, "../etc/passwd")

    def test_absolute():
        with pytest.raises(PermissionError):
            resolve_under(ROOT, "/etc/passwd")

    def test_sibling_prefix():
        with pytest.raises(PermissionError):
            resolve_under(ROOT, "../data2/x")
    """,
    "hidden/test_hidden.py": """
    import pytest
    from src.files import resolve_under

    ROOT = "/srv/data"

    def test_nested_dotdot_inside_ok():
        assert resolve_under(ROOT, "a/../b.txt") == "/srv/data/b.txt"

    def test_double_slash_and_dot():
        assert resolve_under(ROOT, ".//a//b") == "/srv/data/a/b"

    def test_root_itself():
        assert resolve_under(ROOT, ".") == "/srv/data"

    def test_escape_after_deep():
        with pytest.raises(PermissionError):
            resolve_under(ROOT, "a/b/../../../x")
    """,
})

# ---------------------------------------------------------------- 8 refactor: 保持测试绿
task("refactor_report", "refactor", 2, """
    src/report.py 里的 build_report 是一个 60 行的大函数，把统计、格式化、输出三块混在一起。
    把它重构成至少三个职责单一的函数（例如 summarize / format_rows / render），build_report 只做编排；
    保持 tests/test_report.py 全部通过、输出逐字节不变。不要改测试；不要改变函数签名 build_report(records) -> str。
""", {
    "src/__init__.py": "",
    "src/report.py": """
    def build_report(records):
        # records: list of dict(name, dept, salary)
        depts = {}
        for r in records:
            d = r["dept"]
            if d not in depts:
                depts[d] = {"count": 0, "total": 0, "max": None, "names": []}
            depts[d]["count"] += 1
            depts[d]["total"] += r["salary"]
            if depts[d]["max"] is None or r["salary"] > depts[d]["max"]["salary"]:
                depts[d]["max"] = r
            depts[d]["names"].append(r["name"])
        lines = []
        lines.append("DEPT       COUNT   AVG      TOP")
        lines.append("-" * 36)
        for d in sorted(depts):
            info = depts[d]
            avg = info["total"] / info["count"]
            top = info["max"]["name"]
            line = d.ljust(10) + " " + str(info["count"]).rjust(5) + "   " + ("%.1f" % avg).rjust(6) + "   " + top
            lines.append(line)
        lines.append("-" * 36)
        total_count = 0
        total_sum = 0
        for d in depts:
            total_count += depts[d]["count"]
            total_sum += depts[d]["total"]
        if total_count:
            lines.append("TOTAL".ljust(10) + " " + str(total_count).rjust(5) + "   " + ("%.1f" % (total_sum / total_count)).rjust(6))
        else:
            lines.append("TOTAL".ljust(10) + " " + "0".rjust(5) + "   " + "0.0".rjust(6))
        out = ""
        for i, line in enumerate(lines):
            out += line
            if i != len(lines) - 1:
                out += "\\n"
        return out
    """,
    "tests/test_report.py": """
    from src.report import build_report

    RECS = [
        {"name": "ann", "dept": "eng", "salary": 100},
        {"name": "bob", "dept": "eng", "salary": 300},
        {"name": "cid", "dept": "ops", "salary": 150},
    ]

    EXPECTED = (
        "DEPT       COUNT   AVG      TOP\\n"
        "------------------------------------\\n"
        "eng            2    200.0   bob\\n"
        "ops            1    150.0   cid\\n"
        "------------------------------------\\n"
        "TOTAL          3    183.3"
    )

    def test_report_exact():
        assert build_report(RECS) == EXPECTED

    def test_empty():
        assert build_report([]).endswith("TOTAL          0      0.0")
    """,
    "hidden/test_hidden.py": """
    import inspect
    import src.report as m
    from src.report import build_report

    def test_split_into_functions():
        funcs = [f for f, o in inspect.getmembers(m, inspect.isfunction) if o.__module__ == m.__name__]
        assert len(funcs) >= 4, funcs
        assert len(inspect.getsource(build_report).splitlines()) <= 20

    def test_single_record():
        out = build_report([{"name": "z", "dept": "x", "salary": 7}])
        assert "x              1      7.0   z" in out and out.endswith("TOTAL          1      7.0")
    """,
})

# ---------------------------------------------------------------- 9 feature: 罗马数字
task("roman", "feature", 1, """
    在 src/roman.py 实现 to_roman(n) 和 from_roman(s)：支持 1~3999，标准减法记法（IV、IX、XL、XC、CD、CM）；
    from_roman 对非法输入（空串、非法字符、不规范写法如 'IIII'、'VX'）抛 ValueError。tests/test_roman.py 是验收测试，不要改测试。
""", {
    "src/__init__.py": "",
    "src/roman.py": """
    def to_roman(n: int) -> str:
        raise NotImplementedError


    def from_roman(s: str) -> int:
        raise NotImplementedError
    """,
    "tests/test_roman.py": """
    import pytest
    from src.roman import to_roman, from_roman

    def test_to():
        assert to_roman(1994) == "MCMXCIV" and to_roman(4) == "IV" and to_roman(3999) == "MMMCMXCIX"

    def test_from():
        assert from_roman("MCMXCIV") == 1994 and from_roman("XLII") == 42

    def test_invalid():
        for s in ["", "IIII", "VX", "ABC"]:
            with pytest.raises(ValueError):
                from_roman(s)
    """,
    "hidden/test_hidden.py": """
    import pytest
    from src.roman import to_roman, from_roman

    def test_roundtrip_all():
        for n in range(1, 4000):
            assert from_roman(to_roman(n)) == n

    def test_range():
        for n in (0, 4000, -1):
            with pytest.raises(ValueError):
                to_roman(n)

    def test_more_invalid():
        for s in ["IL", "IC", "XM", "VV", "LL", "DD", "MMMM"]:
            with pytest.raises(ValueError):
                from_roman(s)
    """,
})

# ---------------------------------------------------------------- 10 bugfix: 线程安全计数器
task("threadsafe_counter", "bugfix", 2, """
    src/counter.py 的 Counter 在多线程下会丢更新，tests/test_counter.py 用 8 个线程各加 20000 次来检验。
    修复它（加锁即可），同时保证 snapshot() 返回的是某一时刻的一致快照（所有 key 同时读取）。不要改测试。
""", {
    "src/__init__.py": "",
    "src/counter.py": """
    class Counter:
        def __init__(self):
            self._data = {}

        def incr(self, key, by=1):
            current = self._data.get(key, 0)
            self._data[key] = current + by

        def get(self, key):
            return self._data.get(key, 0)

        def snapshot(self):
            return dict(self._data)
    """,
    "tests/test_counter.py": """
    import sys, threading
    from src.counter import Counter

    sys.setswitchinterval(1e-6)  # 放大线程切换频率，让丢更新稳定复现

    def test_concurrent_incr():
        c = Counter()
        def work():
            for _ in range(20000):
                c.incr("k")
        ts = [threading.Thread(target=work) for _ in range(8)]
        [t.start() for t in ts]; [t.join() for t in ts]
        assert c.get("k") == 160000
    """,
    "hidden/test_hidden.py": """
    import sys, threading
    from src.counter import Counter

    sys.setswitchinterval(1e-6)

    def test_multi_key():
        c = Counter()
        def work(i):
            for _ in range(5000):
                c.incr("a"); c.incr("b", 2)
        ts = [threading.Thread(target=work, args=(i,)) for i in range(6)]
        [t.start() for t in ts]; [t.join() for t in ts]
        assert c.snapshot() == {"a": 30000, "b": 60000}

    def test_snapshot_is_copy():
        c = Counter(); c.incr("x")
        s = c.snapshot(); s["x"] = 99
        assert c.get("x") == 1
    """,
})

# ---------------------------------------------------------------- 11 feature: 事件总线
task("event_bus", "feature", 2, """
    在 src/bus.py 实现 EventBus：subscribe(topic, handler) 返回一个可调用的取消函数；publish(topic, payload) 按订阅顺序同步调用所有 handler(payload)；
    支持通配订阅 "a.*" 匹配 "a.b"（只匹配一级，不匹配 "a.b.c"）和 "a.#" 匹配任意多级；某个 handler 抛异常不影响其他 handler，异常收集后由 publish 返回 list。
    tests/test_bus.py 是验收测试，不要改测试。
""", {
    "src/__init__.py": "",
    "src/bus.py": """
    class EventBus:
        def __init__(self):
            raise NotImplementedError

        def subscribe(self, topic, handler):
            raise NotImplementedError

        def publish(self, topic, payload):
            raise NotImplementedError
    """,
    "tests/test_bus.py": """
    from src.bus import EventBus

    def test_basic_and_unsubscribe():
        bus = EventBus(); got = []
        off = bus.subscribe("x", got.append)
        bus.publish("x", 1); off(); bus.publish("x", 2)
        assert got == [1]

    def test_wildcards():
        bus = EventBus(); got = []
        bus.subscribe("a.*", lambda p: got.append(("star", p)))
        bus.subscribe("a.#", lambda p: got.append(("hash", p)))
        bus.publish("a.b", 1); bus.publish("a.b.c", 2)
        assert got == [("star", 1), ("hash", 1), ("hash", 2)]

    def test_errors_isolated():
        bus = EventBus(); got = []
        def bad(p): raise ValueError("boom")
        bus.subscribe("t", bad); bus.subscribe("t", got.append)
        errs = bus.publish("t", 5)
        assert got == [5] and len(errs) == 1 and isinstance(errs[0], ValueError)
    """,
    "hidden/test_hidden.py": """
    from src.bus import EventBus

    def test_order_and_multiple_unsub():
        bus = EventBus(); got = []
        off1 = bus.subscribe("t", lambda p: got.append(1))
        bus.subscribe("t", lambda p: got.append(2))
        bus.publish("t", None); off1(); off1(); bus.publish("t", None)
        assert got == [1, 2, 2]

    def test_star_does_not_match_root_or_deep():
        bus = EventBus(); got = []
        bus.subscribe("a.*", got.append)
        bus.publish("a", 1); bus.publish("a.b.c", 2); bus.publish("b.c", 3)
        assert got == []

    def test_no_subscribers_returns_empty():
        assert EventBus().publish("nothing", 1) == []
    """,
})

# ---------------------------------------------------------------- 12 multi: 多文件 CLI
task("cli_wordcount", "multi", 3, """
    这是一个小工具项目：src/wc/ 下 reader.py 负责读文件、stats.py 负责统计、cli.py 负责命令行。目前只有骨架。
    实现 `python -m src.wc.cli <文件>... [--top N] [--ignore-case]`：统计所有文件的总行数、总词数，以及出现频率最高的 N 个词（默认 5，按频率降序、频率相同按字典序），
    词按空白切分并去掉首尾标点 .,;:!?"'()；--ignore-case 时统一小写。输出格式见 tests/test_cli.py。文件不存在时向 stderr 打印 "error: <path> not found" 并以退出码 2 结束。
    tests 是验收测试，不要改测试。
""", {
    "src/__init__.py": "",
    "src/wc/__init__.py": "",
    "src/wc/reader.py": """
    def read_lines(path):
        \"\"\"TODO: 返回文件的行列表；不存在时抛 FileNotFoundError\"\"\"
        raise NotImplementedError
    """,
    "src/wc/stats.py": """
    def tokenize(line, ignore_case=False):
        raise NotImplementedError


    def top_words(lines, n=5, ignore_case=False):
        raise NotImplementedError
    """,
    "src/wc/cli.py": """
    import sys


    def main(argv=None):
        raise NotImplementedError


    if __name__ == "__main__":
        sys.exit(main())
    """,
    "tests/test_cli.py": """
    import subprocess, sys, os

    def run(args, cwd):
        return subprocess.run([sys.executable, "-m", "src.wc.cli", *args], cwd=cwd, capture_output=True, text=True)

    def test_output(tmp_path):
        (tmp_path / "a.txt").write_text("Hello world! hello, Bob.\\nbob bob\\n")
        (tmp_path / "b.txt").write_text("world\\n")
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        r = run([str(tmp_path / "a.txt"), str(tmp_path / "b.txt"), "--top", "2", "--ignore-case"], root)
        assert r.returncode == 0, r.stderr
        assert r.stdout == "lines: 3\\nwords: 7\\ntop:\\n  bob 3\\n  hello 2\\n"

    def test_missing_file(tmp_path):
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        r = run([str(tmp_path / "nope.txt")], root)
        assert r.returncode == 2 and "error:" in r.stderr and "not found" in r.stderr
    """,
    "hidden/test_hidden.py": """
    import subprocess, sys, os
    from src.wc.stats import tokenize, top_words

    def test_tokenize_punct():
        assert tokenize('"Hi," she said (quietly).') == ["Hi", "she", "said", "quietly"]

    def test_case_sensitive_default_and_tie_order():
        lines = ["b a B a", "c"]
        assert top_words(lines, 3) == [("a", 2), ("B", 1), ("b", 1)]
        assert top_words(lines, 2, ignore_case=True) == [("a", 2), ("b", 2)]

    def test_default_top5(tmp_path):
        (tmp_path / "f.txt").write_text(" ".join(f"w{i}" for i in range(10)) + "\\n")
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        r = subprocess.run([sys.executable, "-m", "src.wc.cli", str(tmp_path / "f.txt")], cwd=root, capture_output=True, text=True)
        assert r.returncode == 0 and r.stdout.count("\\n  ") == 5 and r.stdout.startswith("lines: 1\\nwords: 10\\n")
    """,
})

# ---------------------------------------------------------------- 13 bugfix: 日期解析
task("date_parse", "bugfix", 2, """
    src/dates.py 的 parse_date 要支持 'YYYY-MM-DD'、'DD/MM/YYYY'、'Mon DD, YYYY'（英文月份缩写）三种格式并返回 datetime.date；
    现有实现有多处错误（月日顺序、两位年、无效日期没有报错）。修复到 tests 全过：非法输入统一抛 ValueError。不要改测试。
""", {
    "src/__init__.py": "",
    "src/dates.py": """
    from datetime import date

    MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


    def parse_date(s):
        s = s.strip()
        if "-" in s:
            y, m, d = s.split("-")
            return date(int(y), int(m), int(d))
        if "/" in s:
            m, d, y = s.split("/")
            return date(int(y), int(m), int(d))
        parts = s.replace(",", "").split()
        return date(int(parts[2]), MONTHS.index(parts[0]), int(parts[1]))
    """,
    "tests/test_dates.py": """
    import pytest
    from datetime import date
    from src.dates import parse_date

    def test_iso():
        assert parse_date("2024-02-29") == date(2024, 2, 29)

    def test_dmy():
        assert parse_date("31/12/1999") == date(1999, 12, 31)

    def test_mon_dd_yyyy():
        assert parse_date("Mar 5, 2021") == date(2021, 3, 5)

    def test_invalid():
        for s in ["2023-02-30", "13/13/2020", "Foo 1, 2020", "2020-1", "", "12/31/1999"]:
            with pytest.raises(ValueError):
                parse_date(s)
    """,
    "hidden/test_hidden.py": """
    import pytest
    from datetime import date
    from src.dates import parse_date

    def test_whitespace_and_case():
        assert parse_date("  Dec 25, 2020 ") == date(2020, 12, 25)

    def test_leading_zeros():
        assert parse_date("01/02/2003") == date(2003, 2, 1)

    def test_two_digit_year_rejected():
        with pytest.raises(ValueError):
            parse_date("01/02/03")
    """,
})

# ---------------------------------------------------------------- 14 feature: 模板引擎
task("template_engine", "feature", 3, """
    在 src/tpl.py 实现 render(template, ctx)：支持 {{ var }} 变量替换（支持点号取属性/键，如 {{ user.name }}），
    {% if cond %}...{% else %}...{% endif %}（cond 为变量真值），{% for x in items %}...{% endfor %}（可嵌套，循环体内可用 {{ loop.index }} 从 1 计数）；
    变量不存在时渲染为空字符串；标签内外的空白按原样保留。tests/test_tpl.py 是验收测试，不要改测试。
""", {
    "src/__init__.py": "",
    "src/tpl.py": """
    def render(template: str, ctx: dict) -> str:
        raise NotImplementedError
    """,
    "tests/test_tpl.py": """
    from src.tpl import render

    def test_vars_and_dots():
        assert render("Hi {{ name }}, {{ user.name }}/{{ user.age }}", {"name": "A", "user": {"name": "B", "age": 3}}) == "Hi A, B/3"

    def test_missing_is_empty():
        assert render("[{{ nope }}][{{ a.b }}]", {"a": {}}) == "[][]"

    def test_if_else():
        t = "{% if ok %}yes{% else %}no{% endif %}"
        assert render(t, {"ok": True}) == "yes" and render(t, {"ok": 0}) == "no" and render(t, {}) == "no"

    def test_for_with_index():
        t = "{% for x in items %}{{ loop.index }}:{{ x }},{% endfor %}"
        assert render(t, {"items": ["a", "b"]}) == "1:a,2:b,"
    """,
    "hidden/test_hidden.py": """
    from src.tpl import render

    def test_nested_for_and_if():
        t = "{% for r in rows %}{% for c in r %}{% if c %}{{ c }}{% else %}-{% endif %}{% endfor %}|{% endfor %}"
        assert render(t, {"rows": [[1, 0], [0, 2]]}) == "1-|-2|"

    def test_whitespace_preserved():
        assert render("a  {{ x }}  b\\n", {"x": "y"}) == "a  y  b\\n"

    def test_attr_access():
        class U: name = "obj"
        assert render("{{ u.name }}", {"u": U()}) == "obj"
    """,
})

# ---------------------------------------------------------------- 15 bugfix: JSON 合并
task("deep_merge", "bugfix", 1, """
    src/merge.py 的 deep_merge(a, b) 应返回新字典：b 的值覆盖 a，嵌套 dict 递归合并，list 不合并直接用 b 的；不能修改入参。
    现有实现会修改入参且嵌套合并有 bug，tests 有失败。修复。不要改测试。
""", {
    "src/__init__.py": "",
    "src/merge.py": """
    def deep_merge(a, b):
        for k, v in b.items():
            if k in a and isinstance(v, dict):
                deep_merge(a[k], v)
            else:
                a[k] = v
        return a
    """,
    "tests/test_merge.py": """
    from src.merge import deep_merge

    def test_no_mutation():
        a = {"x": {"y": 1}}; b = {"x": {"z": 2}}
        out = deep_merge(a, b)
        assert out == {"x": {"y": 1, "z": 2}} and a == {"x": {"y": 1}} and b == {"x": {"z": 2}}

    def test_dict_over_scalar():
        assert deep_merge({"x": 1}, {"x": {"y": 2}}) == {"x": {"y": 2}}

    def test_list_replaced():
        assert deep_merge({"l": [1, 2]}, {"l": [3]}) == {"l": [3]}
    """,
    "hidden/test_hidden.py": """
    from src.merge import deep_merge

    def test_scalar_over_dict():
        assert deep_merge({"x": {"y": 2}}, {"x": 1}) == {"x": 1}

    def test_deep_copy_of_nested_from_a():
        a = {"x": {"y": {"z": 1}}}
        out = deep_merge(a, {})
        out["x"]["y"]["z"] = 9
        assert a["x"]["y"]["z"] == 1
    """,
})

# ---------------------------------------------------------------- 16 multi: 库存服务 + 校验
task("inventory_service", "multi", 3, """
    实现一个小型库存服务，分三个文件：src/inv/models.py（Item 数据类：sku、name、qty、price）、src/inv/store.py（InMemoryStore：add/get/list/adjust_qty）、
    src/inv/service.py（InventoryService：reserve(sku, n) 扣减库存不足时抛 OutOfStock；release(sku, n) 归还；total_value() 汇总 qty*price；
    import_csv(text) 从 'sku,name,qty,price' 文本导入，跳过表头，qty/price 非法的行收集到返回的 errors 列表里而不是中断）。
    tests/ 里有三个测试文件是验收标准，不要改测试。
""", {
    "src/__init__.py": "",
    "src/inv/__init__.py": "",
    "src/inv/models.py": "# TODO: Item 数据类\n",
    "src/inv/store.py": "# TODO: InMemoryStore\n",
    "src/inv/service.py": "# TODO: InventoryService, OutOfStock\n",
    "tests/test_models.py": """
    from src.inv.models import Item

    def test_item():
        it = Item(sku="A1", name="bolt", qty=3, price=0.5)
        assert it.sku == "A1" and it.qty == 3 and it.price == 0.5
    """,
    "tests/test_store.py": """
    from src.inv.models import Item
    from src.inv.store import InMemoryStore

    def test_add_get_list_adjust():
        s = InMemoryStore()
        s.add(Item("A1", "bolt", 3, 0.5)); s.add(Item("B2", "nut", 1, 0.2))
        assert s.get("A1").name == "bolt" and s.get("zz") is None
        assert [i.sku for i in s.list()] == ["A1", "B2"]
        s.adjust_qty("A1", -2)
        assert s.get("A1").qty == 1
    """,
    "tests/test_service.py": """
    import pytest
    from src.inv.service import InventoryService, OutOfStock

    def test_reserve_release_value():
        svc = InventoryService()
        errs = svc.import_csv("sku,name,qty,price\\nA1,bolt,10,0.5\\nB2,nut,x,0.2\\nC3,washer,4,abc\\n")
        assert len(errs) == 2
        svc.reserve("A1", 4)
        with pytest.raises(OutOfStock):
            svc.reserve("A1", 7)
        svc.release("A1", 1)
        assert svc.total_value() == 3.5
    """,
    "hidden/test_hidden.py": """
    import pytest
    from src.inv.service import InventoryService, OutOfStock

    def test_reserve_unknown_sku():
        svc = InventoryService()
        with pytest.raises((OutOfStock, KeyError)):
            svc.reserve("nope", 1)

    def test_import_blank_lines_and_value():
        svc = InventoryService()
        errs = svc.import_csv("sku,name,qty,price\\n\\nA1,bolt,2,1.25\\n")
        assert errs == [] and svc.total_value() == 2.5

    def test_release_increases():
        svc = InventoryService(); svc.import_csv("sku,name,qty,price\\nA1,b,1,1\\n")
        svc.release("A1", 2)
        assert svc.total_value() == 3
    """,
})


def main():
    if os.path.exists(ROOT):
        shutil.rmtree(ROOT)
    index = []
    for name, t in T.items():
        d = os.path.join(ROOT, name)
        for rel, content in t["files"].items():
            p = os.path.join(d, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            open(p, "w", encoding="utf-8").write(content)
        open(os.path.join(d, "task.md"), "w", encoding="utf-8").write(t["prompt"] + "\n")
        index.append({"name": name, "kind": t["kind"], "difficulty": t["difficulty"]})
    json.dump(index, open(os.path.join(ROOT, "index.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"生成 {len(index)} 个任务 → {ROOT}")


if __name__ == "__main__":
    main()
