# P-2 安卓真机基线测试报告

> 执行日期：2026-11-02 · 设备：vivo V2232A（USB 直连） · 被测提交：`86a6be7`
> 被测包：debug `app-debug.apk` 16,223,225 B / sha256 `540abdc8…b9b58`
> 一句话结论：**V1/V2/V3 全部通过，安卓基线已建立并可复现；同时测出一个 P0 级真机专属缺陷——缺 `ACCESS_MEDIA_LOCATION`，导致所有照片的 GPS 被系统剥离。**

---

## 1. 测试目标与范围

### 1.1 为什么必须做这一轮

PC 侧黄金样本基线早已入库（`docs/golden/desktop-baseline.json`），安卓侧一直是空的。后果很具体：

- T11 要把 `AppViewModel` 从 1165 行压到约 250 行，T12 要改照片归属口径 —— 改完怎么证明"产物没变"？
- 当时只有 10 个 Robolectric 单测兜底，而单测覆盖不到「66 张照片 → MediaStore → EXIF → 分类 → 打包 → 台账」这条完整链路。
- 之前计划走模拟器，卡在 AEHD 驱动拒绝访问（`Unable to open AEHD device: ERROR_ACCESS_DENIED`），跑不起来。

真机接入后这条阻塞消失：**真机不需要硬件加速，且比模拟器更接近用户环境**。

### 1.2 范围

| 在范围内 | 不在范围内 |
| --- | --- |
| 单次导出产物的结构与字节（照片、目录、台账） | 交互体验、视觉还原度 |
| 同版本重跑的幂等性 | 多机型 / 多 ROM 兼容性 |
| 快照工具本身的分辨力 | 性能基准（帧率、内存峰值） |
| 跨端（PC ↔ 安卓）差异登记与归因 | SAF 人工选择输出目录的完整路径（仅冒烟） |

---

## 2. 环境配置

### 2.1 设备与工具链（实测）

| 项 | 实测值 |
| --- | --- |
| 设备序列号 | `10AD2G1FLK001AQ` |
| 机型 | vivo V2232A（`ro.product.model=PD2232`） |
| 系统 | **Android 16 / API 36** / arm64-v8a |
| 屏幕 | 1080×2400 @ 480dpi |
| 时区 | `Asia/Shanghai`（已确认，未做改写） |
| App | `com.photomgm.app` v1.3.2 (versionCode 6)，targetSdk 34 / minSdk 26，`DEBUGGABLE` |
| 宿主 | Windows + Git Bash，`/d/Android/sdk`，JDK 17.0.2 |

### 2.2 与模拟器方案的环境差异

真机省掉了三件事，也带来三个新坑：

| 模拟器方案 | 真机方案 | 原因 |
| --- | --- | --- |
| `emulator -accel-check` 门禁 | 不需要 | 真机是原生 ARM 执行，无模拟开销 |
| `-no-window -no-snapshot` 启动 | 不需要 | 设备已由用户手动开机 |
| AVD 创建与 `-gpu swiftshader` | 不需要 | — |
| —— | **必须 `MSYS_NO_PATHCONV=1`** | Git Bash 会把 `/sdcard/...` 改写成 Windows 路径，实测报 `mkdir: 'D:': Read-only file system` |
| —— | **临时目录必须用 `pwd -W`** | bash 的 `/tmp` 是 `%LOCALAPPDATA%\Temp`，Python 却解释成 `C:\tmp`，两边不是同一个目录 |
| —— | **素材不能放 `DCIM/Camera`** | 那是用户真实相册（本机已有 27 张真实照片），会污染且被误扫 |

这三点已全部写进 `scripts/golden/run_android_real.sh` 的注释与实现。

---

## 3. 测试前提条件

逐条都做了检查，不满足则脚本直接 `exit 1` 并打印原因。

| 编号 | 前提 | 检查方式 | 实测 | 不满足的后果 |
| --- | --- | --- | --- | --- |
| P2-1 | USB 调试已授权 | `adb get-state` = `device` | ✅ | `unauthorized` 时所有命令被拒 |
| P2-2 | API ≥ 30（scoped storage） | `getprop ro.build.version.sdk` | ✅ 36 | 低于 29 走不到 MediaStore 新查询路径 |
| P2-3 | 装的是 debug 包 | `dumpsys package \| grep DEBUGGABLE` | ✅ | 无 `run-as`，无法注入 prefs |
| P2-4 | 照片权限已授予 | `dumpsys \| grep READ_MEDIA_IMAGES` | ✅ granted=true | MediaStore 查询返回空，"照片 0 张" |
| P2-5 | 素材已进 MediaStore | `content query` 计数 ≥ 66 | ✅ 69 | 粗筛不到照片，全落未分类 |
| P2-6 | prefs 注入字节一致 | 本地 `wc -c` vs `run-as wc -c` | ✅ 344 B / 15,642 B | 编码或截断问题，App 读不到设置 |
| P2-7 | 输出目录可写 | 导出后 `ls` 有 ZIP | ✅ | 分区存储 EACCES，导出以"目录不可写"失败 |
| P2-8 | 无同名残留 ZIP | 导出前 `rm -rf` 输出目录 | ✅ | 旧包被误判为本次产物 |
| P2-9 | 时区为 `Asia/Shanghai` | `getprop persist.sys.timezone` | ✅ | 导出文件名与"今天"偏 8 小时 |

> **P2-4 与 `pm clear` 的顺序陷阱**：`pm clear` 会连运行时权限一起清掉。脚本把 `pm clear` 放在安装之后、`pm grant` 之前，并回读校验 `granted=true`，避免"清了数据但忘了重新授权"导致照片 0 张这种难查的假失败。

---

## 4. 测试步骤

一键执行：

```bash
scripts/golden/run_android_real.sh .p2work/run-a     # 首次
scripts/golden/run_android_real.sh .p2work/run-b     # 幂等复跑（SKIP_INSTALL=1 KEEP_PHOTOS=1 可省时间）
```

脚本内部 S0~S9 与本轮实际执行的命令一一对应：

| 步骤 | 动作 | 关键命令 / 实现要点 | 实测耗时 |
| --- | --- | --- | --- |
| S0 | 环境自检 | `adb get-state` / `getprop` / `dumpsys package` | <2s |
| S1 | 设备就绪 | 真机免启动，仅关动画（`settings put global *_animation_scale 0`） | <1s |
| S2 | 构建安装 | `gradlew :app:assembleDebug` + `adb install -r -t` | 28s（增量） |
| S3 | 清数据 + 授权重授 | `pm clear` → `pm grant READ_MEDIA_IMAGES` → 回读校验 | 3s |
| S4 | 素材进 MediaStore | `adb push` 到 `Pictures/pmgolden` → `content call scan_volume` → 轮询计数 | 12s |
| S5 | prefs 注入 | `make_android_prefs.py` → `push` → `run-as cp` → 逐文件 `chmod 600` → 回读字节 | 4s |
| S6 | 拉起 App | `am start -n com.photomgm.app/.MainActivity` | 8s（含等待首帧） |
| S7 | 解析日志 | `uiautomator dump` → `find_node.py` 取坐标 → `input tap` → 轮询底栏"导出" | **5s** |
| S8 | 导出 ZIP | 点"导出" → 每 3s 轮询文件大小直到稳定 | **138s** |
| S9 | 拉取快照 | `adb pull` → `snapshot_zip.py --normalize` → 照片字节指纹 | 6s |

### 4.1 为什么日志要"注入"而不是"输入"

安卓端日志入口只有文本框 + [粘贴导入]，没有文件导入。416 行中文无法用 `adb shell input text`（只支持 ASCII），剪贴板注入在 Android 10+ 受后台限制。

注入只替代"用户上次填好的状态"，**解析、分类、导出仍走 App 真实代码路径** —— 被跳过的是"打字"这一步，不是被测逻辑。

### 4.2 为什么每次都要 `pm clear`

App 有 parsed / classify / photos / coarse 四类缓存。复用旧数据会让 `parseLogs` 走快路径（只清人工移动、不重扫照片），产物看似相同却没验到完整流水线。PC 侧踩过同类坑（复用 `--data-dir` 读到上一轮事件）。

---

## 5. 判定标准

| 级别 | 判据 | 不通过意味着 |
| --- | --- | --- |
| **V1 环境** | 设备在线 / API ≥ 30 / debug 包 / 权限 granted / MediaStore ≥ 66 张 / prefs 字节一致 / dump 出现"导出" | 本轮结果无效，修完重跑 |
| **V2 幂等** | 全新数据连跑两次，结构快照 **0 差异**、照片字节全等、台账单元格全等 | 基线不可用，必须先定位抖动源 |
| **V3 分辨力** | 篡改一个 sha256 必须报出；删一个条目必须报出 | 标尺失效（多半是归一化过度） |
| **V4 跨端** | 差异逐条登记 + 归因 | 这是**产出**，不是门槛 |

**V4 刻意不要求零差异**：两端当前在派生判定（D5）、EXIF 时间格式（D9）上本就有分叉，要求此刻零差异等于要求"重构前先完成重构"。这张差异表就是 T04/T07/T12 的验收目标——改造后差异只能减少、不能新增。

---

## 6. 实际运行结果

### 6.1 流水线各阶段实测

| 阶段 | 数值 | 与 PC 对照 |
| --- | --- | --- |
| MediaStore 命中 `Pictures/pmgolden` | 69 条 | — |
| 粗筛 | **65 张** | PC 同 65 |
| 解析事件 | **71 条**（未识别 1 行） | PC 同 71 |
| 分类 | 正常 44 / 待复查 27 / 未匹配 3 | PC 同 |
| 台账 | 71 数据行 + 1 表头 = **72 行**，504 非空单元格，50 嵌图 / 50 锚点 | PC 72 行 / 504 单元格 / **44 嵌图** |
| ZIP | **57 条目 / 26 目录 / 454,481 B** | PC 82 条目 / 59 文件 / 23 目录 |
| 导出耗时 | 138s（65 张，含压缩与台账生成） | PC 约 40s |

两条运行（run-a / run-b）的 ZIP 字节数完全相同（454,481 B），是幂等的第一个信号。

### 6.2 V1 环境 —— 通过

全部 9 条前提实测满足，见 §3。设备识别、权限、MediaStore 索引、prefs 字节数逐项核对无异常。

### 6.3 V2 幂等 —— 通过

```
=== 结构快照（57 条目 / 26 目录 / 每张照片 sha256）===
[SAME] 0 差异：android-baseline.json == snap-struct.json

=== 照片字节指纹 ===
run-a 57 张 / run-b 57 张
  字节一致 57 / 不同 0

=== 台账单元格（72 行 / 504 非空单元格 / 50 嵌图 / 50 锚点）===
[SAME] 0 差异：android-ledger-baseline.json == ledger-a.json
```

> 台账在 ZIP 内的文件名带导出时刻（`巡查台账_20260814_20261102_122556.xlsx`），字节必然不同（xlsx 内含 `docProps` 时间戳）。所以**结构比对排除台账、台账单独按单元格比**——这是有意的分工，不是漏测。

### 6.4 V3 分辨力 —— 通过

| 实验 | 操作 | 期望 | 实测 |
| --- | --- | --- | --- |
| V3-a | 把 `entries[0].sha256` 改成全 0 | 报差异 | ✅ 定位到 `entries[0].sha256`，exit=1 |
| V3-b | 删掉 2 个未分类条目 | 报差异 | ✅ `entries.length 57 → 55`，exit=1 |

标尺既不过敏（V2 连跑 0 差异）也不迟钝（人为篡改 100% 捕获）。

### 6.5 V4 跨端差异 —— 登记完成

#### 差异总表

| # | 差异项 | PC | 安卓 | 归因 | 判定 |
| --- | --- | --- | --- | --- | --- |
| D-a | **台账单元格文本** | 71 条数据 | 71 条数据 | — | ✅ **0 差异** |
| D-b | 有照片的事件数 | 23 | 26 | D9（EXIF 短横格式） | 安卓正确 |
| D-c | 未分类照片 | 11 张 | 3 张 | 同上 | 安卓正确 |
| D-d | 台账嵌图数 | 44 | 50 | 随 D-b（+3 事件 ×2 张） | 安卓正确 |
| D-e | 嵌图字节 | 10,873 B 级 | 7,595 B 级 | **编码器不同**（见下） | 不可比，非缺陷 |
| D-f | 嵌图锚点行 | — | 34 处不同 | 随照片归属变化 | 随 D-b |
| D-g | ZIP 条目数 | 82 | 57 | PC 含 manifest/log，且目录口径不同 | 形态差异（D4 决策） |

#### D-b / D-c 的精确证据

「仅安卓有」的三个目录，全部是 `exif="dash"` 的事件：

```
06_K519+500发现路面抛洒物（轮胎皮）…
12_十点五十分 隧道渗水，K523+400排水沟积水…
21_检查高边坡K537+100，坡面冲刷，截水沟堵塞…
```

对应的 8 张照片 PC 全落 `99_未分类`：

```
IMG_20260814_091000.jpg  IMG_20260814_091030.jpg
IMG_20260814_105000.jpg  IMG_20260814_105030.jpg  IMG_20260814_105000_original.jpg
IMG_20260814_144500.jpg  IMG_20260814_144530.jpg  IMG_20260814_144500_original.jpg
```

**方向明确：安卓对、PC 漏。** PC 的 EXIF 时间只认 `yyyy:MM:dd`，遇到 `yyyy-MM-dd`（短横分隔）读不出拍摄时间 → 落未分类；安卓的 `ExifTimeParser` 两种都认 → 正确归位，且 `_original` 副本被派生去重正确丢弃。

这正是计划里 T03 要统一的项（D9），**不是本轮引入的回归**。

#### D-e 为什么不可比

两端规格常量数值完全相同：

| | 宽度 | 高度 | 质量 |
| --- | --- | --- | --- |
| PC `PcImageEmbedder` | 400 | 300 | `0.5f` |
| 安卓 `ImageUtils` | 400 | 300 | `50` |

但底层编码器不同：PC 用 **Java ImageIO**（`ImageWriteParam.compressionQuality`），安卓用 **`Bitmap.compress(JPEG, 50)`**（Skia/libjpeg-turbo）。同样"质量 50"在两套实现下的量化表与色度下采样策略不一致，输出字节必然不同（实测差约 30%）。

**结论（架构级）**：可以共享"规格"（400/300/50），但**无法共享"编码器"**。所以「台账嵌图字节跨端一致」这个目标本身不可达，跨端比对只能比 **尺寸 + 数量 + 视觉相似度**，不比字节。这条要写进 T04 的验收口径，否则会误判成缺陷去追。

---

## 7. 证据

| 证据 | 路径 |
| --- | --- |
| 启动后初始态（日期 2026-08-14 / 照片 65 / 日志已注入 / 底栏"尚未解析"） | `docs/golden/screenshots/shot-launch.png` |
| 解析完成（日志71·照片65 / 全部71·正常44·待复查27·未匹配3 / 台账 71 行） | `docs/golden/screenshots/shot-parsed.png` |
| 导出完成（底栏"导出完成 ✓"、"打开导出的台账 ›"） | `docs/golden/screenshots/shot-exported.png` |
| 安卓结构基线（57 条目 / 26 目录 / 每张 sha256） | `docs/golden/android-baseline.json` |
| 安卓台账基线（72 行 / 504 单元格 / 50 嵌图 / 锚点） | `docs/golden/android-ledger-baseline.json` |
| 一键复现脚本 | `scripts/golden/run_android_real.sh` |

基线 meta 里固化了追溯信息：

```
platform: android-real      device: vivo V2232A      os: Android 16 (API 36) arm64-v8a
commit: 86a6be7             apkSha: 540abdc8…b9b58  apkBytes: 16223225
appVersion: 1.3.2 (versionCode 6, targetSdk 34, minSdk 26)
sampleManifest: 3a7c15b3b97165cc                    timezone: Asia/Shanghai
```

---

## 8. 发现的问题

### P0-1 · 缺 `ACCESS_MEDIA_LOCATION`，所有照片 GPS 被系统剥离　**（已修复，见 §8.1）**

**现象**：导出的 ZIP 里，有 GPS 的照片位置信息全部变成 0。

```
原始（素材）：GPSLatitude = N 30°15'4.32"    GPSLongitude = E 120°10'5.88"
导出后：      (nan, nan, nan)                (nan, nan, nan)
```

**定位过程**（四步排除法）：

1. 本地素材 vs 设备文件 → **md5 相同**（`f56634e8…`），排除 push 损坏
2. 设备文件 vs ZIP 内文件 → **不同**，确认是 App 导出环节
3. 逐字节 diff → **文件长度完全相同、仅 14 字节不同**，全部落在 EXIF IFD 的值区，非零值被清零 → 不是重编码（重编码会改长度），是**读写过程中被改写**
4. 拉取 App 缓存 `photo_mgm_settings.xml` 的 `cache_photos` → **65 条记录 `latitude` / `longitude` 全部为 `null`**，而 `captureTimeMs` 正常

**根因**：`AndroidManifest.xml` 只声明了 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`，**没有 `ACCESS_MEDIA_LOCATION`**。Android 10+ 起，没有该权限的 App 读取媒体时，系统（MediaProvider / FUSE）会主动剥离位置信息——这是平台的隐私保护设计，不是 bug。

**影响面**（三处，逐级加重）：

| 受影响 | 具体后果 |
| --- | --- |
| `Photo.latitude/longitude` | 恒为 `null` |
| 去重判定 | 从「时间 1s + GPS 距离 10m」退化为**降级路径**：时间 1s + 派生后缀 + 文件名末位数字块差 ≤1000 |
| 交付物 | 导出的照片丢失地理位置元数据（EXIF 标签仍在、值被清零成 `nan`） |

> **口径更正（2026-11-02 修复时复核代码）**：本节初稿把去重退化写成"仅按时间 1s、同秒不同地点会被误删"，
> 这是**过度概括**。`Dedup.kt` 里 GPS 双无效时走的是 `ExifContentDedupStrategy` 的降级分支：
> **必须至少一方带派生后缀**（`_original` 等）且**文件名末位数字块差 ≤1000** 才成组，
> 根本不存在"仅按时间就合并"的路径，所以"同秒不同地点被误删"不会发生。
> 真实的退化是两件更隐蔽的事：
> ① **漏并**——文件名不含毫秒序号、或序号差 >1000 的双份不再合并，用户以为已去重、实际导出两份；
> ② **误并的残余窗口**——两张都带派生后缀、同秒、序号差 ≤1000 的不同场景照片会被合并丢一张。
> 结论不变（必须在 T11 之前修），但理由要说准：**GPS 缺失让"内容判重"退化成"文件名猜谜"**。

**本轮为何没导致可见错误**：合成素材里每个事件内照片间隔 30s，且重复组按"同时间 + 同 GPS + 逐像素相同"设计，
降级分支恰好也能并上，所以去重结果看起来是对的。**这属于素材设计恰好避开了，不能当作没问题**。

**修复方向**：见 **§8.1（已实施）**——manifest 声明 + 运行时一并申请 + 未授权时的降级提示 + 缓存版本位。

**修复后必须重跑本报告 §6 的 V1/V2/V3**：GPS 一旦真的读到，`Photo.latitude` 从恒 null 变非 null，
去重会从降级分支切回「时间 + GPS 距离」分支，照片分组可能变化——`docs/golden/android-baseline.json`
需要用修复后的产物**重新建立基线**（旧基线是在"GPS 被剥离"的前提下拍的）。

**为什么之前测不出**：
- Robolectric 单测不走真实 MediaProvider/FUSE，`ExifInterface` 读到的是测试桩数据
- `TestImages.kt` 程序化生成，未必带 GPS
- 模拟器理论上同样可复现，但一直卡在 AEHD 启动失败

**建议**：作为独立提交修复（改 manifest + 权限申请 UI + 一条"无权限时 GPS 应为 null 而非崩溃"的单测），**赶在 T11 改 ViewModel 之前**——否则去重口径会在错误的 GPS 假设上被重构。

### 8.1 P0-1 修复记录（2026-11-02，已实施并验证）

完整说明见 **`docs/P0-GPS-MEDIA-LOCATION-FIX.md`**。要点：

| 项 | 内容 |
| --- | --- |
| 改动 | ① `AndroidManifest.xml` 声明 `ACCESS_MEDIA_LOCATION`；② 新增 `MediaPermissions.kt`（按 SDK 版本裁剪清单的纯函数）+ `MainActivity` 改 `RequestMultiplePermissions`；③ `UiState.geoAvailable` + 未授权时的 Snackbar / 设置页红条；④ `SettingsStore` 增加 GPS 语义版本位（作废旧缓存，否则老用户升级后仍吃 null GPS 缓存） |
| 单测 | 新增 `MediaPermissionsTest`（15 条）+ `SettingsStoreGeoCacheTest`（7 条）；安卓三模块 debug/release 单测全绿 |
| 正向实测 | `cache_photos`：**59/65 有 GPS**（修复前 0/65）；导出 ZIP：**52/56 有 GPS**（修复前 0/56） |
| 字节级确认 | 导出 ZIP 内 **56 张照片与素材逐字节相同**（修复前 52 张各差 14 字节，正是被清零的 GPS 值区） |
| 负向实测 | 未授权时仅**前 9 张（按拍摄时间序）**丢 GPS、其余仍可读 → 与"全量剥离"的初版结论不符，见下 |

**两个必须记住的结论**：

1. **旧安卓黄金基线已失效**：`docs/golden/android-baseline.json` 里 52 条照片 sha256 是在"GPS 被清零"的字节上算的。
   GPS 恢复后这些条目全部变化（本轮实测 52/57 条 sha 不同），**T11 之前必须重建**，否则"黄金样本全等"是在和错误基线对比。
2. **负向验证出现部分生效**：`pm clear` 后不授予位置权限再解析，只有按拍摄时间最早的 9 张丢了 GPS，其余 50 张仍读到。
   说明平台的剥离不是"一刀切"（疑似 MediaProvider/FUSE 侧对权限判定有缓存或时序窗口），
   已登记为 **P1-2（待复现）**；不影响本次修复的有效性，但意味着"未授权 = 所有照片无定位"这句要改成
   "可能只有部分照片有定位"——**部分可用比全部不可用更危险**：`ExifContentDedupStrategy` 在
   "一方有 GPS、另一方无"时直接判为不重复，会让本该合并的双份漏并。

### P1-1 · 快照归一化正则跨字段截断（已修）

台账文件名 `巡查台账_20260814_20261102_121837.xlsx` 被旧规则 `(?:照片分类|巡查台账)_\d{8}_\d{6}` 截成 `巡查台账_<TS>02_121837.xlsx`——它吃掉了 `20260814_202611`，剩 `_02_121837`。

同一条规则更早还误伤过照片名（`IMG_20260814_080000.jpg` → `IMG_<TS>.jpg`，一目录几十张照片折成同名，跨端完全无法区分）。

已改为 `(?:照片分类|巡查台账)_[\d_]+(?=\.)`：只认这两个前缀 + 整条时间串整体折叠。验证输出：

```
巡查台账_20260814_20261102_121837.xlsx → 巡查台账_<TS>.xlsx
照片分类_20260814_121837.zip            → 照片分类_<TS>.zip
01_交接班/IMG_20260814_080000.jpg       → 不变（业务数据必须保留）
```

### P1-2 · 台账嵌图字节跨端不可比（认知修正，非代码缺陷）

见 §6.5 D-e。需要在 T04 的验收口径里明确：嵌图只比尺寸与数量，不比字节。否则后续会有人把"字节不同"当成回归来追。

### P2-1 · 待复查占比偏高（27/71 = 38%）

27 条"分类不明确·需人工复核"且全部标注"无时间"。这是合成日志里续行合并导致部分事件丢失时间前缀所致（`0825`、`八点四十分` 这类写法）。**属素材特征，不是 App 缺陷** —— PC 侧同样是 27 条。真实日志的构成会不同，等真实素材到位（P-1 遗留）后再评估。

---

## 9. 结论

1. **P-2 达标**：安卓侧基线已建立（结构 + 台账两份），V1/V2/V3 全部通过，可通过一条命令复现：
   ```bash
   scripts/golden/run_android_real.sh .p2work/run-b
   python scripts/golden/compare.py --base docs/golden/android-baseline.json \
          --after .p2work/run-b/snap-struct.json --ignore '^meta'
   ```
2. **跨端差异已闭环归因**：台账 71 行业务数据**完全一致**；唯一实质差异是 3 个事件 / 8 张照片（全部短横 EXIF），方向为安卓正确、PC 漏识别，属 T03/D9 的既定修复范围。
3. **测出一个 P0 缺陷**：`ACCESS_MEDIA_LOCATION` 缺失导致 GPS 全剥离。这是真机专属、单测覆盖不到的问题，**必须在 T11 之前修**。
4. **对重构计划的调整建议**：
   - T03 补一条：统一 EXIF 时间格式后，PC 侧的 8 张照片应回到正确事件，`android-baseline.json` 与 PC 新基线的差异应只剩嵌图字节（不可比）；
   - T04 验收口径写明"嵌图不比字节"；
   - T11 之前先修 P0-1，否则去重逻辑会在 GPS 恒 null 的假设上被重构。

---

## 10. 常见问题与应对（真机版）

| # | 症状 | 根因 | 处置 |
| --- | --- | --- | --- |
| 1 | `adb devices` 显示 `unauthorized` | 手机上没点"允许 USB 调试" | 拔插线重试；无效则 `adb kill-server && adb start-server` 再弹一次 |
| 2 | `mkdir: 'D:': Read-only file system` | Git Bash 把 `/sdcard/...` 改写成 Windows 路径 | `export MSYS_NO_PATHCONV=1`（脚本已设） |
| 3 | Python 报"找不到 /tmp/xxx" | bash 的 `/tmp` ≠ Python 的 `/tmp` | 一律用 `$(pwd -W)` 生成 Windows 绝对路径 |
| 4 | `content query` 计数恒为 0 | `--projection` 用了逗号（应为**冒号**） | `--projection _id:relative_path` |
| 5 | 照片 0 张 / 全落未分类 | `pm clear` 后忘了重新 `pm grant` | 脚本在 clear 后立刻授权并回读 `granted=true` |
| 6 | `chmod: shared_prefs/*.xml: No such file` | `run-as` 的 glob 由外层 shell 在 `/` 下展开 | 逐个列出文件名，不用通配符 |
| 7 | 导出提示"输出目录不可写" | 输出目录写成公共目录，分区存储 EACCES | 用 `/storage/emulated/0/Android/data/<pkg>/files/golden` |
| 8 | App 里日志是空的 | prefs 被覆盖（App 退出时写回）或格式非法 | 先 `am force-stop` 再 `cp`；用生成器而非手写 heredoc |
| 9 | **导出的照片 GPS 全为 0** | 缺 `ACCESS_MEDIA_LOCATION`（§8 P0-1） | 加权限声明 + 运行时申请；本轮已登记为缺陷 |
| 10 | 照片归属与 PC 不同 | D9 分叉（短横 EXIF） | **预期差异**，登记进 V4，不要为对齐去改代码 |
| 11 | 台账嵌图字节与 PC 不同 | 编码器不同（ImageIO vs Bitmap） | **不可比**，只比尺寸/数量（§6.5 D-e） |
| 12 | 双跑对比报台账文件名不同 | 文件名含导出时刻 | 结构比对排除台账，台账按单元格比 |
| 13 | `uiautomator dump` 拿不到界面 | App 切到后台或屏锁 | 先 `am start` 拉起、`input keyevent 82` 解锁 |
| 14 | 导出 2 分钟没动静 | 65 张照片压缩 + 台账生成，真机比 PC 慢 3 倍 | 轮询文件大小稳定而非固定等待；超时阈值放到 6 分钟 |

---

## 11. 复现清单

```bash
# 0. 素材（若尚未生成）
python scripts/golden/make_sample.py

# 1. 单次执行（含装包、清数据、授权、注入、解析、导出、拉取、快照）
scripts/golden/run_android_real.sh .p2work/run-a

# 2. 幂等复跑
SKIP_INSTALL=1 KEEP_PHOTOS=1 scripts/golden/run_android_real.sh .p2work/run-b

# 3. V2 判定（结构）
python scripts/golden/compare.py \
  --base .p2work/run-a/snap-struct.json --after .p2work/run-b/snap-struct.json

# 4. V2 判定（台账，先各自解出 xlsx 再拍快照）
python scripts/golden/snapshot_xlsx.py --in ledger-a.xlsx --out ledger-a.json
python scripts/golden/snapshot_xlsx.py --in ledger-b.xlsx --out ledger-b.json
python scripts/golden/compare.py --base ledger-a.json --after ledger-b.json --ignore '^file'

# 5. V4 跨端（PC 目录快照 vs 安卓 ZIP 快照，两侧都要 --normalize）
python scripts/golden/snapshot_dir.py --in <PC导出目录> --out pc.json --normalize \
  --exclude '^(export-manifest\.json|export\.log|ledger\.xlsx)$'
python scripts/golden/snapshot_zip.py --in <zip> --out android.json --normalize \
  --exclude '^巡查台账_.*\.xlsx$'
python scripts/golden/compare.py --base pc.json --after android.json \
  --ignore '^(file|meta)' --ignore '\.method$'
```

增量成本：装包 28s + 解析 5s + 导出 138s + 拉取快照 6s ≈ **3 分钟/轮**，双跑约 6 分钟。
