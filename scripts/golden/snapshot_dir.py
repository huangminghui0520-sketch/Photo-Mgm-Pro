#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 PC 端的「导出文件夹」拍成与 snapshot_zip.py **同构**的快照，用于跨端比对。

为什么需要它
------------
两端产物形态不同：PC 导出的是文件夹（每个事件一个子目录 + ledger.xlsx），Android 导出的是
ZIP（同样的目录结构 + 台账 xlsx）。要判断"同一素材、两端是否产出同样的照片归属"，
就得把两种形态归一化成同一份可比对的数据结构。

本脚本的输出字段与 snapshot_zip.py 完全一致（file/entries/entryCount/fileCount/dirs），
因此可以直接：

    python snapshot_dir.py   --in <PC 导出目录>  --out pc.json --normalize
    python snapshot_zip.py   --in <Android ZIP> --out android.json --normalize
    python compare.py --base pc.json --after android.json --ignore '^file$'

**目录条目也要输出**：ZIP 快照里目录是独立条目（`xxx/`，method=0），如果这里只输出文件，
两次快照的 entryCount 永远差 N，比对必然报"长度不一致"，噪音会盖住真差异。

用法：
    python scripts/golden/snapshot_dir.py --in /tmp/run-a/1102_111837 --out /tmp/pc.json --normalize
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

# ★ 与 snapshot_zip.py 必须**逐字符相同**，否则跨端比对会假阳性。
#   锚定「照片分类_」「巡查台账_」两个前缀，绝不能写成裸 \d{8}_\d{6}——
#   照片名就是 IMG_20260814_080000.jpg，裸正则会把整批照片折成同名（实测踩过）。
# 导出产物文件名带「导出时刻」，每次导出都不同，但不影响内容：
#   照片分类_{巡查日期8}_{导出时刻6}.zip
#   巡查台账_{巡查日期8}_{导出日期8}_{导出时刻6}.xlsx
# 归一化成 <TS> 整体折叠。
#
# ★ 为什么把整条时间串折叠掉、而不是只折叠"导出时刻"：
#   旧规则 `(?:照片分类|巡查台账)_\d{8}_\d{6}` 在台账名上会**跨字段截断**——
#   20260814_20261102_121837 里它吃掉 20260814_202611，剩 _02_121837，
#   于是"导出日期变了一位"这种真差异反而看不出来，幂等对比还会误报。
#   同一条规则更早还误伤过照片名（IMG_20260814_080000.jpg → IMG_<TS>.jpg，
#   一目录几十张照片折成同名）。收敛成"只认这两个前缀 + 整条时间串"后两种情况都没了。
#   台账/照片分类的文件名不承载业务信息（巡查日期在台账 A 列与 ZIP 内目录名里都有），
#   折叠掉不会降低比对的辨别力。
_TS_RE = re.compile(r"(?:照片分类|巡查台账)_[\d_]+(?=\.)")

# 系统噪声文件：PC 侧目录可能被资源管理器/同步盘写入，不属于产物
_IGNORE = {".DS_Store", "Thumbs.db", "desktop.ini"}


def normalize_name(name: str) -> str:
    head, sep, base = name.rpartition("/")
    return f"{head}{sep}{_TS_RE.sub(lambda m: m.group(0).split('_')[0] + '_<TS>', base)}"


def snapshot(root: Path, normalize: bool = False,
             exclude: re.Pattern[str] | None = None) -> dict:
    entries: list[dict] = []
    for p in sorted(root.rglob("*")):
        rel = p.relative_to(root).as_posix()
        if p.name in _IGNORE:
            continue
        if exclude is not None and exclude.search(rel):
            continue
        name = normalize_name(rel) if normalize else rel
        if p.is_dir():
            entries.append({"name": name + "/", "dir": True, "method": 0})
        else:
            data = p.read_bytes()
            entries.append({
                "name": name,
                "dir": False,
                "method": 0,          # 目录形态不压缩，与 ZIP 的 STORED 对齐
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            })
    entries.sort(key=lambda e: e["name"])
    dirs = sorted({e["name"].rsplit("/", 1)[0] for e in entries if "/" in e["name"]})
    names = [e["name"] for e in entries]
    dups = sorted(n for n, c in Counter(names).items() if c > 1)
    return {
        "file": "<DIR>",
        "entries": entries,
        "entryCount": len(entries),
        "fileCount": sum(1 for e in entries if not e["dir"]),
        "dirs": dirs,
        "duplicateNames": dups,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="导出目录 → 与 ZIP 同构的快照 JSON")
    ap.add_argument("--in", dest="src", required=True, help="导出目录（含事件子目录与 ledger.xlsx）")
    ap.add_argument("--out", dest="dst", required=True)
    ap.add_argument("--normalize", action="store_true", help="折叠导出时刻（照片分类_/巡查台账_ 前缀）")
    ap.add_argument("--exclude", default=None,
                    help="正则：按条目名排除（跨端比对时剔除 PC 侧独有的 export-manifest.json / export.log）")
    ap.add_argument("--meta", action="append", default=[], help="key=value（可重复）")
    args = ap.parse_args()

    src = Path(args.src)
    if not src.is_dir():
        print(f"[FAIL] 目录不存在：{src}")
        return 1

    snap = snapshot(src, normalize=args.normalize,
                    exclude=re.compile(args.exclude) if args.exclude else None)
    if args.normalize:
        snap["normalized"] = True
    if args.meta:
        snap = {"meta": dict(kv.split("=", 1) for kv in args.meta if "=" in kv), **snap}
    out = Path(args.dst)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snap, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
    print(f"[OK] {src} → {out}")
    print(f"     条目 {snap['entryCount']}（文件 {snap['fileCount']} / 目录 {len(snap['dirs'])}）"
          f"{'  [已归一化导出时刻]' if args.normalize else ''}")
    if snap["duplicateNames"]:
        print(f"[WARN] 归一化后有 {len(snap['duplicateNames'])} 组重名条目（折叠规则可能误伤业务字段）：")
        for n in snap["duplicateNames"][:5]:
            print("   -", n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
