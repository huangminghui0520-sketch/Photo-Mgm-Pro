#!/usr/bin/env bash
# 在 **Android 真机**（USB 连接）上跑一次 P-2 基线导出，并拍 ZIP 快照。
#
# 与 run_android.sh（模拟器版）的关系：
#   流程同构（S0~S9 一致），差异只在"设备来源"与若干真机特有坑，见下。
#   之所以不合并成一个脚本：模拟器需要 accel-check / AVD 创建 / -no-window 启动，
#   真机全部不需要；混在一起会让两个分支都难以阅读，且模拟器分支从未在真机上验证过。
#
# 真机特有、模拟器上不会遇到（或表现形式不同）的 6 个坑，逐条都已在这里处理：
#
# 1) **MSYS 路径转换**：Git Bash 会把 `--uri content://...`、`/sdcard/...` 当作
#    Windows 路径改写（实测 mkdir 报 "D: Read-only file system"）。必须
#    `export MSYS_NO_PATHCONV=1`。
# 2) **Python 看到的 /tmp 与 bash 不同**：bash 的 /tmp = %LOCALAPPDATA%\Temp，
#    而 Python 把 `/tmp/x` 解释成当前盘符的 `C:\tmp\x`。所以临时目录一律用
#    `$(pwd -W)` 生成 Windows 绝对路径，不用 /tmp。
# 3) **content query 的 projection 用冒号分隔**，不是逗号。用逗号会报
#    `Invalid column _id,relative_path`，且 grep 计数会静默返回 0，
#    让"MediaStore 没索引到"看起来像真失败。
# 4) **不能往 DCIM/Camera 塞素材**：真机那里是用户真实相册。用独立目录
#    Pictures/pmgolden，靠 prefs 的 source_dirs 精确指定。
# 5) **run-as 的 glob 不展开**：`run-as pkg chmod 600 shared_prefs/*.xml` 的 `*`
#    由外层 shell 在 `/` 下展开，必然 No such file。必须逐个列文件名。
# 6) **pm clear 会重置运行时权限**：清数据后 READ_MEDIA_IMAGES 变回未授予，
#    必须重新 pm grant，否则 MediaStore 查询返回空、"照片 0 张"。
# 7) **GPS 被系统剥离**（★ P0，2026-11-02）：Android 10+ 的 App 若没有
#    ACCESS_MEDIA_LOCATION，系统在**读取媒体那一刻**就把 EXIF 里的 GPS 值区清零
#    （标签还在、值变成 (nan,nan,nan)，文件长度不变）——看起来像"照片本来没有定位"。
#    脚本默认 pm grant 该权限；GEO_PERM=0 则故意不授权，用于负向验证。
# 8) **MSYS_NO_PATHCONV 连环境变量一起关**：JAVA_HOME 若写成 /d/... 形式，cmd 执行的
#    gradlew.bat 会判为无效目录 → 构建步骤直接失败。脚本内统一转成 D:/... 形式。
#
# 用法：
#   scripts/golden/run_android_real.sh                     # 出到 .p2work/run
#   scripts/golden/run_android_real.sh .p2work/run-b       # 指定输出目录（双跑对比）
#   SKIP_INSTALL=1  ...   不重装 APK（默认每次都装，保证测的是当前 HEAD）
#   KEEP_PHOTOS=1   ...   不重新 push 素材（设备上已有且未变动时省时间）
#   MANUAL=1        ...   不自动点按钮，人工操作后回车
#   GEO_PERM=0      ...   不授予位置权限（负向验证：GPS 应全为 null / NaN）
#   EXPECT_GPS=none ...   覆盖 9.5 步的 GPS 期望值（默认随 GEO_PERM 推导）
set -euo pipefail

export MSYS_NO_PATHCONV=1          # 坑 1

PKG="com.photomgm.app"
ACTIVITY="$PKG/.MainActivity"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/d/Android/sdk}}"
# ★ 坑 8：`MSYS_NO_PATHCONV=1` 关掉的不只是命令行参数改写，环境变量也不再被 Git Bash 转换。
#   gradlew.bat 由 cmd 执行，看到 JAVA_HOME=/d/Android/jdk-17.0.2 会直接判无效目录
#   （"JAVA_HOME is set to an invalid directory"），构建步骤第一步就失败。
#   故显式转成 Windows 混合形式 D:/...（cmd 与 bash 都认）。
JAVA_HOME="${JAVA_HOME:-/d/Android/jdk-17.0.2}"
case "$JAVA_HOME" in /*) JAVA_HOME="$(cygpath -m "$JAVA_HOME")" ;; esac
export JAVA_HOME
# 同理 ANDROID_HOME / ANDROID_SDK_ROOT：Gradle（cmd 进程）不认 /d/Android/sdk，
# 会报 "SDK location not found"。两种形式都转（bash 侧用 D:/... 也照常可执行）。
case "$SDK" in /*) SDK="$(cygpath -m "$SDK")" ;; esac
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export PATH="$JAVA_HOME/bin:$PATH"

ADB="$SDK/platform-tools/adb.exe"

# 真机上素材放独立目录，绝不用 DCIM/Camera（坑 4）
SRC_REL="Pictures/pmgolden"
DEV_PHOTOS="/sdcard/$SRC_REL"
DEV_GOLDEN="/sdcard/Android/data/$PKG/files/golden"

SKIP_INSTALL="${SKIP_INSTALL:-0}"
KEEP_PHOTOS="${KEEP_PHOTOS:-0}"
MANUAL="${MANUAL:-0}"
WAIT_PARSE="${WAIT_PARSE:-180}"

REPO="$(cd "$(dirname "$0")/../.." && pwd -W)"   # 坑 2：用 Windows 绝对路径
SAMPLE="$REPO/scripts/golden/sample"
OUT="${1:-$REPO/.p2work/run}"
mkdir -p "$OUT"
WORK="$OUT/.work"
rm -rf "$WORK"; mkdir -p "$WORK"

say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
die() { printf '\n[FAIL] %s\n' "$*" >&2; exit 1; }
info() { printf '    %s\n' "$*"; }

# 选设备：优先 SERIAL 环境变量，否则取第一个 "device" 状态的（排除 offline/unauthorized）
pick_serial() {
  if [ -n "${SERIAL:-}" ]; then echo "$SERIAL"; return; fi
  "$ADB" devices | tr -d '\r' | awk '$2=="device"{print $1; exit}'
}
SERIAL="$(pick_serial)"
[ -n "$SERIAL" ] || die "没有处于 device 状态的设备。先确认手机已连接并点了「允许 USB 调试」：$ADB devices"
adb_() { "$ADB" -s "$SERIAL" "$@"; }

# ── 0. 环境自检（V1）─────────────────────────────────────────────────────────
say "0/9 环境自检"
state="$(adb_ get-state 2>/dev/null || echo offline)"
[ "$state" = "device" ] || die "设备 $SERIAL 状态为 $state（unauthorized = 手机上没点「允许」）"
API="$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')"
MODEL="$(adb_ shell "getprop ro.product.manufacturer; getprop ro.product.model" | tr -d '\r' | paste -sd' ' -)"
REL="$(adb_ shell getprop ro.build.version.release | tr -d '\r')"
ABI="$(adb_ shell getprop ro.product.cpu.abi | tr -d '\r')"
TZ_="$(adb_ shell getprop persist.sys.timezone | tr -d '\r')"
info "设备      $SERIAL"
info "机型      $MODEL"
info "系统      Android $REL (API $API) / $ABI"
info "时区      $TZ_"
info "包名      $PKG"
# V1 门槛：API ≥ 33 才有 READ_MEDIA_IMAGES；更低的走旧权限分支（脚本仍能跑，仅提示）
if [ "$API" -lt 30 ]; then die "API $API 过低：本方案按 scoped storage（API 29+）设计"; fi
[ "$TZ_" = "Asia/Shanghai" ] || printf '\033[33m    [WARN] 时区不是 Asia/Shanghai，导出文件名/“今天”会偏\033[0m\n'

# ── 1~2. 设备就绪 ───────────────────────────────────────────────────────────
say "1/9 设备已连接，跳过启动"
say "2/9 屏幕与状态"
info "$(adb_ shell wm size | tr -d '\r')  $(adb_ shell wm density | tr -d '\r')"
# 关动画：减少 Compose 动画期间的 dump/tap 抖动（真机默认有动画）
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb_ shell settings put global "$k" 0 >/dev/null 2>&1 || true
done

# ── 2.5 预检：两个"一卡就是十分钟"的前置条件 ───────────────────────────────
# 这两项都是**静默失败**型：不预检的话，后面的自动化会一路空转到超时才报错，
# 看起来像"设备很慢"，实际是第一步就没法点（本次实测踩过）。
say "2.5/9 预检：屏幕解锁 + UI 自动化可用"
# (a) 存储未解锁：重启后手机停在锁屏，/sdcard 是空挂载点，素材、App 数据、dump 文件全读不到
#     （`uiautomator dump /sdcard/x.xml` 还会谎报 "dumped to"，因为写失败没人检查）。
n_sd="$(adb_ shell "ls /sdcard/ 2>/dev/null" | tr -d '\r' | grep -c . || true)"
if [ "${n_sd:-0}" -lt 3 ]; then
  die "手机处于锁定状态（/sdcard 仅 ${n_sd:-0} 项，用户 RUNNING_LOCKED）。请先在手机上解锁屏幕，再重跑本脚本"
fi
info "/sdcard 可读（${n_sd} 项）"

# (b) UI 自动化不可用：上一次 uiautomator 被强杀会留下 UiAutomation 注册残留，
#     此后每次 dump 都抛 IllegalStateException: already registered（进程被 SIGKILL，rc=137）。
#     ★ 处置只有两条：先 pkill -f uiautomator；仍不行必须 adb reboot（pkill/force-stop 都清不掉）。
UI_TMP=/data/local/tmp/_p2_ui.xml
probe="$(adb_ shell "uiautomator dump $UI_TMP 2>&1" | tr -d '\r')"
case "$probe" in
  *"dumped to"*) info "uiautomator 可用" ;;
  *) die "UI 自动化不可用：${probe:-无输出}
     常见原因：① 上一次 uiautomator 被强杀 → UiAutomation 注册残留（already registered）
               ② Appium / UiAutomatorViewer / IDE 调试等占用了 UiAutomation
     处置：adb shell 'pkill -f uiautomator'；仍失败则 adb reboot（实测只有重启能清掉）" ;;
esac

# ── 3. 安装 ─────────────────────────────────────────────────────────────────
APK="$REPO/android/app/build/outputs/apk/debug/app-debug.apk"
if [ "$SKIP_INSTALL" = 1 ]; then
  say "3/9 复用已安装的包"
else
  say "3/9 构建并安装 debug APK"
  # ★ M5（单根多模块）：仓库根已是唯一 Gradle 根，task 用**全限定项目路径**。
  #   projectDir 未变（android/app），所以下面 APK= 的产物路径一个字符都不用改。
  ( cd "$REPO" && ./gradlew.bat :android:app:assembleDebug --console=plain -q )
  adb_ install -r -t "$APK" >/dev/null || die "安装失败"
fi
[ -f "$APK" ] || die "APK 不存在：$APK"
APK_SHA="$(python -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest()[:16])" "$APK")"
info "APK sha256[0:16]=$APK_SHA  size=$(wc -c < "$APK") B"

# pm clear 必须在装包之后：清四类缓存与人工移动表
# ★ 坑 6：clear 之后权限也一起清了，下面立刻重新 grant
adb_ shell "pm clear $PKG" >/dev/null 2>&1 || true
adb_ shell "pm grant $PKG android.permission.READ_MEDIA_IMAGES" >/dev/null 2>&1 \
  || adb_ shell "pm grant $PKG android.permission.READ_EXTERNAL_STORAGE" >/dev/null 2>&1 || true
adb_ shell "pm grant $PKG android.permission.READ_MEDIA_VISUAL_USER_SELECTED" >/dev/null 2>&1 || true

# ★ P0（2026-11-02）：位置元数据权限。缺它时系统读取媒体会剥离 EXIF GPS（值区清零成 nan），
#   表现为「照片本来没有定位」——必须有，否则本轮基线测的就不是 App 而是系统的脱敏行为。
#   GEO_PERM=0 → 故意不授权，用于负向验证「没权限就该读不到」。
GEO_PERM="${GEO_PERM:-1}"
if [ "$GEO_PERM" = 1 ]; then
  if [ "$API" -ge 29 ]; then
    adb_ shell "pm grant $PKG android.permission.ACCESS_MEDIA_LOCATION" >/dev/null 2>&1 || true
  fi
else
  adb_ shell "pm revoke $PKG android.permission.ACCESS_MEDIA_LOCATION" >/dev/null 2>&1 || true
  printf '\033[33m    [WARN] GEO_PERM=0：未授予位置权限，本轮仅用于负向验证（GPS 应全为 null）\033[0m\n'
fi

# run-as 是 debug 包专有能力
adb_ shell "run-as $PKG id" >/dev/null 2>&1 || die "run-as 不可用（需 debuggable APK）"

perm="$(adb_ shell "dumpsys package $PKG | grep 'READ_MEDIA_IMAGES: granted'" | tr -d '\r')"
case "$perm" in *granted=true*) info "READ_MEDIA_IMAGES granted=true";; *) die "照片权限未授予：$perm";; esac

# ★ P0 验收点 1/2：权限真的进了 App 的 uid（声明没写或没申请时这里会没有这一行）
geo_perm="$(adb_ shell "dumpsys package $PKG | grep 'ACCESS_MEDIA_LOCATION'" | tr -d '\r')"
case "$geo_perm" in
  *"granted=true"*) info "ACCESS_MEDIA_LOCATION granted=true";;
  *"ACCESS_MEDIA_LOCATION"*) info "ACCESS_MEDIA_LOCATION 已声明但未授予（GEO_PERM=$GEO_PERM）";;
  *) die "ACCESS_MEDIA_LOCATION 未声明（manifest 漏了？）：$geo_perm";;
esac
if [ "$GEO_PERM" = 1 ]; then
  case "$geo_perm" in
    *"granted=true"*) ;;
    *) die "位置权限未授予，无法验收 GPS：$geo_perm" ;;
  esac
fi

# ── 4. 素材进 MediaStore ────────────────────────────────────────────────────
say "4/9 推送照片到 $DEV_PHOTOS"
[ -d "$SAMPLE/photos" ] || die "缺素材：$SAMPLE/photos（先跑 make_sample.py）"
adb_ shell "mkdir -p $DEV_PHOTOS"
need_push=1
if [ "$KEEP_PHOTOS" = 1 ]; then
  n_have="$(adb_ shell "ls -1 $DEV_PHOTOS 2>/dev/null | wc -l" | tr -d '\r ')"
  [ "${n_have:-0}" -ge 66 ] && need_push=0 && info "设备已有 $n_have 个文件，跳过推送"
fi
if [ "$need_push" = 1 ]; then
  adb_ push "$SAMPLE/photos/." "$DEV_PHOTOS/" >/dev/null 2>&1 \
    || { info "目录同步失败，改逐文件"; for f in "$SAMPLE/photos"/*; do adb_ push "$f" "$DEV_PHOTOS/" >/dev/null; done; }
  info "推送后设备文件数：$(adb_ shell "ls -1 $DEV_PHOTOS | wc -l" | tr -d '\r ')"
fi

# 触发 MediaStore 索引。Android 11+ 用 content call scan_volume（比逐文件广播可靠）
adb_ shell "content call --uri content://media/external --method scan_volume --arg external_primary" >/dev/null 2>&1 || true

# ★ 坑 3：projection 用冒号，且统计用 relative_path 精确匹配本目录
count_photos() {
  adb_ shell "content query --uri content://media/external/images/media --projection _id:relative_path" 2>/dev/null \
    | tr -d '\r' | grep -c "relative_path=$SRC_REL" || true
}
n=0
for i in $(seq 1 15); do
  n="$(count_photos)"
  [ "${n:-0}" -ge 66 ] && break
  [ $((i % 3)) -eq 0 ] && info "第 $((i * 2))s：MediaStore 命中 $n 张，等待索引…"
  sleep 2
done
info "MediaStore 命中 $SRC_REL：$n 张"
[ "${n:-0}" -ge 66 ] || die "MediaStore 未索引到足够照片（$n < 66）。见文档 §9：手动打开一次系统相册后重试"

# ── 5. prefs 注入 ───────────────────────────────────────────────────────────
say "5/9 注入 settings / logs prefs"
adb_ shell "am force-stop $PKG" >/dev/null 2>&1 || true   # 必须先停：退出时会把内存态写回
sleep 1
python "$REPO/scripts/golden/make_android_prefs.py" --out "$WORK/prefs" \
  --date 2026-08-14 --dir "$SRC_REL" --template 1 \
  --output-dir "/storage/emulated/0/Android/data/$PKG/files/golden" | sed 's/^/    /'

adb_ push "$WORK/prefs/photo_mgm_settings.xml" /data/local/tmp/ >/dev/null
adb_ push "$WORK/prefs/photo_mgm_logs.xml" /data/local/tmp/ >/dev/null
adb_ shell "run-as $PKG mkdir -p shared_prefs"
adb_ shell "run-as $PKG cp /data/local/tmp/photo_mgm_settings.xml shared_prefs/photo_mgm_settings.xml"
adb_ shell "run-as $PKG cp /data/local/tmp/photo_mgm_logs.xml shared_prefs/photo_mgm_logs.xml"
# ★ 坑 5：逐个文件名，不用 glob
adb_ shell "run-as $PKG chmod 600 shared_prefs/photo_mgm_settings.xml shared_prefs/photo_mgm_logs.xml"

for f in photo_mgm_settings.xml photo_mgm_logs.xml; do
  lb=$(wc -c < "$WORK/prefs/$f")
  db=$(adb_ shell "run-as $PKG wc -c shared_prefs/$f" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  info "$f  本地=${lb}B 设备=${db:-?}B"
  [ "$lb" = "${db:-}" ] || die "prefs 注入后字节数不一致（$f），App 会读不到设置"
done

# 输出目录必须清空，否则残留旧 ZIP 会被误判成本次导出
adb_ shell "rm -rf $DEV_GOLDEN" >/dev/null 2>&1 || true

# ── 6~8. 拉起 → 解析 → 导出 ─────────────────────────────────────────────────
say "6/9 启动 App"
adb_ shell "am start -n $ACTIVITY" >/dev/null
sleep 8

# ★ 落盘到 /data/local/tmp 而不是 /sdcard：device-encrypted 存储先于用户解锁可用，
#   锁屏状态下 /sdcard 写不进去（dump 还会谎报成功），而 /data/local/tmp 一直可写。
#   顺带省掉一次 adb pull：直接 cat 回来比 push/pull 少一个进程往返。
dump_ui() {
  adb_ shell "uiautomator dump $UI_TMP" >/dev/null 2>&1 || return 1
  adb_ shell "cat $UI_TMP" 2>/dev/null > "$WORK/ui.xml" || return 1
  [ -s "$WORK/ui.xml" ] && grep -q "<hierarchy" "$WORK/ui.xml" 2>/dev/null
}
tap_text() {
  local xy try=0
  while [ $try -lt 2 ]; do
    try=$((try + 1))
    if dump_ui; then
      xy="$(python "$REPO/scripts/golden/find_node.py" "$WORK/ui.xml" "$1" 2>/dev/null || true)"
      [ -n "$xy" ] || return 1
      info "点「$1」→ ($xy)"
      adb_ shell "input tap $xy"
      return 0
    fi
    sleep 1
  done
  return 1
}
manual_step() { printf '\n    ── 需要人工操作：%s\n       完成后按回车继续\n' "$1"; read -r _ || true; }

shot() {
  adb_ shell "screencap -p /sdcard/_p2_$1.png" >/dev/null 2>&1 || return 0
  adb_ pull "/sdcard/_p2_$1.png" "$OUT/shot-$1.png" >/dev/null 2>&1 || true
}

shot launch
say "7/9 解析日志"
if [ "$MANUAL" = 1 ]; then
  manual_step "切到日志页 → 点「解析日志」→ 等底栏出现「导出」"
else
  tap_text "·照片" || manual_step "切到「日志」页"
  sleep 2
  tap_text "解析日志" || manual_step "点「解析日志」"
fi

# 解析完成的两条判据（任一成立即可，避免 UI 树抖动误判）：
#   ① 底栏出现「导出」；② prefs 里落下了 cache_parsed（解析结束必写，与 UI 无关）。
parsed_ready() {
  adb_ exec-out run-as "$PKG" cat shared_prefs/photo_mgm_settings.xml 2>/dev/null \
    | grep -q "cache_parsed"
}
info "等待解析完成（最多 ${WAIT_PARSE}s）…"
ready=0
for i in $(seq 1 $((WAIT_PARSE / 5))); do
  if dump_ui && python "$REPO/scripts/golden/find_node.py" "$WORK/ui.xml" "导出" >/dev/null 2>&1; then
    ready=1; info "第 $((i * 5))s：底栏出现「导出」，解析完成"; break
  fi
  if parsed_ready; then
    ready=1; info "第 $((i * 5))s：cache_parsed 已写入，解析完成"; break
  fi
  [ $((i % 6)) -eq 0 ] && info "第 $((i * 5))s：仍在解析…"
  sleep 5
done
[ "$ready" = 1 ] || manual_step "解析未在 ${WAIT_PARSE}s 内完成，人工确认后回车"
shot parsed

# 记录解析摘要（判定用）
python "$REPO/scripts/golden/find_node.py" --digest "$WORK/ui.xml" 2>/dev/null | sed 's/^/    /' || true

say "8/9 导出 ZIP 并等待落盘"
if [ "$MANUAL" = 1 ]; then
  manual_step "点「导出」，等提示导出完成"
else
  tap_text "导出" || manual_step "点「导出」"
fi

zip_name=""; prev=-1
for _ in $(seq 1 120); do
  zip_name="$(adb_ shell "ls -1 $DEV_GOLDEN 2>/dev/null" | tr -d '\r' | grep -i '\.zip$' | head -1 || true)"
  if [ -n "$zip_name" ]; then
    cur="$(adb_ shell "stat -c %s '$DEV_GOLDEN/$zip_name' 2>/dev/null" | tr -d '\r' || echo 0)"
    if [ "$cur" = "$prev" ] && [ "${cur:-0}" -gt 100000 ]; then
      info "ZIP 稳定：$zip_name（$cur B）"; break
    fi
    prev="$cur"
  fi
  sleep 3
done
[ -n "$zip_name" ] || die "约 6 分钟未见 ZIP。见文档 §9：导出前置状态 / 输出目录权限"
shot exported

# ── 9. 拉取并与快照 ─────────────────────────────────────────────────────────
say "9/9 拉取并拍快照"
adb_ pull "$DEV_GOLDEN" "$OUT/raw" >/dev/null || die "adb pull 失败"
ZIP="$(python - "$OUT/raw" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])
zips = sorted(root.rglob("*.zip"), key=lambda p: p.stat().st_size, reverse=True)
print(zips[0] if zips else "")
PY
)"
[ -n "$ZIP" ] || die "拉取目录里没有 ZIP"

SMP="$(python -c "import hashlib,pathlib;print(hashlib.sha256(pathlib.Path('$REPO/scripts/golden/sample/sample-manifest.json').read_bytes()).hexdigest()[:16])")"
COMMIT="$(cd "$REPO" && git rev-parse --short HEAD 2>/dev/null || echo nogit)"
# ★ 必须 --exclude '*.xlsx'（2026-11-02 修）：台账 xlsx 内含导出时刻，两次运行 sha256 必然不同（P1-2），
#   留着它会让 V2 双跑幂等永远红。此前基线是手工加参数生成的，脚本没带 → 脚本产出的快照
#   比基线多一条 `巡查台账_<TS>.xlsx`，"脚本能不能复现基线"这件事本身不成立。
#   台账走 docs/golden/android-ledger-baseline.json 做单元格级校验，不靠这条 ZIP 条目。
python "$REPO/scripts/golden/snapshot_zip.py" --in "$ZIP" --out "$OUT/snapshot.json" --normalize \
  --exclude '.*\.xlsx$' \
  --meta platform=android-real \
  --meta device="$MODEL" \
  --meta api="$API" \
  --meta apkSha="$APK_SHA" \
  --meta commit="$COMMIT" \
  --meta sampleManifest="$SMP" \
  --meta generator="run_android_real.sh" | sed 's/^/    /'

# 照片字节指纹：ZIP 内每张 jpg 的 sha256 汇总，用于 V2 幂等对比与跨端比对
python - "$ZIP" "$OUT/photo-fingerprints.json" <<'PY' | sed 's/^/    /'
import sys, zipfile, hashlib, json, pathlib
z = zipfile.ZipFile(sys.argv[1])
out = {}
for n in z.namelist():
    if n.endswith("/"): continue
    if not n.lower().endswith((".jpg", ".jpeg", ".png", ".heic")): continue
    out[n.rsplit("/", 1)[-1]] = hashlib.sha256(z.read(n)).hexdigest()
pathlib.Path(sys.argv[2]).write_text(json.dumps(out, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
same = len({v for v in out.values()})
print(f"照片字节指纹：{len(out)} 张 / {same} 个独立哈希")
PY

printf '\n产物：\n  ZIP        %s\n  快照       %s\n  照片指纹   %s\n  截图       %s/shot-*.png\n' \
  "$ZIP" "$OUT/snapshot.json" "$OUT/photo-fingerprints.json" "$OUT"

# ── 9.5 P0 验收：GPS 到底读到了没有 ─────────────────────────────────────────
# 两个观测点分别对应缺陷链的两端：
#   · cache_photos（App 内存里的 Photo）→ 说明 EXIF 读取环节有没有拿到定位；
#   · ZIP 内照片字节 → 说明导出产物有没有丢位置（剥离发生在读取那一刻，导出的就是被读出的字节）。
# 期望值随 GEO_PERM 走：授权跑要"读得到"，未授权跑要"读不到"（负向验证）。
say "9.5/9 P0 验收：GPS 读取"
EXPECT_GPS="${EXPECT_GPS:-}"
if [ -z "$EXPECT_GPS" ]; then
  if [ "$GEO_PERM" = 1 ]; then EXPECT_GPS=any; else EXPECT_GPS=none; fi
fi
info "期望：--expect $EXPECT_GPS（GEO_PERM=$GEO_PERM）"

PREFS_OUT="$OUT/photo_mgm_settings.xml"
if adb_ exec-out run-as "$PKG" cat shared_prefs/photo_mgm_settings.xml > "$PREFS_OUT" 2>/dev/null \
   && [ -s "$PREFS_OUT" ]; then
  python "$REPO/scripts/golden/check_gps.py" --prefs "$PREFS_OUT" --expect "$EXPECT_GPS" | sed 's/^/    /' \
    || die "cache_photos 的 GPS 不符合预期（${EXPECT_GPS}）——见上方统计"
else
  info "[WARN] 拉取 cache_photos 失败，跳过 App 侧观测点"
fi

python "$REPO/scripts/golden/check_gps.py" --photos "$ZIP" --expect "$EXPECT_GPS" | sed 's/^/    /' \
  || die "导出 ZIP 内照片的 GPS 不符合预期（${EXPECT_GPS}）——见上方统计"

printf '\n双跑幂等验证（V2）：\n  scripts/golden/run_android_real.sh .p2work/run-b\n  python scripts/golden/compare.py --base %s --after .p2work/run-b/snapshot.json --max 10\n' "$OUT/snapshot.json"
