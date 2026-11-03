# Changelog

本项目所有显著变更均记录于此文件，格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 工程（桌面端 · T10 收窄为适配器 + 组装根，**无用户可见变化**）
- 桌面端从「实现」退化为「适配 + 装配」：`logic/PhotoMgmCoreImpl.kt`（743 行）删除，
  `desktop/core` 主源码 **2660 → 1814 行**。整条流水线（解析 → 扫描 → 粗筛 → 派生去重 → EXIF →
  GPS 分组 → 分类 → 视图重算 → 台账 → 导出计划）现在都在 `shared/app`，与安卓共用同一份
  - `DesktopCore`（253 行）：组装根 + 共享门面实现。它只决定三件事 —— 长任务在哪跑（`Executor` +
    显式 `OperationHandle`）、状态发到哪（一路 `StateFlow` 到 `close()`）、关闭时释放什么（线程池）
  - 新增 7 个端口适配器 `adapter/Pc*`：`SettingsStorage` / `LogTextStorage` / `FileBytesReader` /
    `ImageEncoder` / `ShellLauncher` / `PhotoIdSource` / `ExportExecutor`
  - `desktop/core/api/` 删除，UI/CLI 直接依赖 `app.api.*`。**没有做 typealias 垫片** —— 让同一批类型
    长期有两个名字，正是这轮重构要消灭的那类重复
- 导出**计划**不再由桌面执行器自己算：`PcFolderExporter.buildPlan` 整段删除，改用
  `shared/app/export/ExportPlanner.build`；`PcFolderExporter` → `PcExportExecutor`，只负责
  「把计划里的相对路径写到磁盘上」+ 台账 + 清单 + 日志
- 窗口几何的落点：`Settings.window` 已不在跨端契约上（T09 的减法），改由 `PcSettingsStorage` 自己保管，
  `settings.json` 的 `window` 键**格式不变、老配置照读**。这里有个不会报错只会"下次启动窗口回默认位置"的坑：
  `settings.json` 是一次性整体重写，而 `updateSettings` 每次都会调 `save(settings)` —— 那份 settings 里
  没有 window。**已做反证**：把 `write(settings, window)` 改成 `write(settings, null)`，新增的
  `WindowMemoryTest` 立刻变红
- 断点续传的校验强度不再挂在 `ExportRequest` 上（安卓没有 manifest 续传），改为桌面导出执行器的
  **取值函数**：UI 在设置页改了立刻生效，不用重启
- 顺手收掉仓库最后两处 `MessageDigest.getInstance("SHA-256")`（复制循环、照片 id 分配）：
  `domain.hash.Sha256` 补 `digester()`（边读边写边算 + 幂等 `finish()`），不再让同一套算法留四份拷贝。
  `PhotoIdAllocator` 取的 id **位模式与原先逐位相同** —— id 一变，用户上一轮的人工调整会整体错位
- UI/CLI 侧 66 处 import 机械替换 + 五处不可避免的签名适配（`settings.window`→`core.windowState`、
  `readLogFile(Path)`→`readLogText(String)`、`ExportRequest(outputDir: Path)`→`(target: String)`、
  `verifyMode` 改走 `core.setVerifyMode`、`ExportResult` 路径字段 `Path`→`String`）。
  除这五处，`desktop/ui` 的 diff 里只有 import 行，没有顺手优化
  - `affects:` 无（导出产物、界面、设置文件格式、`export.log`/manifest 格式全部不变；老配置与老产物照常可用）
  - `verify:` 桌面黄金样本 **0 差异**（72 行 / 504 单元格 / 50 嵌图，`--ignore '^meta'`）；`export.log`
    62 行全为合法 JSON（start 1 + copy 60 + end 1）；清单 60 条 SHA-256 用 Python `hashlib` **独立复算 60/60 一致**；
    自检「产物照片 60 张 / 目标 60 张 ✔」；`desktop:core` **49 条**（44 → 49）、`ui` 123、`shared:app` 91、
    `shared:domain` 71、`shared:algorithm` 335、安卓 `app` 58 / `data` 26（debug 变体；release 56/20 同样全绿），
    失败 0；三条静态门禁 0 问题（136 / 59 / 77 文件）；`:ui:createDistributable` 成功

### 工程（跨端共用 · T09 编排上移，**无用户可见变化**）
- 「一遍流水线」第一次有了唯一实现，位于 `shared/app`（25 文件 / 2389 行）：
  - `usecase/AnalysisPipeline`：解析日志 → 扫描 → 文件名日期粗筛 → 派生去重 → 分配稳定 id → EXIF 精读 → GPS 分组 → 分类 → 发布视图。三步顺序被明确写进注释并各有用例：id 必须早于 EXIF/分类、派生去重必须早于 id 分配、清空人工调整必须在解析之后
  - `usecase/AdjustUseCase`：移动 / 复制 / 台账标记 / 重置。返回值口径为「**真正改变了归属**的张数」而不是「传进来几张」——目标本来就是它的家时不再谎报"已移动 1 张"
  - `usecase/SettingsUseCase`：改巡查日期/源目录 → 既有结果失效并提示重新分析；改命名模板 → 立即重算；切三态 → **同步**重算（避免"快速连切 A→B→A"时晚到的任务覆盖新口径）
  - `usecase/ExportUseCase` + `export/ExportPlanner`：导出计划（文件夹编号、重名 `_2`、`99_未分类`、空事件不建文件夹、同一照片多事件各一份）上移；"怎么把字节写到磁盘"仍是平台执行器
  - `state/AppStateStore`：锁内组装、一次性发布。`publish` 在锁外调用会**直接抛异常**——这条纪律若只写在注释里，写错了只表现为 UI 偶发闪一帧半成品状态
  - **本步不切调用**：两端生产代码一行未改，新实现只被测试调用。T10（桌面）/ T11（安卓）才切换
- 契约里的平台概念被清了出去：`Settings` 不再带 `window`（窗口几何，安卓无意义）、`ExportRequest.outputDir: Path` 改为不透明 `target: String`、`ExportResult` 的四个路径字段同样改为字符串、`ResumeVerifyMode` 单独成文件且不再挂在请求上。**契约里出现 `window`/`Path`/`Uri` 即视为失败**
- 日志文本解码（UTF-8 优先 / 剥 BOM / 回落 GBK）上移为 `app/infra/decodeText`：两端各写一份的话，GBK 回落迟早只在一端生效，表现为"同一份日志 PC 能解析、安卓满屏问号"
- `heicCount` 判定收进 `algorithm/isHeicPhotoName`：HEIC 是"台账里有格子只写文件名"的唯一原因，两端该看到同一句解释（此前只有桌面端统计）
- 顺带修掉两处既有缺陷（要等 T10 切调用后才可见）：没有分类结果时提示语被吞（"未选择源目录，暂不扫描照片"原本传进去又丢掉）、重新分析清空源目录后旧照片视图残留
  - `affects:` 无（未切调用；导出产物、界面、设置文件格式全部不变）
  - `verify:` 桌面黄金样本 **0 差异**（72 行 / 504 单元格 / 50 嵌图，`--ignore '^meta'`），`export.log` 62 行全为合法 JSON；`shared:app` **91 条**（21 → 91，全部假端口、纯 JVM）、`shared:domain` 68、`core` 44、`ui` 123、安卓 `app` 58 / `data` 26，失败 0；三条静态门禁 0 问题（74 / 131 / 57 文件）

### 工程（跨端共用 · T07 视图派生上移，**无用户可见变化**）
- 「人工调整后的有效归属」这套规则第一次有了**唯一实现**，位于 `shared/domain`：
  - `view/ViewDerivation`：`effectiveMap`（移动 + 复制合并）、`expandedEventMap`（三态展开）、`effectiveUnmatched`（未匹配池）、`ownersOf`。这三段此前在 PC 与安卓各有一份逐行抄写，注释互相写着"与另一端一致"但没有任何机制保证
  - `adjust/ManualAdjustment`：`move` / `copy` / `mark` / `reset` 的纯状态变换，连同「真正变更了几张」「实际新增了几份复制」两个计数。重复点击、目标即原事件、未知 id 三类边界第一次被单独断言
  - `ledger/LedgerProjection`：台账行 + 照片列标签（`文件夹名/文件名`）。照片列前缀必须与 ZIP 内路径同源，这条跨端口径现在只有一处定义
  - `view/PhotoOrder`：照片先后口径（拍摄时间 → 文件名）。**本次不接线**——两端目前都没有显式排序，事件内顺序由 `Files.walk` / MediaStore 查询顺序决定，属未定义行为；直接接上去会让黄金样本基线集体移动，无法区分"修好了"和"改坏了"。留 T12 单独提交并重采两端基线
  - **本步不切调用**：生产仍走两端原有实现，新实现只被测试调用。等 T12 一次性切换
  - `affects:` 无（未切调用；导出产物、界面、设置文件格式全部不变）
  - `verify:` 桌面黄金样本 **0 差异**（72 行 / 504 单元格 / 50 嵌图，`--ignore '^meta'`）；`shared:domain` 67 条（+50）、`core` 44 条（+4）、安卓 `app` 58 条（+6）全绿；**双跑对比** PC 侧 400 轮随机组合（固定种子）+ 安卓侧 6 条真实路径，逐字段相等；另有两条例证测试证明"若把 `passthrough` 判据写错，对比会红"。安卓黄金样本待设备接入后补跑

### 工程（安卓 · T06 适配层归位，**无用户可见变化**）
- 三处平台职责从 `:app` 挪进 `:data`，`:app` 往「UI + 编排」再近一步（T08/T09 的前提）：
  - `ImageUtils.kt` → `data/image/BitmapImageEncoder.kt`：解码 / 4:3 裁剪 / 400×300 / JPEG50 的逻辑**一字未改**，只是换了模块。顺带删掉 `TARGET_WIDTH`/`TARGET_HEIGHT`/`JPEG_QUALITY` 三个转发别名（真值只有 `MediaSpec` 一处，别名只为"不动调用点"而存在，而调用点全仓库只有一处）
  - `SettingsStore` 的物理存储 → `data/storage/PrefsSettingsStorage.kt`：prefs 文件名、key、值类型、人工移动与台账标记的字符串编码、日志旧 key 迁移。JSON 编解码与缓存版本位判定仍留 `:app`——判据是"换掉存储介质时哪部分要跟着改"
  - 「打开导出台账」→ `data/shell/UriShellLauncher.kt`：FileProvider + ACTION_VIEW + chooser，结果从 Boolean 改成三态（`STARTED` / `URI_UNAVAILABLE` / `NO_VIEWER_APP`），三种情况给用户的提示不同。**只持 `applicationContext`、不持 Activity**（AR3），启动一律带 `NEW_TASK`
  - `affects:` 无（导出产物、界面、设置文件格式、prefs key 全部不变；老用户升级后设置与人工标记照常读出）
  - `verify:` 桌面黄金样本 0 差异（72 行 / 504 单元格 / 50 嵌图）；`:data` 26 条、`:app` 52 条、桌面 5 组共 536 条全绿；`SettingsStoreLogsMigration` / `SettingsStoreZoneMigration` / `BitmapImageEncoderOwnership` **断言一行未改**即通过；安卓黄金样本待设备接入后补跑

### 修复（PC 界面 · T04 派生副本角标漏标）
- **桌面端缩略图的「派生」白点漏标了两类加工副本**：`isDerived` 只在 PC 侧用私有函数判定，那份实现只认 4 个后缀（`_original`/`_compressed`/`_edited`/`_modified`），而核心 `DerivedNames` 认 8 个（另有 `_optimized`/`_resized`/`_缩略`/`_压缩`/`_原图`）。同一个文件因此出现「**核心判派生、界面判不派生**」的相反结论
  - 用户可见变化：3 张照片右上角的白色圆点从**无变有** —— `IMG_20260814_134015_optimized.jpg`（`19_1340 检查K533+000交安设施…`）、`IMG_20260814_155015_缩略.jpg`（`23_发现摩托车违规上高速…`）、`IMG_20260814_161015_resized.jpg`（`24_巡查K540+000至K542+000…`）。它们都是所在事件里唯一的照片，所以表现为「主图上带派生点」——**这是对的**：白点描述的是文件名属性（这是副本），不是它在组内的身份
  - 真实素材（`/sdcard/Pictures/pmgolden-real`，111 个文件）**0 张受影响**：其中的派生文件全是 `_original`，新旧判定一致
  - 修复方式：删掉 PC 的私有拷贝，改调共享层公开门面 `isDerivedPhotoName`（清单仍只有 `DerivedNames` 一处）。**不做**"两端各自保留"——那等于承认一个文件可以有两个相反属性
  - 方向性保证：新增 `DerivedNamesTest` 用**超集断言**锁死「旧实现判派生的每一处，新实现也必须判」——即角标**只会从无变有，绝不会从有变无**。这比"变化比例 <5%"更硬：比例随素材变，方向不变
- **嵌图规格常量收敛**：台账嵌图的 `400×300 / JPEG 50%` 原先在 PC 与安卓各写一份字面量，注释互相声称"与另一端一致"但无机制保证（改一边不会报错）。现由 `shared/algorithm` 的 `MediaSpec` 单点定义，两端引用；`XlsxWriter` 的排版尺寸（3.81cm / EMU）**刻意不并入**——那是显示大小，与源分辨率无换算关系
- 删掉 `derivedSuffix()`（全仓 0 调用，返回的 `"派生"` 文案从未接入 UI）。留着它只会让人以为界面上有文字角标，而实际只有一个 5dp 白点
- 订正 `AppState.isDerived` 的注释：原写"UI 可据此显示**无水印备份**角标"，与实现不符，且已导致一次判断走偏。白点是**派生副本**标记，不专指 `_original`
  - `affects:` `desktop/ui` 缩略图角标（5dp 白点）；黄金样本不受影响（`isDerived` 不落盘，标尺测不到）
  - `verify:` 跑 `:shared:algorithm:test --tests "*DerivedNamesTest*"`（7 条，含"旧实现是子集"方向断言）；打开合成样本看 `19_`/`23_`/`24_` 三个事件各有一张缩略图带白点

### 工程（跨端共用 · T05 收尾，**无用户可见变化**）
- 手写 JSON、取消信号、日志落盘防抖三处上移到 `shared/app/infra`，两端各留薄适配器：
  - `Json`（279 行）：PC 的设置文件 / 导出清单 / 导出日志共用同一份实现，正文逐字未改，只换 package 与可见性（`internal` 跨不了模块）
  - `Cancellation`：两端取消信号统一为 JDK `CancellationException`——它正是协程取消在 JVM 上的实际类型，安卓侧零改动即被同一套判定覆盖。桌面删掉私有 `CancellationExceptionMarker`，改 `checkCancelled { }`；此前桌面/安卓都可能被 `runCatching` 或 `catch (Exception)` 静默吞掉取消（安卓 2026-10-31 踩过，桌面只是恰好没触发）
  - `DebouncedTextStore`：只保留策略（400ms 窗口、连打合并、`flush` / `cancelPending` 的顺序），落盘与"多久后执行"全走端口，共享层不含线程、协程、文件、SharedPreferences；两端从各 ~75 行降到 ~40 行适配器
  - `affects:` 无（导出产物、界面、设置文件格式均不变）
  - `verify:` 桌面黄金样本 0 差异（72 行 / 504 单元格 / 50 嵌图）；`shared:app` 21 条、`core` 40 条、安卓 `app` 56 条全绿；安卓 `LogsTextStoreTest` / `SetLogsTextWiringTest` **一行未改**即通过

### 变更（跨端共用 · T03 纯函数上移，含一处用户可见行为变化）
- **PC 端首次能识别 `yyyy-MM-dd`（短横）格式的 EXIF 拍摄时间**：此前 PC 只认 `yyyy:MM:dd`，而安卓两种都认，导致同一张照片"手机上归了事件、电脑上归不了"。真实素材里 8 张短横 EXIF 照片因此在 PC 上全部落进 `99_未分类`
  - 用户可见变化：这 8 张从 `99_未分类` 归位到 `06_K519+500` / `12_十点五十分` / `21_检查高边坡K537+100` 三个事件，台账对应行多出照片（`99_未分类` 10 → 2，剩下的是 2 张本来就没有 EXIF 的照片）
  - 其中 `IMG_20260814_105000_original` / `_144500_original` 两张**不再单独导出**：它们原先因为"没有拍摄时间"逃过了派生去重，现在有了时间，被正确识别为主图的复制副本并合并。**不是丢照片**
  - 语义统一：EXIF 时间戳一律按**业务时区**（北京时间）解释成绝对时刻，尾部的毫秒与 `+08:00` 只忽略、不做偏移换算——这条与两端原有行为一致，现已写进单测锁死
  - 顺带补上安卓缺失的一格能力：带毫秒 / 时区尾巴的时间戳（`…08:30:00.123`、`…+08:00`）原来在安卓上解析失败，现在两端都能解析。现有素材没有这类照片，故安卓侧可观测行为不变
- EXIF 时间解析与 SHA-256 收敛为**一份实现**（`shared/domain` 的 `ExifTime` / `Sha256`），安卓删除私有 `ExifTimeParser`、`FileHasher` 与桌面端各自保留的 10 行平台适配器只负责"怎么打开文件"
- 黄金样本口径补充：**台账快照不能按图片序号比**。新增嵌图会让后续图片编号整体后移，按索引比会报出大量假差异；判断口径与脚本用法见 `docs/golden/README.md` §4.1
  - `affects:` PC 导出目录（8 张照片的归属、`99_未分类` 与三个事件目录）与台账行；安卓侧无可观测变化
  - `verify:` `python scripts/golden/compare.py --base docs/golden/desktop-baseline.json --after <新快照> --ignore '^meta'` 应为 0 差异；真机 `run_android_real.sh` 双跑 ZIP 60 条目 0 差异

### 修复（去重 · 两端共用）
- **连拍间隔 <1s 时真实照片被当作重复丢弃**：`ExifContentDedupStrategy.isSame` 的 GPS 有效分支只有「时间 ±1s + 距离 ≤10m」两条判据，二者都只能证明"同一地点同一时刻"，无法区分"同一张的两个版本"与"两张不同照片"。同一个人在同地连拍，两张不同照片必然同时满足两条 → 后者被当作前者的副本丢弃，**且不可恢复**（ZIP 里没有，原件也不在输出里）
  - 真实素材实测（10月24日 K152+200M 过车涵洞，4 连拍）：`…153741122` 与 `…153742079` 相差 **957ms / 2.74m**、**内容完全不同**（sha256 不同、差 37 KB），被并为一组 → 110 张素材只导出 **54** 张
  - 修复：`groupDupContent` 改为「**锚点 + 派生就近附着**」——不带派生后缀的照片是锚点，**锚点之间永不合并**；派生挂到「满足判重且文件名序号差最小」的锚点上。`isSame` / `DedupConfig` / `expandForKeepMode` 一行未改
  - 有意接受的代价（均朝"多留一张"）：两张都不带后缀的**真重复**（用户手动复制的副本）不再合并；序号差 >1000 的派生不附着。**宁可漏并，不可误删**
  - 验证：真机真实素材 **54 → 55 张**、缺失主图归零、**55/55 与素材逐字节相同**、55 对副本全部正确合并；PC 与 Android 黄金样本（合成素材）**零回归**（56 张照片 sha256 全等、台账 72 行/504 单元格/嵌图数全等）；新增 1 条用真实时间戳与真实 GPS 的 regression 测试
  - ★ 这次是**真实素材第一次跑端到端**才暴露：合成素材刻意让同事件照片间隔 30s，"连拍"这一最常见场景从未被覆盖；同类问题 2026-10-24 修过一次（链式传递），但当时的测试用例用 `+1000/+2000` 恰好卡在容差外，等于"用自己的假设验证自己"

### 修复（Android）
- **照片定位信息全部丢失**：`AndroidManifest.xml` 从未声明 `ACCESS_MEDIA_LOCATION`，Android 10+ 的 MediaProvider/FUSE 在 App 读取媒体时把 EXIF GPS 值区清零（标签保留、长度不变、值变 `nan`），表现为"照片本来没有定位"——`Photo.latitude/longitude` 恒 `null`、导出照片无位置、去重从"时间 + GPS 距离"退化为"时间 + 派生后缀 + 文件名序号"
  - 修复：声明权限 + 运行时与照片权限**一并申请**（`MediaPermissions.kt` 新增，纯函数按 29/33 两个分水岭裁剪清单）
  - 未授权时明确提示降级口径（Snackbar + 设置页「重复照片」区红条），不再静默降级
  - 新增缓存 GPS 语义版本位 `cache_geo_version`：旧版本写下的 `latitude=null` 缓存一次性作废，否则老用户升级后"从缓存恢复"不会重读 EXIF，修复形同无效
  - 真机实测（vivo V2232A / Android 16）：`cache_photos` 有 GPS **0/65 → 59/65**；导出 ZIP 照片 **56/56 与素材逐字节相同**（修复前 52 张各差 14 字节，正是被清零的 GPS 值区）
  - ★ **已知遗留**：安卓黄金基线需按新产物重建（52 条照片 sha256 已变）；未授权时的剥离呈"部分生效"（实测仅前 9 张丢 GPS），已登记待复现——去重判据不能假设 GPS "全有或全无"

### 工程
- 仓库布局重组：Android 端收进 `android/`（独立 Gradle 工程，`:app` + `:data`），Windows 端保持 `desktop/` 不变，共用核心 `algorithm/` 留在根目录供两端以 `../algorithm` 挂载 —— 一份源码、无副本（详见 `docs/REPO-LAYOUT.md`）
- 仓库根不再有 `settings.gradle.kts`：Android 构建须 `cd android`（或 CI 用 `gradle -p android`）；桌面端构建方式不变，**PC 侧代码零改动**
- 签名目录随工程根迁到 `android/keystore/`，并保留对旧位置（仓库根 `keystore/`）的兼容探测，老开发机无需搬文件
- 三个静态门禁脚本默认扫描根改为 `android/app`、`algorithm`、`android/data`
- 新增 `build-desktop.yml`：`desktop-v*` 标签/手动派发时在 windows-latest 出便携包（MSI 仍因依赖 WiX 留在打包机产出）

## [desktop-v2.0.0] - 2026-11-02

Windows 桌面版（Compose Multiplatform）首发。与 Android 1.3.2 为**独立版本线**，共用 `algorithm` 台账/分类逻辑。

### 新增
- 缩略图真实加载：`ui/image/Thumbnails.kt` 按目标边长 2 的幂做 `ImageIO` 降采样 + 字节预算 LRU（96MB）+ 3 路并发，1000 张网格不 OOM；缓存命中同步返回，不闪白块
- 事件弹窗可拖动：`SheetHost` 交付 `dragHandle`，`awaitEachGesture` 在 Initial pass 超过 touchSlop 才消费，位移夹在窗口内（原实现只能固定在居中位置）
- 巡查日期改用浮层选择：新增 `dialog/DatePickerLayer.kt`，按 `boundsInWindow` 定位、下方空间不足自动上翻；不再就地展开把整页推挤 240dp。Esc 分层：日历 → 弹窗 → 勾选
- 照片多选：单选 / Shift+点连选（从锚点连续区间）/ 右上角红勾切换三套行为并存；事件内照片按拍摄时间升序（无时间排最后，同刻按 id）
- 事件弹窗右侧新增「巡查日志内容」区，显示该事件原始日志（含续行合并），侧栏 240 → 300
- 顶栏导出按钮始终可点（仅忙时禁用），条件不足弹提示文案而非灰掉；`Ctrl+E` 走同一判定

### 变更
- 导出目录名由 `PhotoMgm_export_20261101_203200` 改为 `1101_203200`（`MMdd_HHmmss`，冲突续号 `_2/_3` 保留）；续传识别改正则 `\d{4}_\d{6}(_\d+)?`
- 台账排版：数据行行高恒等于照片高（81pt），照片下方不再写文字，B 列只显示 `hh:mm`（值仍是完整日期序列号，跨零点排序正确，表头「日期」→「时间」），全表水平+垂直居中并自动换行
- 事件内照片间隔判定阈值 240s → **60s**（`NEAR_GAP_MS` 独立常量，提示文案写实际秒数）；`backfillMs` 仍 120s

### 修复
- 缩略图格只画 id 取色占位块、从未加载图片
- `pointerInput(Unit)` 闭包捕获初始 state，导致 Ctrl 多选最多只到两张：改为 `rememberUpdatedState` 转发 + 纯函数化 `clickPhotoSelection`
- 点击判定错乱（两层根因）：①`pmPress` 靠 `isConsumed` 猜区域，而 `clickable` 按下阶段不消费，点红勾同时触发切换与重置；②`Ctrl` 分支与单选挂在同一次 press 上。改为几何判定 `inTopEndZone`（与格子严格互补）+ 红勾 `awaitEachGesture`（down 即 consume、抬起须仍在区内）+ 删除 `Ctrl` 分支，「切换这一张、保留其它」收归红勾唯一入口
- 台账照片格尺寸与照片不符：F/G 列宽由手写 22 字符（≈4.21cm）改为由照片宽度折算的 20 字符（Excel 读回 108 磅 = 3.81cm，与照片同宽，两张照片左右严丝合缝）
- 台账行高在非 100% 缩放的 Excel 里被缩成 0.8 倍（81pt → 64.8pt），照片比行高出一截、上下压行：worksheet 补 `<sheetViews>`（Excel 对缺视图声明的第三方文件会把 `ht` 当 96 DPI 像素重新解释）；实测补后行距 = 81pt = 照片高 2.86cm
- 照片尺寸/行高/列宽统一由同一组 EMU 常量换算（`XlsxWriter.PIC_CX_EMU / PIC_CY_EMU`），不再三处各写魔数；A~E 列宽与其余格式未动

### 已知限制
- 台账行高固定后，日志超约 150 汉字在 E 列会被裁切；完整原文见 `export.log` 与事件文件夹（换取"行行等高、照片与行对齐"便于打印装订）

## [1.3.2] - 2026-10-24

### 修复
- 三态切换快速连切竞态：台账行与模式改为同一事务内同步重算（原异步重建时旧协程可能晚到覆盖，导致底栏台账与当前模式错配）；`buildLedger` 增加任务句柄取消 + `isActive` 防晚到覆盖
- 断点续传状态文件解析脆弱：手写正则 `"completed":\s*\[(.*?)\]` 在路径含 `]`/`,`/`"` 等字符时提前截断或拆碎 → 改为自包含标准 JSON 编解码（完整转义），旧格式（无 extra 字段）兼容保留，新增特殊字符路径回归测试

### 优化
- 判重分组缓存：`dupGroupsOf` 结果按照片集合引用缓存，三态切换/预览/导出不再每次全量 O(n²) 重算
- 派生后缀名单收敛为单一事实来源 `DerivedNames`（`PhotoSource.dedupDerived` 与 `Dedup.kt` 共用），消除三处名单漂移
- 「仅保留不带水印」按派生优先级选 `_original`（原实现取列表顺序第一个派生，同组含 `_original` 与 `_compressed` 时可能选错压缩版）
- 删除粗筛 TTL 缓存死代码（30 分钟版 `coarseCandidates` 与 `PhotoCache`），生产统一强制实时入口，消除双实现漂移
- 分类完成自动生成台账行（解析日志 / 手动分类 / 恢复最初分类后），底栏「台账 N 行」不再等导出才回填
- 设置提示语修正：「两者都保留」不再误称"已合并"
- 台账准备失败记录异常日志（原静默吞错，真机无法诊断）

## [1.3.1] - 2026-10-24

### 修复
- `dedupDerived` 误合并水印相机同序号 `_original` 照片：主图 `IMG_…661.jpg` 与 `IMG_…661_original.jpg` 去派生后缀后基名相同，旧逻辑将独立原图当副本剔除（真机"两者都保留"下 10:10/10:22/15:59 事件各显示 3 张、应为 4 张）
- 修复方案（方案B' 后缀语义 + 内容哈希双层判定）：同基名 + SHA-256 内容哈希一致 → 合并复制副本；哈希不同 + `_original` → 保留（水印相机同序号原图，独立照片）；哈希不同 + 其他派生 → 合并；哈希读取失败 → 保守保留。新增 `data/FileHasher`（SHA-256 文件哈希，读失败返回 null 不抛异常）
- 真实数据回归：110 jpg + 10-24 巡查日志端到端全通过（派生去重 110 张 / 0 合并，三态导出 仅带水印 54 / 仅不带水印 54 / 都保留 110）

## [1.3.0] - 2026-10-24

### 新增
- 照片三态去重：水印相机双份（主图 + `_original`）与手机自带相机单份并存场景，支持「仅保留带水印（默认）/ 仅保留不带水印 / 两者都保留」三选一；单份照片三态一律保留，未匹配照片不受三态影响恒全量
- B4 GPS 降级判重：真机 ROM 相册清零 GPS 时（诊断证据：`GPSLat=0/1`、ref 空、latLong=null），按 时间≤1s + 任一方含派生后缀 + 文件名最后数字块差≤1000 判重合并
- 导出台账增强：断点续传（内容未变化跳过）、导出失败明细清单、导出完成一键打开台账、结束时间列（跨日按实际日期、单点留空）、日期列 Excel 真实日期类型、照片列文件夹前缀（与 ZIP 内路径一致）、压缩包文件夹时间升序 + 序号前缀（`01_`…，未匹配归 `99_未分类`）
- GitHub Actions 构建镜像切换：本地阿里云 / 腾讯云镜像，CI 自动切 `google()` / `mavenCentral()` 原生源，解决海外 runner 超时

## [1.0.0] - 2026-09-08

### 新增
- 日志解析：按巡查日期解析日志文本（时间/地点含方向/事件类型/人员/车牌），黄金样本全量通过
- GPS 分组：50 米内合并为一组，离群拆簇（>1.5×50m 可配置），跨日按完整时间戳
- 事件分类：按日志时间窗口自动归类照片（首条日志起点归属/后续终点归属），支持人工移动兜底与干预提示
- 台账生成：每事件一行（日期/地点/日志内容/照片首张+末张），导出 Excel（OOXML 手写，零第三方依赖）
- 压缩包导出：按「事件类型」或「日期+事件类型+日志内容」建文件夹，照片 + 台账 Excel 打包 ZIP
- 设置：源目录（SAF 系统选择器）/ 输出目录 / 巡查日期（日历）/ 命名模板，变更自动触发粗筛+精读
- 预览页：事件卡片 + 照片管理弹窗（多选批量移动/移除/台账标记）+ 未匹配池

### 变更
- 导出嵌入照片 4:3 比例（宽 3.8cm，400×300px JPEG50）
- APK 启用 R8 混淆与资源裁剪（约 48MB → 3.7MB）
- 移除拍照 OCR 入口（仅保留粘贴导入）
- 移除「导出台账」独立按钮，统一由「导出压缩包」交付照片+台账

### 修复
- Android 分区存储下通过 MediaStore content URI 读取照片
- 导出完成提示不再展示文件路径/目录
- 设置页日期弹窗尺寸按人机交互缩小
- 预览页搜索栏固定不随列表滚动

### 技术栈
- Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.10.2（国内镜像）
- Jetpack Compose Material3 / Coil / minSdk 26 / targetSdk 34
- 核心算法模块 `:algorithm` 纯 Kotlin JVM，零 Android 依赖，可移植 PC
