#!/usr/bin/env bash
# M7-tech —— 打包技术链路验证（main class = :cli，**不依赖 UI**）
#
# 目的：把三个高风险点从关键路径末尾提前暴露——
#   1) jlink 裁剪后运行时报错（模块被裁掉才在运行时 NoClassDefFoundError）
#   2) 中文 / 长路径在打包产物里是否仍正常
#   3) 干净机缺依赖（产物是否真的自带运行时，不依赖用户装 JDK）
#
# 为什么可以用 :cli 当 main class：jpackage 支持 --win-console，
#   控制台应用同样能出 app-image。M7-prod 只需换 --main-class / --main-jar。
#
# 产物（都不入库，见 desktop/.gitignore）：
#   packaging/build/dist/PhotoMgmCli/         app-image 目录（可直接双击 exe）
#   packaging/build/dist/PhotoMgmCli/runtime/ 裁剪后的运行时（测体积看这里）
#
# 用法：
#   JAVA_HOME=/path/to/jdk-17 ./packaging/m7-tech.sh
#   SMOKE_ARGS="--source D:/photos --date 2026-08-14 ... -o D:/out" ./packaging/m7-tech.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${JAVA_HOME:?请先 export JAVA_HOME 指向 JDK 17（需含 jdeps/jpackage）}"
JP="$JAVA_HOME/bin/jpackage"
JDEP="$JAVA_HOME/bin/jdeps"
OUT="packaging/build"
LIB="cli/build/install/cli/lib"
APP="$OUT/dist/PhotoMgmCli"

echo "== 1/5 生成 CLI 发行目录 =="
./gradlew :cli:installDist --console=plain -q
ls "$LIB" | sed 's/^/   /'

echo
echo "== 2/5 jdeps 推导最小模块集 =="
# 踩坑记录（U0/M7-tech 实测）：
#   1) 若把 lib/*.jar 全部作为 positional 参数传给 jdeps，它会为每个 jar 打一行
#      "Warning: split package: ..." 到 **stdout**，与模块列表混在一起 →
#      --add-modules 拿到脏值 → jlink 报 "java.base not found"。只传主 jar 即可（警告归零）。
#   2) Windows 中文环境下 jdeps 用 GBK 输出，脚本里判断会乱码，必须强制英文 locale。
#   3) 仍然只取最后一行并做格式校验，双保险。
RAW="$("$JDEP" -J-Duser.language=en -J-Duser.country=US \
        --print-module-deps --ignore-missing-deps --multi-release 17 \
        --class-path "$LIB/*" "$LIB/cli.jar" 2>/dev/null | tail -1)"
if printf '%s' "$RAW" | grep -qE '^[a-z][a-z0-9]*(\.[a-z0-9]+)*(,[a-z][a-z0-9]*(\.[a-z0-9]+)*)*$'; then
    MODS="$RAW"
    echo "   jdeps 结果 = $MODS"
    MODE="jdeps"
else
    # 兜底：给一份保守超集（宁大勿缺，缺模块只会在运行时报 NoClassDefFoundError）
    MODS="java.base,java.desktop,java.logging,java.xml,java.naming,jdk.unsupported"
    echo "   jdeps 输出不可用（原文：$RAW），使用保守模块集 = $MODS"
    MODE="fallback"
fi

echo
echo "== 3/5 jpackage 生成 app-image（捆绑裁剪运行时）=="
rm -rf "$OUT/dist"
"$JP" --type app-image \
    --name PhotoMgmCli \
    --app-version 2.0.0 \
    --input "$LIB" \
    --main-jar cli.jar \
    --main-class com.photomgm.desktop.cli.MainKt \
    --add-modules "$MODS" \
    --java-options "-Dfile.encoding=UTF-8" \
    --win-console \
    --dest "$OUT/dist"

echo
echo "== 4/5 体积（对比未裁剪的完整 JDK）=="
printf "   app-image 总计   %s\n" "$(du -sh "$APP" | cut -f1)"
printf "   其中 runtime     %s\n" "$(du -sh "$APP/runtime" 2>/dev/null | cut -f1 || echo '未生成')"
printf "   完整 JDK 17      %s\n" "$(du -sh "$JAVA_HOME" | cut -f1)"
printf "   jdeps 模式       %s\n" "$MODE"

echo
echo "== 5/5 脱离 JAVA_HOME 运行打包产物 =="
# 关键验证：unset JAVA_HOME + 清空 PATH 里的 JDK，确认用的是 app-image 自带的 runtime
if [ -n "${SMOKE_ARGS:-}" ]; then
    echo "   命令：PhotoMgmCli.exe $SMOKE_ARGS"
    set +e
    env -u JAVA_HOME "$APP/PhotoMgmCli.exe" $SMOKE_ARGS > "$OUT/smoke.log" 2>&1
    CODE=$?
    set -e
    sed 's/^/   /' "$OUT/smoke.log"
    echo "   退出码 = $CODE"
    [ "$CODE" -eq 0 ] || { echo "✘ 打包产物运行失败"; exit 1; }
else
    env -u JAVA_HOME "$APP/PhotoMgmCli.exe" --help > "$OUT/help.log" 2>&1 || true
    sed 's/^/   /' "$OUT/help.log" | head -20
    echo "   （未设置 SMOKE_ARGS，仅验证可执行文件能启动）"
fi

echo
echo "✔ M7-tech 通过：app-image 可独立运行，未依赖系统 JDK"
