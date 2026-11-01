#!/usr/bin/env bash
# 兼容测试脚本（M6 · T6.1）—— 用 C:\Users\28988\Desktop\ceshi 真实素材驱动 CLI 全链路。
#
# 用法（在仓库根目录执行，或任意目录用绝对路径调用）：
#   bash desktop/scripts/compat-test.sh [模式]
#
# 模式：
#   analyze   只分析不导出（dry-run，输出统计与事件清单）——默认
#   export    真实导出（在 ceshi/导出结果 下生成事件子目录 + 台账 xlsx）
#   help      显示本说明
#
# 依赖：
#   - JDK 17（脚本默认 JAVA_HOME=C:/Users/28988/.workbuddy/jdks/jdk-17.0.2，可被环境变量覆盖）
#   - 素材目录 C:/Users/28988/Desktop/ceshi 已存在
#
# 说明：Windows 控制台默认 GBK 会导致中文输出乱码，脚本统一注入 -Dfile.encoding=UTF-8，
#       并重定向到 UTF-8 日志文件，避免乱码影响结果核对。

set -euo pipefail

# ── 可配置项 ──────────────────────────────────────────────
SOURCE_DIR="${COMPAT_SOURCE_DIR:-C:/Users/28988/Desktop/ceshi}"
PATROL_DATE="${COMPAT_DATE:-2026-10-24}"
LOG_FILE="${COMPAT_LOG:-C:/Users/28988/Desktop/ceshi/10月24日巡查日志.txt}"
OUT_DIR="${COMPAT_OUT:-C:/Users/28988/Desktop/ceshi/导出结果}"
DATA_DIR="${COMPAT_DATA_DIR:-/tmp/photomgm-compat-test}"
TEMPLATE="${COMPAT_TEMPLATE:-1}"          # 0=事件类型 / 1=月日+事件类型+日志内容
DEDUP="${COMPAT_DEDUP:-keep-watermarked}" # keep-watermarked / keep-plain / keep-both
JAVA_HOME="${JAVA_HOME:-C:/Users/28988/.workbuddy/jdks/jdk-17.0.2}"

MODE="${1:-analyze}"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DESKTOP_DIR="$REPO_DIR/desktop"
LOG_DIR="$DESKTOP_DIR/build/compat-logs"
mkdir -p "$LOG_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
RUN_LOG="$LOG_DIR/compat-$MODE-$STAMP.log"

export JAVA_HOME
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"

echo "兼容测试 [$MODE] 开始 $(date '+%F %T')"
echo "  素材目录: $SOURCE_DIR"
echo "  日志文件: $LOG_FILE"
echo "  巡查日期: $PATROL_DATE"
echo "  输出目录: $OUT_DIR"
echo "  运行日志: $RUN_LOG"

COMMON_ARGS="--source $SOURCE_DIR --date $PATROL_DATE --log $LOG_FILE \
  --out $OUT_DIR --template $TEMPLATE --dedup $DEDUP --data-dir $DATA_DIR"

case "$MODE" in
  analyze)
    cd "$DESKTOP_DIR"
    ./gradlew :cli:run -q --args="$COMMON_ARGS --dry-run" 2>&1 | tee "$RUN_LOG"
    echo ""
    echo "✔ 分析结束（dry-run，未导出）。完整输出见: $RUN_LOG"
    ;;
  export)
    cd "$DESKTOP_DIR"
    ./gradlew :cli:run -q --args="$COMMON_ARGS" 2>&1 | tee "$RUN_LOG"
    echo ""
    echo "✔ 导出结束。导出结果在: $OUT_DIR"
    echo "  台账与照片子目录应已生成；请打开 xlsx 核对字段与照片数量。"
    ;;
  help|--help|-h)
    sed -n '2,20p' "$0"
    ;;
  *)
    echo "未知模式: $MODE（可选 analyze / export / help）" >&2
    exit 2
    ;;
esac
