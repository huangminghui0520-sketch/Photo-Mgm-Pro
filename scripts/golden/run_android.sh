#!/usr/bin/env bash
# 在 Android 模拟器上跑一次 P-2 基线导出，并拍 ZIP 快照。
#
# 目标：产出与 PC 侧 docs/golden/desktop-baseline.json 可比的 Android 侧基线
#      （docs/golden/android-baseline.json），并证明它自身是幂等的。
#
# 三个绕不开的设计决定（都是实测/读码得出的，不是偏好）：
#
# 1) 日志与设置走 **prefs 注入**，不走 UI 输入
#    安卓端日志只有一个文本框 + [粘贴导入]，没有文件导入入口。416 行中文无法用
#    `adb shell input text`（只支持 ASCII），剪贴板注入在 Android 10+ 受后台限制。
#    所以由 make_android_prefs.py 生成 SharedPreferences XML，run-as 推进去。
#    注意：这只替代"用户上次填好的状态"，解析/分类/导出仍走 App 真实代码路径。
#
# 2) 输出目录写 **App 私有外部目录**，不用 SAF
#    SAF 需要点系统选择器（无法稳定自动化）；写公共目录（/sdcard/Download）在
#    targetSdk 34 上会因分区存储 EACCES，导出会以"输出目录不可写"失败。
#    App 私有外部目录 /sdcard/Android/data/<pkg>/files/... 两侧都可读写。
#    代价：这条路径不是用户真实路径（真实是 SAF），所以 SAF 通道另做一次人工冒烟，
#    该冒烟不在本脚本覆盖范围内（历史基线文档已移除）。
#
# 3) 每次跑前 `pm clear`
#    App 有 parsed / classify / photos / coarse 四类缓存与"人工移动"表。复用旧数据会让
#    第二次跑走 parseLogs 的**快路径**（只清人工移动，不重扫照片），产物看似相同却
#    没验证到完整流水线。PC 侧踩过同类坑（复用 --data-dir 读到上轮事件）。
#
# 用法：
#   scripts/golden/run_android.sh                     # 出到 scripts/golden/baseline/android
#   scripts/golden/run_android.sh /tmp/p2-a           # 指定输出目录（双跑对比用）
#   MANUAL=1 scripts/golden/run_android.sh /tmp/p2-a   # 不自动点按钮，人工操作后回车
#   NO_BUILD=1 scripts/golden/run_android.sh           # 用现有 APK，不重新构建
#
# 环境变量：AVD（默认 pmgolden_api34）、SERIAL（默认 emulator-5554）、
#          ANDROID_SDK_ROOT（默认 /d/Android/sdk）、JAVA_HOME（默认 /d/Android/jdk-17.0.2）、
#          WAIT_PARSE（解析超时秒，默认 300）、KEEP_EMULATOR=1（跑完不关模拟器）
set -euo pipefail

PKG="com.photomgm.app"
ACTIVITY="$PKG/.MainActivity"
AVD="${AVD:-pmgolden_api34}"
SERIAL="${SERIAL:-emulator-5554}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/d/Android/sdk}}"
# ★ JAVA_HOME 若是 /d/... 形式，cmd 执行的 gradlew.bat 会判无效目录（MSYS_NO_PATHCONV=1
#   会让 Git Bash 不再转换环境变量）→ 显式转成 D:/... 混合形式。
JAVA_HOME="${JAVA_HOME:-/d/Android/jdk-17.0.2}"
case "$JAVA_HOME" in /*) JAVA_HOME="$(cygpath -m "$JAVA_HOME")" ;; esac
export JAVA_HOME
# ★ SDK 路径同理：Gradle（cmd 进程）不认 /d/Android/sdk，会报 "SDK location not found"。
case "$SDK" in /*) SDK="$(cygpath -m "$SDK")" ;; esac
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export PATH="$JAVA_HOME/bin:$PATH"

ADB="$SDK/platform-tools/adb.exe"
EMU="$SDK/emulator/emulator.exe"
GRADLEW_WIN="gradlew.bat"

# 设备路径：/sdcard 是 /storage/emulated/0 的符号链接，shell 下等价
DEV_PHOTOS="/sdcard/DCIM/Camera"
DEV_GOLDEN="/sdcard/Android/data/$PKG/files/golden"

MANUAL="${MANUAL:-0}"
NO_BUILD="${NO_BUILD:-0}"
WAIT_PARSE="${WAIT_PARSE:-300}"

win() { ( cd "$1" && pwd -W ) 2>/dev/null || echo "$1"; }
REPO="$(cd "$(dirname "$0")/../.." && pwd -W)"
SAMPLE="$REPO/scripts/golden/sample"
OUT="${1:-$REPO/scripts/golden/baseline/android}"
mkdir -p "$OUT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
die() { printf '\n[FAIL] %s\n' "$*" >&2; exit 1; }
adb_() { "$ADB" -s "$SERIAL" "$@"; }

# ── 0. 环境自检 ────────────────────────────────────────────────────────────────
say "0/9 环境自检"
[ -x "$ADB" ] || die "找不到 adb：$ADB（装 platform-tools，或设 ANDROID_SDK_ROOT）"
[ -x "$EMU" ] || die "找不到 emulator：$EMU"

accel="$("$EMU" -accel-check 2>&1 || true)"
printf '%s\n' "$accel" | sed 's/^/    /'
if ! printf '%s\n' "$accel" | grep -qi "is installed and usable"; then
  cat >&2 <<'EOS'

[FAIL] 硬件加速不可用，x86_64 模拟器无法启动。
       实测遇到过的原样报错：
         ERROR | x86_64 emulation currently requires hardware acceleration!
         CPU acceleration status: Unable to open AEHD device: ERROR_ACCESS_DENIED

       两种修法（都要管理员，二选一）：
         A. 装/修 AEHD：以管理员运行
            D:\Android\sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat
         B. 启用 WHPX：Windows 功能里勾「虚拟机监控程序平台」与「Virtual Machine Platform」，重启

       修完自检判据（必须出现 "is installed and usable"）：
         emulator -accel-check
       历史基线文档已移除；按上面 "is installed and usable" 判据自查。
EOS
  exit 2
fi

if ! adb_ get-state >/dev/null 2>&1; then
  say "1/9 启动模拟器 $AVD"
  if ! "$EMU" -list-avds 2>/dev/null | tr -d '\r' | grep -qx "$AVD"; then
    echo "    未找到 AVD，按 API34 google_apis x86_64 创建…"
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager.bat" create avd \
      -n "$AVD" -k "system-images;android-34;google_apis;x86_64" -d pixel_5 >/dev/null
  fi
  # -no-window：无窗口仍可用 uiautomator dump 与 input tap（无障碍树不依赖窗口）
  "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
         -gpu swiftshader_indirect -port 5554 > "$OUT/emulator.log" 2>&1 &
else
  say "1/9 复用已连接设备 $SERIAL"
fi

say "2/9 等待开机完成"
"$ADB" -s "$SERIAL" wait-for-device
booted=0
for _ in $(seq 1 150); do
  if [ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then booted=1; break; fi
  sleep 2
done
[ "$booted" = 1 ] || die "开机超时（300s）：查看 $OUT/emulator.log"
echo "    $(adb_ shell getprop ro.build.version.release | tr -d '\r') / API $(adb_ shell getprop ro.build.version.sdk | tr -d '\r') / $(adb_ shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "    fingerprint: $(adb_ shell getprop ro.build.fingerprint | tr -d '\r')"

# 时区固定：导出文件名与"今天"都依赖它；模拟器默认可能是 UTC
adb_ shell settings put global auto_time_zone 0 >/dev/null 2>&1 || true
adb_ shell setprop persist.sys.timezone Asia/Shanghai >/dev/null 2>&1 || true
# 关动画：减少 Compose 动画期间的 dump/tap 抖动
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb_ shell settings put global "$k" 0 >/dev/null 2>&1 || true
done

# ── 构建与安装 ────────────────────────────────────────────────────────────────
APK="$REPO/android/app/build/outputs/apk/debug/app-debug.apk"
if [ "$NO_BUILD" != 1 ]; then
  say "3/9 构建 debug APK"
  # ★ M5（单根多模块）：仓库根已是唯一 Gradle 根，task 用**全限定项目路径**。
  #   projectDir 未变（android/app），所以下面 APK= 的产物路径一个字符都不用改。
  ( cd "$REPO" && ./"$GRADLEW_WIN" :android:app:assembleDebug --console=plain -q )
else
  say "3/9 复用现有 APK"
fi
[ -f "$APK" ] || die "APK 不存在：$APK"
APK_SHA="$(python -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest()[:16])" "$APK")"
echo "    $APK"
echo "    sha256[0:16]=$APK_SHA  size=$(wc -c < "$APK") B"

adb_ install -r -t "$APK" >/dev/null || die "安装失败"
adb_ shell pm clear "$PKG" >/dev/null 2>&1 || true   # ★ 必须：清四类缓存与人工移动表
adb_ shell pm grant "$PKG" android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 \
  || adb_ shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
# ★ P0（2026-11-02）：缺 ACCESS_MEDIA_LOCATION 时系统读取媒体会剥离 EXIF GPS，基线就测不到定位。
adb_ shell pm grant "$PKG" android.permission.ACCESS_MEDIA_LOCATION >/dev/null 2>&1 || true

# run-as 是 debug 包专有能力；release 包会在这一步失败，提前给出可读的原因
if ! adb_ shell "run-as $PKG id" >/dev/null 2>&1; then
  die "run-as 不可用（需 debuggable APK）。请用 debug 包：:android:app:assembleDebug"
fi

# ── 素材：照片进 MediaStore ───────────────────────────────────────────────────
say "4/9 推送照片到 $DEV_PHOTOS"
[ -d "$SAMPLE/photos" ] || die "缺素材：$SAMPLE/photos（先跑 make_sample.py）"
adb_ shell mkdir -p "$DEV_PHOTOS"
# 先试同步整个目录内容；Windows 下 adb 对 "dir/." 支持不稳，失败则逐文件
if ! adb_ push "$(win "$SAMPLE/photos")/." "$DEV_PHOTOS/" >/dev/null 2>&1; then
  echo "    目录同步失败，改为逐文件推送…"
  for f in "$SAMPLE/photos"/*; do adb_ push "$(win "$f")" "$DEV_PHOTOS/" >/dev/null; done
fi
echo "    设备上文件数：$(adb_ shell "ls -1 $DEV_PHOTOS | wc -l" | tr -d '\r')"

# MediaProvider（Android 11+）通过 FUSE 监听，通常几秒内自动入库；不足则逐文件广播补救
count_photos() {
  adb_ shell content query --uri content://media/external/images/media \
    --projection _id --where "_display_name LIKE 'IMG_20260814%'" 2>/dev/null \
    | grep -c "Row:" || true
}
sleep 10
n="$(count_photos)"
if [ "${n:-0}" -lt 60 ]; then
  echo "    MediaStore 只索引到 $n 张，触发扫描…"
  adb_ shell "for f in $DEV_PHOTOS/*; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://\$f >/dev/null 2>&1; done" || true
  sleep 8
  n="$(count_photos)"
fi
echo "    MediaStore 命中 IMG_20260814*：$n 张"
[ "${n:-0}" -ge 60 ] || die "MediaStore 未索引到足够照片（$n < 60）。见 §9 问题 3：手动打开一次系统相册，或重启 MediaProvider"

# ── prefs 注入 ───────────────────────────────────────────────────────────────
say "5/9 注入 settings / logs prefs"
adb_ shell am force-stop "$PKG" >/dev/null 2>&1 || true   # ★ 必须先停：App 退出时会把内存态写回，覆盖注入值
python "$REPO/scripts/golden/make_android_prefs.py" --out "$TMP/prefs" \
  --date 2026-08-14 --dir DCIM/Camera --template 1 --output-dir "/storage/emulated/0/Android/data/$PKG/files/golden" \
  | sed 's/^/    /'
adb_ push "$TMP/prefs/photo_mgm_settings.xml" /data/local/tmp/ >/dev/null
adb_ push "$TMP/prefs/photo_mgm_logs.xml" /data/local/tmp/ >/dev/null
adb_ shell "run-as $PKG mkdir -p shared_prefs"
adb_ shell "run-as $PKG cp /data/local/tmp/photo_mgm_settings.xml shared_prefs/photo_mgm_settings.xml"
adb_ shell "run-as $PKG cp /data/local/tmp/photo_mgm_logs.xml shared_prefs/photo_mgm_logs.xml"
adb_ shell "run-as $PKG chmod 600 shared_prefs/photo_mgm_settings.xml shared_prefs/photo_mgm_logs.xml"

# 回读校验：注入文件字节数必须与本地一致（不一致通常是 CRLF/BOM 或 push 截断）
# ★ 不能用 `run-as pkg wc -c < file`：重定向由**外层 shell 用户**执行，读不到 app 私有文件，
#   会得到 Permission denied。必须把文件名作为参数传给 wc，让 run-as 后的进程自己打开。
for f in photo_mgm_settings.xml photo_mgm_logs.xml; do
  local_bytes=$(wc -c < "$TMP/prefs/$f")
  dev_bytes=$(adb_ shell "run-as $PKG wc -c shared_prefs/$f" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  echo "    $f  本地=${local_bytes}B 设备=${dev_bytes:-?}B"
  [ "$local_bytes" = "${dev_bytes:-}" ] || die "prefs 注入后字节数不一致（$f），App 会读不到设置"
done

# 输出目录必须清空：同名 ZIP 会被覆盖，残留旧包会让"文件出现"误判为本次导出完成
adb_ shell rm -rf "$DEV_GOLDEN" >/dev/null 2>&1 || true

# ── 拉起并操作 ────────────────────────────────────────────────────────────────
say "6/9 启动 App"
adb_ shell am start -n "$ACTIVITY" >/dev/null
sleep 8

dump_ui() {
  adb_ shell uiautomator dump /sdcard/_p2_ui.xml >/dev/null 2>&1 || return 1
  adb_ pull /sdcard/_p2_ui.xml "$TMP/ui.xml" >/dev/null 2>&1 || return 1
  [ -s "$TMP/ui.xml" ]
}

# 找文本并点击。找不到返回 1，由调用方决定"人工接管"还是"失败"
tap_text() {
  dump_ui || return 1
  local xy
  xy="$(python "$REPO/scripts/golden/find_node.py" "$TMP/ui.xml" "$1" 2>/dev/null || true)"
  [ -n "$xy" ] || return 1
  echo "    点「$1」→ ($xy)"
  # shellcheck disable=SC2086
  adb_ shell input tap $xy
}

manual_step() {
  echo
  echo "    ── 需要人工操作：$1"
  echo "       完成后按回车继续（Ctrl-C 中止）"
  read -r _ || true
}

say "7/9 解析日志"
if [ "$MANUAL" = 1 ]; then
  manual_step "在 App 里切到「日志」页，点「解析日志」，等底栏出现「导出」"
else
  # tab 的 label 是「日志N·照片M」；用「·照片」是为了不撞上「解析日志」按钮（实测会点错）
  tap_text "·照片" || manual_step "切到「日志」页"
  sleep 2
  tap_text "解析日志" || manual_step "点「解析日志」"
fi

echo "    等待解析完成（最多 ${WAIT_PARSE}s）…"
ready=0
for i in $(seq 1 $((WAIT_PARSE / 5))); do
  if dump_ui && python "$REPO/scripts/golden/find_node.py" "$TMP/ui.xml" "导出" >/dev/null 2>&1; then
    ready=1; echo "    第 $((i * 5))s：底栏出现「导出」，解析完成"; break
  fi
  if [ $((i % 6)) -eq 0 ]; then echo "    第 $((i * 5))s：仍在解析…"; fi
  sleep 5
done
[ "$ready" = 1 ] || manual_step "解析未在 ${WAIT_PARSE}s 内完成。请人工确认底栏状态后回车"

say "8/9 导出 ZIP 并等待落盘"
if [ "$MANUAL" = 1 ]; then
  manual_step "点底栏「导出」，等提示导出完成"
else
  tap_text "导出" || manual_step "点底栏「导出」"
fi

zip_name=""
prev_size=-1
for _ in $(seq 1 120); do
  zip_name="$(adb_ shell "ls -1 $DEV_GOLDEN 2>/dev/null" | tr -d '\r' | grep -i '\.zip$' | head -1 || true)"
  if [ -n "$zip_name" ]; then
    cur="$(adb_ shell "stat -c %s '$DEV_GOLDEN/$zip_name' 2>/dev/null" | tr -d '\r' || echo 0)"
    if [ "$cur" = "$prev_size" ] && [ "${cur:-0}" -gt 100000 ]; then
      echo "    ZIP 稳定：$zip_name（$cur B）"; break
    fi
    prev_size="$cur"
  fi
  sleep 2
done
[ -n "$zip_name" ] || die "120 次轮询（约 4 分钟）未见 ZIP。见 §9 问题 6：导出前置状态/输出目录权限"

say "9/9 拉取并拍快照"
adb_ pull "$DEV_GOLDEN" "$OUT/raw" >/dev/null || die "adb pull 失败"
# 用 Python 找 ZIP：中文文件名经 shell 变量会受控制台代码页影响
ZIP="$(python - "$OUT/raw" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])
zips = sorted(root.rglob("*.zip"), key=lambda p: p.stat().st_size, reverse=True)
print(zips[0] if zips else "")
PY
)"
[ -n "$ZIP" ] || die "拉取目录里没有 ZIP"

SAMPLE_MANIFEST="$(python -c "import hashlib,pathlib;print(hashlib.sha256(pathlib.Path('$REPO/scripts/golden/sample/sample-manifest.json').read_bytes()).hexdigest()[:16])")"
COMMIT="$(cd "$REPO" && git rev-parse --short HEAD 2>/dev/null || echo nogit)"
python "$REPO/scripts/golden/snapshot_zip.py" --in "$ZIP" --out "$OUT/snapshot.json" --normalize \
  --meta platform=android \
  --meta avd="$AVD" \
  --meta api="$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')" \
  --meta apkSha="$APK_SHA" \
  --meta commit="$COMMIT" \
  --meta sampleManifest="$SAMPLE_MANIFEST" \
  --meta generator="run_android.sh" | sed 's/^/    /'

echo
echo "产物："
echo "  ZIP      $ZIP"
echo "  快照     $OUT/snapshot.json"
echo "  模拟器日志 $OUT/emulator.log"
echo
echo "下一步（双跑幂等验证，V2 判定）："
echo "  scripts/golden/run_android.sh /tmp/p2-b \\"
echo "    && python scripts/golden/compare.py \\"
echo "         --base $OUT/snapshot.json --after /tmp/p2-b/snapshot.json --max 10"

if [ "${KEEP_EMULATOR:-0}" != 1 ]; then
  echo
  echo "提示：模拟器仍在运行。关闭：$ADB -s $SERIAL emu kill"
fi
