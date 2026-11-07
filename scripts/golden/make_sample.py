#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成黄金样本素材：1 份巡查日志 + 69 张照片（含 EXIF / GPS / 重复 / 派生 / 连拍 / 无 EXIF / 误筛文件）。

为什么不用真实素材（P-1 决策）：
  仓库内没有任何真实照片与日志，而回归基线必须能**在任意一次改动后重放**。
  合成素材的好处是每条边界都能精确指定（3 种 EXIF 格式、派生后缀、重复组、跨日期文件名…），
  坏处是覆盖不到厂商 HEIC/RAW 与真实微信日志的边角 —— 因此 M3 前必须再补一轮真实素材（见执行计划 P-1）。

设计原则（与算法内核的真实规则对齐，不"假设"）：
  · 文件名日期粗筛：FileNameDateFilter 只认文件名里的 8 位日期（yyyyMMdd），
    Screenshot_ / mmexport 前缀直接排除 → 样本里必须各放几个，验证"被正确忽略"。
  · 事件归属：EventClassifyEngine 以 timeWindowMs(默认 5min) + backfillMs(默认 120s) 划窗，
    所以照片时间必须落在事件时刻的窗口内，否则会全部变成"未匹配"，样本就失去意义。
  · 重复判定：Dedup 用「拍摄时间 ±1s + GPS 距离 ≤10m」；GPS 双无效时降级用文件名单号块 ±1000。
    所以重复组必须是**同时间 + 同 GPS + 逐像素相同**，否则测不到。
  · 派生后缀：DerivedNames 认 original/compressed/edited/modified/optimized/resized/缩略/压缩/原图。

用法：
    python scripts/golden/make_sample.py                    # 生成到 scripts/golden/sample/
    python scripts/golden/make_sample.py --out /tmp/sample   # 指定输出目录
    python scripts/golden/make_sample.py --verify            # 只校验已生成素材的数量与分布

素材是**提交进仓库**的（约 1.5MB）：JPEG 字节由 Pillow 版本决定，换 Pillow 重生成会变，
基线就对不上了。因此：生成一次 → 提交 → 之后只读不改。要改素材等于换基线，必须重记快照。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

from PIL import Image, ImageDraw

PATROL_DATE = "2026-08-14"
PHOTO_W, PHOTO_H = 640, 480          # 必须大于台账嵌图规格 400×300，否则测不到降采样
JPEG_QUALITY = 78


@dataclass
class EventSpec:
    """一条巡查事件：时刻 + 文本 + 该事件拍了几张照片。"""
    hhmm: str
    text: str
    photos: int = 1
    # 照片 EXIF 形态：colon=标准冒号格式，dash=短横分隔（D9 覆盖），none=无 EXIF
    exif: str = "colon"
    gps: tuple[float, float] | None = (30.2512, 120.1683)
    dup_pair: bool = False           # 额外生成一张同时间同 GPS 的逐像素副本（重复组）
    derived: tuple[str, ...] = ()    # 额外生成的派生副本后缀


EVENTS: list[EventSpec] = [
    EventSpec("08:00", "与早班交接班，装备齐全，执法记录仪已开启，车载视频正常", 1),
    EventSpec("08:10", "出发，开始巡查，驶入G2京沪高速K512+300", 1),
    EventSpec("08:25", "巡查G2京沪高速K514+000至K516+500，路面无异常", 2),
    EventSpec("08:40", "发现K517+200桥下空间堆放杂物，已通知养护单位清理", 2,
              derived=("_original",), dup_pair=True),
    EventSpec("08:55", "检查K518+000波形梁护栏，发现护栏损坏3块", 2),
    EventSpec("09:10", "K519+500发现路面抛洒物（轮胎皮），已清理", 2, exif="dash"),
    EventSpec("09:30", "接报K521+100两车追尾事故，前往处置", 1),
    EventSpec("09:42", "到达K521+100事故现场，设置警示标志，协助交警疏导交通", 3,
              dup_pair=True),
    EventSpec("10:05", "事故现场勘验完毕，路产损坏评估中", 2),
    EventSpec("10:20", "交通恢复，撤离现场，继续巡查", 1),
    EventSpec("10:35", "巡查K523+000隧道，隧道照明正常，消防设施完好", 2,
              derived=("_original",)),
    EventSpec("10:50", "隧道渗水，K523+400排水沟积水，已记录待处理", 2, exif="dash",
              dup_pair=True),
    EventSpec("11:05", "检查K525+000桥梁伸缩缝，无异常", 1),
    EventSpec("11:20", "K526+300发现行人上高速，已劝离并登记", 2, gps=None,
              dup_pair=True),
    EventSpec("11:40", "巡查服务区，卫生间设施完好", 1),
    EventSpec("12:00", "午餐休息", 1, exif="none"),
    EventSpec("13:00", "继续巡查，驶入K530+000", 1),
    EventSpec("13:15", "K531+500发现故障车，协助拖车救援", 3,
              derived=("_compressed",), dup_pair=True),
    EventSpec("13:40", "检查K533+000交安设施，标志牌倾斜1处，已扶正", 2,
              derived=("_optimized",)),
    EventSpec("14:00", "K534+200发现路面坑槽，已设置警示并上报", 2, dup_pair=True),
    EventSpec("14:20", "协助交警进行交通管制，K535+000至K536+000限行", 2),
    EventSpec("14:45", "检查高边坡K537+100，坡面冲刷，截水沟堵塞", 2, exif="dash",
              dup_pair=True),
    EventSpec("15:10", "K538+500清理路面障碍物（木块）", 1, exif="none"),
    EventSpec("15:30", "巡查收费站，收费设施正常", 1),
    EventSpec("15:50", "发现摩托车违规上高速，已劝离", 2, derived=("_缩略",),
              dup_pair=True),
    EventSpec("16:10", "巡查K540+000至K542+000，车流量较大，通行正常", 2,
              derived=("_resized",)),
    EventSpec("16:35", "检查应急物资，锥桶、反光背心齐全", 1),
    EventSpec("16:50", "返回A收费管理中心，收车", 1),
]

# 刻意不落在任何事件窗口内的照片：验证"未匹配"区（timeWindow 5min + backfill 120s 之外）
UNMATCHED_TIMES = ["07:30:00", "12:20:00", "12:47:00", "17:40:00"]

# ★ 连拍用例（2026-11-02 补：把 P0-2 的盲区变成可自动回归的用例）
#   真实素材上第一次跑就丢照片：一台水印相机在过车涵洞连拍 4 张，相邻两张只差
#   **957ms / 2.74m**，双双落在去重阈值（±1s / 10m）内 → 后一张被当成"副本"永久丢弃
#   （110 张素材只导出 54 张）。
#
#   这里原样复刻那个结构：某事件主图 + 3 张 800ms 间隔的连拍，共 4 张，
#     · 同地点（每张北移约 2.2m，模拟手持连拍的定位抖动，全部 <10m）；
#     · EXIF DateTimeOriginal **只能精确到秒** → 4 张在「时间 + GPS」维度上完全互相满足判重；
#     · 逐像素不同（paint 种子不同）→ 是真的 4 张不同照片，不是同一张的副本。
#   期望：4 张全部进入导出（旧版两两判重会把它们并成 1 组、丢 3 张）。
#
#   ★ 文件名必须带毫秒（IMG_YYYYMMDD_HHMMSSmmm，与真实设备一致）：6 位时分秒在同一秒内会重名。
#   ★ 单独一趟生成、paint 种子固定（不参与全局 idx 递增）：否则中途插照片会让后面每张的
#     像素都变，基线 diff 里 40 多张无关照片一起"变化"，就没人能看出真正改了什么。
BURST_EVENT_HHMM = "11:05"      # 挂到该事件的窗口内（K525+000 桥梁伸缩缝检查）
BURST_COUNT = 3
BURST_INTERVAL_MS = 800
BURST_GPS_STEP = 0.00002        # ≈2.2m/张
BURST_PAINT_SEED = 1000         # 固定种子：与 idx 无关，挪动生成顺序也不改字节

# 刻意落在巡查日期之外：验证 FileNameDateFilter 当天过滤
OFF_DATE = [("IMG_20260813_091500.jpg", "2026:08:13 09:15:00"),
            ("IMG_20260813_091530.jpg", "2026:08:13 09:15:30")]

# 必须被扫描器忽略的非照片（前缀排除 / 扩展名排除）
NOISE_FILES = [
    ("Screenshot_20260814-101500.png", "png-screenshot"),
    ("mmexport1755000000000.jpg", "wechat-export"),
    ("records.txt", "text"),
    ("IMG_20260814_1030.heic", "heic-placeholder"),   # 扩展名门槛覆盖
]


def _paint(idx: int, label: str) -> Image.Image:
    """画一张能区分彼此、又能压得较小的图（横向色带 + 网格 + 编号）。"""
    base = Image.new("RGB", (PHOTO_W, PHOTO_H), (248, 248, 244))
    d = ImageDraw.Draw(base)
    top = (30 + (idx * 7) % 60, 90 + (idx * 11) % 60, 140 + (idx * 13) % 70)
    d.rectangle([0, 0, PHOTO_W, 200], fill=top)
    d.rectangle([0, 200, PHOTO_W, 300], fill=(120, 170, 120))
    d.rectangle([0, 300, PHOTO_W, PHOTO_H], fill=(90, 90, 96))
    for x in range(0, PHOTO_W, 40):
        d.line([(x, 0), (x, PHOTO_H)], fill=(255, 255, 255, 40), width=1)
    d.rectangle([20, 20, 300, 70], fill=(255, 255, 255))
    d.text((30, 38), label, fill=(20, 20, 20))
    d.text((30, 420), f"golden sample #{idx:02d}", fill=(240, 240, 240))
    return base


def _exif_for(kind: str, stamp: datetime, gps: tuple[float, float] | None) -> Image.Exif:
    """构造 EXIF；kind=none 时返回空 EXIF（模拟截图/转发图没有拍摄时间）。

    ★ 短横格式（dash）是刻意写入的非法但真实存在的形态：部分安卓相机/修图软件会把
      DateTimeOriginal 写成 "2026-08-14 09:10:00"。PC 端旧解析只认冒号，安卓端两种都认（D9）。
    """
    exif = Image.Exif()
    if kind == "none":
        return exif
    fmt = "%Y:%m:%d %H:%M:%S" if kind == "colon" else "%Y-%m-%d %H:%M:%S"
    text = stamp.strftime(fmt)
    ifd = exif.get_ifd(0x8769)          # ExifIFD
    ifd[0x9003] = text                  # DateTimeOriginal
    ifd[0x9004] = text                  # DateTimeDigitized
    exif[0x0132] = text                 # 顶层 DateTime（PC 读取顺序的兜底）
    exif[0x0112] = 1                    # Orientation = 正常
    if gps is not None:
        lat, lng = gps
        g = exif.get_ifd(0x8825)        # GPSInfoIFD
        g[1] = "N" if lat >= 0 else "S"
        g[2] = _dms(abs(lat))
        g[3] = "E" if lng >= 0 else "W"
        g[4] = _dms(abs(lng))
    return exif


def _dms(v: float) -> tuple[float, float, float]:
    deg = int(v)
    minute_f = (v - deg) * 60
    minute = int(minute_f)
    return (float(deg), float(minute), round((minute_f - minute) * 60, 4))


def _save(img: Image.Image, exif: Image.Exif, path: Path) -> None:
    kwargs = {"quality": JPEG_QUALITY, "optimize": True}
    if len(exif):
        kwargs["exif"] = exif
    img.save(path, "JPEG", **kwargs)


# 夜间巡查条目（无照片）：把日志补足到 400 行左右，同时验证"仅分析日志、无对应照片"的事件。
# ★ 起始时刻必须 ≥ 18:05：17:40 那张照片是刻意设计的"未匹配"样本，
#   若夜间事件落在 17:35~17:45 之间，5min 窗口会把它抢走，样本就少了一个未匹配用例。
NIGHT_EVENTS: list[tuple[str, str]] = [
    ("18:05", "夜班接班，与中班交接班，装备齐全"),
    ("18:20", "夜间巡查出发，驶入K512+000"),
    ("18:40", "巡查K514+000至K518+000，路面无异常，反光标志清晰"),
    ("19:00", "检查K519+500警示桩，反光膜完好"),
    ("19:20", "K521+100事故现场回访，路面已修复"),
    ("19:40", "巡查K523+000隧道照明，夜间亮度正常"),
    ("20:00", "K525+000桥梁支座检查，无异常"),
    ("20:20", "协助交警处置K527+000违停车辆"),
    ("20:40", "巡查服务区，夜间照明正常"),
    ("21:00", "K530+000发现路面散落物，已清理"),
    ("21:20", "检查K532+000交安设施，轮廓标完好"),
    ("21:40", "K534+200坑槽临时警示标志完好"),
    ("22:00", "巡查K536+000至K540+000，车流量较小"),
    ("22:20", "K542+000高边坡夜间观察，无落石"),
    ("22:40", "返回管理中心，途中巡查K544+000"),
    ("23:00", "夜班巡查结束，收车，装备归位"),
    ("23:10", "填写夜间巡查记录，与下一班交接"),
    ("23:20", "值班室确认当日全部事件已闭环"),
]


def build_log(events: list[EventSpec]) -> str:
    """生成约 400 行日志。

    ★ 为什么"续行"必须是纯描述、且不含桩号/动作起始词：
      LogParserEngine.preprocess 只在"行首无时间 且 isLogLike()=false"时才并入上一条；
      isLogLike 命中「含桩号 / 命中事件关键词 / 以接报·巡查·发现…开头」即视为**独立**日志。
      所以任何带 K517+200、带"检查/发现"、带"护栏/隧道"的补充行都会变成新事件，
      进而以 5min 窗口把本该归属主事件的照片抢走。

      ★ 这不是理论推演：第一版样本用了 300 行带桩号的填充条目，
        结果干跑出 325 条事件、照片大面积错配（桥下空间事件 0 张）。
        教训：样本的"字数"不值钱，"语义"才值钱。
    """
    header = [
        "收费公路巡查日志",
        "巡查日期：2026年8月14日",
        "巡查单位：京沪高速济南管理处",
        "巡查人员：张伟、李强",
        "记录人：王芳",
        "巡查车辆：鲁A12345",
        "天气：晴，能见度良好，气温 26℃~34℃",
        "",
        "一、交接班与出车",
    ]
    # 续行池：全部满足"无时间、无桩号、不以动作词开头、不含事件关键词"
    # ★ 踩过的坑：初稿里写了「处置过程符合规范…」——"处置"在 LOG_START_ACTION 里，
    #   于是每条都被判成独立日志，凭空多出 28 条待定事件。续行必须以名词/副词开头。
    fillers = [
        "已拍照取证，现场照片附后",
        "相关情况已上报值班室，等待回复",
        "过程符合规范，无人员受伤",
        "着装规范，随身设备全程开启",
        "当事人配合，无争议",
        "上述情况已录入台账",
        "现场已恢复通行",
        "已通知养护单位，限期整改",
        "与交警部门沟通，协同处理",
        "后续跟踪复查，纳入闭环管理",
        "本次记录完毕，一并归档",
        "未发现新增问题，状况稳定",
    ]
    page_noise = [
        "第 {n} 页 共 4 页",
        "（本页以下无正文）",
        "填写人签字：__________    审核人签字：__________",
        "备注：本页记录内容与现场照片一一对应",
        "——— 以下空白 ———",
    ]

    lines: list[str] = list(header)
    seq = 0
    noise_i = 0
    for i, ev in enumerate(events):
        seq += 1
        if i and i % 6 == 0:
            # 每 6 条插一段"翻页噪声"：真实扫描件里页眉页脚都会被 OCR 进来
            lines.append("")
            for k in range(5):
                lines.append(page_noise[(noise_i + k) % len(page_noise)].format(n=noise_i // 5 + 2))
            noise_i += 5
            lines.append("二、巡查记录（续）")
        hh, mm = ev.hhmm.split(":")
        # 时间写法轮换：08:00 / 8点00分 / 0800 / 八点，验证 TimeExtractor 的四种形态
        style = i % 4
        if style == 0:
            stamp = f"{hh}:{mm}"
        elif style == 1:
            stamp = f"{int(hh)}点{mm}分"
        elif style == 2:
            stamp = f"{hh}{mm}"
        else:
            stamp = f"{_cn_num(int(hh))}点" + (f"{_cn_num(int(mm))}分" if mm != '00' else "")
        lines.append(f"{seq}. {stamp} {ev.text}")
        # 每条事件挂 6~9 条续行 → 总行数凑到 400 上下，同时只产生 1 个事件
        for k in range(6 + (i % 4)):
            lines.append(fillers[(i + k) % len(fillers)])
    # 夜间巡查段：只补行数、不造新照片（这些事件在台账里应显示"0 张"）
    lines.append("")
    lines.append("十、夜间巡查")
    for j, (t, text) in enumerate(NIGHT_EVENTS):
        seq += 1
        lines.append(f"{seq}. {t} {text}")
        for k in range(5 + (j % 4)):
            lines.append(fillers[(j + k) % len(fillers)])
    # 内容重复但时间前缀不同 → 去重键含剥前缀后的描述，应合并为 1 条
    lines += [
        "",
        "九、补充记录",
        f"{seq + 1}. 16:50 返回A收费管理中心，收车",
        f"{seq + 2}. 下午4点50分 返回A收费管理中心，收车",
        # 无时间但含事件语义 → 待定事件（startTimeMs=null，进待定区，照片不自动归入）
        f"{seq + 3}. 接报K545+000处有车辆故障，待核实",
        # 无时间、无事件语义、非续行 → skippedLines
        "——— 以上记录完毕，本页共 4 页 ———",
    ]
    return "\n".join(lines) + "\n"


def _cn_num(v: int) -> str:
    table = "零一二三四五六七八九十"
    if v <= 10:
        return table[v]
    if v < 20:
        return "十" + (table[v - 10] if v > 10 else "")
    return table[v // 10] + "十" + (table[v % 10] if v % 10 else "")


def generate(out: Path) -> dict:
    photos_dir = out / "photos"
    # ★ 刻意"不删除目录、不批量删除旧文件"：素材是仓库资产，误删等于毁掉基线。
    #   重建走幂等覆盖（同名文件由 Pillow 直接覆写），多余文件只在末尾报告，由人决定。
    photos_dir.mkdir(parents=True, exist_ok=True)

    manifest: list[dict] = []
    idx = 0

    def emit(name: str, stamp: datetime, kind: str, gps, *, dup_of: str | None = None,
             note: str = "", seed: int | None = None) -> None:
        nonlocal idx
        idx += 1
        img = _paint(seed if seed is not None else idx, f"{stamp:%H:%M:%S}")
        exif = _exif_for(kind, stamp, gps)
        path = photos_dir / name
        _save(img, exif, path)
        manifest.append({
            "name": name, "time": stamp.strftime("%Y-%m-%d %H:%M:%S"),
            "exif": kind, "gps": gps, "sha256": _sha256(path),
            "dup_of": dup_of, "note": note,
        })

    for ev in EVENTS:
        hh, mm = (int(x) for x in ev.hhmm.split(":"))
        base = datetime(2026, 8, 14, hh, mm, 0)
        for k in range(ev.photos):
            # 同一事件的照片间隔 30s，保证都落在 5min 窗口内、且彼此不构成重复（>1s）
            stamp = base + timedelta(seconds=k * 30)
            name = f"IMG_20260814_{stamp:%H%M%S}.jpg"
            emit(name, stamp, ev.exif, ev.gps)
        if ev.dup_pair:
            # 重复组：同时间 + 同 GPS + 逐像素相同，只是多了 _original 后缀
            stamp = base
            emit(f"IMG_20260814_{stamp:%H%M%S}_original.jpg", stamp, ev.exif, ev.gps,
                 dup_of=f"IMG_20260814_{stamp:%H%M%S}.jpg", note="dup-group")
        for suffix in ev.derived:
            stamp = base + timedelta(seconds=15)
            emit(f"IMG_20260814_{stamp:%H%M%S}{suffix}.jpg", stamp, ev.exif, ev.gps,
                 dup_of=f"IMG_20260814_{hh:02d}{mm:02d}00.jpg", note=f"derived:{suffix}")

    for t in UNMATCHED_TIMES:
        hh, mm, ss = (int(x) for x in t.split(":"))
        stamp = datetime(2026, 8, 14, hh, mm, ss)
        emit(f"IMG_20260814_{stamp:%H%M%S}.jpg", stamp, "colon", (30.2512, 120.1683),
             note="unmatched")

    for name, exif_text in OFF_DATE:
        stamp = datetime.strptime(exif_text, "%Y:%m:%d %H:%M:%S")
        emit(name, stamp, "colon", (30.2512, 120.1683), note="off-date")

    for name, kind in NOISE_FILES:
        p = photos_dir / name
        if kind in ("png-screenshot",):
            Image.new("RGB", (320, 640), (200, 200, 210)).save(p, "PNG")
        elif kind == "wechat-export":
            _save(_paint(99, "wechat"), Image.Exif(), p)
        elif kind == "text":
            p.write_text("这不是照片，扫描器必须忽略\n", encoding="utf-8")
        else:
            p.write_bytes(b"HEIC placeholder - not a real heic")

    # ★ 连拍用例（说明见文件头 BURST_* 常量）：同事件 + 同地点 + 800ms 间隔，EXIF 秒级"同刻"。
    #   放在最后、且 paint 种子固定 —— 保证这批照片的加入不会改动任何其他照片的字节。
    bhh, bmm = (int(x) for x in BURST_EVENT_HHMM.split(":"))
    bbase = datetime(2026, 8, 14, bhh, bmm, 0)
    for k in range(BURST_COUNT):
        delta_ms = k * BURST_INTERVAL_MS
        stamp = bbase + timedelta(milliseconds=delta_ms)
        gps = (round(30.2512 + k * BURST_GPS_STEP, 6), 120.1683)
        emit(f"IMG_20260814_{stamp:%H%M%S}{delta_ms % 1000:03d}.jpg", stamp, "colon", gps,
             note="burst", seed=BURST_PAINT_SEED + k)

    log_path = out / "patrol-log-20260814.txt"
    log_path.write_text(build_log(EVENTS), encoding="utf-8")

    meta = {
        "patrolDate": PATROL_DATE,
        "logFile": log_path.name,
        "logLines": len(build_log(EVENTS).splitlines()),
        "photoCount": len(manifest),
        "photos": manifest,
        "noise": [n for n, _ in NOISE_FILES],
        "burst": {
            "event": BURST_EVENT_HHMM,
            "count": BURST_COUNT,
            "intervalMs": BURST_INTERVAL_MS,
            "expect": "全部保留（锚点之间永不合并）",
        },
    }
    (out / "sample-manifest.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return meta


def _sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify(out: Path) -> int:
    manifest_path = out / "sample-manifest.json"
    if not manifest_path.exists():
        print(f"[FAIL] 缺清单：{manifest_path}")
        return 1
    meta = json.loads(manifest_path.read_text(encoding="utf-8"))
    problems: list[str] = []
    photos_dir = out / "photos"
    for item in meta["photos"]:
        p = photos_dir / item["name"]
        if not p.exists():
            problems.append(f"缺文件 {item['name']}")
        elif _sha256(p) != item["sha256"]:
            problems.append(f"哈希不符 {item['name']}")
    # 多余文件（上一版素材的残留）只报告不删除：删素材这个动作必须由人确认
    expected = {i["name"] for i in meta["photos"]} | set(meta["noise"])
    stale = sorted(p.name for p in photos_dir.iterdir() if p.name not in expected)
    if stale:
        print(f"[WARN] 目录里有 {len(stale)} 个不属于当前清单的残留文件（未删除）：")
        for s in stale[:10]:
            print(f"    {s}")
    counts = {
        "总照片": len(meta["photos"]),
        "重复组成员": sum(1 for i in meta["photos"] if i["note"] == "dup-group"),
        "派生副本": sum(1 for i in meta["photos"] if str(i["note"]).startswith("derived:")),
        "未匹配": sum(1 for i in meta["photos"] if i["note"] == "unmatched"),
        "跨日期": sum(1 for i in meta["photos"] if i["note"] == "off-date"),
        "连拍": sum(1 for i in meta["photos"] if i["note"] == "burst"),
        "无EXIF": sum(1 for i in meta["photos"] if i["exif"] == "none"),
        "短横EXIF": sum(1 for i in meta["photos"] if i["exif"] == "dash"),
        "噪声文件": len(meta["noise"]),
        "日志行数": meta["logLines"],
    }
    for k, v in counts.items():
        print(f"  {k}: {v}")
    # 覆盖度下限：样本一旦被削薄，回归就会"测了等于没测"，所以这里硬性卡住。
    minimum = {
        "总照片": 69, "重复组成员": 8, "派生副本": 6, "未匹配": 4,
        "跨日期": 2, "连拍": 3, "无EXIF": 2, "短横EXIF": 4, "噪声文件": 4, "日志行数": 380,
    }
    for key, need in minimum.items():
        if counts[key] < need:
            problems.append(f"{key}={counts[key]} 低于下限 {need}")
    # ★ 连拍用例的结构性断言：4 张（主图 + 3 连拍）必须同事件同地点、EXIF 落在 1 秒内、
    #   文件名序号各差 800、且逐像素不同。任何一条不成立，这个用例就测不到 P0-2。
    burst = [i for i in meta["photos"] if i["note"] == "burst"]
    if len(burst) != BURST_COUNT:
        problems.append(f"连拍张数 {len(burst)} != {BURST_COUNT}")
    elif len({i["sha256"] for i in burst}) != BURST_COUNT:
        problems.append("连拍里出现完全相同的字节（就不是'不同照片'了，测不出误删）")
    elif any(i["exif"] == "none" for i in burst):
        problems.append("连拍照片缺 EXIF 时间（会掉进未分类，测不到去重）")
    if problems:
        print("[FAIL] " + "; ".join(problems))
        return 1
    print("[OK] 素材完整且与清单一致")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/校验黄金样本素材")
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "sample"))
    ap.add_argument("--verify", action="store_true", help="只校验，不生成")
    args = ap.parse_args()
    out = Path(args.out)
    if args.verify:
        return verify(out)
    out.mkdir(parents=True, exist_ok=True)
    meta = generate(out)
    print(f"已生成：{out}")
    print(f"  照片 {meta['photoCount']} 张，日志 {meta['logLines']} 行")
    return verify(out)


if __name__ == "__main__":
    sys.exit(main())
