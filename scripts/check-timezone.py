#!/usr/bin/env python3
"""check-timezone.py —— 生产代码禁止隐式依赖「设备默认时区」（issue #2）。

背景
----
时间在本项目里是**绝对时刻**（epoch 毫秒）；把「本地挂钟」和「绝对时刻」互相换算
必须用**业务时区** `algorithm.AppZone.ID`（Asia/Shanghai，产品决策）。

历史上这一层用的是 `ZoneId.systemDefault()`，后果是：runner 默认 UTC 时台账日期列
整体偏移 8 小时、"当日零点"边界错位（CI 首次跑测试即 5 条红）。CI 里固定
`TZ=Asia/Shanghai` 只是兜底，**不能作为正确性依据**——真机换了时区照样错。

规则（扫 src/main 下的 .kt）
---------------------------
  T1  `ZoneId.systemDefault()` / `TimeZone.getDefault()` / `Calendar.getInstance()`
      → 默认时区隐式注入。要固定业务时区就写 `AppZone.ID`。
  T2  无参时钟：`LocalDate.now()` / `LocalDateTime.now()` / `LocalTime.now()`
      → 同样隐式依赖默认时区。改成 `LocalDate.now(AppZone.ID)` 等。
      （`Instant.now()` 不含时区，**不报**。）

豁免
----
  行尾（或紧邻上一行）写 `// tz-ok: 原因`。只在确实需要「设备本地语义」时才用，
  且必须在原因里写清为什么不能用业务时区。豁免样例见下方 SELFTEST。

可重现性四条（与另外两个门禁脚本一致）
------------------------------------
  1. 只用标准库；
  2. 相对路径按**脚本所在仓库根**解析，不依赖调用方 cwd；
  3. 读源码按字节读并归一 CRLF/CR + 去 BOM（core.autocrlf 的差异不得影响判定）；
  4. 必须入库（.gitignore 有全局 `*.py`，靠 `!scripts/*.py` 白名单放行）；
     CI 用 `git ls-files --error-unmatch` 断言。

用法
----
    python3 scripts/check-timezone.py [路径 ...]     # 默认扫 app algorithm data 的 src/main
    python3 scripts/check-timezone.py --selftest
    退出码 0=通过  1=发现违规
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# ── 规则（正则按「源码文本」，已剥离注释与字符串，见 strip_noise） ──────────────

# T1：拿到"设备默认时区"的所有常见写法
T1_RE = re.compile(
    r'(?:\bZoneId\s*\.\s*systemDefault\s*\(\s*\)'
    r'|\bTimeZone\s*\.\s*getDefault\s*\(\s*\)'
    r'|\bCalendar\s*\.\s*getInstance\s*\(\s*\))'
)

# T2：无参时钟（后面紧跟 '(' 且括号内为空）。Instant.now() 无时区语义，不在其中。
T2_RE = re.compile(r'\b(?:LocalDate|LocalDateTime|LocalTime)\s*\.\s*now\s*\(\s*\)')

SUPPRESS = 'tz-ok'

RULES = [
    ('T1', T1_RE, '隐式使用设备默认时区；业务时区请用 algorithm.AppZone.ID'),
    ('T2', T2_RE, '无参时钟隐式依赖设备默认时区；请传入 AppZone.ID'),
]


def read_source(path: Path) -> str:
    """按字节读 + 去 BOM + 归一换行，保证跨平台判定一致。"""
    raw = path.read_bytes()
    text = raw.decode('utf-8', errors='replace')
    if text.startswith('\ufeff'):
        text = text[1:]
    return text.replace('\r\n', '\n').replace('\r', '\n')


def strip_noise(src: str) -> str:
    """把注释与**字符串内容**都替换为空白（保留行号/列位与引号本身）。

    与 check-ignore-policy.py 不同：那个脚本要读 `@Ignore("原因")` 的**参数原文**，
    所以保留字符串；本脚本只判"有没有出现某个 API 调用"，字符串里的文字一律不该算数
    ——否则 `val s = "ZoneId.systemDefault() 是禁止的"` 这种说明文字会误报。

    ★ 状态机要点：
      · 先判字符串再判注释，否则 `val s = "http://x"` 里的 `//` 会被当注释；
      · Kotlin 块注释**可嵌套**，所以要记深度；
      · 三引号原始字符串里 `\\` 不转义，必须单独一个状态，否则提前结束会把后半段当代码。
    """
    out: list = []
    i, n = 0, len(src)
    state = None          # None | '"' | 'r'(raw string) | '/' | '*'
    depth = 0             # 块注释嵌套深度
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        nxt2 = src[i + 2] if i + 2 < n else ''
        if state is None:
            if c == '"' and nxt == '"' and nxt2 == '"':
                state = 'r'
                out.append('"""')
                i += 2
            elif c == '"':
                state = '"'
                out.append(c)
            elif c == '/' and nxt == '/':
                state = '/'
                out.append('  ')
                i += 1
            elif c == '/' and nxt == '*':
                state = '*'
                depth = 1
                out.append('  ')
                i += 1
            else:
                out.append(c)
        elif state == '"':
            if c == '\\' and i + 1 < n:
                out.append('  ')
                i += 1
            elif c == '"':
                state = None
                out.append(c)
            else:
                out.append('\n' if c == '\n' else ' ')
        elif state == 'r':
            if c == '"' and nxt == '"' and nxt2 == '"':
                out.append('"""')
                i += 2
                state = None
            else:
                out.append('\n' if c == '\n' else ' ')
        elif state == '/':
            # ★ 行注释必须在换行处结束。漏了 `state = None` 会让"文件里第一个 // 之后的
            #   所有代码"都被当成注释——表现是永远绿（本脚本初版就栽在这里，靠
            #   "注释后仍有违规代码"的样例才发现）。
            if c == '\n':
                state = None
                out.append('\n')
            else:
                out.append(' ')
        elif state == '*':
            if c == '/' and nxt == '*':
                depth += 1
                out.append('  ')
                i += 1
            elif c == '*' and nxt == '/':
                depth -= 1
                out.append('  ')
                i += 1
                if depth == 0:
                    state = None
            else:
                out.append('\n' if c == '\n' else ' ')
        i += 1
    return ''.join(out)


def check(path: Path) -> list:
    """返回违规行描述列表（空 = 通过）。"""
    raw = read_source(path)
    code = strip_noise(raw)
    raw_lines = raw.split('\n')
    code_lines = code.split('\n')

    problems: list = []
    for rule_id, regex, hint in RULES:
        for m in regex.finditer(code):
            line_no = code.count('\n', 0, m.start()) + 1
            # 豁免：本行或上一行含 `tz-ok:`
            cur = raw_lines[line_no - 1] if line_no - 1 < len(raw_lines) else ''
            prev = raw_lines[line_no - 2] if line_no - 2 >= 0 else ''
            if SUPPRESS in cur or SUPPRESS in prev:
                continue
            line = code_lines[line_no - 1].strip() if line_no - 1 < len(code_lines) else ''
            problems.append(f'{path.as_posix()}:{line_no}: [{rule_id}] {hint} :: {line}')
    return problems


# ── 自测 ────────────────────────────────────────────────────────────────────

SELFTEST_CASES = [
    # (名称, 源码, 期望命中的规则 id 集合)
    ('t1_zoneid_必须报', '''package a
fun f() = java.time.ZoneId.systemDefault()
''', {'T1'}),

    ('t1_timezone_必须报', '''package a
fun f() = java.util.TimeZone.getDefault()
''', {'T1'}),

    ('t1_calendar_必须报', '''package a
fun f() = java.util.Calendar.getInstance()
''', {'T1'}),

    ('t2_localdate_now_必须报', '''package a
fun f() = java.time.LocalDate.now()
''', {'T2'}),

    ('t2_localdatetime_now_必须报', '''package a
fun f() = LocalDateTime.now( )
''', {'T2'}),

    ('instant_now_不报', '''package a
fun f() = java.time.Instant.now()
''', set()),

    ('显式业务时区_不报', '''package a
fun f() = java.time.LocalDate.now(AppZone.ID)
fun g() = java.time.ZoneId.of("Asia/Shanghai")
''', set()),

    ('注释里的_不报', '''package a
// ZoneId.systemDefault() 曾经这么写
/* LocalDate.now() 也是 */
/** KDoc: 不要用 ZoneId.systemDefault() */
fun f() = 1
''', set()),

    ('字符串里的_不报', '''package a
val s = "ZoneId.systemDefault() 是禁止的"
val t = "LocalDate.now()"
''', set()),

    ('行尾豁免_不报', '''package a
fun f() = java.time.ZoneId.systemDefault() // tz-ok: 仅用于诊断日志，不参与业务计算
''', set()),

    ('上一行豁免_不报', '''package a
// tz-ok: 设备本地语义（用户本地时间显示）
fun f() = java.time.ZoneId.systemDefault()
''', set()),

    ('跨行写法_必须报', '''package a
fun f() = java.time.ZoneId
    .systemDefault()
''', {'T1'}),

    ('修复后写法_不报', '''package a
fun f() = java.time.LocalDate.now(algorithm.AppZone.ID)
fun g() = algorithm.AppZone.ID
''', set()),

    # ★ 回归样例：行注释必须在换行处结束。初版脚本漏了 state 复位，导致
    #   "文件里第一个 // 之后的代码全部被当注释" → 永远绿。本样例专门盯这个。
    ('行注释后仍有违规代码_必须报', '''package a
// 这是说明，后面还有真代码
fun f() = java.time.ZoneId.systemDefault()
''', {'T1'}),

    # ★ 回归样例：三引号原始字符串内容同样不算代码，且必须能正常闭合
    #   （原始字符串里 \ 不转义，状态机写错会把后半段当代码或整段吞掉）
    ('三引号原始字符串_不报', '''package a
val s = """
ZoneId.systemDefault()
LocalDate.now()
"""
fun f() = 1
''', set()),

    ('三引号之后仍有违规代码_必须报', '''package a
val s = """
ZoneId.systemDefault()
"""
fun f() = java.time.LocalDate.now()
''', {'T2'}),
]


def selftest() -> int:
    import tempfile

    failed: list = []
    with tempfile.TemporaryDirectory(prefix='check-timezone-') as td:
        root = Path(td)
        for name, src, want in SELFTEST_CASES:
            p = root / f'{name}.kt'
            p.write_text(src, encoding='utf-8', newline='')
            got = set()
            for prob in check(p):
                m = re.search(r'\[(T\d)\]', prob)
                if m:
                    got.add(m.group(1))
            if got != want:
                failed.append(f'  ✗ {name}: 期望 {sorted(want) or "无违规"}，实际 {sorted(got) or "无违规"}')

        # CRLF / LF 结果必须一致（core.autocrlf=true 的 Windows 检出）
        lf_src = SELFTEST_CASES[0][1]
        lf = root / 'lf.kt'
        lf.write_text(lf_src, encoding='utf-8', newline='')
        crlf = root / 'crlf.kt'
        crlf.write_bytes(lf_src.replace('\n', '\r\n').encode('utf-8'))
        if [p.split(':', 2)[2] for p in check(lf)] != [p.split(':', 2)[2] for p in check(crlf)]:
            failed.append('  ✗ CRLF 与 LF 的判定结果不一致')

        # BOM 不应影响判定
        bom = root / 'bom.kt'
        bom.write_bytes(b'\xef\xbb\xbf' + lf_src.encode('utf-8'))
        if len(check(bom)) != len(check(lf)):
            failed.append('  ✗ BOM 影响了判定结果')

    if failed:
        print('自测失败：')
        for f in failed:
            print(f)
        return 1
    print(f'check-timezone --selftest: OK（{len(SELFTEST_CASES)} 组样例 + CRLF/LF 等价性 + BOM）')
    return 0


# ── 主流程 ──────────────────────────────────────────────────────────────────

def repo_root() -> Path:
    """按脚本位置解析仓库根（scripts/ 的上一级），不依赖调用方 cwd。"""
    return Path(__file__).resolve().parent.parent


def collect(roots: list) -> list:
    base = repo_root()
    files: list = []
    for r in roots:
        p = base / r
        if p.is_file():
            files.append(p)
        elif p.is_dir():
            files += [f for f in p.rglob('*.kt') if '/build/' not in f.as_posix()]
    return sorted(set(files))


def main(argv: list) -> int:
    if '--selftest' in argv:
        return selftest()

    roots = [a for a in argv[1:] if not a.startswith('-')] or [
        'android/app/src/main', 'algorithm/src/main', 'android/data/src/main',
    ]
    files = collect(roots)
    if not files:
        print(f'未找到任何 Kotlin 文件（roots={roots}）', file=sys.stderr)
        return 1

    problems: list = []
    for f in files:
        problems += check(f)

    if problems:
        print('发现隐式依赖设备默认时区（issue #2：业务时区必须显式传 AppZone.ID）：\n')
        for p in problems:
            print('  ' + p)
        print(f'\n共 {len(problems)} 处。若确实需要设备本地语义，在行尾写 `// tz-ok: 原因`。')
        return 1

    print(f'check-timezone: OK（{len(files)} 个文件，无隐式默认时区依赖）')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
