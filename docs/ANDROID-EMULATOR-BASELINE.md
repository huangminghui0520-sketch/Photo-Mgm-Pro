# 安卓模拟器 P-2 基线测试方案

> 版本：v1 · 2026-11-02 · 对应执行计划 `docs/REFACTOR-EXECUTION-PLAN.md` 的 **P-2（Android 侧黄金样本基线）**
>
> 一句话：**在模拟器上把安卓端导出产物钉成一份可逐字节复现的快照**，让后续每一次跨端重构都能回答
> “安卓端有没有被我改坏”，而不是靠人眼看界面。
>
> **当前状态（必须先读）**：本机 **无法启动 x86_64 模拟器**——AEHD 驱动打不开
> （`Unable to open AEHD device: ERROR_ACCESS_DENIED`），`emulator -accel-check` 未通过。
> 方案、脚本、工具链已就绪并通过自检与单测；**端到端首跑需先修加速**（§3.3）。
> 本文严格区分「已实测」与「待实测」，不把未验证的步骤写成已验证。

---

## 1. P-2 是什么，为什么必须在模拟器上做

### 1.1 在计划中的位置

PC 侧基线已于 T01 落地（`docs/golden/desktop-baseline.json`，72 行 / 504 单元格 / 44 张嵌图），
Android 侧当时因“导出走 SAF、需要真机或模拟器”而推迟。P-2 就是补上这一半。

没有 Android 基线会怎样：T11（`AppViewModel` 1165→250 行）与 T12（`copyPhotos` 同步）改完之后，
**只有 PC 端能证明等价**；安卓端只能靠 10 个单测 + 手工点几下——而单测覆盖不到
“66 张照片 → 媒体读取 → EXIF → 分类 → 打包 → 台账”这条完整链路。

### 1.2 为什么不能只靠单元测试

| 层 | 单测覆盖 | 覆盖不到的部分 |
| --- | --- | --- |
| `shared/algorithm` | ✅ 22 个测试 | — |
| `android/data`（MediaStore / EXIF） | ⚠️ 少量 | 真机权限、分区存储、content URI 回退、厂商 EXIF 变体 |
| `android/app`（导出编排） | ✅ 10 个 Robolectric | SAF 落盘、`getExternalFilesDir` 实际可写性、真实 ZIP 字节 |
| **产物** | ❌ | **照片归属、目录命名、ZIP 内字节、台账内容** |

P-2 测的正是最后一行：**产物**。

### 1.3 为什么是模拟器而不是真机

- **可重复**：真机相册随时被系统/其他 App 写入，同一个 `--date` 下命中集不可控；模拟器可以 `pm clear`
  + 固定 66 张素材，做到“同一输入”。
- **可回滚**：`-no-snapshot` 冷启动，状态干净；真机做不到。
- **可脚本化**：`adb push` / `run-as` / `uiautomator dump` 全部可用；真机受厂商 ROM 限制。

真机不是不需要，而是**放在 M3 做补充**（厂商 EXIF、HEIC/RAW、SAF 真实路径）。两者分工：
模拟器负责“每次提交都能跑的回归标尺”，真机负责“真实素材与真实权限的抽样验证”。

---

## 2. 测试对象

| 项 | 值 | 来源 |
| --- | --- | --- |
| 包名 | `com.photomgm.app` | `android/app/build.gradle.kts` |
| 入口 | `com.photomgm.app.MainActivity` | APK manifest 实测 |
| 变体 | `debug`（必须：`run-as` 依赖 `debuggable=true`） | 实测 `android:debuggable="true"` |
| minSdk / targetSdk | 26 / 34 | `build.gradle.kts` |
| 模拟器镜像 | `system-images;android-34;google_apis;x86_64` | 本机已安装 |
| 素材 | 70 个文件（66 张照片 + 4 个误筛文件）+ 416 行日志 | `scripts/golden/sample/` |
| 巡查日期 | `2026-08-14` | 与日志内容一致，写错会导致照片全不匹配 |

---

## 3. 环境配置

### 3.1 组件清单（本机实测状态）

| 组件 | 版本 / 路径 | 状态 |
| --- | --- | --- |
| JDK | `/d/Android/jdk-17.0.2` | ✅ 必须显式设 `JAVA_HOME`，`avdmanager` 缺它会直接报错退出 |
| platform-tools | `37.0.1` / `D:\Android\sdk\platform-tools\adb.exe` | ✅ |
| emulator | `37.2.12.0` / `D:\Android\sdk\emulator\emulator.exe` | ✅ |
| system-image | `android-34;google_apis;x86_64` | ✅ 已安装 |
| AVD | `pmgolden_api34`（pixel_5 外形） | ✅ 已创建（本方案第 3.2 步） |
| 硬件加速 | AEHD / WHPX | ❌ **未通过，当前唯一阻塞项** |

> 注：`google_apis` 而非 `google_apis_playstore` 是刻意的——不需要 Play 服务，且 `google_apis`
> 镜像允许 `adb root`，排查 MediaStore 问题时方便。

### 3.2 创建 AVD

```bash
export JAVA_HOME=/d/Android/jdk-17.0.2
export PATH="$JAVA_HOME/bin:$PATH"

echo no | /d/Android/sdk/cmdline-tools/latest/bin/avdmanager.bat create avd \
  -n pmgolden_api34 \
  -k "system-images;android-34;google_apis;x86_64" \
  -d pixel_5
```

**预期**：`avdmanager list avd` 输出 `Name: pmgolden_api34 / Device: pixel_5 / Target: Android 14.0`。
（本机已执行，输出与预期一致。`echo no` 用于拒绝“自定义硬件配置”交互提问。）

固定 `pixel_5` 的理由：分辨率 1080×2340、density 420。**不是为了坐标点击**（脚本按无障碍树找文本），
而是为了让 UI 布局稳定，避免窄屏把「导出」按钮挤出可视区。

### 3.3 硬件加速（当前阻塞项，必须先解决）

**实测报错原文**：

```
ERROR | x86_64 emulation currently requires hardware acceleration!
CPU acceleration status: Unable to open AEHD device: ERROR_ACCESS_DENIED
```

本机 CPU 虚拟化已在 BIOS 开启（`Win32_Processor.VirtualizationFirmwareEnabled = True`），
但 `HypervisorPresent = False`，AEHD 设备句柄被拒——通常是驱动未安装/未启动，或与 Hyper-V 抢占。

**修法 A：安装 / 修复 AEHD（推荐，不需重启）**

```bash
# 以管理员身份运行（脚本内部会自己弹 UAC）
"D:\Android\sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat"
```

**修法 B：启用 WHPX（需重启）**

```
启用或关闭 Windows 功能 → 勾选：
  ☑ 虚拟机监控程序平台 (HypervisorPlatform)
  ☑ Virtual Machine Platform
重启后生效
```

两者**不要同时开**：AEHD 与 Hyper-V 会争夺 VT-x。

**通过判据（硬门槛）**：

```bash
/d/Android/sdk/emulator/emulator.exe -accel-check
# 必须出现 "is installed and usable"，例如：
#   AEHD (version 2.2) is installed and usable.
# 当前输出（不通过）：
#   accel: 11 / Unable to open AEHD device: ERROR_ACCESS_DENIED
```

`run_android.sh` 已在第 0 步内置这条断言，未通过直接 `exit 2` 并打印上述两种修法。

### 3.4 启动参数与设备状态固定

```bash
/d/Android/sdk/emulator/emulator.exe -avd pmgolden_api34 \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -port 5554
```

| 参数 | 为什么 |
| --- | --- |
| `-no-window` | 无头运行；`uiautomator dump` 与 `input tap` 不依赖窗口，仍可用 |
| `-no-snapshot` | 每次冷启动，杜绝上次会话残留（缓存、权限、prefs） |
| `-no-boot-anim` | 省 10~20 秒启动时间，减少超时误判 |
| `-gpu swiftshader_indirect` | 软件渲染，无 GPU 的 CI/服务器也能跑；Compose 渲染结果一致 |
| `-port 5554` | 固定序列号 `emulator-5554`，脚本可直接寻址，不用解析 `adb devices` |

启动后固定三项设备状态：

```bash
adb -s emulator-5554 shell settings put global auto_time_zone 0
adb -s emulator-5554 shell setprop persist.sys.timezone Asia/Shanghai
adb -s emulator-5554 shell settings put global window_animation_scale 0
adb -s emulator-5554 shell settings put global transition_animation_scale 0
adb -s emulator-5554 shell settings put global animator_duration_scale 0
```

- **时区**：导出文件名与“今天”都取设备时间；默认 UTC 会让 `2026-08-14 23:30` 归到 08-15。
- **动画**：解析/导出期间有进度动画，`uiautomator dump` 在动画帧上偶尔抓到过渡态节点，
  关掉可显著降低“找不到按钮”的偶发失败。

---

## 4. 前提条件

开工前逐条打勾，任何一条不满足都会让结果不可信：

| 编号 | 条件 | 检查方式 | 不满足的后果 |
| --- | --- | --- | --- |
| **P2-1** | 硬件加速通过 | `emulator -accel-check` 含 `is installed and usable` | 模拟器根本不启动 |
| **P2-2** | 设备 API = 34 | `getprop ro.build.version.sdk` → `34` | 权限模型（READ_MEDIA_IMAGES）与 SAF 行为不同，基线不可比 |
| **P2-3** | 装的是 **debug** 包 | `run-as com.photomgm.app id` 成功 | 无法注入 prefs，只能人工输入 416 行日志 |
| **P2-4** | 素材未被修改 | `sample-manifest.json` 的 sha256 与文件一致 | 基线指向的输入变了，后续比对全是噪声 |
| **P2-5** | 命名模板与 PC 一致 | prefs `naming_template=1`，PC CLI 用同一模板 | 目录名不同，跨端比对 100% 差异 |
| **P2-6** | 设备时区 = Asia/Shanghai | `getprop persist.sys.timezone` | 跨日照片归属错误 |
| **P2-7** | 每次跑前 `pm clear` | 脚本第 3 步自动执行 | 走解析快路径，等于没验完整流水线 |
| **P2-8** | 输出目录已清空 | 脚本第 5 步 `rm -rf` | 旧 ZIP 残留 → “文件出现”被误判为本次导出完成 |

素材自检（P2-4 的具体命令）：

```bash
python scripts/golden/make_sample.py     # 幂等重建 + 自校验，末尾应输出「自校验通过」
```

---

## 5. 执行步骤

全部命令在仓库根执行。一键脚本封装了 S1~S9：

```bash
scripts/golden/run_android.sh /tmp/p2-a
```

下面是脚本内部实际做的事，逐条列出便于排障（任一步失败，按 §8 对应条目处理）。

### S0 环境自检

```bash
/d/Android/sdk/emulator/emulator.exe -accel-check
/d/Android/sdk/platform-tools/adb.exe version
```

**预期**：加速 `is installed and usable`；adb `1.0.41`。
**失败**：`exit 2` + 打印 §3.3 的两种修法。

### S1 启动模拟器并等开机

```bash
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell getprop sys.boot_completed    # 轮询到 1，超时 300s
```

**预期**：`Android 14 / API 34 / x86_64`，fingerprint 打印出来。
**失败**：`开机超时（300s）` → 看 `$OUT/emulator.log`，多为加速或磁盘不足。

### S2 构建并安装

```bash
# ★ 单根多模块（M3 起）：在**仓库根**执行，task 用全限定项目路径；
#   产物路径未变（仍是 android/app/build/outputs/apk/debug/app-debug.apk）。
cd <仓库根> && ./gradlew.bat :android:app:assembleDebug --console=plain -q
adb -s emulator-5554 install -r -t android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell pm clear com.photomgm.app
adb -s emulator-5554 shell pm grant com.photomgm.app android.permission.READ_MEDIA_IMAGES
```

**预期**：`Success`；`pm clear` 输出 `Success`；`pm grant` 无输出（成功）。
**注意**：`pm clear` 必须在安装之后、注入之前；顺序颠倒会把刚注入的 prefs 清掉。

### S3 照片进 MediaStore

```bash
adb -s emulator-5554 shell mkdir -p /sdcard/DCIM/Camera
adb -s emulator-5554 push <repo>/scripts/golden/sample/photos/. /sdcard/DCIM/Camera/
sleep 10
adb -s emulator-5554 shell content query \
  --uri content://media/external/images/media \
  --projection _id --where "_display_name LIKE 'IMG_20260814%'"
```

**预期**：命中 **≥ 60** 行（素材 66 张，其中 4 个是误筛用的非照片扩展名，不会入库）。
**失败**：命中 0 或远小于 60 → Android 11+ 的 MediaProvider 虽通过 FUSE 监听，
但冷启动首次 push 偶尔不触发。脚本会自动补一轮逐文件 `MEDIA_SCANNER_SCAN_FILE` 广播；
仍不足则按 §8 问题 3 处理（手动打开一次系统相册，或重启 MediaProvider 触发全量扫描）。

> 为什么不用 `content insert` 直接写 MediaStore：会跳过 EXIF 解析与 `RELATIVE_PATH` 计算，
> 与真实拍照入库的记录不一致，App 里的日期粗筛会选出不同的集合。

### S4 注入 prefs

```bash
python scripts/golden/make_android_prefs.py --out /tmp/prefs \
  --date 2026-08-14 --dir DCIM/Camera --template 1 \
  --output-dir /storage/emulated/0/Android/data/com.photomgm.app/files/golden

adb -s emulator-5554 shell am force-stop com.photomgm.app     # ★ 先停，否则退出时覆盖
adb -s emulator-5554 push /tmp/prefs/photo_mgm_settings.xml /data/local/tmp/
adb -s emulator-5554 push /tmp/prefs/photo_mgm_logs.xml     /data/local/tmp/
adb -s emulator-5554 shell "run-as com.photomgm.app mkdir -p shared_prefs"
adb -s emulator-5554 shell "run-as com.photomgm.app cp /data/local/tmp/photo_mgm_settings.xml shared_prefs/"
adb -s emulator-5554 shell "run-as com.photomgm.app cp /data/local/tmp/photo_mgm_logs.xml     shared_prefs/"
adb -s emulator-5554 shell "run-as com.photomgm.app wc -c shared_prefs/photo_mgm_logs.xml"   # 回读校验
```

**预期**：两个文件字节数与本地一致（settings ≈ 338 B，logs ≈ 15.6 KB）。
**为什么注入而不是输入**：见 §1.2 与脚本头注释——416 行中文无法用 `input text`，剪贴板注入受后台限制。

### S5 拉起并解析

```bash
adb -s emulator-5554 shell am start -n com.photomgm.app/.MainActivity
# 切「日志」页 → 点「解析日志」
adb -s emulator-5554 shell input tap <x> <y>
```

脚本用 `uiautomator dump` + `find_node.py` 按文本定位，不写死坐标：

```bash
adb shell uiautomator dump /sdcard/_p2_ui.xml
adb pull /sdcard/_p2_ui.xml /tmp/ui.xml
python scripts/golden/find_node.py /tmp/ui.xml "解析日志"      # 输出 "x y"
```

**两个必须知道的细节**（都已写进脚本注释）：

1. **切 tab 不能搜「日志」**。底部 tab 的文案是 `日志3·照片59`，而「解析日志」按钮也含“日志”，
   遍历会先撞上后者，结果点了按钮而不是切页。脚本用 `·照片` 定位 tab（`find_node.py` 优先匹配
   `clickable=true` 的节点，实测能稳定区分）。
2. **Compose 的按钮是“父节点 clickable + 子节点 text”两层**。`find_node.py` 命中文字后会向上找
   最近的 clickable 祖先，用祖先的 bounds 求中心——否则点在文字边缘时可能落在内边距上不触发。

**预期**：底栏由 `gateHint`（如“未设置照片目录…”）变为 `台账 N 行` + 「导出」按钮。
脚本轮询 `uiautomator dump` 里出现「导出」，超时 `WAIT_PARSE=300s`。

### S6 导出

```bash
# 点底栏「导出」后轮询产物
adb -s emulator-5554 shell "ls -1 /sdcard/Android/data/com.photomgm.app/files/golden"
```

**预期**：出现 `照片分类_20260814_HHmmss.zip`，大小 > 100 KB 且**连续两次轮询大小不变**
（判定写完的依据；只看“文件存在”会在写入中拉到半个包）。

### S7 拉取并拍快照

```bash
adb pull /sdcard/Android/data/com.photomgm.app/files/golden /tmp/p2-a/raw
python scripts/golden/snapshot_zip.py --in <zip> --out /tmp/p2-a/snapshot.json --normalize \
  --meta platform=android --meta api=34 --meta commit=<sha>
```

**预期**：`条目 N（文件 M / 目录 K）`，无 `[WARN] 归一化后有重名条目`。

### S8 双跑幂等（V2 判定，必做）

```bash
scripts/golden/run_android.sh /tmp/p2-a
scripts/golden/run_android.sh /tmp/p2-b
python scripts/golden/compare.py --base /tmp/p2-a/snapshot.json --after /tmp/p2-b/snapshot.json --max 10
```

**预期**：`✓ 两份快照完全一致`（退出码 0）。这是基线可用的前提。

### S9 入库

```bash
cp /tmp/p2-a/snapshot.json docs/golden/android-baseline.json
```

同时在 `docs/golden/README.md` 记录：commit、APK sha256 前 16 位、API、AVD、样本 manifest 哈希、
实际照片数/目录数（这些值来自 `meta` 与快照，别手抄）。

---

## 6. 预期结果

一次成功的 P-2 产出四样东西：

| 产物 | 位置 | 说明 |
| --- | --- | --- |
| 导出 ZIP | `/tmp/p2-a/raw/照片分类_20260814_*.zip` | 真实产物，**不入库**（体积大且可重生成） |
| ZIP 快照 | `/tmp/p2-a/snapshot.json` → 入库 `docs/golden/android-baseline.json` | 条目名 + 每条 sha256 + 方法 + 目录集合 |
| 模拟器日志 | `/tmp/p2-a/emulator.log` | 启动失败时唯一线索 |
| 双跑比对报告 | 终端输出 | V2 的证据 |

**数量预期（不是硬编码，首次跑出来后以实测为准并写入 meta）**：

| 指标 | 预期区间 | 依据 |
| --- | --- | --- |
| ZIP 内文件数 | 55~70 | PC 侧同素材为 59 张 |
| 目录数 | 20~26 | PC 侧为 23（含 `99_未分类`） |
| 未分类照片 | 0~6 | 素材中 4 张刻意落在事件窗口外 |
| 归一化后重名条目 | **0** | 非 0 说明折叠规则误伤业务字段，必须停下修规则 |

> 为什么给区间而不是精确值：Android 与 PC 在派生判定（D5）、EXIF 时间格式（D9）上**当前本就不同**，
> 这两处正是 T04 要统一的。P-2 记录的是“改造前长什么样”，不是“应该长什么样”。

---

## 7. 判定标准

分四级。**V1~V3 是 P-2 的通过条件；V4 是产出，不要求零差异。**

### V1 环境就绪（不通过 = 本轮结果无效）

| 检查 | 通过判据 |
| --- | --- |
| 硬件加速 | `emulator -accel-check` 含 `is installed and usable` |
| 设备 | `sys.boot_completed=1`，`ro.build.version.sdk=34` |
| 安装 | `pm list packages` 含 `com.photomgm.app`；`run-as ... id` 成功 |
| 权限 | `pm grant` 成功，启动后无权限弹窗遮挡（dump 里无 `允许` 按钮） |
| 素材 | MediaStore 命中 `IMG_20260814*` ≥ 60 条 |
| 注入 | 两个 prefs 文件字节数与本地一致 |
| 前置状态 | dump 里出现「导出」按钮（= 解析完成、台账已生成） |

任一条不满足 → **失败**，修复后重跑，不得继续看后面的判定。

### V2 幂等（基线可用的生命线）

| 检查 | 通过判据 |
| --- | --- |
| 双跑快照 | `compare.py` 退出码 0，差异数 **0** |
| 重名 | `duplicateNames` 为 **空数组** |
| 条目数 | 两次 `entryCount` 相等 |

不通过 → **基线不可用**。常见原因按可能性排序：
① 导出时刻混进了条目内容（归一化规则没覆盖）；② 照片遍历顺序不稳定导致同名序号漂移；
③ 两次跑的照片集合不同（MediaStore 索引未稳定 / `pm clear` 漏掉）。
**不允许**用 `--ignore` 把整类差异屏蔽掉来“让它通过”——`--ignore` 只允许用于
`^meta`（元信息）与已知的导出时刻字段。

### V3 分辨力（证明标尺能抓到问题）

| 实验 | 通过判据 |
| --- | --- |
| 篡改快照（把任一条目 `sha256` 改一位） | `compare.py` 必须报出该条目，且指到具体路径 |
| 真删一张照片后重跑 | 文件数减 1，且报出缺失条目名 |

不通过 → 标尺失效（多半是归一化过度，把条目名折叠成了同一个），必须修规则后重建基线。

### V4 跨端差异登记（产出，不是通过条件）

```bash
# PC 侧：目录 → 同构快照（剔除 PC 独有的 manifest/log 与两端口径不同的 ledger）
python scripts/golden/snapshot_dir.py --in <PC导出目录> --out /tmp/pc.json \
  --normalize --exclude '^(export-manifest\.json|export\.log|ledger\.xlsx)$'

# Android 侧：ZIP → 快照（剔除台账，其文件名含导出时刻且内容含生成时刻）
python scripts/golden/snapshot_zip.py --in <zip> --out /tmp/android.json \
  --normalize --exclude '^(巡查台账_.*\.xlsx)$'

# 比对：file 两端取值不同（<DIR>/<ZIP>），method 在目录形态下不存在压缩概念
python scripts/golden/compare.py --base /tmp/pc.json --after /tmp/android.json \
  --ignore '^(file|meta)' --ignore '\.method$' --max 30
```

**为什么必须屏蔽 `method` 而不是把它从快照里删掉**：`method` 记录 ZIP 条目的压缩方式
（0=STORED / 8=DEFLATED）。**单端双跑**时它是有效判据——压缩级别一变，包体就变，
但用户可见产物没变，属于"要能区分开"的差异；而**跨端**比对时目录形态没有压缩概念，
两边必然一个是 0、一个是 8。所以保留字段、跨端时显式屏蔽。
这不是"为了让比对通过而屏蔽"，是"这个概念只在一端成立"。

> 实测（2026-11-02）：把 PC 导出目录原样打成 ZIP 后跨端比对，得到 **82 条目结构完全一致**、
> 仅 `method` 82 处差异；显式屏蔽后 **0 差异**。这同时验证了比对链路的两个方向——
> 同构时能全等（不误报），结构不同时能准确报出（不漏报）。

| 允许差异（须书面解释一条） | 不允许差异 |
| --- | --- |
| 照片张数 / 归属不同（D5 派生判定、D9 EXIF 格式） | 同一目录内同名照片字节不同 |
| 目录名不同（若命名模板未统一——属 P2-5 未满足，应修条件而非登记） | 目录结构层级不同（多一层/少一层） |
| 台账条目（已被 `--exclude` 剔除） | `duplicateNames` 非空 |

**产出物是一张差异表**，逐条写「差异条目 / PC 值 / Android 值 / 归因 / 由哪个任务消灭」。
这张表就是 T04、T07、T12 的验收目标：**改造后重跑 V4，差异只能减少，不能新增**。

> 这里刻意不把“跨端零差异”当作 P-2 的通过条件：当前两端本就有 4 处已分叉
> （`effectiveMap` 的 copy 语义、派生判定口径、EXIF 时间格式、400×300/JPEG50 双份常量）。
> 要求此刻零差异，等于要求“重构前就完成重构”。

---

## 8. 常见问题

| # | 症状 | 根因 | 处置 | 判定已修复 |
| --- | --- | --- | --- | --- |
| 1 | `x86_64 emulation currently requires hardware acceleration!` / `Unable to open AEHD device: ERROR_ACCESS_DENIED` | AEHD 驱动未装/未启动，或被 Hyper-V 占用 | 管理员运行 `extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat`；或启用 WHPX 后重启。**不要**试图加 `-accel off` | `-accel-check` 出现 `is installed and usable` |
| 2 | `avdmanager` 报 `JAVA_HOME is not set` | 本机 `java` 不在 PATH | `export JAVA_HOME=/d/Android/jdk-17.0.2 && export PATH="$JAVA_HOME/bin:$PATH"` | `avdmanager list avd` 能列出 AVD |
| 3 | App 里照片数为 0 / 远少于 66 | MediaStore 未索引（冷启动首次 push 常见） | 脚本自动广播 `MEDIA_SCANNER_SCAN_FILE`；仍不行则手动打开一次系统相册，或 `adb shell am force-stop com.google.android.providers.media.module` 让 MediaProvider 重启并全量扫描 | `content query ... LIKE 'IMG_20260814%'` ≥ 60 行 |
| 4 | 导出提示「输出目录不可写」 | `output_dir` 写了公共目录（`/sdcard/Download`），targetSdk 34 分区存储 EACCES | 改成 App 私有外部目录 `/storage/emulated/0/Android/data/com.photomgm.app/files/golden` | 导出后 `ls $DEV_GOLDEN` 有 ZIP |
| 5 | App 里日志框是空的 / 日期是今天 | prefs 注入被覆盖或格式非法（BOM、CRLF、XML 未转义） | 必须先 `am force-stop` 再 `cp`；用 `make_android_prefs.py` 生成（内置转义与自校验），不要手写 heredoc | `run-as ... wc -c` 字节数与本地一致；UI 里日志行数 = 416 |
| 6 | 点了导出但一直没有 ZIP | ① 底栏还没 ready（解析未完成）；② 导出重入被以前的失败卡住；③ 输出目录没清空 | 先确认 dump 里有「导出」按钮（ready 才渲染）；`am force-stop` 后重开；脚本每次 `rm -rf $DEV_GOLDEN` | 4 分钟内出现 ZIP 且大小稳定 |
| 7 | `uiautomator dump` 里找不到「导出」/「解析日志」 | Compose 语义未导出 / 页面还在过渡动画 / 节点在滚动区外 | 关动画（§3.4）；先 dump 全量看 `text` 与 `content-desc`；必要时 `MANUAL=1` 人工点一次 | 手动 dump 能看到该文本节点 |
| 8 | 双跑差异集中在 `照片分类_<TS>.zip` 的条目名 | 归一化没开，或折叠规则误伤 | 必须加 `--normalize`；规则只折叠 `照片分类_`/`巡查台账_` 前缀（裸 `\d{8}_\d{6}` 会把 `IMG_20260814_080000.jpg` 也折掉，实测踩过） | `duplicateNames` 为空且两次全等 |
| 9 | 快照里照片名全变成 `IMG_<TS>.jpg`，重名一堆 | 同上，折叠规则过宽 | 修 `snapshot_zip.py` / `snapshot_dir.py` 的 `_TS_RE`（两处必须逐字符一致） | 快照里能看到完整 `IMG_20260814_080000.jpg` |
| 10 | 跨端比对全部报差异，连照片名都不同 | 两端命名模板不一致（P2-5） | Android prefs `naming_template=1`，PC CLI 用同一模板 | 目录名前缀（`01_`/`02_`）与结构一致 |
| 11 | 照片归属与 PC 不同（同一张落到别的事件） | 两端 EXIF 时间解析/时区不同（D9），或派生判定口径不同（D5） | **这是预期差异**，登记进 V4 表；不要为了对齐去改代码（改代码是 T04 的事） | 差异已被登记且有归因 |
| 12 | 模拟器磁盘/内存不足，导出中途失败 | 默认 data 分区偏小、cache 残留 | 启动加 `-partition-size 2048 -memory 3072`；`pm clear` 会清 cache | 导出稳定完成，`df /data` 有剩余 |
| 13 | `adb pull` 中文文件名乱码/失败 | Windows 控制台代码页与 UTF-8 路径冲突 | 脚本先 pull **整个目录**，再用 Python 在本地找 `*.zip`（不经 shell 变量传中文名） | `$OUT/raw` 下有 ZIP |
| 14 | `run-as` 报 `package not debuggable` | 装了 release 包 | 改用 `:app:assembleDebug` 产物 | `run-as com.photomgm.app id` 输出 uid |

---

## 9. 与后续任务的衔接

P-2 不是一次性动作，是**回归开关**。后续每个任务收尾都要跑一次：

| 任务 | 需要验证的 | 判定 |
| --- | --- | --- |
| T03 EXIF 时间 / SHA-256 上移 | 照片归属不变 | V2 全等；V4 差异不新增 |
| T04 媒体规格 / 派生命名上移 | **预期有变化**（D5 判定收紧） | V4 差异逐条登记进 CHANGELOG，角标变化面 > 5% 则回退 D5 |
| T05 防抖/取消/JSON 上移 | 无行为变化 | V2 全等 |
| T08/T11 契约化 + ViewModel 瘦身 | 导出产物完全不变 | V2 全等 + V4 差异不扩大 + 10 个既有单测绿 |
| T12 copyPhotos 同步 | 无 UI 入口时不可达 | V2 全等（不可达 = 不应有差异） |
| T13 UI 重排 | 产物不变（只动表现层） | V2 全等 |

**M3 / M4 停止点的硬要求**：给出 V2 全等的证据（两份 snapshot 的 `compare.py` 输出）+ V4 差异表
与上一版逐条对比。缺任一项，停止点不算通过。

---

## 10. 附录

### 10.1 文件清单

| 文件 | 作用 |
| --- | --- |
| `scripts/golden/run_android.sh` | 一键执行 S0~S9（环境自检、启动、装包、灌素材、注 prefs、解析、导出、拉取、拍快照） |
| `scripts/golden/make_android_prefs.py` | 生成两个 SharedPreferences XML（含 XML 转义、BOM/CRLF 检查、回读自校验） |
| `scripts/golden/find_node.py` | 从 `uiautomator dump` 按文本定位可点坐标（优先 clickable 自身 → 向上找祖先） |
| `scripts/golden/snapshot_zip.py` | ZIP → 快照；`--normalize` 折叠导出时刻，`--exclude` 剔除跨端不可比条目 |
| `scripts/golden/snapshot_dir.py` | PC 导出目录 → **与 ZIP 同构**的快照（跨端比对用） |
| `scripts/golden/compare.py` | 两份快照逐路径 diff |
| `docs/golden/android-baseline.json` | 待产出（首次跑通后入库） |

### 10.2 命令速查

```bash
# 0. 加速自检（唯一阻塞项）
/d/Android/sdk/emulator/emulator.exe -accel-check

# 1. 一键跑一次
scripts/golden/run_android.sh /tmp/p2-a

# 2. 人工模式（自动点击失效时的退路）
MANUAL=1 scripts/golden/run_android.sh /tmp/p2-a

# 3. 双跑幂等
scripts/golden/run_android.sh /tmp/p2-b
python scripts/golden/compare.py --base /tmp/p2-a/snapshot.json --after /tmp/p2-b/snapshot.json

# 4. 跨端差异
python scripts/golden/snapshot_dir.py --in <PC导出目录> --out /tmp/pc.json \
  --normalize --exclude '^(export-manifest\.json|export\.log|ledger\.xlsx)$'
python scripts/golden/snapshot_zip.py --in <zip> --out /tmp/android.json \
  --normalize --exclude '^(巡查台账_.*\.xlsx)$'
python scripts/golden/compare.py --base /tmp/pc.json --after /tmp/android.json \
  --ignore '^(file|meta)' --ignore '\.method$'

# 5. 清理
adb -s emulator-5554 emu kill
```

### 10.3 本机已完成的验证（截至 2026-11-02）

| 项 | 结果 |
| --- | --- |
| AVD 创建 | ✅ `pmgolden_api34` 创建成功，`avdmanager list avd` 可见 |
| 模拟器启动 | ❌ 阻塞：`Unable to open AEHD device: ERROR_ACCESS_DENIED` |
| `run_android.sh` 语法 | ✅ `bash -n` 通过 |
| 环境自检分支 | ✅ 实测 `exit 2` 并打印两种修法原文 |
| `make_android_prefs.py` | ✅ 生成 338 B + 15,642 B，自校验通过（416 行 / 6,049 字符逐字符一致） |
| `find_node.py` | ✅ 三组用例：按钮点父节点中心（300,2050）、tab 优先于同名文字按钮（180,2270）、不存在返回 exit 1 |
| `snapshot_dir.py` | ✅ PC 导出目录 → 82 条目（59 文件 + 23 目录），照片名完整保留，0 重名 |
| `snapshot_zip.py --normalize` | ✅ 修正了裸正则误伤照片名的缺陷（见 §8 问题 9） |
| 跨端比对链路 | ✅ PC 目录 → 同构快照（82 条目）→ 打成 ZIP 后再拍 → 比对：结构全等，仅 `method` 82 处差异（0 vs 8，属概念差异）；显式屏蔽后 **0 差异** |
| 端到端 S1~S9 | ⏳ 待加速修好后首跑 |

> 这份“已完成/待完成”的划分是刻意的：把未跑通的步骤当已验证写进文档，
> 下一个接手的人会按错的假设排障，比没文档更糟。
