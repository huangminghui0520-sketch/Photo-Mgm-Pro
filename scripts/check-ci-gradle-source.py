#!/usr/bin/env python3
"""check-ci-gradle-source.py —— CI 不得用 wrapper 或子根指定来调用 Gradle（G10 完备判据）。

为什么需要它（而不是一条 grep）
--------------------------------
`docs/REPO-LAYOUT.md` §3.2 的通则是「判据一律只看调用行/代码行」。裸 grep
`gradlew|gradle -p ` 在本仓**永远不可能为 0**：`ci.yml` 的结构约束里有一份
`for f in … gradlew gradlew.bat …` 的**存在性断言清单**，那是数据不是调用。
把判据收紧成"只看 `run:` 行"能消除这个假红，但它仍有两类**结构性漏判**：

  M1  `run: |` 多行块 —— wrapper 调用几乎总落在第二行以后，只看 `run:` 行必漏；
  M2  `working-directory: android` + 裸 `gradle` —— 这是 `-p android` 的隐式等价形态，
      文本里连 `-p` 都不出现，任何基于字面量的 grep 都看不见。

本脚本用「YAML 块标量解析 + shell 命令位识别」覆盖这两类，且只用标准库
（仓库 5 个门禁脚本至今零第三方依赖，不得为此引入 PyYAML）。

判据（G10）
-----------
  C1  命令位出现 wrapper（`gradlew` / `gradlew.bat` / `./gradlew` / `bash ./gradlew`）
      或 `$(./gradlew …)`  → 违规。CI 必须用 setup-gradle 提供的全局 `gradle`：
      海外 runner 拉腾讯云分发包会超时（`gradle/wrapper/gradle-wrapper.properties`）。
  C2  `gradle` 带 `-p android|desktop|shared` 或 `--project-dir=…` → 违规。
  C3  step 的 `working-directory` 指到 `android|desktop|shared` 且命令是裸 `gradle`
      → 违规（隐式的 `-p`）。

  数据位不算调用：`grep gradlew`、`for f in gradlew …`、`[ -e gradlew ]`、`echo …`。
  这条边界是本脚本最容易写错的地方 —— 见下方 SELFTEST 的 D1~D4。

防空真绿灯
----------
解析到的 `run` 块总数为 0 时 **exit 1**（否则 workflow 改名/解析失败会静默全绿）。

可重现性（与另外四个门禁脚本一致）
----------------------------------
  1. 只用标准库；
  2. 相对路径按**脚本所在仓库根**解析，不依赖调用方 cwd；
  3. 读文本按字节读并归一 CRLF/CR + 去 BOM（core.autocrlf 的差异不得影响判定）；
  4. `--selftest` 覆盖正例、反例与 4 个变异；任一不符即 exit 1。

退出码
------
  0  通过（且解析到的 run 块数 > 0）
  1  违规，或自测失败，或解析到 0 个 run 块
  2  用法/环境错误（找不到 .github/workflows）
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# ── 判据参数 ────────────────────────────────────────────────────────────────
SUBROOTS = ("android", "desktop", "shared")

# 这些 head 之后是「数据位」，不是调用位：断言清单、文本搜索、测试表达式
DATA_HEADS = {
    "for", "case", "select", "while", "until",
    "echo", "printf", "cat", "ls", "find", "grep", "egrep", "rg",
    "test", "[", "[[", "true", "false", "set", "export", "unset",
    "mkdir", "cp", "mv", "rm", "touch", "cd", "pushd", "popd",
    "python3", "python", "node", "npm", "npx", "java", "unzip", "zip",
    "if", "elif", "then", "do", "done", "fi", "else", "esac",
}

# 允许出现在真正命令前的包装词（剥掉后继续看下一个 token）
PREFIX_WORDS = {"sudo", "command", "exec", "time", "nohup", "env"}

QUOTES = "'\"`"


# ── 文本归一 ────────────────────────────────────────────────────────────────
def read_text(path: Path) -> str:
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        raw = raw[3:]
    return raw.decode("utf-8", errors="replace").replace("\r\n", "\n").replace("\r", "\n")


# ── 极简 YAML：只解析 step 的 run / working-directory ──────────────────────
def iter_step_ranges(lines: list[str]):
    """按 `- ` 列表项切块，产出 [start, end) 行区间（0 基）。

    只用于 step 级结构；`with: path: |` 这类块标量的内容不会以 `- ` 开头，
    故不会被误切成新 step。
    """
    i, n = 0, len(lines)
    while i < n:
        m = re.match(r"^(\s*)-(\s+)(\S.*)$", lines[i])
        if not m:
            i += 1
            continue
        base = len(m.group(1))
        j = i + 1
        while j < n:
            raw = lines[j]
            if raw.strip() == "":
                j += 1
                continue
            ind = len(raw) - len(raw.lstrip(" "))
            if ind <= base:
                break
            j += 1
        yield i, j
        i = j


def effective(lines: list[str]) -> list[str]:
    """把 `- key: value` 的列表项行改写为 `  key: value`（缩进 = base + 2）。

    只影响**键的定位**（下标与原文一一对应），块标量正文仍按原文取，
    否则 `run: |` 正文里以 `- ` 开头的行（markdown 列表）会被误当成新 step。
    """
    out = []
    for raw in lines:
        m = re.match(r"^(\s*)-(\s+)(\S.*)$", raw)
        out.append(" " * (len(m.group(1)) + 2) + m.group(3) if m else raw)
    return out


def find_key(keys: list[str], lo: int, hi: int, key: str):
    """在 [lo, hi) 内找 `key:`，返回 (行下标, 行内值, key 缩进)；没有则 None。

    `lo` 是 `- ` 列表项所在行：`- run: …` 这种单行写法也必须在起点被找到。
    """
    pat = re.compile(r"^(\s*)" + re.escape(key) + r":(.*)$")
    for k in range(lo, hi):
        m = pat.match(keys[k])
        if m:
            return k, m.group(2).strip(), len(m.group(1))
    return None


def strip_trailing_comment(value: str) -> str:
    """去掉 YAML 行内注释（引号内的 # 不算）。"""
    q = None
    for i, ch in enumerate(value):
        if q:
            if ch == q:
                q = None
        elif ch in QUOTES:
            q = ch
        elif ch == "#" and (i == 0 or value[i - 1] in " \t"):
            return value[:i].rstrip()
    return value.strip()


def unquote(value: str) -> str:
    v = strip_trailing_comment(value)
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
        return v[1:-1]
    return v


def extract_run(lines: list[str], keys: list[str], lo: int, hi: int):
    """抽出 run 脚本文本，返回 [(源码行号 1 基, 该行文本)] 或 None。"""
    hit = find_key(keys, lo, hi, "run")
    if not hit:
        return None
    idx, val, key_indent = hit
    if val[:1] in ("|", ">"):
        body: list[tuple[int, str]] = []
        for k in range(idx + 1, hi):
            raw = lines[k]
            if raw.strip() == "":
                body.append((k + 1, ""))
                continue
            ind = len(raw) - len(raw.lstrip(" "))
            if ind <= key_indent:
                break
            body.append((k + 1, raw))
        dedent = min((len(t) - len(t.lstrip(" ")) for _, t in body if t.strip()), default=0)
        body = [(ln, t[dedent:] if t.strip() else "") for ln, t in body]
        if val.startswith(">"):  # 折叠块：空行分段，段内空格连接
            out: list[tuple[int, str]] = []
            buf: list[str] = []
            first = 0
            for ln, t in body:
                if t.strip() == "":
                    if buf:
                        out.append((first, " ".join(buf)))
                        buf = []
                else:
                    if not buf:
                        first = ln
                    buf.append(t.strip())
            if buf:
                out.append((first, " ".join(buf)))
            return out
        return body
    return [(idx + 1, unquote(val))]


def extract_wd(keys: list[str], lo: int, hi: int) -> str | None:
    hit = find_key(keys, lo, hi, "working-directory")
    if not hit:
        return None
    return unquote(hit[1]) or None


# ── shell 片段切分与命令位判定 ─────────────────────────────────────────────
def strip_shell_comment(line: str) -> str:
    q = None
    for i, ch in enumerate(line):
        if q:
            if ch == q:
                q = None
        elif ch in QUOTES:
            q = ch
        elif ch == "#" and (i == 0 or line[i - 1] in " \t"):
            return line[:i]
    return line


def split_segments(line: str) -> list[str]:
    """按 shell 分隔符切段（引号内的不算），用于逐段判定命令位。"""
    segs, buf, q, i = [], [], None, 0
    while i < len(line):
        ch = line[i]
        if q:
            buf.append(ch)
            if ch == q:
                q = None
            i += 1
            continue
        if ch in QUOTES:
            q = ch
            buf.append(ch)
            i += 1
            continue
        two = line[i:i + 2]
        if two in ("&&", "||"):
            segs.append("".join(buf))
            buf = []
            i += 2
            continue
        if ch in ";|&(){}":
            segs.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    segs.append("".join(buf))
    return [s.strip() for s in segs if s.strip()]


def tokenize(seg: str) -> list[str]:
    """尽量轻量的分词：只处理引号与转义，不展开变量。"""
    toks, buf, q, i = [], [], None, 0
    while i < len(seg):
        ch = seg[i]
        if q:
            if ch == q:
                q = None
            else:
                buf.append(ch)
            i += 1
            continue
        if ch in QUOTES:
            q = ch
            i += 1
            continue
        if ch == "\\" and i + 1 < len(seg):
            buf.append(seg[i + 1])
            i += 2
            continue
        if ch in " \t":
            if buf:
                toks.append("".join(buf))
                buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if buf:
        toks.append("".join(buf))
    return toks


def norm_head(tok: str) -> str:
    """`$(./gradlew`、`"gradlew"`、`(`(已切) 等形态归一到 basename 判定前的裸词。"""
    t = tok
    while t[:1] in "$(`{":
        t = t[1:]
    return t.strip("(){};\"'`")


def is_wrapper(tok: str) -> bool:
    t = norm_head(tok).replace("\\", "/")
    return t.rsplit("/", 1)[-1] in ("gradlew", "gradlew.bat")


def is_subroot(value: str | None) -> bool:
    if not value:
        return False
    v = value.replace("\\", "/").strip()
    while v.startswith("./"):
        v = v[2:]
    v = v.rstrip("/")
    return v.split("/", 1)[0] in SUBROOTS


def project_dir_arg(args: list[str]) -> str | None:
    for i, a in enumerate(args):
        if a in ("-p", "--project-dir") and i + 1 < len(args):
            return args[i + 1]
        if a.startswith("--project-dir="):
            return a.split("=", 1)[1]
    return None


def check_segment(seg: str, wd: str | None) -> str | None:
    """返回违规原因（简码），无违规则 None。"""
    toks = tokenize(seg)
    while toks and re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", toks[0]):
        toks.pop(0)  # 前置环境变量赋值
    while toks and norm_head(toks[0]) in PREFIX_WORDS:
        toks.pop(0)
    if not toks:
        return None
    head = norm_head(toks[0])
    if head in ("bash", "sh", "zsh", "dash"):
        if any(is_wrapper(t) for t in toks[1:]):
            return "C1"
        return None
    if is_wrapper(head):
        return "C1"
    if head in DATA_HEADS:
        return None
    if head.rsplit("/", 1)[-1] == "gradle" or head == "gradle":
        pd = project_dir_arg(toks[1:])
        if pd is not None and is_subroot(pd):
            return "C2"
        if pd is None and is_subroot(wd):
            return "C3"
    return None


def logical_lines(script: list[tuple[int, str]]):
    """反斜杠续行拼成逻辑行；整行注释丢弃；返回 [(行号, 逻辑行)]。"""
    out, buf, first = [], "", 0
    for lineno, text in script:
        s = text.rstrip()
        if not buf:
            first = lineno
            if s.lstrip().startswith("#"):
                continue
        if s.endswith("\\"):
            buf += s[:-1] + " "
            continue
        buf += s
        out.append((first, buf))
        buf = ""
    if buf:
        out.append((first, buf))
    return out


# ── 分析一个 workflow 文本 ─────────────────────────────────────────────────
def analyze_text(name: str, text: str):
    """返回 (违规列表 [(name, 行号, 简码, 片段)], run 块数)。"""
    lines = text.split("\n")
    keys = effective(lines)
    violations: list[tuple[str, int, str, str]] = []
    run_blocks = 0
    for lo, hi in iter_step_ranges(lines):
        script = extract_run(lines, keys, lo, hi)
        if script is None:
            continue
        run_blocks += 1
        wd = extract_wd(keys, lo, hi)
        for lineno, line in logical_lines(script):
            code = strip_shell_comment(line)
            if not code.strip():
                continue
            for seg in split_segments(code):
                why = check_segment(seg, wd)
                if why:
                    violations.append((name, lineno, why, seg.strip()))
    return violations, run_blocks


# ── 自测 ────────────────────────────────────────────────────────────────────
SELFTEST_CASES = [
    # (标题, workflow 文本, 期望违规简码列表, 期望 run 块数)
    (
        "P1 行内 wrapper 调用",
        "jobs:\n  a:\n    steps:\n      - name: x\n        run: ./gradlew build\n",
        ["C1"], 1,
    ),
    (
        "P2 多行 run 块内 wrapper（锚定 grep 的盲区 M1）",
        "jobs:\n  a:\n    steps:\n      - name: x\n        run: |\n          echo start\n          ./gradlew build\n          echo done\n",
        ["C1"], 1,
    ),
    (
        "P3 working-directory 指到子根 + 裸 gradle（盲区 M2）",
        "jobs:\n  a:\n    steps:\n      - name: x\n        working-directory: android\n        run: gradle assembleRelease\n",
        ["C3"], 1,
    ),
    (
        "P4 -p 子根",
        "jobs:\n  a:\n    steps:\n      - run: gradle -p desktop packageMsi\n",
        ["C2"], 1,
    ),
    (
        "P5 根目录裸 gradle（合法）",
        "jobs:\n  a:\n    steps:\n      - run: gradle testAll --no-daemon\n",
        [], 1,
    ),
    (
        "P6 grep 的参数字面量不算调用（数据位）",
        'jobs:\n  a:\n    steps:\n      - run: grep -rn "./gradlew" .github/\n',
        [], 1,
    ),
    (
        "P7 for-in 断言清单不算调用（数据位）",
        "jobs:\n  a:\n    steps:\n      - run: |\n          for f in gradlew gradlew.bat; do\n            [ -e \"$f\" ] && exit 1\n          done\n",
        [], 1,
    ),
    (
        "P8 块内整行注释不算调用",
        "jobs:\n  a:\n    steps:\n      - run: |\n          # 不要用 ./gradlew\n          echo ok\n",
        [], 1,
    ),
    (
        "P9 bash ./gradlew 变体",
        "jobs:\n  a:\n    steps:\n      - run: bash ./gradlew test\n",
        ["C1"], 1,
    ),
    (
        "P10 命令替换里的 wrapper",
        "jobs:\n  a:\n    steps:\n      - run: V=$(./gradlew -q projects); echo $V\n",
        ["C1"], 1,
    ),
    (
        "P11 反斜杠续行后在命令位",
        "jobs:\n  a:\n    steps:\n      - run: |\n          cd sub && \\\n            ./gradlew build\n",
        ["C1"], 1,
    ),
    (
        "P12 working-directory 为 . 时的裸 gradle（合法）",
        "jobs:\n  a:\n    steps:\n      - working-directory: .\n        run: gradle testAll\n",
        [], 1,
    ),
    (
        "P13 折叠块 > 中的 wrapper 也要抓到（换行折成空格，故用 && 连接）",
        "jobs:\n  a:\n    steps:\n      - run: >\n          echo start &&\n          ./gradlew assemble\n",
        ["C1"], 1,
    ),
    (
        "P14 引号包住的 wrapper 命令",
        'jobs:\n  a:\n    steps:\n      - run: "./gradlew test"\n',
        ["C1"], 1,
    ),
    (
        "P15 mkdir -p android/keystore 不是 -p 子根（历史假红）",
        "jobs:\n  a:\n    steps:\n      - run: |\n          mkdir -p android/keystore\n          gradle testAll\n",
        [], 1,
    ),
]

# 防空真绿灯：没有任何 run 块时必须判红
SELFTEST_NO_RUN = "jobs:\n  a:\n    runs-on: ubuntu-24.04\n"


def run_selftest() -> int:
    failed = []
    for title, text, want_codes, want_blocks in SELFTEST_CASES:
        got, blocks = analyze_text("selftest.yml", text)
        codes = [c for _, _, c, _ in got]
        if codes != want_codes or blocks != want_blocks:
            failed.append(
                f"  ✗ {title}: 期望 codes={want_codes} blocks={want_blocks}，"
                f"实得 codes={codes} blocks={blocks}"
                + (f"（{got}）" if got else "")
            )
    got, blocks = analyze_text("selftest.yml", SELFTEST_NO_RUN)
    if blocks != 0 or got:
        failed.append(f"  ✗ 无 run 块用例：期望 blocks=0 且无违规，实得 blocks={blocks} {got}")
    if failed:
        print("check-ci-gradle-source --selftest: FAIL")
        print("\n".join(failed))
        return 1
    print(
        f"check-ci-gradle-source --selftest: OK（{len(SELFTEST_CASES)} 组样例 + "
        "防空真绿灯（0 run 块）+ 数据位/注释位反例）"
    )
    return 0


# ── 主流程 ──────────────────────────────────────────────────────────────────
CODE_DESC = {
    "C1": "CI 用 wrapper 调 Gradle（应改用 setup-gradle 提供的全局 gradle）",
    "C2": "CI 用 -p/--project-dir 指向子根（单根后应直接跑全限定 task）",
    "C3": "CI 把 working-directory 切到子根再裸跑 gradle（等价于 -p）",
}


def main(argv: list[str]) -> int:
    root = Path(__file__).resolve().parent.parent
    wf_dir = root / ".github" / "workflows"
    if not wf_dir.is_dir():
        print(f"check-ci-gradle-source: 找不到 {wf_dir}", file=sys.stderr)
        return 2

    files = sorted([*wf_dir.glob("*.yml"), *wf_dir.glob("*.yaml")])
    if not files:
        print(f"check-ci-gradle-source: {wf_dir} 下没有 workflow", file=sys.stderr)
        return 2

    total_blocks = 0
    all_v: list[tuple[str, int, str, str]] = []
    for f in files:
        v, blocks = analyze_text(f.name, read_text(f))
        total_blocks += blocks
        all_v.extend(v)

    print(f"扫描 {len(files)} 个 workflow / {total_blocks} 个 run 块")
    if total_blocks == 0:
        print("check-ci-gradle-source: FAIL —— 解析到 0 个 run 块（判据会静默全绿，故判红）")
        return 1
    if all_v:
        for name, lineno, code, seg in all_v:
            print(f"  ✗ {name}:{lineno} [{code}] {CODE_DESC[code]}  ::  {seg}")
        print(f"check-ci-gradle-source: FAIL（{len(all_v)} 处）")
        return 1
    print("check-ci-gradle-source: OK（无 wrapper 调用 / 无子根指定）")
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv[1:]:
        sys.exit(run_selftest())
    sys.exit(main(sys.argv[1:]))
