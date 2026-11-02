#!/usr/bin/env bash
# 跑一次 PC 端黄金样本导出，并拍快照。
#
# ★ 每次必须用**全新 data 目录**：实测过——复用同一个 --data-dir 会读到上一轮的分析缓存，
#   日志明明换了、界面上还是旧事件（本次就踩到：新日志 416 行，却解出旧样本里的 K842+000 事件）。
#   所以这里用 mktemp -d，天然不可能复用。
#
# 用法：
#   scripts/golden/run_desktop.sh                          # 出到 scripts/golden/baseline/desktop
#   scripts/golden/run_desktop.sh /tmp/run-a               # 指定输出目录（做双跑对比时用）
set -euo pipefail

# ★ 路径必须是 Windows 形式：Java 不认 MSYS 的 /c/... （实测报"日志文件不存在：\c\Users\..."）。
#   git bash 的 `pwd -W` 能把目录转成 C:/... 形式，且不需要 cygpath。
win() { ( cd "$1" && pwd -W ) 2>/dev/null || echo "$1"; }

REPO="$(cd "$(dirname "$0")/../.." && pwd -W)"
SAMPLE="${SAMPLE:-$REPO/scripts/golden/sample}"
OUT="${1:-$REPO/scripts/golden/baseline/desktop}"
JAVA_HOME="${JAVA_HOME:-/d/Android/jdk-17.0.2}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -f "$SAMPLE/patrol-log-20260814.txt" ]; then
  echo "[FAIL] 缺样本：$SAMPLE/patrol-log-20260814.txt（先跑 make_sample.py）"
  exit 1
fi

DATA="$(mktemp -d)"
mkdir -p "$OUT"
SAMPLEW="$(win "$SAMPLE")"
OUTW="$(win "$OUT")"
DATAW="$(win "$DATA")"

cd "$REPO/desktop"
./gradlew :cli:run --console=plain -q \
  --args="--source $SAMPLEW/photos --date 2026-08-14 --log $SAMPLEW/patrol-log-20260814.txt --data-dir $DATAW --out $OUTW" \
  > "$OUT/cli.log" 2>&1 || {
    echo "[FAIL] 导出失败，末尾输出："
    iconv -f GBK -t UTF-8 "$OUT/cli.log" 2>/dev/null | tail -20 || tail -20 "$OUT/cli.log"
    exit 1
  }

LEDGER="$(ls -t "$OUT"/*/ledger.xlsx 2>/dev/null | head -1)"
if [ -z "$LEDGER" ]; then
  echo "[FAIL] 没找到 ledger.xlsx"
  exit 1
fi

python "$REPO/scripts/golden/snapshot_xlsx.py" --in "$LEDGER" --out "$OUT/snapshot.json"
echo "导出目录：$(dirname "$LEDGER")"
