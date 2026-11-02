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

用法
----
    python3 scripts/check-ignore-policy.py [路径 ...]   # 默认扫 app algorithm data
    python3 scripts/check-ignore-policy.py --selftest   # 内置样例自测（不依赖仓库代码）
    退出码 0=通过  1=发现违规 / 自测失败

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
            if c == '\n':
                state = None
            out.append('\n')
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

        # CRLF 等价性：同一份源码换成 CRLF 后判定结果必须完全一致
        p = root / 'crlf.kt'
        for name, src, _want in CASES:
            p.write_text(src.replace('\n', '\r\n'), encoding='utf-8', newline='')
            a = [re.sub(r'\[(I\d)\]', '', x) for x in check(p)]
            p.write_text(src, encoding='utf-8', newline='')
            b = [re.sub(r'\[(I\d)\]', '', x) for x in check(p)]
            if a != b:
                failed.append(f'  ✗ {name}: CRLF 与 LF 判定不一致\n      CRLF={a}\n      LF  ={b}')
    print('  ✓ CRLF/LF 等价性')

    if failed:
        print('\n自测失败：')
        print('\n'.join(failed))
        return 1
    print(f'\n自测通过：{len(CASES)} 组样例 + CRLF 等价性')
    return 0


def main() -> int:
    roots = sys.argv[1:] or ['android/app', 'shared', 'android/data']
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
    for f in sorted(set(files)):
        # 只管测试源码：生产代码里的注解（如 @Deprecated）不属本规则
        if '/src/test/' not in f.as_posix().replace('\\', '/'):
            continue
        scanned += 1
        problems += check(f)

    if problems:
        print(f'发现 {len(problems)} 处跳过的测试不可溯源（扫描 {scanned} 个测试文件）：\n')
        for x in problems:
            print('  ' + x)
        print('\n修法：给注解补上「原因 + #<issue 编号>」，'
              '例如 @Ignore("W5(#3): …,修复后删除本注解")')
        print('确属无处可挂的样板注解，行尾加 // ci-ok: 原因 单独豁免。')
        return 1

    print(f'check-ignore-policy: OK（{scanned} 个测试文件，'
          f'所有 @Ignore/@Disabled 均带原因与 issue 编号）')
    return 0


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--selftest':
        sys.exit(selftest())
    sys.exit(main())
