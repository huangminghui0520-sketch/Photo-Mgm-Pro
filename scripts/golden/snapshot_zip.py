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
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path

# 导出文件名带「导出时刻」：照片分类_20260814_111636.zip、巡查台账_20260814_111636.xlsx。
# 这个时刻每次导出都不同，但它**不影响产物内容**——不归一化就会把"重新导出了一次"
# 误报成"行为变了"，基线从此不可用。
#
# ★ 必须锚定前缀，不能只写 \d{8}_\d{6}：照片名恰好是 IMG_20260814_080000.jpg，
#   同一个正则会把几十张照片全折成 IMG_<TS>.jpg —— 同目录内重名、跨端完全无法区分，
#   基线表面"通过"却什么都没验到（实测踩过）。
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


def normalize_name(name: str) -> str:
    """只折叠导出时刻；保留目录部分，避免影响条目路径结构。"""
    head, sep, base = name.rpartition("/")
    return f"{head}{sep}{_TS_RE.sub(lambda m: m.group(0).split('_')[0] + '_<TS>', base)}"


def snapshot(path: Path, normalize: bool = False, exclude: re.Pattern[str] | None = None) -> dict:
    entries = []
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if exclude is not None and exclude.search(info.filename):
                continue
            is_dir = info.filename.endswith("/")
            item = {
                "name": normalize_name(info.filename) if normalize else info.filename,
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
    names = [e["name"] for e in entries]
    dups = sorted(n for n, c in Counter(names).items() if c > 1)
    return {
        "file": "<ZIP>" if normalize else path.name,
        "entries": entries,
        "entryCount": len(entries),
        "fileCount": sum(1 for e in entries if not e["dir"]),
        "dirs": dirs,
        # ★ 归一化后若出现重名，说明折叠规则误伤了业务字段（照片名/事件名），
        #   此时快照"看起来通过"但已失去分辨力。留一个显式字段，比对时一眼能看到。
        "duplicateNames": dups,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="导出 ZIP → 可 diff 的快照 JSON")
    ap.add_argument("--in", dest="src", required=True)
    ap.add_argument("--out", dest="dst", required=True)
    ap.add_argument("--meta", action="append", default=[], help="key=value（可重复）")
    ap.add_argument("--exclude", default=None,
                    help="正则：按条目名排除（跨端比对时用来剔除 PC 侧独有的 export-manifest.json / "
                         "export.log，以及两端口径不同的 ledger.xlsx）")
    ap.add_argument("--normalize", action="store_true",
                    help="把 8 位日期_6 位时分秒（导出时刻）折叠为 <TS>，用于双跑幂等与跨端比对。"
                         "跨端比对必须开；单次留证可不开")
    args = ap.parse_args()
    src = Path(args.src)
    if not src.exists():
        print(f"[FAIL] 文件不存在：{src}")
        return 1
    snap = snapshot(src, normalize=args.normalize,
                    exclude=re.compile(args.exclude) if args.exclude else None)
    if args.normalize:
        snap["normalized"] = True
    if args.meta:
        meta = dict(kv.split("=", 1) for kv in args.meta if "=" in kv)
        snap = {"meta": meta, **snap}
    out = Path(args.dst)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snap, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
    print(f"[OK] {src.name} → {out}")
    print(f"     条目 {snap['entryCount']}（文件 {snap['fileCount']} / 目录 {len(snap['dirs'])}）"
          f"{'  [已归一化导出时刻]' if args.normalize else ''}")
    if snap["duplicateNames"]:
        print(f"[WARN] 归一化后有 {len(snap['duplicateNames'])} 组重名条目（折叠规则可能误伤业务字段）：")
        for n in snap["duplicateNames"][:5]:
            print("   -", n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
