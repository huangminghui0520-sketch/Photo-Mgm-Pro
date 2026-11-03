#!/usr/bin/env python3
"""check-shared-purity.py —— 共享层「该放哪层」的静态门禁（T14 / G2 / G3）。

背景
----
`shared/{algorithm,domain,app}` 被安卓与桌面两端以 `../shared/*` 同源挂载。
好处是"没有第二份实现可以漂移"，代价是**共享层里任何一行平台 API 都会同时污染两端**，
而且往往只在一端炸：

  · `import android.*` → 桌面工程编译失败（好，立刻可见）；
  · `System.getProperty("user.dir")` → 两端都能编，安卓运行时 `user.dir` 是 `/`，
    落盘静默失败或写到只读目录（坏，半年后发现）。

历史上这类"能编但错"的越界发生过多次（`ZoneId.systemDefault()` 导致台账日期列整体
偏移 8 小时；`user.dir` 写诊断报告）。重构期间靠人肉 grep 拦了几次，但
"下一轮就会有人加回一行 `import algorithm.AlgorithmApi`"是必然的 —— 所以固化成本脚本。

规则（5 条，与 docs/REFACTOR-EXECUTION-PLAN.md §20 一一对应）
--------------------------------------------------------------
  P1  平台 import：`shared/*/src/main` 不得 import android / androidx / java.awt /
      javax.swing / javax.imageio / javafx / com.photomgm.*（两端的实现包）。
  P2  无参时钟：隐式依赖设备默认时区。与 check-timezone.py 的 T1/T2 **有意重叠**：
      共享层错一次两端全错，两条门禁都红是预期行为；豁免词也共用 `tz-ok`，
      避免"在一条门禁里豁免、在另一条里没豁免"的分叉。
  P3  环境依赖：`System.getProperty` / `System.getenv`。共享层不知道自己在谁的进程里，
      路径、编码、区域设置都必须由平台层作为参数传进来。
  P4  契约常量重复：登记表里的"产物契约字符串"（如 `99_未分类`）在 `src/main` 里
      **只允许有一处定义**。同一个字面量被写两遍，改一处忘另一处的症状是
      "ZIP 里叫 A、台账里叫 B"，而对拍脚本只会说"对不上"。
  P5  UI 越界 import：UI 层只允许 `app.api.*`（跨端契约）；
      `app.usecase/state/port/export/infra` 与 `domain.*` 一律禁止。
      `algorithm.*` 是**棘轮**：当前实测 9 处，上限就锁在 9，只减不增
      （UI 直接读 `algorithm.model.Photo` 属越层，但清理它不在本轮范围）。

豁免
----
  行尾（或紧邻上一行）写 `purity-ok: 原因`（P2 用 `tz-ok:`，与 check-timezone 一致）。

可重现性四条（与另外三个门禁脚本一致）
------------------------------------
  1. 只用标准库；
  2. 相对路径按**脚本所在仓库根**解析，不依赖调用方 cwd；
  3. 读源码按字节读并归一 CRLF/CR + 去 BOM；
  4. 必须入库（.gitignore 有全局 `*.py`，靠 `!scripts/*.py` 白名单放行），
     CI 用 `git ls-files --error-unmatch` 断言，并断言"扫描文件数 > 0"。

用法
----
    python3 scripts/check-shared-purity.py                # 默认扫全部规则
    python3 scripts/check-shared-purity.py --selftest     # 判据自测
    退出码 0=通过  1=发现违规
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# ── 规则定义 ────────────────────────────────────────────────────────────────

# P1：共享层不得碰任何一端**独有**的实现包。
#   「桌面/JVM 独有」：java.awt / javax.swing / javax.imageio / javafx —— 安卓没有，一旦出现
#     安卓工程直接编不过（好，立刻可见）。
#   「安卓独有」：android.* / androidx.* —— 桌面编不过。
#   「两端实现包」：com.photomgm.* —— 共享层反向依赖端实现，架构上就错了。
#   注意**不拦** java.nio.file / java.io / java.util / java.time：这些是 JVM 通用 API
#   （安卓 API 26+ 有），拦了会误杀 ExportLogWriter 的 java.nio.file.Path 等合法用法。
P1_RE = re.compile(
    r'^\s*import\s+('
    r'android|androidx|java\.awt|javax\.swing|javax\.imageio|javafx|com\.photomgm'
    r')\.',
    re.MULTILINE,
)
P1_HINT = '共享层 import 了平台/端实现包；平台能力必须经端口传入（见 shared/app/port/）'

# P1b：共享层里「路径」这类平台知识（§19.4 判据④）。java.io.File 在安卓上语义与
#   JVM 不同（无真实 cwd、无盘符）。两端通用的 java.nio.file.Path 不在此列。
#   （androidx DocumentFile 已被 P1 的 androidx 前缀覆盖，不再单列。）
P1B_RE = re.compile(r'^\s*import\s+java\.io\.File\b', re.MULTILINE)
P1B_HINT = '共享层不该直接构造 java.io.File；路径是平台知识，由平台层经端口传入'

# P2：与 check-timezone.py 的 T1/T2 同集（有意重叠，见文件头）
P2_RULES = [
    (re.compile(r'\bZoneId\s*\.\s*systemDefault\s*\(\s*\)'), '隐式设备默认时区；业务时区用 algorithm.AppZone.ID'),
    (re.compile(r'\bTimeZone\s*\.\s*getDefault\s*\(\s*\)'), '隐式设备默认时区；业务时区用 algorithm.AppZone.ID'),
    (re.compile(r'\bCalendar\s*\.\s*getInstance\s*\(\s*\)'), '隐式设备默认时区；业务时区用 algorithm.AppZone.ID'),
    (re.compile(r'\b(?:LocalDate|LocalDateTime|LocalTime)\s*\.\s*now\s*\(\s*\)'), '无参时钟隐式依赖设备默认时区；请传入 AppZone.ID'),
]

# P3：环境依赖（共享层不知道自己跑在谁的进程里）
P3_RE = re.compile(r'\bSystem\s*\.\s*(getProperty|getenv)\s*\(')
P3_HINT = '共享层读进程环境（cwd/home/APPDATA）；路径与区域设置应由平台层作为参数传入'

# P4：产物契约字符串必须只有一处定义
# (字面量, 权威定义位置（相对仓库根）, 说明)
CONTRACT_LITERALS = [
    ('99_未分类', 'shared/algorithm/src/main/kotlin/algorithm/ZipExporter.kt',
     '未匹配照片的文件夹名：ZIP 目录名与台账照片列必须同源'),
    ('ledger.xlsx', 'shared/app/src/main/kotlin/app/api/ExportModels.kt',
     '桌面端台账默认文件名：执行器与 CLI 的元文件名集合必须同源'),
    ('export.log', 'shared/app/src/main/kotlin/app/export/ExportLogWriter.kt',
     '导出日志文件名'),
    ('export-manifest.json', 'shared/app/src/main/kotlin/app/export/ExportManifest.kt',
     '导出清单文件名'),
]

# P5：UI 层禁止 import 的包（跨端契约 app.api / 平台自己的包不在此列）
P5_FORBIDDEN = ('app.usecase', 'app.state', 'app.port', 'app.export', 'app.infra', 'domain')
# 棘轮：UI 层直接 import algorithm.* 的**去重类型数**上限（实测值，只减不增）。
# ★ 2026-11-03 T13 修正：从「import 语句条数(9)」改为「去重 FQCN 数(4)」——见 check_ui_layer 注释。
MAX_UI_ALGORITHM_IMPORTS = 4

UI_ROOTS = [
    'android/app/src/main/java/com/photomgm/app/ui',
    'desktop/ui/src/main/kotlin',
]
SHARED_ROOTS = ['shared/algorithm/src/main', 'shared/domain/src/main', 'shared/app/src/main']
# P4 要扫全部生产代码（共享层与两端）
PROD_ROOTS = SHARED_ROOTS + [
    'android/app/src/main', 'android/data/src/main', 'desktop/core/src/main',
    'desktop/ui/src/main', 'desktop/cli/src/main',
]

SUPPRESS = 'purity-ok'
SUPPRESS_TZ = 'tz-ok'


# ── 源码读取与噪声剥离 ──────────────────────────────────────────────────────

def read_source(path: Path) -> str:
    """按字节读 + 去 BOM + 归一换行，保证跨平台判定一致。"""
    raw = path.read_bytes()
    text = raw.decode('utf-8', errors='replace')
    if text.startswith('\ufeff'):
        text = text[1:]
    return text.replace('\r\n', '\n').replace('\r', '\n')


def strip_noise(src: str) -> str:
    """把注释与**字符串内容**替换为空白（保留行号/列位与引号本身）。

    与 check-timezone.py 的实现一致（同一条纪律：注释里的示例代码不算违规，
    字符串里的说明文字也不算）。状态机四个要点：
      · 先判字符串再判注释，否则 `"http://x"` 里的 `//` 会被当注释吃掉后半个文件；
      · Kotlin 块注释可嵌套，要记深度；
      · 三引号原始字符串里 `\\` 不转义，必须单独一个状态；
      · **字符字面量 `'"'` / `'\\''` 必须先吃掉**。漏了这一步，一个 `'"'` 就会把后面
        整段代码当成字符串抹掉 —— 表现是"永远绿"，而 `ZipExporter.kt` 的
        `'"' -> sb.append('"')` 正是这种写法（本条是被 P4 的行号错位暴露出来的）。
    """
    out: list = []
    i, n = 0, len(src)
    state = None          # None | '"' | 'r' | "'" | '/' | '*'
    depth = 0
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        nxt2 = src[i + 2] if i + 2 < n else ''
        if state is None:
            if c == '"' and nxt == '"' and nxt2 == '"':
                state = 'r'
                out.append('"""')
                i += 2
            elif c == "'":
                state = "'"
                out.append(c)
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
        elif state == "'":
            if c == '\\' and i + 1 < n:
                out.append('  ')
                i += 1
            elif c == "'":
                state = None
                out.append(c)
            else:
                out.append(' ')
        elif state == '/':
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


def _line_of(code: str, pos: int) -> int:
    return code.count('\n', 0, pos) + 1


def _suppressed(raw_lines: list, line_no: int, token: str) -> bool:
    """本行或上一行含豁免词。"""
    cur = raw_lines[line_no - 1] if line_no - 1 < len(raw_lines) else ''
    prev = raw_lines[line_no - 2] if line_no - 2 >= 0 else ''
    return token in cur or token in prev


# ── 单文件检查 ──────────────────────────────────────────────────────────────

def check_shared_file(path: Path, root_rel: str) -> list:
    """P1/P2/P3：只对 shared/*/src/main 生效。"""
    raw = read_source(path)
    code = strip_noise(raw)
    raw_lines = raw.split('\n')
    code_lines = code.split('\n')
    problems: list = []

    def add(rule: str, pos: int, hint: str, token: str) -> None:
        line_no = _line_of(code, pos)
        if _suppressed(raw_lines, line_no, token):
            return
        src = code_lines[line_no - 1].strip() if line_no - 1 < len(code_lines) else ''
        problems.append(f'{path.as_posix()}:{line_no}: [{rule}] {hint} :: {src}')

    for m in P1_RE.finditer(code):
        add('P1', m.start(), P1_HINT, SUPPRESS)

    # P1b 只对 shared/app 生效（§19.4 判据④的原文是「shared/app 无 java.io.File」）。
    #   algorithm 层的 ZipExporter 用 File 做 ZIP 字节输出是 JVM 通用用法，不算平台越界。
    if '/shared/app/' in path.as_posix():
        for m in P1B_RE.finditer(code):
            add('P1b', m.start(), P1B_HINT, SUPPRESS)

    for regex, hint in P2_RULES:
        for m in regex.finditer(code):
            add('P2', m.start(), hint, SUPPRESS_TZ)

    for m in P3_RE.finditer(code):
        add('P3', m.start(), P3_HINT, SUPPRESS)

    return problems


def check_contract_literals(files: list) -> list:
    """P4：登记的字面量只允许一处**定义**（`= "字面量"`），多出即报。

    ★ 这里不能直接跑在 `strip_noise()` 的结果上：那个函数把字符串内容也抹成空白，
      而本条规则要判的正是字符串本身。做法是**在原文上匹配、在剥离后的代码上确认**
      ——被注释掉的定义（`// const val X = "99_未分类"`）或写在字符串里的示例
      （`val doc = "const val X = ..."`）在剥离后的那一行里找不到 `val X`，于是不算定义。
    """
    problems: list = []
    for literal, owner, why in CONTRACT_LITERALS:
        pat = re.compile(
            r'\b(?:const\s+)?val\s+([A-Za-z_]\w*)\s*(?::\s*[\w.<>?]+\s*)?=\s*"' + re.escape(literal) + r'"'
        )
        sites: list = []
        for f in files:
            raw = read_source(f)
            code_lines = strip_noise(raw).split('\n')
            raw_lines = raw.split('\n')
            for line_idx, line in enumerate(raw_lines):
                for m in pat.finditer(line):
                    name = m.group(1)
                    # 剥离后该行仍含 `val <name>` 才算"真定义"
                    if f'val {name}' not in code_lines[line_idx]:
                        continue
                    sites.append((f.as_posix(), line_idx + 1, name))
        if len(sites) > 1:
            detail = '、'.join(f'{p}:{ln}（{name}）' for p, ln, name in sites)
            problems.append(
                f'[P4] 契约常量 "{literal}" 有 {len(sites)} 处定义：{detail}；'
                f'权威位置应为 {owner}（{why}）'
            )
        elif len(sites) == 0:
            # 定义消失同样要报：说明权威位置搬走了，登记表已过期
            problems.append(f'[P4] 契约常量 "{literal}" 在 src/main 里找不到定义（登记表过期？权威位置 {owner}）')
    return problems


def check_ui_layer(files: list) -> tuple:
    """P5：UI 层越界 import。返回 (硬违规列表, algorithm 类型集合)。

    ★ 棘轮按**去重的 algorithm 类型 FQCN**计数，而不是「import 语句条数」：
      UI 层依赖多少个 algorithm 类型，是「越层程度」的正确度量；而 import 语句条数
      会被文件拆分虚增（同一个 `algorithm.model.Photo` 在 N 个文件里各 import 一次，
      就会被算成 N 处）。T13 把 1209 行的 PreviewScreen 拆成 6 文件后，正是这个原因
      让条数从 9 涨到 13，而实际依赖类型始终是 4 种。"""
    problems: list = []
    algorithm_hits: list = []          # 明细（文件:行: FQCN），供报错展示
    algorithm_types: set = set()       # 去重后的 FQCN，供棘轮判定
    for f in files:
        raw = read_source(f)
        code = strip_noise(raw)
        raw_lines = raw.split('\n')
        for m in re.finditer(r'^\s*import\s+([\w.]+)', code, re.MULTILINE):
            fqcn = m.group(1)
            line_no = _line_of(code, m.start())
            if _suppressed(raw_lines, line_no, SUPPRESS):
                continue
            if fqcn.split('.')[0] == 'algorithm':
                algorithm_hits.append(f'{f.as_posix()}:{line_no}: {fqcn}')
                algorithm_types.add(fqcn)
                continue
            for bad in P5_FORBIDDEN:
                if fqcn == bad or fqcn.startswith(bad + '.'):
                    problems.append(
                        f'{f.as_posix()}:{line_no}: [P5] UI 层不得 import {fqcn}；'
                        f'跨端契约只在 app.api，实现细节留在平台层'
                    )
                    break
    return problems, algorithm_types


# ── 自测 ────────────────────────────────────────────────────────────────────

SELFTEST_CASES = [
    # (名称, 共享层源码, 期望命中的规则 id 集合)
    ('p1_android_import_必须报', '''package app.x
import android.content.Context
''', {'P1'}),

    ('p1_androidx_import_必须报', '''package domain.x
import androidx.compose.runtime.Composable
''', {'P1'}),

    ('p1_端实现包_必须报', '''package app.x
import com.photomgm.data.FileHasher
''', {'P1'}),

    ('p1_桌面实现包_必须报', '''package app.x
import com.photomgm.desktop.core.DesktopCore
''', {'P1'}),

    ('p1b_DocumentFile_必须报', '''package app.x
import androidx.documentfile.provider.DocumentFile
''', {'P1'}),  # androidx 前缀先被 P1 拦下（比 P1b 更上游，符合预期）

    ('p1_跨行import注释_不报', '''package app.x
// import android.content.Context   ← 只是说明文字
''', set()),

    ('p1_字符串里提到_不报', '''package app.x
val s = "import android.content.Context"
''', set()),

    ('p2_无参时区_必须报', '''package app.x
fun f() = java.time.ZoneId.systemDefault()
''', {'P2'}),

    ('p2_无参时钟_必须报', '''package app.x
fun f() = java.time.LocalDate.now()
''', {'P2'}),

    ('p2_带业务时区_不报', '''package app.x
fun f() = java.time.LocalDate.now(algorithm.AppZone.ID)
''', set()),

    ('p2_tz_ok豁免_不报', '''package app.x
fun f() = java.time.LocalDate.now()   // tz-ok: 测试里就是要验证默认时区
''', set()),

    ('p3_getProperty_必须报', '''package app.x
fun f() = System.getProperty("user.dir")
''', {'P3'}),

    ('p3_getenv_必须报', '''package app.x
fun f() = System.getenv("APPDATA")
''', {'P3'}),

    # 跨行写法同样算违规（正则里的 \s* 会跨行匹配；人写代码时确实会断行）
    ('p3_跨行写法_必须报', '''package app.x
fun f() = System
    .getenv("APPDATA")
''', {'P3'}),

    ('p3_purity_ok豁免_不报', '''package app.x
fun f() = System.getenv("X")   // purity-ok: 已经作为参数传入
''', set()),

    # ★ 回归样例：行注释必须在换行处结束（初版状态机漏了 state 复位 → 永远绿）
    ('行注释后仍有违规_必须报', '''package app.x
// 说明
fun f() = System.getProperty("user.dir")
''', {'P3'}),

    ('三引号原始字符串_不报', '''package app.x
val s = """
import android.content.Context
System.getProperty("user.dir")
"""
''', set()),

    ('三引号之后有违规_必须报', '''package app.x
val s = """
import android.content.Context
"""
fun f() = System.getProperty("user.dir")
''', {'P3'}),
]


def selftest() -> int:
    import tempfile

    failed: list = []
    with tempfile.TemporaryDirectory(prefix='check-shared-purity-') as td:
        root = Path(td)
        for name, src, want in SELFTEST_CASES:
            p = root / f'{name}.kt'
            p.write_text(src, encoding='utf-8', newline='')
            got = set()
            for prob in check_shared_file(p, name):
                m = re.search(r'\[(P\d[b]?)\]', prob)
                if m:
                    got.add(m.group(1))
            if got != want:
                failed.append(f'  ✗ {name}: 期望 {sorted(want) or "无违规"}，实际 {sorted(got) or "无违规"}')

        # P1b 只对 shared/app 生效，用带 /shared/app/ 的路径单独验
        for name, src, want in [
            ('p1b_java_io_File_必须报', 'package app.x\nimport java.io.File\n', {'P1b'}),
            ('p1b_java_nio_Path_不报', 'package app.x\nimport java.nio.file.Path\nimport java.io.BufferedWriter\n', set()),
        ]:
            p = root / 'shared' / 'app' / 'src' / 'main' / 'kotlin' / 'app' / f'{name}.kt'
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(src, encoding='utf-8', newline='')
            got = set()
            for prob in check_shared_file(p, name):
                m = re.search(r'\[(P\d[b]?)\]', prob)
                if m:
                    got.add(m.group(1))
            if got != want:
                failed.append(f'  ✗ {name}: 期望 {sorted(want) or "无违规"}，实际 {sorted(got) or "无违规"}')

        # P4：同一字面量定义两次必须报，只定义一次不报
        a = root / 'a.kt'
        b = root / 'b.kt'
        a.write_text('package x\nobject A { const val UNMATCHED_FOLDER = "99_未分类" }\n', encoding='utf-8', newline='')
        b.write_text('package y\nobject B { const val OTHER = "99_未分类" }\n', encoding='utf-8', newline='')
        if not check_contract_literals([a, b]):
            failed.append('  ✗ P4 未报出"同一契约常量两处定义"')
        if check_contract_literals([a]):
            # 只剩一处时，其余登记项会报"找不到定义"——但 99_未分类 那条不该报
            if any('99_未分类' in p and '找不到定义' in p for p in check_contract_literals([a])):
                failed.append('  ✗ P4 把"只有一处定义"误报成找不到定义')

        # P5：UI 层越界必须报，app.api 与 algorithm 不算越界
        ui = root / 'ui'
        ui.mkdir()
        bad = ui / 'Bad.kt'
        bad.write_text('package ui\nimport app.usecase.AnalysisPipeline\n', encoding='utf-8', newline='')
        good = ui / 'Good.kt'
        good.write_text('package ui\nimport app.api.AppState\nimport algorithm.model.Photo\n', encoding='utf-8', newline='')
        probs, algo = check_ui_layer([bad, good])
        if not probs or 'app.usecase' not in probs[0]:
            failed.append('  ✗ P5 未报出 UI 层 import app.usecase')
        if len(algo) != 1:
            failed.append(f'  ✗ P5 的 algorithm 计数错：期望 1，实际 {len(algo)}')

        # CRLF / LF / BOM 判定必须一致
        lf_src = SELFTEST_CASES[0][1]
        lf = root / 'lf.kt'
        lf.write_text(lf_src, encoding='utf-8', newline='')
        crlf = root / 'crlf.kt'
        crlf.write_bytes(lf_src.replace('\n', '\r\n').encode('utf-8'))
        if [p.split(':', 2)[2] for p in check_shared_file(lf, '')] != \
                [p.split(':', 2)[2] for p in check_shared_file(crlf, '')]:
            failed.append('  ✗ CRLF 与 LF 的判定结果不一致')
        bom = root / 'bom.kt'
        bom.write_bytes(b'\xef\xbb\xbf' + lf_src.encode('utf-8'))
        if len(check_shared_file(bom, '')) != len(check_shared_file(lf, '')):
            failed.append('  ✗ BOM 影响了判定结果')

    if failed:
        print('自测失败：')
        for f in failed:
            print(f)
        return 1
    print(f'check-shared-purity --selftest: OK（{len(SELFTEST_CASES)} 组样例 + P4/P5 判据 + CRLF/LF + BOM）')
    return 0


# ── 主流程 ──────────────────────────────────────────────────────────────────

def repo_root() -> Path:
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

    shared_files = collect(SHARED_ROOTS)
    ui_files = collect(UI_ROOTS)
    prod_files = collect(PROD_ROOTS)

    if not shared_files or not ui_files or not prod_files:
        print(f'扫描路径为空（shared={len(shared_files)} ui={len(ui_files)} prod={len(prod_files)}）',
              file=sys.stderr)
        return 1

    problems: list = []
    for f in shared_files:
        problems += check_shared_file(f, f.as_posix())
    problems += check_contract_literals(prod_files)
    ui_problems, algorithm_hits = check_ui_layer(ui_files)
    problems += ui_problems

    print(f'扫描：shared {len(shared_files)} 个文件 / UI {len(ui_files)} 个 / 生产代码 {len(prod_files)} 个')

    if len(algorithm_hits) > MAX_UI_ALGORITHM_IMPORTS:
        problems.append(
            f'[P5] UI 层 import algorithm.* 的去重类型增至 {len(algorithm_hits)} 种，超过棘轮上限 '
            f'{MAX_UI_ALGORITHM_IMPORTS} 种（只减不增）：\n        '
            + '\n        '.join(sorted(algorithm_hits))
        )
    elif len(algorithm_hits) < MAX_UI_ALGORITHM_IMPORTS:
        print(f'  提示：UI 层 algorithm.* 已降到 {len(algorithm_hits)} 种，'
              f'可把 MAX_UI_ALGORITHM_IMPORTS 下调到 {len(algorithm_hits)}')
    else:
        print(f'  UI 层 algorithm.* = {len(algorithm_hits)} 种（棘轮上限 {MAX_UI_ALGORITHM_IMPORTS}，持平）')

    if problems:
        print('\n共享层纯度违规：\n')
        for p in problems:
            print('  ' + p)
        print(f'\n共 {len(problems)} 处。确实需要豁免时在行尾写 `// purity-ok: 原因`'
              f'（时区类用 `// tz-ok: 原因`）。')
        return 1

    print(f'check-shared-purity: OK（P1 平台 import 0 处 / P2 无参时钟 0 处 / '
          f'P3 环境依赖 0 处 / P4 {len(CONTRACT_LITERALS)} 个契约常量各一处定义 / '
          f'P5 UI 越界 0 处）')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
