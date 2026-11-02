#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对比两份快照，输出「改了哪里」而不是「变了 / 没变」。

设计意图（这是整个回归体系的核心工具，不是辅助脚本）：
  · 逐路径递归 diff，定位到 sheet/行/列/媒体文件名，人能直接判断"这是预期变更还是踩坏了"；
  · 支持 --ignore 正则屏蔽**天然抖动**的字段（导出时间戳之类）。屏蔽项必须显式声明，
    不允许"整类不比对"，否则会把真实回归一起屏蔽掉；
  · 退出码：0=完全一致，1=有差异，2=参数/文件问题 —— 便于接 CI。

用法：
    python scripts/golden/compare.py --base docs/golden/desktop-baseline.json --after /tmp/after.json
    python scripts/golden/compare.py --base a.json --after b.json --ignore '导出时间' --max 40
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

def walk(a, b, path: str, diffs: list[tuple[str, object, object]], ignores: list[re.Pattern]) -> None:
    if any(p.search(path) for p in ignores):
        return
    if type(a) is not type(b):
        diffs.append((path, a, b))
        return
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            sub = f"{path}.{k}" if path else str(k)
            # ★ 屏蔽判断必须在这里再做一次：一侧整键缺失时是在父层直接记 diff 的，
            #   只在 walk 入口判断会漏（曾经 --ignore '^meta' 对这种差异完全无效）。
            if any(p.search(sub) for p in ignores):
                continue
            if k not in a:
                diffs.append((sub, "<缺失>", b[k]))
            elif k not in b:
                diffs.append((sub, a[k], "<缺失>"))
            else:
                walk(a[k], b[k], sub, diffs, ignores)
    elif isinstance(a, list):
        if len(a) != len(b) and not any(p.search(f"{path}.length") for p in ignores):
            diffs.append((f"{path}.length", len(a), len(b)))
        for i in range(min(len(a), len(b))):
            walk(a[i], b[i], f"{path}[{i}]", diffs, ignores)
    else:
        if a != b:
            diffs.append((path, a, b))


def _short(v: object, limit: int = 90) -> str:
    s = json.dumps(v, ensure_ascii=False) if not isinstance(v, str) else v
    return s if len(s) <= limit else s[:limit] + "…"


def main() -> int:
    ap = argparse.ArgumentParser(description="对比两份快照 JSON")
    ap.add_argument("--base", required=True, help="基线快照")
    ap.add_argument("--after", required=True, help="待比对快照")
    ap.add_argument("--ignore", action="append", default=[],
                    help="正则：匹配到的路径不比对（可重复）")
    ap.add_argument("--max", type=int, default=25, help="最多打印多少条差异")
    args = ap.parse_args()

    for p in (args.base, args.after):
        if not Path(p).exists():
            print(f"[FAIL] 文件不存在：{p}")
            return 2

    base = json.loads(Path(args.base).read_text(encoding="utf-8"))
    after = json.loads(Path(args.after).read_text(encoding="utf-8"))
    ignores = [re.compile(p) for p in args.ignore]

    diffs: list[tuple[str, object, object]] = []
    walk(base, after, "", diffs, ignores)

    if not diffs:
        print(f"[SAME] 0 差异：{Path(args.base).name} == {Path(args.after).name}")
        if args.ignore:
            print(f"       已屏蔽 {len(args.ignore)} 条正则：{', '.join(args.ignore)}")
        return 0

    print(f"[DIFF] 共 {len(diffs)} 处差异")
    # 按"顶层部件"分组，先给个总览，避免一屏全是单元格级的刷屏
    groups: dict[str, int] = {}
    for path, _, _ in diffs:
        head = re.sub(r"\[\d+\]", "[*]", path).split(".")[:3]
        key = ".".join(head)
        groups[key] = groups.get(key, 0) + 1
    for key, n in sorted(groups.items(), key=lambda kv: -kv[1])[:8]:
        print(f"   {n:>4}  {key}")

    print("   —— 明细 ——")
    for path, a, b in diffs[: args.max]:
        print(f"   · {path}")
        print(f"       基线: {_short(a)}")
        print(f"       现在: {_short(b)}")
    if len(diffs) > args.max:
        print(f"   … 另有 {len(diffs) - args.max} 处，用 --max 放大")
    if args.ignore:
        print(f"   已屏蔽 {len(args.ignore)} 条正则：{', '.join(args.ignore)}")
    else:
        print("   若某条差异属运行期抖动（如导出时间戳），用 --ignore <正则> 显式屏蔽，不要整类跳过")
    return 1


if __name__ == "__main__":
    sys.exit(main())
