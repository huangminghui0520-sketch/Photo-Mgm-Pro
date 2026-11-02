#!/usr/bin/env python3
"""CI 拦截：协程取消信号被吞（CancellationException swallowed）。

背景
----
JVM 上 kotlinx.coroutines.CancellationException 是 java.util.concurrent.CancellationException
的 typealias，**继承 IllegalStateException**。因此下面两种写法都会把取消信号吞掉：

    runCatching { ... }                 // 捕获 Throwable
    try { ... } catch (e: Exception) {} // 捕获 Exception（含取消）

后果：协程已被取消，代码却继续跑完并把状态写成"失败"，外层感知不到取消。
本轮（2026-10-31）exportZip / prepareLedger 就是这样漏掉的（S1/S2）。逐处靠人肉 review
不可靠，故固化为两条可机器判定的规则。

规则
----
R1  catch(Exception/Throwable/…) 必须配取消分支
    在"存在协程语义"的文件里（含 suspend / launch / async / withContext / coroutineScope /
    runBlocking），同一个 try 若有 catch 宽泛异常，则该 try 必须另有
    `catch (e: CancellationException)`，或 catch 体内调用 ensureActive()。

R2  协程上下文内禁止裸 runCatching
    runCatching 的最近外层作用域若是 suspend fun / launch / async / runBlocking / withContext /
    coroutineScope，须改用 app 模块的 runCatchingCancellable（取消信号重抛）。
    非 suspend 的纯函数（XlsxWriter / SettingsStore / FileHasher 等）内部没有挂起点，
    取消不可能传入，用普通 runCatching 是**允许**的，本规则不会误报。

用法
----
    python3 scripts/check-cancellation.py [路径 ...]     # 默认扫 app algorithm data
    python3 scripts/check-cancellation.py --selftest     # 内置样例自测（不依赖仓库代码）
    退出码 0=通过  1=发现违规 / 自测失败

★ CI 可重现性（2026-10-31 复核，四条都必须成立，否则"本地绿、CI 红"）
----
1. 解释器：只用标准库 + `python3`（ubuntu-24.04 预装 3.12；本机 Windows 同名可用）。
   不开子线程、不读环境变量、不访问网络 → 与操作系统无关。
2. 路径：相对路径按**脚本所在仓库根**解析（[REPO_ROOT]），不依赖调用方 cwd。
   Actions 的 `run:` 默认 cwd 是工作区，但换 runner / 换 job 不该是隐式前提。
3. 行尾：core.autocrlf=true 的检出会把 `\r\n` 带进工作树。扫描前统一规范化为
   `\n`（CRLF / CR 都归一），保证 Windows 与 Linux 的行号、列位、括号深度完全一致。
4. 入库：脚本必须未被 .gitignore 忽略，否则 CI checkout 里根本没有这个文件。
   由 CI 步骤 `git ls-files --error-unmatch scripts/check-cancellation.py` 显式断言
   （比"python3: can't open file"的报错可读，能直接指到 .gitignore 白名单）。

豁免（误报处理）
----
    在违规行末尾加注释 `// ci-ok: 原因`，可单独豁免该处（需写明理由）。
    判据是"该行原始文本含 ci-ok 子串"，注释位置在行尾即可，前面可以有代码。
    优先顺序：**能改代码就改代码**；确属"无挂起点、不可能收到取消"的纯函数，
    才用 ci-ok 豁免，并把理由写在冒号后（review 时逐条看理由，不看数量）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# 仓库根 = 本脚本（scripts/）的上级目录。
# ★ 相对路径一律按它解析：Actions 的 cwd 恰好是工作区，但那属于巧合，不该成为前提。
REPO_ROOT = Path(__file__).resolve().parent.parent

# ---------- 词法：去掉注释与字符串，避免把注释里的代码当成真代码 ----------

def strip_noise(src: str) -> str:
    """把注释与字符串内容替换为等长空白，保留行号与列位，便于按行匹配。

    ★ 必须是**带状态**的扫描：早期版本先判 `//` 再判字符串，结果把
      `/** …（用户可见位置：content:// 或本地路径）*/` 里的 `content://`
      当成行注释，整行被抹掉后 `*/` 丢失，后续所有行的括号深度全部错位。
    """
    out = list(src)
    i, n = 0, len(src)

    def blank(lo: int, hi: int) -> None:
        for k in range(lo, min(hi, n)):
            if src[k] != '\n':
                out[k] = ' '

    while i < n:
        c = src[i]
        # 块注释（Kotlin 支持嵌套，必须按深度匹配，否则 KDoc 里的 `/*` 会提前收尾）
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            depth = 1
            blank(i, i + 2); i += 2
            while i < n and depth:
                if src[i] == '/' and i + 1 < n and src[i + 1] == '*':
                    depth += 1; blank(i, i + 2); i += 2; continue
                if src[i] == '*' and i + 1 < n and src[i + 1] == '/':
                    depth -= 1; blank(i, i + 2); i += 2; continue
                if src[i] != '\n':
                    out[i] = ' '
                i += 1
            continue
        # 行注释
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            blank(i, n if j < 0 else j)
            i = n if j < 0 else j
            continue
        # 字符串（先于 `//` 之外的判断；`"content://"` 会在这里被整段吞掉，不会误判为注释）
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and src[j] != '"':
                    j += 2 if src[j] == '\\' else 1
                j = min(j + 1, n)
            blank(i, j)
            i = j
            continue
        i += 1
    return ''.join(out)


# ---------- 规则常量 ----------

# 宽泛捕获：会把 CancellationException 一起吞掉
BROAD_CATCH = re.compile(
    r'catch\s*\(\s*(?:_|[A-Za-z_]\w*)\s*:\s*(Exception|Throwable|Error|RuntimeException'
    r'|IllegalStateException|Exception\??)\s*\)'
)
CANCEL_CATCH = re.compile(r'catch\s*\(\s*(?:_|[A-Za-z_]\w*)\s*:\s*.*CancellationException')
ANY_CATCH = re.compile(r'\bcatch\s*\(')

# 协程上下文标志（用于 R1 的"文件级"判定与 R2 的"作用域级"判定）
CORO_SCOPE = re.compile(
    r'\bsuspend\s+fun\b'
    r'|\b(?:viewModelScope|lifecycleScope|coroutineScope|[A-Za-z]*[Ss]cope)\s*\.\s*(?:launch|async)\b'
    r'|\b(?:launch|async|runBlocking|withContext|coroutineScope|supervisorScope|produce|launchIn)\s*[(<{]'
)

FILE_HAS_CORO = re.compile(r'\bsuspend\b|\b(?:launch|async|runBlocking|withContext|coroutineScope)\b')

SUPPRESS = 'ci-ok'


# ---------- 逐行结构分析 ----------

class Line:
    __slots__ = ('no', 'text', 'depth', 'raw')

    def __init__(self, no: int, text: str, depth: int, raw: str):
        self.no, self.text, self.depth, self.raw = no, text, depth, raw


def read_source(path: Path) -> str:
    """读源码并统一行尾为 \\n。

    ★ 不用 [Path.read_text]：它的换行处理随 Python 版本变化（3.13 才支持 newline= 参数），
      而 core.autocrlf=true 的检出把 CRLF 写进工作树——若靠隐式转换，Windows 与 Linux
      的行为会在升级解释器那天悄悄分叉。这里按字节读 + 显式归一，跨版本恒定。
      UTF-8 BOM 一并剥掉，否则首行会多一个不可见字符，影响按行正则的锚点。
    """
    data = path.read_bytes()
    if data.startswith(b'\xef\xbb\xbf'):
        data = data[3:]
    return data.decode('utf-8', errors='replace').replace('\r\n', '\n').replace('\r', '\n')


def analyze(path: Path) -> list[Line]:
    src = read_source(path)
    code = strip_noise(src)
    raws = src.split('\n')
    lines, depth = [], 0
    for idx, text in enumerate(code.split('\n')):
        lines.append(Line(idx + 1, text.rstrip(), depth, raws[idx] if idx < len(raws) else ''))
        depth += text.count('{') - text.count('}')
    return lines


def in_coroutine_context(lines: list[Line], i: int) -> Line | None:
    """判断第 i 行是否处于**可能收到取消信号**的作用域内，返回该作用域行。

    逐层上溯"包裹当前行的作用域"：
    - 命中协程构造（suspend fun / launch / async / withContext / …）→ 会收到取消，返回该行；
    - 命中**非 suspend 的函数声明**（fun）→ 函数边界挡住取消传播，判定安全，返回 None；
    - 命中普通 lambda（.let { / .forEach { / try {）→ 不是边界，继续往外一层。
    """
    d = lines[i].depth
    for j in range(i - 1, -1, -1):
        if lines[j].depth < d and '{' in lines[j].text:
            t = lines[j].text
            if CORO_SCOPE.search(t):
                return lines[j]
            if re.search(r'\bfun\b', t):
                return None            # 非 suspend 函数 = 取消边界
            d = lines[j].depth         # 普通 lambda，继续上溯
    return None


def catch_group(lines: list[Line], i: int) -> list[Line]:
    """同一个 try 的所有 catch 子句。

    判定：同一 try 的 catch 之间夹着各自的**函数体**（更深的行），所以不能按"连续行"取，
    而要跳过更深的行、只在遇到更浅的行（说明 try 已结束）时停止。
    """
    grp = [lines[i]]
    d = lines[i].depth
    for j in range(i - 1, -1, -1):
        if lines[j].depth < d:
            break                                    # 更浅 = 所属 try 已结束
        if lines[j].depth == d and ANY_CATCH.search(lines[j].text):
            grp.append(lines[j])
    for j in range(i + 1, len(lines)):
        if lines[j].depth < d:
            break
        if lines[j].depth == d and ANY_CATCH.search(lines[j].text):
            grp.append(lines[j])
    return sorted(grp, key=lambda l: l.no)


def group_handles_cancel(lines: list[Line], grp: list[Line]) -> bool:
    if any(CANCEL_CATCH.search(l.text) for l in grp):
        return True
    # catch 体内首行调用 ensureActive() 也算处理（允许 3 行内的注释）。
    # ★ 不把 runCatchingCancellable 计入：它只能保证"它自己包住的那段"不吞取消，
    #   证明不了本 catch 捕获的异常类型排除了 CancellationException（会把 R1 变成常绿）。
    for l in grp:
        end = min(l.no + 4, len(lines))
        for k in range(l.no, end):
            if 'ensureActive' in lines[k].text:
                return True
    return False


def check(path: Path) -> list[str]:
    lines = analyze(path)
    problems: list[str] = []
    file_coro = any(FILE_HAS_CORO.search(l.text) for l in lines)

    for i, ln in enumerate(lines):
        if SUPPRESS in ln.raw:
            continue

        # ---- R1 ----
        if file_coro and BROAD_CATCH.search(ln.text):
            grp = catch_group(lines, i)
            if not group_handles_cancel(lines, grp):
                problems.append(
                    f'{path}:{ln.no}  [R1] catch 会吞掉取消信号：{ln.text.strip()}\n'
                    f'{" " * len(str(path))}        同一 try 需配 `catch (e: CancellationException) {{ throw e }}`，'
                    f'或在 catch 首行调用 ensureActive()。'
                )

        # ---- R2 ----
        m = re.search(r'\brunCatching\s*[{(\[]', ln.text)
        if m and 'runCatchingCancellable' not in ln.text:
            scope = in_coroutine_context(lines, i)
            if scope is not None and CORO_SCOPE.search(scope.text):
                problems.append(
                    f'{path}:{ln.no}  [R2] 协程上下文内的裸 runCatching 会吞掉取消：{ln.text.strip()}\n'
                    f'{" " * len(str(path))}        外层作用域 {scope.no}: {scope.text.strip()}\n'
                    f'{" " * len(str(path))}        改用 runCatchingCancellable（app/Cancellable.kt）。'
                )
    return problems


def resolve_root(r: str) -> Path:
    """把命令行给的路径解析成真实路径：相对路径按 [REPO_ROOT]，不依赖调用方 cwd。"""
    p = Path(r)
    return p if (p.is_absolute() or p.exists()) else REPO_ROOT / r


def collect(roots: list[str]) -> list[Path]:
    files: list[Path] = []
    for r in roots:
        p = resolve_root(r)
        if p.is_file():
            files.append(p)
        elif p.is_dir():
            files += [f for f in p.rglob('*.kt') if '/build/' not in f.as_posix()]
        # 目录不存在（如本仓库没有 data/ 模块）：静默跳过，不算失败
    return sorted(set(files))


# ---------- 内置样例自测 ----------
#
# 为什么要有自测：本脚本是 254 行的自制词法分析器，它**没有**外部依赖可对拍，
# 一旦某天 Python 版本或行尾约定变了，"规则还在、判据失效"是最难发现的一种回归
# ——表现是永远绿。下面 11 组样例把"该报的必须报、不该报的不能报、CRLF 与 LF 同结果"
# 三类性质钉死，CI 与本地跑的是同一份判据。

CASES: list[tuple[str, str, int, str]] = [
    # (名字, Kotlin 源码, 期望问题条数, 期望规则标记；标记为空串表示"不应有任何问题")
    ('r1_吞取消_必须报', '''package a

class A {
    suspend fun f() {
        try {
            doWork()
        } catch (e: Exception) {
            log(e)
        }
    }
}
''', 1, '[R1]'),

    ('r1_补了取消分支_不报', '''package a

class A {
    suspend fun f() {
        try {
            doWork()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(e)
        }
    }
}
''', 0, ''),

    ('r1_catch内ensureActive_不报', '''package a

class A {
    suspend fun f() {
        try {
            doWork()
        } catch (e: Exception) {
            ensureActive()
            log(e)
        }
    }
}
''', 0, ''),

    # ★ catch 分组的历史坑：同一 try 的多个 catch 之间夹着各自的**函数体**（更深的行），
    #   早期版本按"连续行"取组，遇到多行 catch 体就只看得见自己 → 明明写了取消分支仍报 R1。
    ('r1_多行catch体_取消分支仍能被正确归组', '''package a

class A {
    suspend fun f() {
        try {
            doWork()
        } catch (e: CancellationException) {
            log(e)
            throw e
        } catch (e: Exception) {
            log(e)
            notify(e)
        }
    }
}
''', 0, ''),

    ('r2_协程内裸runCatching_必须报', '''package a

class A {
    suspend fun f() {
        val r = runCatching { doWork() }
        return r
    }
}
''', 1, '[R2]'),

    ('r2_改用Cancellable_不报', '''package a

class A {
    suspend fun f() {
        val r = runCatchingCancellable { doWork() }
        return r
    }
}
''', 0, ''),

    ('r2_非协程文件纯函数_不报', '''package a

object X {
    fun f(): Int = runCatching { 1 }.getOrDefault(0)
}
''', 0, ''),

    ('r2_协程文件里的非suspend函数_函数边界挡住_不报', '''package a

class A {
    suspend fun g() { delay(1) }
    fun h(): Int = runCatching { 1 }.getOrDefault(0)
}
''', 0, ''),

    # ★ 词法分析器的历史坑：KDoc 里的 content:// 曾被当成行注释，整行被抹掉导致
    #   括号深度错位；字符串里的 "catch (e: Exception)" 曾被当成真代码误报。
    ('词法_嵌套块注释与content与字符串常量_不误报', '''package a

/**
 * 说明：content:// 与 /* 嵌套 /* 内层 */ 写法 */ 都不应被当成行注释
 */
object X {
    private const val S = "try { } catch (e: Exception) { }"
    suspend fun f() {
        try {
            doWork()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(e)
        }
    }
}
''', 0, ''),

    ('豁免_行尾ci-ok_不报', '''package a

class A {
    suspend fun f() {
        try {
            doWork()
        } catch (e: Exception) { log(e) }   // ci-ok: 无挂起点，纯 CPU 转换
    }
}
''', 0, ''),

    ('非协程文件_宽泛catch_不报', '''package a

object X {
    fun f() {
        try {
            doWork()
        } catch (e: Exception) {
            log(e)
        }
    }
}
''', 0, ''),
]


def write_verbatim(path: Path, text: str) -> None:
    """原样写文件（不翻译换行）——样例里要写 CRLF，就必须绕开文本模式的通用换行转换。

    ★ Path.write_text(newline=…) 要 Python 3.10+；这里用 open(..., newline='')
      兼容 3.7 起的全部版本，避免"自测只能在新解释器上跑"。
    """
    with open(path, 'w', encoding='utf-8', newline='') as fh:
        fh.write(text)


def selftest() -> int:
    import tempfile

    failed: list[str] = []
    with tempfile.TemporaryDirectory(prefix='check-cancellation-') as td:
        root = Path(td)
        for name, src, want, kind in CASES:
            p = root / f'{name}.kt'
            write_verbatim(p, src)
            probs = check(p)
            detail = ' | '.join(x.split('\n')[0] for x in probs)
            if len(probs) != want or (want and kind not in ' '.join(probs)):
                failed.append(
                    f'  ✗ {name}：期望 {want} 条{kind or ""}，实际 {len(probs)} 条 → {detail}'
                )

        # ★ CRLF 等价性：core.autocrlf=true 的检出是 CRLF，扫描结果必须与 LF 完全一致
        #   （行号、条数、规则标记）。不一致 = Windows 与 CI 判据分叉。
        eq = root / 'crlf_equivalence.kt'
        write_verbatim(eq, CASES[0][1])
        lf = check(eq)
        write_verbatim(eq, CASES[0][1].replace('\n', '\r\n'))
        crlf = check(eq)
        if len(lf) != len(crlf):
            failed.append(
                f'  ✗ CRLF 等价性：LF {len(lf)} 条 vs CRLF {len(crlf)} 条 —— 行尾处理不一致'
            )
        for a, b in zip(lf, crlf):
            if a.split(':', 1)[-1] != b.split(':', 1)[-1]:
                failed.append(f'  ✗ CRLF 等价性：同一处判定结果不同\n    LF  :{a}\n    CRLF:{b}')

    if failed:
        print('check-cancellation --selftest: 失败\n')
        print('\n'.join(failed))
        return 1
    print('check-cancellation --selftest: OK（%d 组样例 + CRLF/LF 等价性）' % (len(CASES) + 1))
    return 0


def main() -> int:
    args = sys.argv[1:]
    if args and args[0] in ('--selftest', '-t', 'selftest'):
        return selftest()

    roots = args or ['android/app', 'shared', 'android/data']
    files = collect(roots)
    if not files:
        print('check-cancellation: 未找到任何 Kotlin 源文件（roots=%s）' % ' '.join(roots))
        return 1

    problems: list[str] = []
    for f in files:
        problems += check(f)

    if problems:
        print('check-cancellation: 发现 %d 处会吞掉协程取消信号的写法\n' % len(problems))
        for p in problems:
            print(p)
        print('\n豁免：确认无挂起点时在行尾加 `// ci-ok: 原因`。')
        return 1
    print('check-cancellation: OK（已扫描 %d 个 Kotlin 文件，无吞取消模式）' % len(files))
    return 0


if __name__ == '__main__':
    sys.exit(main())
