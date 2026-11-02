#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ledger.xlsx 拍成可 diff 的 JSON 快照。

为什么不用整包哈希：
  ① xlsx 是 zip，条目里带 DOS 时间戳，同一份内容重新生成哈希就变了（假阳性）；
  ② 整包哈希只能告诉你"变了"，不能告诉你"哪个单元格变了"（无法定位）。
  所以这里拆到最小可解释单元：单元格文本 + 单元格样式号 + 行高 + 列宽 + 媒体字节 + 图片锚点。

为什么要连行高/列宽一起拍（这不是过度设计）：
  U1.9 修过一次「照片行高在 125% 缩放下被压成 0.8 倍」的缺陷，根因是 worksheet 缺 <sheetViews>。
  如果快照只拍单元格文本，这类**尺寸/版式回退**完全测不出来 —— 而它恰恰是用户最先看到的。

依赖：只用标准库（zipfile + xml.etree），不依赖 openpyxl / Excel COM，CI 上也能跑。
用法：
    python scripts/golden/snapshot_xlsx.py --in 导出目录/ledger.xlsx --out /tmp/after.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

NS = {
    "m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "xdr": "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing",
    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
    "pr": "http://schemas.openxmlformats.org/package/2006/relationships",
}


def _sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def _shared_strings(z: zipfile.ZipFile) -> list[str]:
    if "xl/sharedStrings.xml" not in z.namelist():
        return []
    root = ET.fromstring(z.read("xl/sharedStrings.xml"))
    out: list[str] = []
    for si in root.findall("m:si", NS):
        # 富文本会拆成多个 <r><t>，必须全部拼接，否则会漏掉局部加粗后的后半段
        parts = [t.text or "" for t in si.iter(f"{{{NS['m']}}}t")]
        out.append("".join(parts))
    return out


def _cell_text(c: ET.Element, shared: list[str]) -> str:
    t = c.get("t")
    if t == "s":
        v = c.find("m:v", NS)
        idx = int(v.text) if v is not None and v.text else -1
        return shared[idx] if 0 <= idx < len(shared) else ""
    if t == "inlineStr":
        return "".join(x.text or "" for x in c.iter(f"{{{NS['m']}}}t"))
    v = c.find("m:v", NS)
    return v.text if v is not None and v.text is not None else ""


def _rels(z: zipfile.ZipFile, part: str) -> dict[str, str]:
    """读某个 part 的 .rels，返回 rId → 包内绝对路径（已归一化 ../）。"""
    base = part.rsplit("/", 1)[0]
    rels_path = f"{base}/_rels/{part.rsplit('/', 1)[1]}.rels"
    if rels_path not in z.namelist():
        return {}
    root = ET.fromstring(z.read(rels_path))
    out: dict[str, str] = {}
    for rel in root:
        target = rel.get("Target", "")
        if target.startswith("/"):
            out[rel.get("Id", "")] = target.lstrip("/")
        else:
            # ★ 必须 normpath：drawing 的关系写的是 "../drawings/drawing1.xml"，
            #   不归一化就匹配不上 zip 里的 "xl/drawings/drawing1.xml"，锚点会静默变成 0 个。
            out[rel.get("Id", "")] = posixpath.normpath(posixpath.join(base, target))
    return out


def _sheet_meta(z: zipfile.ZipFile) -> list[dict]:
    wb = ET.fromstring(z.read("xl/workbook.xml"))
    wb_rels = _rels(z, "xl/workbook.xml")
    sheets = []
    for s in (wb.find("m:sheets", NS) if wb.find("m:sheets", NS) is not None else []):
        rid = s.get(f"{{{NS['r']}}}id")
        sheets.append({"name": s.get("name"), "part": wb_rels.get(rid or "", "")})
    return sheets


def _drawing_anchors(z: zipfile.ZipFile, sheet_part: str) -> list[dict]:
    """抽取图片锚点：oneCellAnchor / twoCellAnchor 的 row/col 与 ext 尺寸（EMU）。"""
    rels = _rels(z, sheet_part)
    drawing_parts = [p for p in rels.values() if "drawings/" in p]
    out: list[dict] = []
    for dp in drawing_parts:
        if dp not in z.namelist():
            continue
        droot = ET.fromstring(z.read(dp))
        drels = _rels(z, dp)
        for anchor in droot:
            tag = anchor.tag.split("}")[-1]
            if tag not in ("oneCellAnchor", "twoCellAnchor"):
                continue
            frm = anchor.find("xdr:from", NS)
            to = anchor.find("xdr:to", NS)
            ext = anchor.find("xdr:ext", NS)
            blip = anchor.find(".//a:blip", NS)
            media = drels.get(blip.get(f"{{{NS['r']}}}embed", ""), "") if blip is not None else ""
            item = {
                "anchor": tag,
                "row": int(frm.find("xdr:row", NS).text) if frm is not None else None,
                "col": int(frm.find("xdr:col", NS).text) if frm is not None else None,
                "media": media.rsplit("/", 1)[-1],
            }
            if to is not None:
                item["toRow"] = int(to.find("xdr:row", NS).text)
                item["toCol"] = int(to.find("xdr:col", NS).text)
            if ext is not None:
                item["cx"] = int(ext.get("cx", "0"))
                item["cy"] = int(ext.get("cy", "0"))
            out.append(item)
    return out


def snapshot(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        shared = _shared_strings(z)
        sheets = []
        for meta in _sheet_meta(z):
            part = meta["part"]
            if part not in z.namelist():
                continue
            root = ET.fromstring(z.read(part))
            cols = [
                {"min": int(c.get("min", "0")), "max": int(c.get("max", "0")),
                 "width": c.get("width"), "custom": c.get("customWidth")}
                for c in (root.find("m:cols", NS) if root.find("m:cols", NS) is not None else [])
            ]
            rows = []
            for row in root.findall(".//m:sheetData/m:row", NS):
                cells = {}
                for c in row.findall("m:c", NS):
                    ref = c.get("r", "")
                    txt = _cell_text(c, shared)
                    style = c.get("s", "0")
                    # 空单元格不记（否则大量 "": 噪音），但有样式的空单元格要记（格式本身就是产物）
                    if txt == "" and style in ("0", None):
                        continue
                    cells[ref] = {"v": txt, "s": style}
                rows.append({"r": int(row.get("r", "0")), "ht": row.get("ht"),
                             "customHt": row.get("customHeight"), "cells": cells})
            dim = root.find("m:dimension", NS)
            sheets.append({
                "name": meta["name"],
                "cols": cols,
                "rows": rows,
                "anchors": _drawing_anchors(z, part),
                "hasSheetViews": root.find("m:sheetViews", NS) is not None,
                # dimension 是"数据范围"声明，改了列数/行数会跟着变，属于廉价的一致性校验
                "geometry": dict(dim.attrib) if dim is not None else {},
            })
        media = []
        for name in sorted(n for n in z.namelist() if n.startswith("xl/media/")):
            data = z.read(name)
            media.append({"name": name.rsplit("/", 1)[-1], "bytes": len(data),
                          "sha256": _sha256_bytes(data)})
        return {
            "file": path.name,
            "bytes": path.stat().st_size,
            "sheetCount": len(sheets),
            "sheets": sheets,
            "media": media,
        }


def main() -> int:
    ap = argparse.ArgumentParser(description="ledger.xlsx → 可 diff 的快照 JSON")
    ap.add_argument("--in", dest="src", required=True)
    ap.add_argument("--out", dest="dst", required=True)
    ap.add_argument("--meta", action="append", default=[],
                    help="附加元信息 key=value（可重复），写进快照的 meta 段，便于日后追溯是哪次提交/哪份样本")
    args = ap.parse_args()
    src = Path(args.src)
    if not src.exists():
        print(f"[FAIL] 文件不存在：{src}")
        return 1
    snap = snapshot(src)
    meta = {}
    for kv in args.meta:
        if "=" not in kv:
            print(f"[FAIL] --meta 需要 key=value：{kv}")
            return 2
        k, v = kv.split("=", 1)
        meta[k] = v
    if meta:
        snap = {"meta": meta, **snap}
    out = Path(args.dst)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snap, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
    n_cells = sum(len(r["cells"]) for s in snap["sheets"] for r in s["rows"])
    print(f"[OK] {src.name} → {out}")
    print(f"     sheet {snap['sheetCount']} / 行 "
          f"{sum(len(s['rows']) for s in snap['sheets'])} / 非空单元格 {n_cells} / "
          f"嵌入图片 {len(snap['media'])} / 锚点 {sum(len(s['anchors']) for s in snap['sheets'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
