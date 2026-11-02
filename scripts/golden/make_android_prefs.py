#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 Android 端 SettingsStore 的两个 SharedPreferences XML（用于 P-2 基线注入）。

为什么需要它
------------
安卓端「日志」只有一个可编辑文本框 + [粘贴导入]，**没有文件导入入口**。416 行中文日志
没法用 `adb shell input text` 输入（input text 只支持 ASCII，且逐字符注入 400 行不现实），
剪贴板注入又要装额外 App、且 Android 10+ 限制后台读剪贴板。

所以 P-2 走「直接写 prefs」这条路：App 启动时 SettingsStore 从 prefs 读 date / source_dirs /
output_dir / logs_text，等价于用户上次填好的状态。这是**输入注入**，不是绕过业务逻辑——
解析、分类、导出仍然由 App 的真实代码路径执行。

为什么用 Python 生成而不是手写 heredoc
--------------------------------------
① XML 转义：日志里有 `&`、`<`、`>` 时手写必然出错，出错后 SharedPreferences 会**静默丢弃整个文件**
   （解析异常被 catch），表现为"注入成功但 App 里啥也没有"，极难查。
② UTF-8 无 BOM：Windows 下 heredoc 常带 BOM 或 CRLF，`XmlPullParser` 遇到 BOM 会抛
   "Unexpected token"，同样是静默失败。
③ 可校验：生成时回读一遍 XML，断言 key 齐全、logs_text 与源文件逐字节一致。

关键格式约定（照抄 SharedPreferences 自己的写法，别自创）
--------------------------------------------------------
    <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
    <map>
        <string name="date">2026-08-14</string>
        <set name="source_dirs"><string>DCIM/Camera</string></set>
        <int name="naming_template" value="1" />
    </map>
字符串集合是 `<set>` 包 `<string>`；int 用 value 属性；**不要**加 `<string>` 之外的包装。

用法：
    python scripts/golden/make_android_prefs.py --out /tmp/prefs
    # 产出 /tmp/prefs/photo_mgm_settings.xml 与 /tmp/prefs/photo_mgm_logs.xml
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# 与 SettingsStore.kt 的常量一一对应（改名必须同步改这里，脚本会做断言）
PREFS_SETTINGS = "photo_mgm_settings"
PREFS_LOGS = "photo_mgm_logs"
KEY_DATE = "date"
KEY_DIRS = "source_dirs"
KEY_OUTPUT = "output_dir"
KEY_TEMPLATE = "naming_template"
KEY_LOGS_TEXT = "logs_text"


def _esc(text: str) -> str:
    """按 XML 1.0 转义。不用 xml.sax.saxutils.escape 是因为它不处理 `"` 之外的控制字符，
    而 SharedPreferences 的读取器对非法控制字符同样会抛异常。"""
    out = []
    for ch in text:
        if ch == "&":
            out.append("&amp;")
        elif ch == "<":
            out.append("&lt;")
        elif ch == ">":
            out.append("&gt;")
        elif ch == '"':
            out.append("&quot;")
        elif ch == "'":
            out.append("&apos;")
        elif ch in "\n\r\t":
            out.append(ch)
        elif ord(ch) < 0x20:
            # XML 1.0 不允许 0x00-0x08 / 0x0B / 0x0C / 0x0E-0x1F；替换为空格而不是丢弃，
            # 保持字符位置（日志里有的话也是粘贴引入的噪声）。
            out.append(" ")
        else:
            out.append(ch)
    return "".join(out)


def build_settings(date: str, dirs: list[str], output_dir: str, template: int) -> str:
    lines = [
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
        "<map>",
        f'    <string name="{KEY_DATE}">{_esc(date)}</string>',
    ]
    if dirs:
        lines.append(f'    <set name="{KEY_DIRS}">')
        for d in dirs:
            lines.append(f"        <string>{_esc(d)}</string>")
        lines.append("    </set>")
    lines.append(f'    <string name="{KEY_OUTPUT}">{_esc(output_dir)}</string>')
    lines.append(f'    <int name="{KEY_TEMPLATE}" value="{int(template)}" />')
    lines.append("</map>")
    return "\n".join(lines) + "\n"


def build_logs(logs_text: str) -> str:
    return "\n".join([
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
        "<map>",
        f'    <string name="{KEY_LOGS_TEXT}">{_esc(logs_text)}</string>',
        "</map>",
    ]) + "\n"


def _verify(path: Path, expect: dict[str, str]) -> list[str]:
    """回读校验：能解析、key 齐全、值逐字符相等。返回问题列表（空=通过）。"""
    problems: list[str] = []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        return [f"{path.name} 不是合法 XML：{e}（App 会静默丢弃该文件）"]
    if root.tag != "map":
        problems.append(f"{path.name} 根节点应为 <map>，实际 <{root.tag}>")
    seen: dict[str, str] = {}
    for node in root:
        name = node.get("name", "")
        if node.tag == "set":
            seen[name] = "\x00".join((c.text or "") for c in node)
        elif node.tag == "int":
            seen[name] = node.get("value", "")
        else:
            seen[name] = node.text or ""
    for k, v in expect.items():
        if k not in seen:
            problems.append(f"{path.name} 缺 key `{k}`")
        elif seen[k] != v:
            problems.append(f"{path.name} key `{k}` 值不一致（期望 {len(v)} 字符，实际 {len(seen[k])}）")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description="生成 Android SharedPreferences 注入文件")
    ap.add_argument("--log", default=None, help="日志文本文件（默认 scripts/golden/sample/patrol-log-20260814.txt）")
    ap.add_argument("--date", default="2026-08-14", help="巡查日期 yyyy-MM-dd（必须与日志内容一致）")
    ap.add_argument("--dir", action="append", default=None,
                    help="源目录相对路径（可重复，默认 DCIM/Camera；对应 MediaStore RELATIVE_PATH）")
    ap.add_argument("--output-dir", default="/storage/emulated/0/Android/data/com.photomgm.app/files/golden",
                    help="App 侧 output_dir。必须是 App 有写权限的私有外部目录，"
                         "写公共目录会因分区存储 EACCES（见文档 §9 问题 4）")
    ap.add_argument("--template", type=int, default=1, help="ZIP 命名模板编号（与 PC 侧保持一致）")
    ap.add_argument("--out", required=True, help="输出目录")
    args = ap.parse_args()

    repo = Path(__file__).resolve().parents[2]
    log_path = Path(args.log) if args.log else repo / "scripts/golden/sample/patrol-log-20260814.txt"
    if not log_path.exists():
        print(f"[FAIL] 日志不存在：{log_path}（先跑 make_sample.py）")
        return 1

    logs_text = log_path.read_text(encoding="utf-8")
    dirs = args.dir or ["DCIM/Camera"]
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    settings_xml = build_settings(args.date, dirs, args.output_dir, args.template)
    logs_xml = build_logs(logs_text)

    p_settings = out / f"{PREFS_SETTINGS}.xml"
    p_logs = out / f"{PREFS_LOGS}.xml"
    # newline="" + encoding utf-8：不写 CRLF、不写 BOM。这两点任一被破坏，App 都会读不到 prefs。
    p_settings.write_text(settings_xml, encoding="utf-8", newline="")
    p_logs.write_text(logs_xml, encoding="utf-8", newline="")

    problems = _verify(p_settings, {
        KEY_DATE: args.date,
        KEY_DIRS: "\x00".join(dirs),
        KEY_OUTPUT: args.output_dir,
        KEY_TEMPLATE: str(args.template),
    })
    problems += _verify(p_logs, {KEY_LOGS_TEXT: logs_text})

    # BOM 检查：ET 有时能容忍，但 Android 的 XmlPullParser 不容忍
    for p in (p_settings, p_logs):
        head = p.read_bytes()[:3]
        if head == b"\xef\xbb\xbf":
            problems.append(f"{p.name} 带 UTF-8 BOM（Android 会解析失败）")
        if b"\r\n" in p.read_bytes():
            problems.append(f"{p.name} 含 CRLF（SharedPreferences 会原样写回，值里多出 \\r）")

    print(f"[OK] {p_settings}  （{len(settings_xml.encode('utf-8'))} B）")
    print(f"[OK] {p_logs}  （日志 {len(logs_text.splitlines())} 行 / "
          f"{len(logs_text)} 字符 → XML {len(logs_xml.encode('utf-8'))} B）")
    print(f"     date={args.date}  dirs={dirs}  template={args.template}")
    print(f"     output_dir={args.output_dir}")

    if problems:
        print("[FAIL] 自校验未通过：")
        for p in problems:
            print("   -", p)
        return 1
    print("[OK] 自校验通过（可被 XmlPullParser 解析、key 齐全、值与源文件逐字符一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
