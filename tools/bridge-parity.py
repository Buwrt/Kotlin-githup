#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
桥接接口一致性检查（JsBridge 迁移的回归闸门）

干什么用 ——
    JsBridge 上那几十个 @JavascriptInterface 方法，是原生层与 WebView 前端
    之间唯一的契约。前端 JS 里写的是 window.Native.xxx(...)，只要原生少一个
    方法、或者参数类型变了，JS 那边就是一个静默的 undefined is not a function
    —— 编译不报错、启动不崩溃，只在用户点到的那一瞬间失效。

    所以每动一次 JsBridge（无论是 Java→Kotlin 转换，还是拆成多个模块），
    都要跑一次本脚本：把「当前源码里的桥接方法」和「迁移前的基线」对一遍，
    少一个、多一个、签名变了都直接失败退出。

怎么用 ——
    # 第一次：把当前（迁移前）的接口清单存成基线
    python3 tools/bridge-parity.py --snapshot

    # 之后每次改完 JsBridge：比对
    python3 tools/bridge-parity.py

    # 只想看看现在有哪些接口
    python3 tools/bridge-parity.py --list

退出码 ——
    0 一致（或已生成快照）   1 有差异   2 用法/环境错误
"""

import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "app", "src", "main", "java")
BASELINE = os.path.join(ROOT, "tools", "bridge-baseline.json")

# 方法声明：Java 的 `public void http(String id, ...)` 与 Kotlin 的 `fun http(id: String, ...)`
DECL = re.compile(
    r"(?:public\s+|internal\s+|protected\s+)*"
    r"(?:fun\s+|[\w.<>\[\]]+\s+)"          # Kotlin 的 fun / Java 的返回类型
    # 方法名。这里刻意排除控制关键字：@JavascriptInterface 后面 400 字符里
    # 完全可能出现 `if (...)`，不挡住的话会被当成方法抓进来（第一版就是这么误报的）。
    r"(?!if\b|for\b|while\b|switch\b|catch\b|return\b|new\b|try\b)"
    r"(\w+)\s*\("                            # 方法名
    r"([^)]*)\)"                             # 参数表
)

# Java 参数上常见的修饰符，归一化时要先剥掉，否则 `final String url` 会被记成类型 final
JAVA_PARAM_MODIFIERS = {"final"}


def norm_type(t: str) -> str:
    """把 Java / Kotlin 的写法归一成同一串：去可空标记、去泛型细节差异。"""
    t = t.strip()
    t = t.replace("?", "")                          # Kotlin 的 String?
    t = re.sub(r"\s+", "", t)                       # 去空格
    t = re.sub(r"<\s*out\s+", "<", t)               # Kotlin 的 out 型变
    t = t.replace("java.lang.", "").replace("kotlin.", "")
    t = t.replace("Array<", "").replace("[]", "")   # String[] 与 Array<String> 视为同型
    t = t.replace(">", "")
    if t in ("Int", "Integer"):
        return "int"
    if t in ("Long",):
        return "long"
    if t in ("Boolean",):
        return "boolean"
    if t in ("Double",):
        return "double"
    return t


def parse_params(raw: str):
    """拆参数表。Java 是 `String id`，Kotlin 是 `id: String`，都要能认。"""
    if not raw.strip():
        return []
    out = []
    depth = 0
    cur = ""
    for ch in raw:
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    out.append(cur)

    types = []
    for p in out:
        p = p.strip()
        if not p:
            continue
        if ":" in p:                     # Kotlin：名字: 类型
            types.append(norm_type(p.split(":", 1)[1]))
        else:                            # Java：类型 名字
            parts = [x for x in p.split() if x not in JAVA_PARAM_MODIFIERS]
            types.append(norm_type(parts[0]) if parts else "")
    return types


def scan():
    """扫描所有 .java / .kt，收集 @JavascriptInterface 方法的签名。"""
    found = {}
    for root, _, files in os.walk(SRC_DIR):
        for fn in files:
            if not (fn.endswith(".java") or fn.endswith(".kt")):
                continue
            path = os.path.join(root, fn)
            src = open(path, encoding="utf-8", errors="replace").read()
            for m in re.finditer(r"@JavascriptInterface", src):
                tail = src[m.end():m.end() + 400]
                dm = DECL.search(tail)
                if not dm:
                    continue
                name = dm.group(1)
                types = parse_params(dm.group(2))
                rel = os.path.relpath(path, ROOT)
                key = "%s(%s)" % (name, ",".join(types))
                found.setdefault(key, []).append(rel)
    return found


def main():
    ap = argparse.ArgumentParser(description="JsBridge 桥接接口一致性检查")
    ap.add_argument("--snapshot", action="store_true", help="把当前接口清单存为基线")
    ap.add_argument("--list", action="store_true", help="只打印当前接口清单")
    args = ap.parse_args()

    if not os.path.isdir(SRC_DIR):
        print("[桥接检查] 找不到源码目录：%s" % SRC_DIR)
        return 2

    cur = scan()

    if args.list or args.snapshot:
        for k in sorted(cur):
            print("  %-70s %s" % (k, ",".join(sorted(set(cur[k])))))
        print("[桥接检查] 共 %d 个桥接接口" % len(cur))

    if args.snapshot:
        with open(BASELINE, "w", encoding="utf-8") as f:
            json.dump(sorted(cur.keys()), f, ensure_ascii=False, indent=2)
        print("[桥接检查] 基线已写入：%s" % os.path.relpath(BASELINE, ROOT))
        return 0

    if not os.path.exists(BASELINE):
        print("[桥接检查] 没有基线文件，先跑：python3 tools/bridge-parity.py --snapshot")
        return 2

    base = set(json.load(open(BASELINE, encoding="utf-8")))
    now = set(cur.keys())

    missing = sorted(base - now)   # 迁移后丢了 —— 危险
    added = sorted(now - base)     # 多的
    # 同名但参数变了，会分别落在 missing / added 里，这里再点名一次
    base_names = {k.split("(")[0] for k in base}
    now_names = {k.split("(")[0] for k in now}
    changed = sorted(n for n in (base_names & now_names)
                     if not (base & {k for k in now if k.split("(")[0] == n}))

    ok = not missing and not added
    print("[桥接检查] 基线 %d 个，当前 %d 个" % (len(base), len(now)))
    if missing:
        print("\n[桥接检查] 丢了这些接口（前端调用会直接失效）：")
        for k in missing:
            print("    - " + k)
    if added:
        print("\n[桥接检查] 新增了这些接口（确认是有意为之）：")
        for k in added:
            print("    + " + k)
    if changed and not missing and not added:
        print("\n[桥接检查] 同名接口签名变了：" + ", ".join(changed))

    if ok:
        print("\n[桥接检查] 通过：桥接接口与基线完全一致")
        return 0
    print("\n[桥接检查] 失败：桥接接口与基线不一致")
    return 1


if __name__ == "__main__":
    sys.exit(main())
