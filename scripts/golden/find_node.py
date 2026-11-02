#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 `uiautomator dump` 的 XML 里定位一个节点，输出可点坐标 "x y"。

为什么不直接在 bash 里 grep bounds
---------------------------------
Compose 的按钮是「父节点 clickable + 子节点 text」两层结构：文字节点的 bounds 通常仍落在
按钮内，但**点击命中的是父节点**。只取文字节点容易点到边缘、或点到 Button 的内边距上
（Material3 按钮的内边距不小，点在文字正中一般没事，但保险做法是向上找 clickable 祖先）。

本脚本的查找顺序：
  1. text / content-desc 精确或包含匹配；
  2. 若该节点不可点，向上找最近的 clickable 祖先，用祖先的 bounds；
  3. 都没有就用匹配节点自己的 bounds（点文字本身）。

退出码：0 = 找到并打印坐标；1 = 未找到（调用方据此决定是否回落人工）。

用法：
    python scripts/golden/find_node.py /tmp/ui.xml 解析日志
    python scripts/golden/find_node.py /tmp/ui.xml 日志 contains
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

_BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def _center(node: ET.Element) -> tuple[int, int] | None:
    m = _BOUNDS_RE.match(node.get("bounds", ""))
    if not m:
        return None
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(root: ET.Element, needle: str, mode: str) -> tuple[int, int] | None:
    parents = {child: parent for parent in root.iter() for child in parent}
    nodes = list(root.iter("node"))

    def label(node: ET.Element) -> str:
        return (node.get("text") or "") + "\x00" + (node.get("content-desc") or "")

    def hit(node: ET.Element) -> bool:
        if mode == "exact":
            return needle in label(node).split("\x00")
        return needle in label(node)

    # ① 自身 clickable 且命中 → 直接用它。
    #    这一条优先的原因：底部 tab 的 label 是「日志3·照片59」，包含「日志」；
    #    若不先看 clickable，遍历会先撞到「解析日志」按钮的文字节点，点错地方。
    for node in nodes:
        if node.get("clickable") == "true" and hit(node):
            c = _center(node)
            if c:
                return c

    # ② 文字节点命中 → 向上找最近的 clickable 祖先（Material3 按钮是父 clickable + 子 text）
    for node in nodes:
        if not hit(node):
            continue
        cur: ET.Element | None = node
        while cur is not None:
            if cur.get("clickable") == "true":
                c = _center(cur)
                if c:
                    return c
            cur = parents.get(cur)
        c = _center(node)
        if c:
            return c
    return None


# 解析摘要里值得留档的关键片段。用白名单而不是"把整棵树打出来"：
# 全量 dump 有 200+ 节点、几千字符，写进测试记录没人看；这 6 类能直接当判定依据。
_DIGEST_PATTERNS = [
    r"解析出\s*\d+\s*条事件[^，,]*",
    r"未识别\s*\d+\s*行?",
    r"台账\s*\d+\s*行[^）)]*",
    r"日志\s*\d+\s*·\s*照片\s*\d+",
    r"未匹配\s*\d+",
    r"全部\s*\d+\s+正常\s*\d+\s+待复查\s*\d+",
]


def digest(path: Path) -> int:
    """打印 UI 里与"这次跑成了什么"有关的文字，作为测试记录的证据行。"""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        print(f"[FAIL] XML 解析失败：{e}", file=sys.stderr)
        return 1
    texts = []
    for node in root.iter("node"):
        t = (node.get("text") or "").strip()
        if t:
            texts.append(t)
    seen: set[str] = set()
    for pat in _DIGEST_PATTERNS:
        for t in texts:
            m = re.search(pat, t)
            if m and m.group(0) not in seen:
                seen.add(m.group(0))
                print(m.group(0))
    if not seen:
        print("（未匹配到摘要字段：可能不在日志页，或 UI 文案已变）")
    return 0


def main() -> int:
    if len(sys.argv) >= 3 and sys.argv[1] == "--digest":
        p = Path(sys.argv[2])
        if not p.exists():
            print(f"[FAIL] 找不到 {p}", file=sys.stderr)
            return 1
        return digest(p)
    if len(sys.argv) < 3:
        print("用法: find_node.py <ui.xml> <text> [exact|contains]\n"
              "      find_node.py --digest <ui.xml>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    needle = sys.argv[2]
    mode = sys.argv[3] if len(sys.argv) > 3 else "contains"
    if not path.exists():
        print(f"[FAIL] 找不到 {path}", file=sys.stderr)
        return 1
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        print(f"[FAIL] XML 解析失败：{e}（dump 可能被截断）", file=sys.stderr)
        return 1
    c = find(root, needle, mode)
    if c is None:
        return 1
    print(f"{c[0]} {c[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
