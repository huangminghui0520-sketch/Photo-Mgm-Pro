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

退出码边界用例（4 条，可直接复算；M1 修完 I-08 后实测 0/1/2/2）：
    python scripts/golden/compare.py --base A --after A                    # 合法相同 → 0
    python scripts/golden/compare.py --base A --after B                    # 合法不同 → 1
    printf '{bad json' > /tmp/bad.json
    python scripts/golden/compare.py --base A --after /tmp/bad.json        # 坏 JSON  → 2
    python scripts/golden/compare.py --base A --after /tmp/not-exist.json  # 文件不存在 → 2

★ 修 I-08 的原因：`json.loads`（原 `:72-73`）没有异常保护，坏 JSON 会抛未捕获的
  JSONDecodeError → 进程以 **1** 退出，与「有差异」撞码。自动化里这是最坏的一种混码：
  "基线变了"和"工具/输入坏了"看起来一样。现在读入与解码统一走 `_load_json`，
  失败一律 EXIT=2 并打印 `[ERROR] 无法解析 …`；`--ignore` 的非法正则同样 EXIT=2。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


class _InputError(Exception):
    """输入不可用（文件缺失 / 非 JSON / 非法正则）→ 映射到 EXIT=2。"""


def _load_json(path: str) -> object:
    """读入并解析一份快照 JSON。任何读/解码/语法错误都映射到 EXIT=2。"""
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError, UnicodeDecodeError) as exc:
        print(f"[ERROR] 无法解析 {path}: {exc}")
        raise _InputError(str(exc)) from exc


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

    try:
        base = _load_json(args.base)
        after = _load_json(args.after)
    except _InputError:
        return 2

    try:
        ignores = [re.compile(p) for p in args.ignore]
    except re.error as exc:
        print(f"[ERROR] --ignore 正则无效: {exc}")
        return 2

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
