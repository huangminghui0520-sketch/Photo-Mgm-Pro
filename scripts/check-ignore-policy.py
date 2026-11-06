#!/usr/bin/env python3
"""CI 拦截：被跳过的测试（@Ignore / @Disabled）必须可溯源。

背景
----
`@Ignore` 与 `@Disabled` 是测试套件里的**黑洞**：加一条只要一秒钟，但从此这条用例
既不执行也不报错，没人再想起它。本仓 Review 第一轮结束时出现过一条只有原因、没有
追踪编号的 `@Ignore`，靠人肉记住"以后要去删"是不可靠的。

约定
----
在 `*/src/test/**` 下，每一条 `@Ignore` / `@Disabled` 都必须同时给出：
    1. **原因**——为什么跳过（非空字符串，说清"修什么才启用"）；
    2. **追踪编号**——`#<数字>` 形式的 issue 或 PR 引用（如 `#3`），
       让人能直接点进去看进展与验收标准。

    @Ignore("W5(#3): preload 未显式 .size(w,h)，修复须同时改缓存键；修复后删除本注解")
                        ↑编号                                    ↑启用条件

规则
----
I1  无参（`@Ignore` / `@Disabled`）→ 违规：既无原因也无编号。
I2  有参但参数为空串（`@Ignore("")`）→ 违规：有参等于没说。
I3  有原因但字符串里不含 `#<数字>` → 违规：无法溯源到 issue。
    （跨行参数按括号平衡读取后再判定，允许
        @Ignore(
            "W5(#3): …"
        )
     这种 Kotlin 常见写法。）

★ 跳过扫描（I-02，M1 修复：本脚本原来的绿灯是"空真"）
--------------------------------------------------
实测事实（2026-11-06）：全仓 `@Ignore`/`@Disabled` = **0 条**，也就是说 I1~I3 一条
都没机会命中；真实跳过靠 JUnit 的 `assumeTrue`（原始提及 22 行 / 调用点 15 处）。
原来的 "OK" 只证明"没人用那两个注解"，**不能**读成"跳过都合规"。

因此新增独立统计段（**不并入 ANNOTATION_RE**：`assumeTrue` 是合法的运行期跳过，
不是"缺原因缺编号"的注解，硬塞进去会造一堆假红）：

    [SKIP-SCAN] assumeTrue/assumeFalse 命中 15 处调用点（原始提及 22 行…）
      [ OK ] path:line  assumeTrue("…", cond)  [原因: 有(消息串)]

  · 默认**只统计不判红**：命中条数、行号、片段、原因来源全部可见，并计入汇总行；
  · 唯一判红条件：**既没有注释说明、也没有非空消息串**的裸调用 `assumeTrue(cond)`
    ——这类跳过连一句"为什么"都没留下，输出 `[WARN]` 并以 exit 1 结束；
    `--allow-unannotated-skip` 可关闭该判红（默认开），供"先落地门禁"的过渡期用；
  · 原因来源两条：① 调用行及其上方 2 行（共 3 行窗口）内的 `//` / `/*` 注释；
    ② 参数里的非空字符串字面量（JUnit 4 的 `assumeTrue(String, boolean)` 与 JUnit 5 的
    `assumeTrue(boolean, String)` 都支持；本仓 15 处**全部**用的是消息串形式，
    若只认注释会凭空造 7 条假红）。**注意**：assumeTrue 属 JUnit 5 运行期跳过，
    当前门禁不覆盖"实例是否真的跑了"，只保证"跳过是可溯源的"。

用法
----
    python3 scripts/check-ignore-policy.py [路径 ...]   # 默认扫 android/app shared android/data + desktop/*/src/test
    python3 scripts/check-ignore-policy.py --selftest   # 内置样例自测（不依赖仓库代码）
    python3 scripts/check-ignore-policy.py --allow-unannotated-skip   # 裸 assumeTrue 不判红
    退出码 0=通过  1=发现违规 / 无原因的跳过 / 自测失败

★ CI 可重现性（与 check-cancellation.py 同样的四条前提）
----
1. 解释器：只用标准库 + `python3`，不读环境变量、不访问网络。
2. 路径：相对路径按**脚本所在仓库根**解析（[REPO_ROOT]），不依赖调用方 cwd。
3. 行尾：扫描前把 CRLF / CR 统一归一为 `\n` 并去 BOM，保证行号列位跨系统一致。
4. 入库：由 CI 步骤 `git ls-files --error-unmatch scripts/check-ignore-policy.py` 显式断言
   （比"python3: can't open file"可读，能直接指到 .gitignore 白名单）。

豁免（误报处理）
----
    在注解行末尾加注释 `// ci-ok: 原因`，可单独豁免该处。
    典型适用场景：测试基类 / 平台相关的样板注解。
    优先顺序仍然是：**能补上原因与编号就补**，ci-ok 只用于确实无处可挂的情况。

★ 为什么这个脚本自己也要有 --selftest
--------------------------------
判据是"用正则找注解"，很容易写出**永远匹配不到**的规则——那样脚本永远绿，
等于没有门禁。自测里既放了"必须报"的样例（规则失效时会静默通过 → 自测变红），
也放了"不能报"的样例（KDoc 里提到 @Ignore、字符串常量里含 @Ignore → 规则过宽会变红）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# 仓库根 = 本脚本（scripts/）的上级目录
REPO_ROOT = Path(__file__).resolve().parent.parent

# 注解名（含全限定写法）。JUnit4 是 @Ignore，JUnit5 是 @Disabled。
ANNOTATION_RE = re.compile(
    r'@(?:org\.junit\.Ignore|org\.junit\.jupiter\.api\.Disabled|Ignore|Disabled)\b'
)

# 追踪编号：`#3` / `(#3)` / `见 #3` / `issues/3` 都算；至少要求 `#<数字>`。
ISSUE_REF_RE = re.compile(r'#\d+')

# 字符串字面量（Kotlin 支持 """三引号"""，这里取第一个字面量的内容）
STRING_RE = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"', re.S)

SUPPRESS = 'ci-ok'


def read_source(path: Path) -> str:
    """按字节读入 → 去 BOM → 归一 CRLF/CR。行号列位跨系统恒定。"""
    return path.read_bytes().decode('utf-8', errors='replace') \
        .lstrip('\ufeff').replace('\r\n', '\n').replace('\r', '\n')


def strip_comments(src: str, blank_strings: bool = False) -> str:
    """把注释替换为等长空白（保留行号与列位）。

    ★ 必须带状态：先判字符串再判注释，否则
        val s = "http://x"     // 会把 //x" 当成注释，括号/行号全乱

    ★ 为什么要两份副本（等长抹除保证下标可以跨副本复用）：
        - blank_strings=False：只抹注释、留字符串 → 用来**读注解参数**（需要字面量原文）；
        - blank_strings=True ：注释与字符串内容都抹掉 → 用来**定位注解名**，
          否则 `val note = "@Ignore 都要带编号"` 会被当成一条真的注解（I1 误报）。
      KDoc 里提到 @Ignore 同样依赖这一点：注释被抹掉后就不会命中。
    """
    out: list[str] = []
    i, n = 0, len(src)
    state = None            # None | '"' | '/' | '*'
    depth = 0               # 块注释嵌套深度（Kotlin 块注释可嵌套）
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if state is None:
            if c == '"':
                state = '"'
                out.append(' ' if blank_strings else c)
            elif c == '/' and nxt == '*':
                depth = 1
                state = '*'
                out.append('  ')
                i += 1
            elif c == '/' and nxt == '/':
                state = '/'
                out.append('  ')
                i += 1
            else:
                out.append(c)
        elif state == '"':
            if c == '\\' and i + 1 < n:
                # 转义序列按 2 个字符整体处理，保证 out 与 src **等长**
                out.append('  ' if blank_strings else c + src[i + 1])
                i += 1
            elif c == '"':
                out.append(' ' if blank_strings else c)
                state = None
            else:
                out.append('\n' if c == '\n' else (' ' if blank_strings else c))
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
        elif state == '/':
            # ★ 2026-11-06 修：这里原来无条件 `out.append('\n')`，等于**每吞掉一个
            #   注释字符就写一个换行** —— out 比 src 长出「注释总字符数」倍，行号随之
            #   虚高（AppViewModelTest 的 :90 被报成 :602）。因为全仓 @Ignore/@Disabled
            #   长期为 0、自测样例的注释又都排在注解之后，这个错一直没被照出来；
            #   加上 assumeTrue 统计段（要贴行号）后立刻暴露。等长是 strip_comments
            #   的契约：保持行号列位与 src 完全一致。
            if c == '\n':
                state = None
                out.append('\n')
            else:
                out.append(' ')
        i += 1
    return ''.join(out)


def read_balanced(src: str, open_idx: int) -> str:
    """从 `(` 位置取到配对的 `)`（含两端）。括号不平衡时取到末尾。"""
    depth = 0
    for j in range(open_idx, len(src)):
        if src[j] == '(':
            depth += 1
        elif src[j] == ')':
            depth -= 1
            if depth == 0:
                return src[open_idx:j + 1]
    return src[open_idx:]


def first_string_literal(text: str) -> str | None:
    """取参数文本里的第一个字符串字面量内容（无转义还原，判定够用）。"""
    m = STRING_RE.search(text)
    return m.group(1) if m else None


# ------------------- 跳过机制（assumeTrue / assumeFalse，I-02） -------------------

# 只匹配**调用**（名字后紧跟 `(`）：`import org.junit.Assume.assumeTrue` 与注释里的
# 提及都不改变运行期行为，算进"跳过了几个用例"会把统计变成噪声。
SKIP_RE = re.compile(r'\b(assumeTrue|assumeFalse)\s*\(')
# 提到名字但没调用的行（import / 注释 / 字符串），用于把"原始 grep 22 行"的口径说清楚。
SKIP_MENTION_RE = re.compile(r'\b(?:assumeTrue|assumeFalse)\b')
IMPORT_LINE_RE = re.compile(r'^\s*import\b')
# 原因窗口：调用行 + 上方 2 行，共 3 行。
SKIP_COMMENT_WINDOW = 3


def scan_skips(path: Path) -> tuple[list[tuple[str, int, str, str]], dict[str, int]]:
    """扫描一条测试源码里的 `assumeTrue` / `assumeFalse` 调用点。

    返回 `(hits, meta)`：
      hits = [(等级, 行号, 行片段, 原因来源)]，等级为 `'ok'` / `'warn'`；
      meta = {'mentions': 提及行数, 'imports': import 行数, 'non_calls': 其余提及行数}。

    ★ 定位用 `strip_comments(raw, blank_strings=True)`：注释与字符串内容都被抹掉，
      KDoc 里的 "assumeTrue 会跳过它" 与 `val s = "assumeTrue("` 都不会被当成调用。
      读消息串用 `strip_comments(raw)`（保留字符串）——两份副本**等长同下标**，
      所以从 locator 上拿到的位置可以直接在 code 上读参数，见 strip_comments 注释。
    """
    raw = read_source(path)
    locator = strip_comments(raw, blank_strings=True)
    code = strip_comments(raw)
    raw_lines = raw.split('\n')

    hits: list[tuple[str, int, str, str]] = []
    for m in SKIP_RE.finditer(locator):
        line_no = locator.count('\n', 0, m.start()) + 1
        if line_no - 1 < len(raw_lines) and SUPPRESS in raw_lines[line_no - 1]:
            # 与注解豁免同一条约定：行尾 `// ci-ok: 原因` 也算写明了原因
            hits.append(('ok', line_no, raw_lines[line_no - 1].strip(), '有(ci-ok)'))
            continue
        message = first_string_literal(read_balanced(code, m.end() - 1))
        has_comment = any(
            '//' in raw_lines[i] or '/*' in raw_lines[i]
            for i in range(max(0, line_no - SKIP_COMMENT_WINDOW), min(line_no, len(raw_lines)))
        )
        if has_comment and message:
            why = '有(注释+消息串)'
        elif has_comment:
            why = '有(注释)'
        elif message:
            why = '有(消息串)'
        else:
            why = '无'
        snippet = raw_lines[line_no - 1].strip() if line_no - 1 < len(raw_lines) else ''
        if len(snippet) > 110:
            snippet = snippet[:110] + '…'
        hits.append(('ok' if why != '无' else 'warn', line_no, snippet, why))

    # 口径对账：原始 grep 的 22 行里含 import 与注释提及，它们不是"跳过的用例"。
    call_lines = {h[1] for h in hits}
    mention_idx = [i for i, line in enumerate(raw_lines) if SKIP_MENTION_RE.search(line)]
    imports = sum(1 for i in mention_idx if IMPORT_LINE_RE.match(raw_lines[i]))
    non_calls = sum(1 for i in mention_idx
                    if (i + 1) not in call_lines and not IMPORT_LINE_RE.match(raw_lines[i]))
    meta = {'mentions': len(mention_idx), 'imports': imports, 'non_calls': non_calls}
    return hits, meta


def skip_exit_code(hits: list, allow_unannotated: bool) -> int:
    """无原因的跳过 → 1；`--allow-unannotated-skip` 时恒 0。"""
    if allow_unannotated:
        return 0
    return 1 if any(h[0] == 'warn' for h in hits) else 0


def warn_count(hits: list) -> int:
    """hits 里 `[WARN]` 的条数。"""
    return sum(1 for h in hits if h[0] == 'warn')


def check(path: Path) -> list[str]:
    raw = read_source(path)
    code = strip_comments(raw)                          # 留字符串 → 读注解参数
    locator = strip_comments(raw, blank_strings=True)   # 抹字符串 → 定位注解名
    raw_lines = raw.split('\n')

    problems: list[str] = []
    for m in ANNOTATION_RE.finditer(locator):
        line_no = code.count('\n', 0, m.start()) + 1
        line = raw_lines[line_no - 1] if line_no - 1 < len(raw_lines) else ''
        if SUPPRESS in line:
            continue

        # 跳过空白与换行，看后面是不是 '('（在留字符串的副本上读，下标与 locator 对齐）
        k = m.end()
        while k < len(code) and code[k] in ' \t\r\n':
            k += 1

        if k >= len(code) or code[k] != '(':
            problems.append(
                f'{path}:{line_no} [I1] {m.group(0)} 无参数：'
                f'必须写明「跳过原因 + issue 编号」，例如 @Ignore("W5(#3): …，修复后删除本注解")'
            )
            continue

        args = read_balanced(code, k)
        literal = first_string_literal(args)
        if literal is None:
            problems.append(
                f'{path}:{line_no} [I2] {m.group(0)} 参数里没有字符串字面量：'
                f'原因必须是可读字符串（顺带带上 #<issue 编号>）'
            )
            continue
        if not literal.strip():
            problems.append(
                f'{path}:{line_no} [I2] {m.group(0)}("") 原因不能为空'
            )
            continue
        if not ISSUE_REF_RE.search(literal):
            problems.append(
                f'{path}:{line_no} [I3] {m.group(0)} 缺少 issue 编号：'
                f'原因里必须含 `#<数字>`（如 "W5(#3): …"），否则日后无人能追溯这条跳过'
            )
    return problems


# ------------------------------- 自测 -------------------------------

CASES: list[tuple[str, str, list[str]]] = [
    # (名字, 源码, 期望命中的规则编号列表；空列表=不应报)
    ('i1_裸Ignore必须报', '''package a

import org.junit.Ignore
import org.junit.Test

class T {
    @Ignore
    @Test
    fun f() {}
}
''', ['I1']),

    ('i1_裸Disabled必须报', '''package a

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class T {
    @Disabled
    @Test
    fun f() {}
}
''', ['I1']),

    ('i2_空原因必须报', '''package a

import org.junit.Ignore

class T {
    @Ignore("")
    fun f() {}
}
''', ['I2']),

    ('i3_有原因无编号必须报', '''package a

import org.junit.Ignore

class T {
    @Ignore("W5：preload 未显式限定解码尺寸，修复后启用")
    fun f() {}
}
''', ['I3']),

    ('ok_原因加编号不报', '''package a

import org.junit.Ignore

class T {
    @Ignore("W5(#3): preload 未显式 .size(w,h)，修复须同时改缓存键；修复后删除本注解")
    fun f() {}
}
''', []),

    ('ok_跨行参数不报', '''package a

import org.junit.jupiter.api.Disabled

class T {
    @Disabled(
        "W5(#3): preload 未显式 .size(w,h)；" +
            "修复须同时改缓存键"
    )
    fun f() {}
}
''', []),

    ('ok_编号在句中不报', '''package a

import org.junit.Ignore

class T {
    @Ignore("等待 #12 合入后再启用")
    fun f() {}
}
''', []),

    ('ok_KDoc里提到Ignore不误报', '''package a

/**
 * ★ 之所以用 @Ignore 而不是删掉：修复后删除 @Ignore 即可变绿。
 * 也不要写成 @Disabled —— 那是 JUnit5 的写法。
 */
class T {
    fun f() {}
}
''', []),

    ('ok_字符串常量含Ignore不误报', '''package a

class T {
    val note = "@Ignore 与 @Disabled 都要带编号"
    fun f() {}
}
''', []),

    ('ok_ci_ok豁免', '''package a

import org.junit.Ignore

class T {
    @Ignore   // ci-ok: 测试基类样板注解，无具体 issue 可挂
    fun f() {}
}
''', []),

    ('ok_块注释里的注解不误报', '''package a

class T {
    fun f() {
        /*
         * @Ignore("这里不是真的注解")
         */
    }
}
''', []),
]


# ---- 跳过扫描样例（I-02）。期望值是 (调用点数, WARN 数)，见 selftest ----
SKIP_CASES: list[tuple[str, str, int, int]] = [
    ('skip_带注释说明不告警', '''package a

import org.junit.Assume.assumeTrue

class T {
    @Test
    fun f() {
        // 源照片不在仓库里，CI 上必然缺失
        assumeTrue(files.isNotEmpty())
    }
}
''', 1, 0),

    ('skip_只有消息串原因不告警', '''package a

class T {
    fun f() {
        assumeTrue("源目录照片缺失，跳过真实样本 oracle", samples.isNotEmpty())
    }
}
''', 1, 0),

    ('skip_裸调用无原因必须告警', '''package a

class T {
    fun f() {
        assumeTrue(files.isNotEmpty())
    }
}
''', 1, 1),

    ('skip_assumeFalse_同样纳入统计', '''package a

class T {
    fun f() {
        assumeFalse("反向断言：该目录不应有照片", hasPhotos)
    }
}
''', 1, 0),

    ('skip_全限定Assumptions写法必须识别', '''package a

class T {
    fun f() {
        org.junit.jupiter.api.Assumptions.assumeTrue("A", condA)
        Assumptions.assumeTrue(condB, "B")
    }
}
''', 2, 0),

    ('skip_KDoc与字符串里提到不算调用', '''package a

/**
 * assumeTrue 会跳过它，所以不会逼近超时。
 */
class T {
    val note = "assumeTrue( 只是说明文字"
    // assumeTrue(cond) 被注释掉，不算调用
    fun f() {}
}
''', 0, 0),
]


def selftest() -> int:
    import tempfile

    failed: list[str] = []
    with tempfile.TemporaryDirectory(prefix='check-ignore-policy-') as td:
        root = Path(td)
        for name, src, want in CASES:
            p = root / f'{name}.kt'
            p.write_text(src, encoding='utf-8', newline='')
            probs = check(p)
            got = sorted({re.search(r'\[(I\d)\]', m).group(1)
                          for m in probs if re.search(r'\[(I\d)\]', m)})
            if got != sorted(want):
                failed.append(
                    f'  ✗ {name}: 期望 {sorted(want)} 实际 {got}\n'
                    + '\n'.join('      ' + x for x in probs)
                )
            else:
                print(f'  ✓ {name}')

        # ---- 跳过扫描（assumeTrue / assumeFalse）样例 ----
        # 期望值是"调用点数 / WARN 数"，不是违规编号：本组判据的性质是
        # 「必须有可见统计」+「无原因必须告警」，不是「命中即违规」。
        for name, src, want_calls, want_warns in SKIP_CASES:
            p = root / f'{name}.kt'
            p.write_text(src, encoding='utf-8', newline='')
            hits, _meta = scan_skips(p)
            got_warns = warn_count(hits)
            if len(hits) != want_calls or got_warns != want_warns:
                failed.append(
                    f'  ✗ {name}: 期望 {want_calls} 处调用 / {want_warns} 条 WARN，'
                    f'实际 {len(hits)} / {got_warns}\n'
                    + '\n'.join(f'      {h[0]} :{h[1]} [{h[3]}]' for h in hits)
                )
            else:
                print(f'  ✓ {name}')

        # WARN 必须真的导致 exit 1；--allow-unannotated-skip 必须真的能关掉它
        warn_hits = [('warn', 12, 'assumeTrue(cond)', '无')]
        if skip_exit_code(warn_hits, False) != 1:
            failed.append('  ✗ 无原因的 assumeTrue 没有导致 exit 1（判据失效）')
        if skip_exit_code(warn_hits, True) != 0:
            failed.append('  ✗ --allow-unannotated-skip 未能关闭判红')
        if skip_exit_code([('ok', 12, 'assumeTrue("x", cond)', '有(消息串)')], False) != 0:
            failed.append('  ✗ 带原因的 assumeTrue 被误判为 exit 1')

        # CRLF 等价性：同一份源码换成 CRLF 后判定结果必须完全一致
        p = root / 'crlf.kt'
        for name, src, _want in CASES:
            p.write_text(src.replace('\n', '\r\n'), encoding='utf-8', newline='')
            a = [re.sub(r'\[(I\d)\]', '', x) for x in check(p)]
            p.write_text(src, encoding='utf-8', newline='')
            b = [re.sub(r'\[(I\d)\]', '', x) for x in check(p)]
            if a != b:
                failed.append(f'  ✗ {name}: CRLF 与 LF 判定不一致\n      CRLF={a}\n      LF  ={b}')
        # CRLF 下跳过扫描的行号必须一致（统计段要贴行号，跨平台不能错位）
        for name, src, _calls, _warns in SKIP_CASES:
            p.write_text(src.replace('\n', '\r\n'), encoding='utf-8', newline='')
            a_crlf = scan_skips(p)[0]
            p.write_text(src, encoding='utf-8', newline='')
            b_lf = scan_skips(p)[0]
            if a_crlf != b_lf:
                failed.append(f'  ✗ {name}: CRLF 与 LF 的跳过扫描结果不一致')
    print('  ✓ CRLF/LF 等价性')

    if failed:
        print('\n自测失败：')
        print('\n'.join(failed))
        return 1
    total = len(CASES) + len(SKIP_CASES)
    print(f'\n自测通过：{total} 组样例（{len(CASES)} 组注解规则 + {len(SKIP_CASES)} 组跳过扫描）'
          f' + WARN 退出码 + CRLF 等价性')
    return 0


DEFAULT_ROOTS = [
    # 安卓端（模块目录，本身含 src/main 与 src/test；本脚本只判 */src/test/**）
    'android/app', 'android/data',
    # 共享层三个 kotlin("jvm") 模块
    'shared',
    # 桌面端（I-04：原来整段在盲区；只传 src/test——本脚本不判生产代码，
    #   src/main 即使传进来也会被下面的 '/src/test/' 过滤掉，故不重复列）
    'desktop/core/src/test', 'desktop/cli/src/test', 'desktop/ui/src/test',
]


def rel(path: Path) -> str:
    """输出用相对路径（临时目录等不在仓库内的路径原样返回）。"""
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def main() -> int:
    flags = {a for a in sys.argv[1:] if a.startswith('-')}
    if '--selftest' in flags:
        return selftest()
    allow_unannotated = '--allow-unannotated-skip' in flags
    roots = [a for a in sys.argv[1:] if not a.startswith('-')] or DEFAULT_ROOTS

    files: list[Path] = []
    for r in roots:
        p = Path(r)
        if not p.is_absolute():
            p = REPO_ROOT / r
        if p.is_file():
            files.append(p)
        else:
            files += [f for f in p.rglob('*.kt') if '/build/' not in f.as_posix()]

    problems: list[str] = []
    scanned = 0
    detail: list[tuple[str, str, int, str, str]] = []   # (文件, 等级, 行号, 片段, 原因来源)
    meta_total = {'mentions': 0, 'imports': 0, 'non_calls': 0}
    for f in sorted(set(files)):
        # 只管测试源码：生产代码里的注解（如 @Deprecated）不属本规则
        if '/src/test/' not in f.as_posix().replace('\\', '/'):
            continue
        scanned += 1
        problems += check(f)
        hits, meta = scan_skips(f)
        for level, line_no, snippet, why in hits:
            detail.append((rel(f), level, line_no, snippet, why))
        for k in meta_total:
            meta_total[k] += meta[k]

    warns = sum(1 for d in detail if d[1] == 'warn')

    # ★ 统计段先打印：I-02 的教训是"绿灯必须能读出 skipped 口径"，
    #   否则 OK 只证明"没人用 @Ignore/@Disabled"。
    print(f'[SKIP-SCAN] assumeTrue/assumeFalse 命中 {len(detail)} 处调用点'
          f'（原始提及 {meta_total["mentions"]} 行：import {meta_total["imports"]} 行 + '
          f'注释/字符串提及 {meta_total["non_calls"]} 行；后两类不计入跳过）')
    for path_s, level, line_no, snippet, why in detail:
        tag = 'WARN' if level == 'warn' else ' OK '
        print(f'  [{tag}] {path_s}:{line_no}  {snippet}  [原因: {why}]')

    if warns:
        print(f'\n{warns} 处跳过既没有注释说明、也没有非空消息串'
              f'（assumeTrue(cond) 裸调用 = 没人知道它为什么被跳过）。')
        if allow_unannotated:
            print('当前以 --allow-unannotated-skip 运行，仅告警不判红。')
        else:
            print('修法：给调用补一句原因（消息串 `assumeTrue("…为什么…", cond)` 或上方注释），'
                  '或显式加 --allow-unannotated-skip 过渡（不推荐长期这样）。')

    if problems:
        print(f'\n发现 {len(problems)} 处跳过的测试不可溯源（扫描 {scanned} 个测试文件）：\n')
        for x in problems:
            print('  ' + x)
        print('\n修法：给注解补上「原因 + #<issue 编号>」，'
              '例如 @Ignore("W5(#3): …,修复后删除本注解")')
        print('确属无处可挂的样板注解，行尾加 // ci-ok: 原因 单独豁免。')

    if problems or (warns and not allow_unannotated):
        return 1

    print(f'check-ignore-policy: OK（{scanned} 个测试文件；'
          f'@Ignore/@Disabled 0 处违规；assumeTrue/assumeFalse 调用点 {len(detail)} 处，'
          f'其中无原因 {warns} 处）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
