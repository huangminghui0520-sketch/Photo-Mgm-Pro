#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Android 导出的 ZIP 拍成快照（条目清单 + 每条 sha256 + 目录结构）。

为什么不比整包哈希：ZIP 每个条目都带 DOS 时间戳，同样的内容重新打包哈希就变，
整包哈希会把"重新导出了一次"误报成"行为变了"。所以逐条目比。

为什么还要记 method（存储/压缩）：Android 侧导出用的是 STORED 还是 DEFLATED、
以及压缩级别变了，会让包体变化但对用户可见产物无影响 —— 这类差异要能区分开，
否则一律当"回归"会让基线失去可信度。

用法：
    python scripts/golden/snapshot_zip.py --in 导出.zip --out /tmp/android.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path


def snapshot(path: Path) -> dict:
    entries = []
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            is_dir = info.filename.endswith("/")
            item = {
                "name": info.filename,
                "dir": is_dir,
                "method": info.compress_type,
            }
            if not is_dir:
                data = z.read(info.filename)
                item["bytes"] = len(data)
                item["sha256"] = hashlib.sha256(data).hexdigest()
            entries.append(item)
    entries.sort(key=lambda e: e["name"])
    dirs = sorted({e["name"].rsplit("/", 1)[0] for e in entries
                   if "/" in e["name"]})
    return {
        "file": path.name,
        "entries": entries,
        "entryCount": len(entries),
        "fileCount": sum(1 for e in entries if not e["dir"]),
        "dirs": dirs,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="导出 ZIP → 可 diff 的快照 JSON")
    ap.add_argument("--in", dest="src", required=True)
    ap.add_argument("--out", dest="dst", required=True)
    ap.add_argument("--meta", action="append", default=[], help="key=value（可重复）")
    args = ap.parse_args()
    src = Path(args.src)
    if not src.exists():
        print(f"[FAIL] 文件不存在：{src}")
        return 1
    snap = snapshot(src)
    if args.meta:
        meta = dict(kv.split("=", 1) for kv in args.meta if "=" in kv)
        snap = {"meta": meta, **snap}
    out = Path(args.dst)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snap, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
    print(f"[OK] {src.name} → {out}")
    print(f"     条目 {snap['entryCount']}（文件 {snap['fileCount']} / 目录 {len(snap['dirs'])}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
