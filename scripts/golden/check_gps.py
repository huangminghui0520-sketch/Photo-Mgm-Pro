#!/usr/bin/env python3
"""校验"照片的 GPS 是否真的读到了"——P0（ACCESS_MEDIA_LOCATION）修复的验收工具。

两个检查口，对应 P0 缺陷的两个观测点：

  --prefs <photo_mgm_settings.xml>
      App 缓存（SharedPreferences）里的 cache_photos。这里 latitude/longitude 全 null
      就是"App 没读到定位"，与导出环节无关——修复前真机实测 65 条全 null。

  --photos <ZIP 或目录>
      导出产物里的照片字节。把 EXIF GPS 标签按原值读出来：
      修复前是"标签还在、值被清零"（系统剥离发生在**读取那一刻**，长度不变、非零值被清）；
      修复后应读到与素材一致的非零度分秒。

退出码：0 = 符合预期（按 --expect 判定），1 = 不符合。
默认 --expect auto：有 GPS 才算通过（修复后验收口径）。
负向验证（确认"没权限就真的读不到"）用 --expect none。

用法：
  python scripts/golden/check_gps.py --prefs .p2work/run/photo_mgm_settings.xml
  python scripts/golden/check_gps.py --photos .p2work/run/raw/xxx.zip
  python scripts/golden/check_gps.py --photos .p2work/run/raw --expect none
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import zipfile

GPS_IFD = 0x8825
TAG_LAT_REF, TAG_LAT, TAG_LON_REF, TAG_LON = 1, 2, 3, 4


def _dms(v) -> float | None:
    """EXIF 的度分秒 -> 十进制度。PIL 可能给 (d, m, s) 元组，也可能给单个有理数。

    ★ NaN 必须当"没有 GPS"：系统剥离后的残留形态是 `(nan, nan, nan)`——
      标签还在、值不是有效数字。若只判 `== 0.0`，NaN 会被算成"有定位"，
      这个校验器就会把 P0 缺陷判成 PASS（实测踩过：52 张全 nan 却报 PASS）。
    """
    import math

    try:
        if isinstance(v, (tuple, list)):
            d = float(v[0])
            m = float(v[1]) if len(v) > 1 else 0.0
            s = float(v[2]) if len(v) > 2 else 0.0
            if any(math.isnan(x) for x in (d, m, s)):
                return None
            return d + m / 60.0 + s / 3600.0
        f = float(v)
        return None if math.isnan(f) else f
    except (TypeError, ValueError, ZeroDivisionError):
        return None


def exif_gps(data: bytes) -> tuple[float, float] | None:
    """从 JPEG/PNG 字节里读 GPS；缺失或全零返回 None（全零 = 系统剥离后的典型残留）。"""
    import io

    from PIL import Image

    try:
        with Image.open(io.BytesIO(data)) as im:
            ifd = im.getexif().get_ifd(GPS_IFD)
    except Exception:
        return None
    if not ifd:
        return None
    lat = _dms(ifd.get(TAG_LAT))
    lon = _dms(ifd.get(TAG_LON))
    if lat is None or lon is None:
        return None
    if ifd.get(TAG_LAT_REF) == b"S":
        lat = -lat
    if ifd.get(TAG_LON_REF) == b"W":
        lon = -lon
    if lat == 0.0 and lon == 0.0:
        return None
    return round(lat, 6), round(lon, 6)


def scan_photos(root: pathlib.Path) -> dict:
    out: dict[str, object] = {"source": str(root), "total": 0, "withGps": 0, "withoutGps": 0}
    rows: list[dict] = []
    if root.is_file() and root.suffix.lower() == ".zip":
        with zipfile.ZipFile(root) as z:
            for n in sorted(z.namelist()):
                if n.endswith("/") or not n.lower().endswith((".jpg", ".jpeg", ".png")):
                    continue
                out["total"] += 1
                gps = exif_gps(z.read(n))
                rows.append({"name": n.rsplit("/", 1)[-1], "zipPath": n, "gps": gps})
    elif root.is_dir():
        for p in sorted(root.rglob("*")):
            if not p.is_file() or p.suffix.lower() not in (".jpg", ".jpeg", ".png"):
                continue
            out["total"] += 1
            rows.append({"name": p.name, "path": str(p), "gps": exif_gps(p.read_bytes())})
    else:
        raise SystemExit(f"不是 ZIP 也不是目录：{root}")

    out["withGps"] = sum(1 for r in rows if r["gps"])
    out["withoutGps"] = out["total"] - out["withGps"]
    out["sample"] = [r for r in rows if r["gps"]][:3]
    return out


def scan_prefs(xml: pathlib.Path) -> dict:
    """从 SharedPreferences XML 里抽 cache_photos 的 latitude/longitude 统计。

    不引入 XML 解析器：cache_photos 的值是 HTML 转义过的 JSON 字符串，直接正则比
    "先反解实体再 json.loads" 更抗历史格式漂移，且这段只看两个字段。
    """
    import html
    import re

    raw = xml.read_text(encoding="utf-8", errors="replace")
    m = re.search(r'name="cache_photos">(.*?)</string>', raw, re.S)
    if not m:
        raise SystemExit("prefs 里没有 cache_photos：App 还没解析过，或键名变了")
    photos = json.loads(html.unescape(m.group(1)))
    lat_ok = sum(1 for p in photos if p.get("latitude") is not None and p.get("longitude") is not None)
    return {
        "source": str(xml),
        "total": len(photos),
        "withGps": lat_ok,
        "withoutGps": len(photos) - lat_ok,
        "sample": [p for p in photos if p.get("latitude") is not None][:3],
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--prefs", help="App 的 photo_mgm_settings.xml")
    ap.add_argument("--photos", help="导出的 ZIP 或解压后的照片目录")
    ap.add_argument("--expect", choices=("auto", "any", "none"), default="auto",
                    help="auto/any=要求读到 GPS；none=要求全部读不到（负向验证）")
    ap.add_argument("--json", action="store_true", help="只输出 JSON")
    a = ap.parse_args()

    if not a.prefs and not a.photos:
        ap.error("--prefs 或 --photos 至少给一个")

    reports = []
    if a.prefs:
        reports.append(scan_prefs(pathlib.Path(a.prefs)))
    if a.photos:
        reports.append(scan_photos(pathlib.Path(a.photos)))

    ok = True
    for r in reports:
        if a.expect == "none":
            passed = r["withGps"] == 0
        else:
            passed = r["withGps"] > 0
        r["pass"] = passed
        ok = ok and passed
        if a.json:
            continue
        print(f"  {r['source']}")
        print(f"    照片 {r['total']} 张 | 读到 GPS {r['withGps']} 张 | 无 GPS {r['withoutGps']} 张"
              f" → {'PASS' if passed else 'FAIL'}（expect={a.expect}）")
        for s in r["sample"]:
            # 两个来源的样例形状不同：照片来源带 gps（元组），prefs 来源带 latitude/longitude。
            label = s.get("name") or s.get("displayName") or "?"
            if "gps" in s:
                detail = s["gps"]
            else:
                detail = f"({s.get('latitude')}, {s.get('longitude')})"
            print(f"    样例 {label}  gps={detail}")

    if a.json:
        print(json.dumps({"ok": ok, "reports": reports}, ensure_ascii=False, indent=1))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
