# 跨端重构执行计划（REFACTOR-EXECUTION-PLAN）

> 性质：**执行计划**（可落地、可验收、可回滚），不是方案论证。
> 上游依据：`docs/CROSSPLATFORM-ARCH.md`（共享层怎么切）+ `docs/PC-AUDIT-AND-ANDROID-REFACTOR.md`（PC 质量与安卓怎么长）。
> 本文件回答四个问题：**做什么、按什么顺序做、每步怎么证明没做坏、什么情况下必须停**。

---

## 0. 一页速览

| 项 | 值 |
| --- | --- |
| 任务数 | 14 个必做 + 1 个可选（默认不做） |
| 提交数 | 14 个（一任务一提交，均可单独回退） |
| 停止点 | 5 个里程碑（M1~M5），每个都是「可编译 / 可打包 / 行为可解释」的发布态 |
| 关键路径 | T01→T02→T03→T04→T05→T07→T08→T11→T13→T14（10 跳） |
| 代码量变化 | 共享层 2564 → ~3900 行（+~1300）；`desktop/core` 2927 → ~1200；`AppViewModel` 1165 → ~250；`PhotoMgmCoreImpl` 在桌面端消失 |
| 行为变更 | 仅 2 处，均在 T04 落地，必须写入 CHANGELOG「已知行为变更」 |
| UI 改动 | `desktop/ui` 与 `android/app/ui` **零逻辑改动**（T13 除外，见 P-3） |
| 阻塞项 | **无**（P-1 素材已解决：66 张合成照片 + 416 行日志已入库并可自校验） |

**一句话**：先造"证明没做坏"的标尺（T01），再把目录摆正（T02），然后把纯函数一层层搬上去（T03~T05），接着拆安卓的塌陷点（T06、T08），最后把编排收进共享层、让两端都变成薄壳（T09~T11）。

---

## 1. 目标

### 1.1 三条主目标（可度量）

| # | 目标 | 度量方式 | 达成任务 |
| --- | --- | --- | --- |
| G1 | **一套业务逻辑，两端共用一份源码** | 同一函数（`effectiveMap`/`expandedEventMap`/`effectiveUnmatched`/`dupGroups`/台账投影/派生判定/EXIF 时间/媒体规格/哈希）在仓库内**只有一处定义**，grep 计数 = 1 | T03~T05、T07、T09 |
| G2 | **UI 与平台层退化为薄壳** | `AppViewModel` ≤ 300 行；`desktop/core` 不再含 `PhotoMgmCoreImpl`；两端 UI 层零 `import shared.domain` / `shared.algorithm` | T08、T10、T11 |
| G3 | **跨端不再靠"人肉同步"** | 新增门禁 `check-shared-purity.py` 进 CI；结构断言覆盖 `shared/` 三模块挂载；任何一端改共享层，另一端构建即失败（而非静默漂移） | T14 |

### 1.2 非目标（本计划不解决）

- 不追求"零重复"：`WorkQueue`/`PhotoSearch`/`Layout`/缩略图/导出执行器**刻意**留在平台层（它们是"怎么显示/怎么写盘"，不是"是什么"）。
- 不改任何界面交互与视觉；不引入 KMP；不换 Compose/AGP/Kotlin 版本；不做性能优化。

---

## 2. 范围

### 2.1 In scope

| 区域 | 动作 |
| --- | --- |
| `algorithm/` 18 文件 2564 行 | 整体 `git mv` 到 `shared/algorithm/`，**内容零改动**（包名仍是 `algorithm`） |
| 新增 `shared/domain`（~500 行） | 视图派生、人工调整、台账投影、EXIF 时间、SHA-256、媒体规格、扩展名、派生命名 |
| 新增 `shared/app`（~800 行） | 门面 + 契约 + 6 个端口 + 4 个用例 + 状态存储 + 防抖/取消/JSON |
| `desktop/core` 17 文件 2927 行 | 收窄为「7 个适配器 + 1 个组装根 + 3 个导出执行器」（~1200 行） |
| `desktop/ui` + `cli` 39 文件 | **只改 import**（约 62 行），逻辑零改动 |
| `android/data` 4 文件 | 端口实现补齐（`ImageEncoder`/`SettingsStorage`/`LogTextStorage`/`UriShellLauncher`） |
| `android/app` 20 文件 5878 行 | `AppViewModel` 瘦身；契约化（`PhotoUiState`/`PhotoActions`）；删 `PhotoRepository`；`ui/` 目录重排（T13） |
| CI / scripts / docs | 新增共享层门禁、扩结构断言、更新 `REPO-LAYOUT.md`、CHANGELOG 标注影响面 |

### 2.2 Out of scope（明确不做，防止蔓延）

- 不统一导出形态（PC 文件夹 / Android ZIP 各自保留）——决策 D4。
- 不上移「分析结果缓存」（Android 独有）——决策 D3。
- 不新建 `:ui` 模块，不引入 Kotlin Multiplatform，不引入 `kotlinx.serialization`。
- 不改 `algorithm/` 的任何实现（只搬目录）；不改 UI 视觉与交互（T13 的拆分是纯机械动作，见 P-3）。
- 不动打包链（jpackage / AGP + R8）、不动版本号策略（除 CHANGELOG）。

### 2.3 与两份上游文档的对应

| 本计划 | 上游 |
| --- | --- |
| T01、T02 | `CROSSPLATFORM-ARCH` §6 P0 |
| T03~T05 | §6 P1（消灭 D8~D12） |
| T07、T09、T10、T11 | §6 P2（主体工程） |
| T12 | §6 P3 的 D2 项 |
| T06、T08、T13 | `PC-AUDIT-AND-ANDROID-REFACTOR` §7 A1、A2、A4 |
| T14 | §6 P4 + §7 A5 的门禁部分（模块拆分不做） |

---

## 3. 已锁定决策（你已接受建议）

| # | 决策项 | 锁定值 | 落地影响 | 落点 |
| --- | --- | --- | --- | --- |
| D1 | `algorithm/` 是否迁入 `shared/` | **迁**（`git mv`，内容零改） | 改 2 个 `settings.gradle.kts` + 3 处 `project(":algorithm")` 引用 + 1 处 buildDir 重定向 + CI 断言 | T02 |
| D2 | `copyPhotos`（一照多事件）是否同步给 Android | **同步** | 合并后 Android 天然获得能力；**UI 入口单列**（见 P-3） | T12 |
| D3 | Android 分析缓存是否上移 | **不上移** | 无改动 | — |
| D4 | 导出形态是否统一 | **不统一** | `ExportExecutor` 留平台；只上移"导出计划" | T09 |
| D5 | 派生后缀判定是否统一到 `DerivedNames` | **统一**（正则严格版） | PC 角标从 `contains` 宽松判定改为正则判定 → **行为变更**（T04 记录） | T04 |
| A1 | 是否引入 `PhotoActions` 接口 | **引入** | 4 个 UI 文件改签名（`vm:` → `actions:`） | T08 |
| A2 | `PreviewScreen`（1209 行 / 16 Composable）是否拆 | **拆**（21 文件 / 最大 ~250 行） | 纯机械拆分，逐 Composable 搬 | T13 |
| A3 | 是否新建 `:ui` 模块 | **暂不做** | 靠门禁保证规则，等契约稳定一轮再评估 | （不做） |

---

## 4. 任务拆解

图例：**依赖** = 前置任务；**产出** = 该任务结束时磁盘上多出来/改变了什么；**验证** 见 §7 命令索引。

### T01 · 黄金样本基线

| 项 | 内容 |
| --- | --- |
| 目标 | 造一把"证明没做坏"的标尺：改动前把两端的可观测产物钉成快照，后续每步比对 |
| 依赖 | **P-1 素材**（已按"合成"决策落地） |
| 改动 | 新增 `scripts/golden/`：`make_sample.py`（生成/校验素材，Pillow 写 EXIF）、`run_desktop.sh`（无头导出+拍照，每次全新 data 目录）、`snapshot_xlsx.py`（纯 `zipfile`+XML：单元格文本/样式号/行高/列宽/嵌图字节/锚点）、`snapshot_zip.py`（ZIP 条目+每条哈希）、`compare.py`（逐路径 diff）；基线 `docs/golden/desktop-baseline.json` + `docs/golden/README.md` |
| 实测产出 | 素材 **66 张照片**（8 组重复、6 个派生、8 张短横 EXIF、2 张无 EXIF、4 张未匹配、2 张跨日期、4 个误筛文件）+ **416 行日志**；台账快照 72 行 / 504 单元格 / 44 张嵌图 / 44 个锚点 |
| 验证 | ① 同版本连跑两次 → **0 差异**（基线不自抖）；② 反向实验：改 1 格 + 列宽改 19.86 + 行高改 64.8 + 删 1 图 → 准确报 **4 处**并指到 `rows[2].ht: 81 → 64.8`；③ 换最终脚本重跑 → 与入库基线全等 |
| 遗留 | **Android 侧基线（P-2）✅ 已完成（真机）**：`docs/golden/android-baseline.json` + `android-ledger-baseline.json` + 三张截图；V1/V2/V3 全通过，报告见 `docs/ANDROID-REAL-DEVICE-P2-REPORT.md`。**遗留 P0 缺陷**：缺 `ACCESS_MEDIA_LOCATION` 导致 GPS 全剥离，须在 T11 前修复 |
| 回滚 | 纯新增文件 |

### T02 · shared/ 骨架 + algorithm 迁移　→ **M1**

| 项 | 内容 |
| --- | --- |
| 目标 | 让"共享 = `shared/`"成为唯一规则；补齐 domain/app 两个空模块，为后续上移备好落点 |
| 依赖 | T01 |
| 改动 | ① `git mv algorithm shared/algorithm`（含 build 配置与 22 个测试，**内容零改动**）；② `desktop/settings.gradle.kts`、`android/settings.gradle.kts` 改为 `include(":shared:algorithm")` + `projectDir`；③ `android/app`、`android/data`、`desktop/core` 的 `project(":algorithm")` → `project(":shared:algorithm")`；④ `desktop/build.gradle.kts` 的 buildDir 重定向由 `path == ":algorithm"` 改为 `path.startsWith(":shared:")`，落 `desktop/build/external/`；⑤ 新建 `shared/domain`、`shared/app`（仅 `build.gradle.kts` + `package-info.kt` 占位），两端挂载；⑥ CI 结构断言、`docs/REPO-LAYOUT.md`、3 个 gate 脚本的扫描路径同步 |
| 产出 | `shared/{algorithm,domain,app}` 三模块；两端 `settings` 各 3 行挂载；CI 断言 4 条 |
| 验证 | §7 的 **L1 全绿 + L2 全绿 + L4 编译通过**；关键一条：`git status` 显示 `algorithm/` 为纯 rename（`git log --stat -1` 里无内容 diff） |
| 回滚 | 单提交回滚（无内容变化，回滚无风险） |
| 风险 | Gradle 项目路径改名会连带影响 `check-timezone.py` 等脚本里写死的 `algorithm/` 路径 → 与 ⑥ 同提交改掉，否则门禁静默变成"扫 0 文件也通过" |

### T03 · 纯函数上移 A：EXIF 时间 / SHA-256 / 扩展名

| 项 | 内容 |
| --- | --- |
| 目标 | 消灭 D9（EXIF 时间两版）、D10（SHA-256 两份）、扩展名集合两处 |
| 依赖 | T02 |
| 改动 | 新增 `shared/domain/exif/ExifTime.kt`（**取并集**：`yyyy:MM:dd` 与 `yyyy-MM-dd` 都认）、`hash/Sha256.kt`、`spec/ImageExtensions.kt`；PC 侧 `PcExifReader`/`PcFileHasher`/`PcPhotoSourceProvider` 改为委派；Android 侧删 `ExifTimeParser.kt`/`FileHasher.kt`，`ExifPhotoReader` 改为委派；补 3 组纯 JVM 单测（含"冒号/短横混合""带时区偏移""无 EXIF 返回 null"）。**★ 只搬"字符串 → epoch"的纯函数，不得把 `ExifPhotoReader.readAll` 拆成两次 `ExifInterface` 构造**（P0 修复后时间与 GPS 仍靠一次构造同时读出，拆开会退回 203ms 并放大 GPS 读取失败面） |
| 产出 | 3 个共享文件 + 3 个测试；删除 2 个安卓文件；PC 3 个文件减重 |
| 验证 | L2（新增测试 + 两端全量）；黄金样本 **必须全等**（D9 是"多认一种格式"，正常素材上不应产生差异；若产生差异 → 说明素材里有 `-` 分隔 EXIF，属预期变更，记入 CHANGELOG 并保留） |
| 回滚 | 单提交；删除的两个安卓文件在回滚时恢复 |
| 风险 | `java.security.MessageDigest` 在 Android minSdk 26 可用，但 `File` API 在 `java.nio` 外无差异——本项只搬算法不搬 IO，安全 |

### T04 · 纯函数上移 B：媒体规格 / 派生命名　（**含 2 处行为变更**）

| 项 | 内容 |
| --- | --- |
| 目标 | 消灭 D8（400×300/JPEG50 两套常量）、D12（派生后缀判定两份且口径不同） |
| 依赖 | T03 |
| 改动 | 新增 `shared/domain/spec/MediaSpec.kt`（唯一常量：400/300/quality=50/4:3/`PT_TO_EMU`）、`naming/DerivedName.kt`（搬 `algorithm.DerivedNames` 正则版并作为唯一实现）；`PcImageEmbedder`/Android `ImageUtils` 改引常量；`PhotoMgmCoreImpl:605` 的 `contains` 判定改为 `DerivedName.isDerived`；`XlsxWriter` 的 `PIC_CX/CY_EMU` 与 `DATA_ROW_H` 改引 `MediaSpec`（值不变，仅去重） |
| 产出 | 2 个共享文件 + 常量单点化（`grep -rn "400\|300" shared/` 仅 `MediaSpec` 命中） |
| 验证 | L2 全绿；**黄金样本预期有差异**：PC 角标判定变严格 + 术语统一。差异必须逐条列进 `CHANGELOG` 的「已知行为变更」，并附"哪些文件名从有角标变无角标"的清单（`compare.py --explain`） |
| 回滚 | 单提交；若角标变化面过大（>5% 文件）→ 回退本项、D5 改判为 B（各自保留），不阻塞后续 |
| 风险 | **R1 最高风险点**：这是全计划唯一"改语义"的任务。纪律：先写测试覆盖 `_original`/`_compressed`/`_1`/双后缀 4 类文件名，再切调用；切换前后各跑一次黄金样本 |

### T05 · 纯函数上移 C：防抖落盘 / 取消信号 / JSON　→ **M2**

| 项 | 内容 |
| --- | --- |
| 目标 | 消灭 D11（防抖两版），统一取消语义（PC `Operation` vs Android `Cancellable`），手写 JSON 上移 |
| 依赖 | T04 |
| 改动 | 新增 `shared/app/infra/DebouncedTextStore.kt`（**只保留策略**：延迟时长、合并写、`flush()`；持久化动作由 `LogTextStorage` 端口回调）、`Cancellation.kt`（合并两端语义，**保留安卓"取消不被吞"的语义**）、`Json.kt`（搬 `desktop/core/infra/Json.kt` 279 行，零改动）；PC `LogsTextStore`、Android `LogsTextStore` 改为薄适配；`desktop/core` 的 `SettingsRepository` 改调共享 JSON |
| 产出 | 3 个共享文件 + 2 个测试；两端各 1 个文件从 ~80 行降到 ~30 行 |
| 验证 | L2 全绿，**必须包含这两个测试**：`DebouncedTextStoreTest`（"连续 5 次 set 只落盘 1 次""close 前 flush 不丢最后一次"）、`CancellationTest`（"取消后 `runCatching` 不吞 `CancellationException`"）；黄金样本全等 |
| 回滚 | 单提交 |
| 风险 | 若把安卓的 `runCatchingCancellable` 语义搬成 PC 的 `Operation`（可能吞取消）→ 导出中断会静默继续。纪律：**以安卓版为语义基准**，PC 侧跟随 |

### T06 · Android 适配层归位（A1）

| 项 | 内容 |
| --- | --- |
| 目标 | 把散在 `:app` 里的平台职责挪进 `:data`，让 `:app` 只剩 UI + 编排 |
| 依赖 | T03、T04（常量与 EXIF 已共享） |
| 改动 | `ImageUtils.kt` → `android/data/.../image/BitmapImageEncoder.kt`（实现即将定义的 `ImageEncoder` 端口形态，本步先只搬位置）；`SettingsStore` 的介质段（prefs 读写）→ `data/storage/PrefsSettingsStorage.kt`，编解码留 `:app`；新增 `data/shell/UriShellLauncher.kt`（端口只接 `ApplicationContext`，**不持 Activity**）；`:app` 侧对应改 import |
| 产出 | `android/data` 4 → 7 文件；`:app` 减少 3 个平台类 |
| 验证 | L2：`ImageUtilsOwnershipTest`、`SettingsStoreLogsMigrationTest`、`SettingsStoreZoneMigrationTest` **保持绿**（这三个测试就是本步的护栏）；黄金样本全等 |
| 回滚 | 单提交 |
| 风险 | AR3：SAF 授权泄漏 `Activity` → 端口签名只接受 `Context`（`ApplicationContext`），`Uri` 持久化授权在 `:app` 侧申请 |

### T07 · shared/domain：视图派生 + 双跑对比（P2①）

| 项 | 内容 |
| --- | --- |
| 目标 | 把"人工调整后的有效归属"这套业务规则搬成唯一实现，先用双跑证明等价 |
| 依赖 | T05 |
| 改动 | 新增 `shared/domain/view/ViewDerivation.kt`（`effectiveMap`/`expandedEventMap`/`effectiveUnmatched`/`dupGroups`）、`adjust/ManualAdjustment.kt`（move/copy/mark/reset 的纯状态变换）、`ledger/LedgerProjection.kt`（`buildRows` + `labelOf`）、`view/PhotoOrder.kt`（照片先后口径：时间→文件名，防止 PC/安卓排序漂移）；**先写"双跑对比测试"**：同一组输入分别喂给 PC 现行实现与新 domain 实现，断言输出逐字段相等 |
| 产出 | 4 个共享文件 + 4 个对比测试；两端旧实现**暂不删除**（双跑期） |
| 验证 | ① 对比测试全绿（这是本任务的唯一验收证据）；② 黄金样本全等（未切调用，必须零差异） |
| 回滚 | 单提交；双跑期旧实现仍在，回滚等于删除新文件 |
| 风险 | R1 的第二次出现：PC 的 `effectiveMap` 支持"复制到多事件"、安卓不支持 → 双跑对比时**安卓侧必须允许差异**，把 `copyMap` 作为可空参数处理：PC 传非空、安卓传空（T12 后再统一为都传） |

### T08 · Android 契约层（A2，**最关键一步**）

| 项 | 内容 |
| --- | --- |
| 目标 | 一次消掉三个塌陷点：UI 依赖具体类、UI 调领域函数、UI 直接 import 领域模型 |
| 依赖 | T07 |
| 改动 | 新增 `android/app/.../ui/PhotoUiState.kt`（纯投影快照：`eventPhotoIds`/`unmatchedPhotoIds`/`ledgerRows`/`canExport`/`progress`…）与 `ui/PhotoActions.kt`（接口：`parseLogs`/`buildLedger`/`movePhotos`/`markLedgerPhoto`/`exportZip`/`cancel`…）；`AppViewModel` 实现 `PhotoActions` 并对外只暴露 `StateFlow<PhotoUiState>` + `actions`；UI 侧改 10 个文件的取数方式（`vm.effectiveUnmatched`→ `state.unmatchedPhotoIds` 等 5 处直接调用、`algorithm.model.*` 2 处），4 个文件签名 `vm: AppViewModel` → `actions: PhotoActions`；`AppViewModel.forTest(ports)` 工厂同步保留 |
| 产出 | 2 个新契约文件；10 个 UI 文件签名/取数改动（~150 行，**不含任何布局与交互变化**） |
| 验证 | ① **编译通过即证明 A1/A2/A3 已消除**（UI 再也 import 不到 `effectiveUnmatched`/`algorithm.model`）；② 10 个既有测试全绿（`AppViewModelTest`/`ExportGateTest`/…）；③ 12 项手工清单逐条过（分析/移动/三态/导出/取消/设置/角标…） |
| 回滚 | 独立提交，可单独回退（此步不动 ViewModel 内部，回退无数据风险） |
| 风险 | AR1：改构造签名会打红 10 个测试 → 保留 `forTest` 工厂，**断言一行不改**，只改装配；AR2：状态提升丢选中态 → 现有 `rememberSaveable` 的 Saver 逐段搬，每步只动一个 Composable |

### T09 · shared/app：用例编排 + 端口 + 门面（P2②）

| 项 | 内容 |
| --- | --- |
| 目标 | 让"一遍流水线"只有一份实现：分析 → 调整 → 导出计划 → 设置 |
| 依赖 | T07（可 ∥ T08） |
| 改动 | 新增 `shared/app/api/`（`PhotoMgmCore`、`AppState`（拆出 `WindowState`）、`Settings`、`ExportModels`（拆出 `ResumeVerifyMode`））、`port/`（`ImageEncoder`/`SettingsStorage`/`LogTextStorage`/`ShellLauncher`/`ExportExecutor`，后两者标明"计划上移、执行留平台"）、`usecase/`（`AnalysisPipeline`/`AdjustUseCase`/`ExportUseCase`/`SettingsUseCase`）、`state/AppStateStore`（锁内一次性发布快照） |
| 产出 | `shared/app` ~800 行 + 单测（用假端口，纯 JVM） |
| 验证 | ① `shared/app` 单测全绿（不依赖 Robolectric/AWT，这是 C5 的硬要求）；② `check-shared-purity.py`（T14 才进门禁，本步先手工跑一次预演脚本）零违规；③ 两端尚未改调用 → 黄金样本必须全等 |
| 回滚 | 单提交 |
| 风险 | 契约拆分不彻底（把 `WindowState` 留在 `AppState` 里）→ 安卓会携带永远为 null 的字段。纪律：`shared/app/api` 里出现 `window`/`Path`/`Uri` 即视为失败 |

### T10 · PC 收窄为「适配器 + 组装根」（P2③）

| 项 | 内容 |
| --- | --- |
| 目标 | 桌面端从"实现"退化为"适配 + 装配"，为安卓示范同构做法 |
| 依赖 | T09 |
| 改动 | 删除 `logic/PhotoMgmCoreImpl.kt`（783 行）→ 拆为 `DesktopCoreModule.kt`（组装根）+ 7 个 `Pc*` 适配器（`PcImageEncoder`/`PcSettingsStorage`/`PcLogTextStorage`/`PcShell`…）；`desktop/core/api` 改为对 `shared/app/api` 的 `typealias`（或直接删除，让 ui 改 import）；`desktop/ui` + `cli` 24 个文件约 62 行 import 机械替换（`com.photomgm.desktop.core.api.*` → `photomgm.app.api.*`） |
| 产出 | `desktop/core` 2927 → ~1200 行；`PhotoMgmCoreImpl` 消失 |
| 验证 | L2：`desktop/core` 9 个测试 + `desktop/ui` 13 个测试全绿（**断言不改**）；**黄金样本全等**（这是 PC 侧最关键的一次验证）；L4：`:ui:createDistributable` 成功 |
| 回滚 | 单提交（本步只动桌面端，安卓不受影响） |
| 风险 | 62 行 import 替换漏改 → 编译失败（可发现、不危险）；真正风险是"顺手优化 UI 逻辑"→ 纪律：本提交的 diff 里 `desktop/ui` 只许出现 `import` 行 |

### T11 · Android ViewModel 瘦身（A3，P2④）　→ **M4**

| 项 | 内容 |
| --- | --- |
| 目标 | 1165 行降到 ~250 行：留 `UiState` 投影 + SAF + 缩略图，其余委派给共享用例 |
| 依赖 | T08、T09 |
| 改动 | `AppViewModel` 内部方法体改为委派 `AnalysisPipeline`/`AdjustUseCase`/`SettingsUseCase`（**公开方法签名全部保留**：`parseLogs`/`buildLedger`/`movePhotos`/`exportZip`/`markLedgerPhoto`/…）；删除 `PhotoRepository.kt`；`LogsTextStore`/`Cancellable` 改调共享实现；**逐段搬运 6 个踩坑修复**：导出重入 `exportBusy.compareAndSet`、`runCatchingCancellable`（取消不被吞）、`dupGroups` 原子引用一次性发布、缓存时区版本位、**GPS 语义版本位 + `geoAvailable` 降级口径（P0 新增，见 `docs/P0-GPS-MEDIA-LOCATION-FIX.md` §8.2）**、**去重锚点模型（P0-2 新增：锚点之间永不合并，见 `docs/DEDUP-ADJACENT-BURST-FIX.md`）** |
| 产出 | 1 个主文件 1165 → ~250；删 1 文件、薄化 3 文件 |
| 验证 | ① 10 个既有测试全绿（**这条是硬门槛**：`ExportGateTest` 验证重入、`SetLogsTextWiringTest` 验证接线、`ThumbnailCacheWiringTest` 验证缓存）；② 黄金样本全等（安卓侧 ZIP 条目清单 + 每条目哈希）；③ 12 项手工清单；④ `AppViewModel` 行数断言进 CI（可选） |
| 回滚 | **分两个提交**（① 委派分析/调整；② 委派导出/设置），便于定位 |
| 风险 | **R2 最高风险**：上移时"顺手重写"会丢踩坑修复 → 纪律：每个 `//` 长注释必须在旧版找得到出处；先搬导出闸门，再搬流水线（导出最危险，先做先验证） |

### T12 · copyPhotos 能力同步（D2）

| 项 | 内容 |
| --- | --- |
| 目标 | 一张照片同属多个事件的语义两端一致（此前 PC 独有，是"同一业务两种行为"） |
| 依赖 | T11 |
| 改动 | `shared/domain/adjust/ManualAdjustment` 的 `copy` 从可空改为必选；`AdjustUseCase` 暴露 `copyPhotos`；`AppViewModel` 转发；**UI 入口暂不加**（见 P-3） |
| 产出 | 共享层 + 安卓 VM 各 1 处能力；PC 侧无行为变化 |
| 验证 | 单测：「复制后 `effectiveMap` 该照片出现在两个事件」「原事件不丢」「导出时两处各生成一份」；黄金样本全等（无 UI 入口 = 不可达 = 不应有差异） |
| 回滚 | 单提交 |
| 风险 | 若后续加 UI 入口，导出体积会随复制数增加 → 需在 T13 后单独评估，不在本计划内 |

### T13 · Android UI 目录重排 + PreviewScreen 拆分（A4）

| 项 | 内容 |
| --- | --- |
| 目标 | 对齐 PC 的粒度（`component/dialog/page/model/theme`），把 1209 行 / 16 Composable 拆到最大 ~250 行 |
| 依赖 | T11 |
| 改动 | `PreviewScreen.kt`（1209）拆 5 文件（页面骨架 / 照片网格 / 事件条 / 预览对话框 / 装配）；`Theme.kt` 拆三类（Color/Type/Theme）；新建 `ui/model/` 3 个纯函数（对齐 PC 的 `ui/model/`）；`ui/` 从 10 → 22 文件 |
| 产出 | 文件级重排，**总行数不变**（±5% 以内），无新增依赖 |
| 验证 | ① 编译 + 4 个既有 UI 测试；② 12 项手工清单（重点：选中态、滚动位置、弹窗开关在旋转/重组后不丢）；③ `git diff --stat` 显示总行数变化 < 5% |
| 回滚 | 单独提交，可只回退本步（不影响 T11 成果） |
| 风险 | AR2：Compose 状态提升出错 → 每步只拆一个 Composable，拆完立刻手工验一次；`rememberSaveable` 的 Saver 原样搬 |

### T14 · 门禁 + 文档 + CHANGELOG　→ **M5**

| 项 | 内容 |
| --- | --- |
| 目标 | 让"该放哪层"从口头约定变成 CI 红灯 |
| 依赖 | T10、T11、T13 |
| 改动 | 新增 `scripts/check-shared-purity.py`（§3.3 的 5 条：禁止平台 import / 无参时间 / `System.getProperty` / 常量重复 / UI 层 import 共享层）；把 `shared/` 加进 `check-timezone.py`、`check-cancellation.py` 扫描路径；CI `ci.yml` 结构断言扩展到三模块；`REPO-LAYOUT.md` 风险矩阵补 R6~R8；CHANGELOG 标注 `affects: android, desktop` + 「已知行为变更」两条（D5 角标、D9 时间格式） |
| 产出 | 1 个新门禁 + 2 个门禁扩容 + 文档更新 + CHANGELOG |
| 验证 | ① **门禁自证**：故意往 `shared/domain` 加一行 `import java.nio.file.Paths` → 必须红灯；删掉 → 绿灯；② 全部门禁 0 问题；③ 两端全量测试 + 打包 |
| 回滚 | 单提交 |
| 风险 | 门禁扫描路径写错 → "扫 0 文件也通过"（最危险的假绿）。纪律：门禁必须打印"扫描文件数"，并为 CI 加 `>0` 断言 |

### T15 · （可选，默认不做）`:ui` 模块拆分

把 `android/app/ui` 提成 `:ui` 模块，用 Gradle 依赖规则让"UI 无法 import `:data`"变成编译错误。**触发条件**：`PhotoUiState`/`PhotoActions` 稳定运行一个版本周期后。本计划不排期。

---

## 5. 依赖图与关键路径

```
T01 基线 ──► T02 骨架 ──► T03 exif/hash/ext ──► T04 spec/derived ──► T05 debounce/cancel/json
                                  │                    │                        │
                                  └────────┬───────────┘                        │
                                           ▼                                    ▼
                                     T06 安卓适配(A1)                      T07 domain 双跑
                                                                              │
                                                        ┌─────────────────────┤
                                                        ▼                     ▼
                                                  T08 安卓契约(A2)      T09 shared/app
                                                        │                     │
                                                        │        ┌────────────┴──────────┐
                                                        │        ▼                       ▼
                                                        │   T10 PC 收窄             T11 安卓瘦身(A3)
                                                        │        │                       │
                                                        │        │              ┌────────┴────────┐
                                                        │        │              ▼                 ▼
                                                        │        │        T12 copyPhotos    T13 UI 重排(A4)
                                                        └────────┴──────────────┴─────────────────┘
                                                                                 ▼
                                                                            T14 门禁/文档
```

**可并行的三组**（单人不强求，但可压缩周期）：

| 组 | 并行项 | 前提 |
| --- | --- | --- |
| P-a | T06（安卓适配） ∥ T07（domain 双跑） | 改的文件不重叠（`:data` vs `shared/domain`） |
| P-b | T08（安卓契约） ∥ T09（shared/app） | 契约类型先在 T08 本地定义，T09 建完 api 后再判是否上移；两者的编译面隔离 |
| P-c | T10（PC 收窄） ∥ T11（安卓瘦身） | 两端独立 Gradle 根，共享层只读；建议先 T10（有 `cli` 做第二消费者，能反向验证门面够用） |

**关键路径**：`T01 → T02 → T03 → T04 → T05 → T07 → T08 → T11 → T13 → T14`（10 跳）。

---

## 6. 里程碑与停止点

在每个里程碑**停下来**做一次完整验证与一次提交整理，确认后再继续。

| 里程碑 | 完成条件 | 此时仓库状态 | 若不继续，损失什么 |
| --- | --- | --- | --- |
| **M1**（T02） | 三模块挂载成功，两端编译/测试全绿，`algorithm` 仅 rename | 结构就位，行为零变化 | 只剩目录整理收益（但这是后续一切的地基） |
| **M2**（T05） | 6 个纯函数上移完毕，2 处行为变更已解释 | 两端已共用 9 类底层实现 | 共享率从 13.8% → ~22%，编排仍重复 |
| **M3**（T09） | 共享层完整（domain + app），两端尚未切换 | `shared/` 可以独立测试 | 编排重复仍在，但已具备一次切换的条件 |
| **M4**（T11） | 两端都跑在共享层上，`PhotoMgmCoreImpl` 消失、`AppViewModel` ≤300 行 | **核心目标达成** | 只剩安卓 UI 目录不整齐（可长期容忍） |
| **M5**（T14） | 门禁生效、文档与 CHANGELOG 就绪 | 可发布、可交接 | 规则靠自觉，半年后大概率回退到重复实现 |

---

## 7. 验证体系（命令索引）

环境（本机已具备，见 §9 P-6）：

```bash
export JAVA_HOME=/d/Android/jdk-17.0.2
export ANDROID_HOME=/d/Android/sdk
export PATH="$JAVA_HOME/bin:$PATH"
```

### L1 静态门禁（每次提交前，30 秒）

```bash
cd "$REPO"
python scripts/check-timezone.py
python scripts/check-cancellation.py
python scripts/check-ignore-policy.py
python scripts/check-shared-purity.py        # T14 起
```
**判定**：每个脚本必须打印非零"扫描文件数"且 0 问题。只看"0 问题"不看扫描数 = 假绿。

### L2 单元测试（每次提交前，3~6 分钟）

```bash
cd "$REPO/android" && ./gradlew :shared:algorithm:test :data:test :app:testDebugUnitTest
cd "$REPO/desktop" && ./gradlew :shared:algorithm:test :core:test :ui:test
```
**基线**：algorithm 22 + desktop/core 9 + desktop/ui 13 + android/app 10 + android/data 5 = **59 个测试文件**，全绿。
**纪律**：任何一步都不许"删断言让它绿"；接线变了就改装配，不改断言。

### L3 黄金样本（每个行为相关任务后，1 分钟）

```bash
python scripts/golden/snapshot_xlsx.py --in <导出目录>/ledger.xlsx --out /tmp/after.json
python scripts/golden/compare.py --base docs/golden/desktop-<commit>.json --after /tmp/after.json
```
**判定**：`EXIT 0` 且报告"0 差异"。**任何差异都必须能解释**：
- 属 T04 的预期变更 → 记 CHANGELOG，并把新快照升为基线；
- 属意外 → 回退当步，不允许"先合了再说"。

### L4 打包（每个里程碑，各一次）

```bash
cd "$REPO/android" && ./gradlew :app:assembleRelease     # 出 APK
cd "$REPO/desktop" && ./gradlew :ui:createDistributable   # 出 app-image（MSI 只在发版时出）
```

### L5 手工冒烟清单（M3 起每次必跑）

**Android 12 项**：① 选目录授权 → ② 选日志 → ③ 分析出事件 → ④ 三态切换 → ⑤ 单张移动 → ⑥ 批量移动 → ⑦ 台账标记 → ⑧ 导出 ZIP → ⑨ 导出中取消 → ⑩ 再导出（验证重入闸门）→ ⑪ 杀进程重开（验证恢复）→ ⑫ 时区切换后再分析（验证缓存版本位）。

**PC 8 项**：① 选目录 → ② 选日志 → ③ 分析 → ④ 三态 → ⑤ 移动/复制到多事件 → ⑥ 导出文件夹（含断点续传）→ ⑦ 打开台账 → ⑧ 设置重启保持。

---

## 8. 风险登记册（按"会不会静默坏数据"排序）

| # | 风险 | 触发任务 | 后果 | 等级 | 探测手段（可自动） | 应对 |
| --- | --- | --- | --- | --- | --- | --- |
| R1 | **抽取即改语义** | T04、T07 | 台账/角标/排序结果变化，用户不可见但产物不同 | **高** | 黄金样本逐单元格比对；双跑对比测试 | 差异必须书面解释；T04 单独提交；角标变化面 >5% 则回退该决策 |
| R2 | **踩坑修复丢失** | T11 | 导出重入失效 / 取消被吞 / 缓存陈旧 → 静默丢数据 | **高** | 10 个既有测试（`ExportGateTest` 等）；注释溯源检查 | 逐段搬运不惜丑；先搬导出闸门再搬流水线；分两提交 |
| R3 | **Compose 状态提升出错** | T08、T13 | 选中态/滚动位置/弹窗开关丢失（用户可感知） | **高** | 12 项手工清单；4 个 UI 测试 | 每步只拆一个 Composable；Saver 原样搬 |
| R4 | **Robolectric 测试大面积红** | T08、T11 | 改构造签名后 10 个测试同时失败，容易"顺手删" | **高** | CI 红灯即触发 | 保留 `forTest(ports)` 工厂；断言一行不改 |
| R5 | **门禁假绿** | T02、T14 | 扫描路径写错 → 扫 0 文件也通过 | 中 | 门禁必须打印扫描文件数，CI 断言 >0 | 路径变更与门禁改动同提交；T14 做"故意违规必须红"的反向实验 |
| R6 | **共享层平台 API 越界** | T03~T11 | PC 能编、安卓编不过（好）；或安卓能编、PC 崩（坏） | 中 | `check-shared-purity.py` | 门禁前置到 T09（先预演脚本），T14 正式进 CI |
| R7 | **Gradle 结构漂移** | 全程 | 有人把 `shared/` 复制进 `android/` → 两份实现必然漂移 | 中 | CI 结构断言（禁 `android/shared`、禁根 `settings.gradle.kts`、强制三模块挂载） | 断言与 T02 同提交 |
| R8 | **构建产物互踩** | T02 | 两端同时构建共享模块，缓存/产物损坏 | 低 | 随机构建失败 | `buildDir` 重定向扩展到 `:shared:*`（T02 ④） |
| R9 | **minSdk 约束被遗忘** | T03~T09 | 共享层用了 API 27+ 的 JDK 能力 → 低版本机运行期崩 | 中 | `check-shared-purity.py` 加 API 白名单（后续） | 共享层只用 API 26 可用子集；`java.time` 在 minSdk 26 原生可用 |
| R10 | **范围蔓延** | 全程 | 顺手"优化" UI / 改导出形态 / 加功能 | 中 | 每个提交 review diff 的文件白名单 | 提交前对照 §2.2；`desktop/ui` 的 diff 只许 import 行 |
| R11 | **测试资产分家** | T07、T11 | 安卓的 Robolectric 断言无法迁到纯 JVM，覆盖面净损失 | 中 | 测试文件数对比 | 上移逻辑配"假端口"纯 JVM 测试；Robolectric 只留平台适配器验证 |

---

## 9. 前提条件（需你确认）

| # | 前提 | 现状 | 我的默认假设 | 影响 |
| --- | --- | --- | --- | --- |
| **P-1** | **回归素材** | ✅ 合成素材已落地（66 张 + 416 行日志，见 `scripts/golden/`）；**真实素材已于 2026-11-02 落到真机** `/sdcard/Pictures/pmgolden-real`（55 主图 + 55 张 `_original` + `10月24日巡查日志.txt`；逐文件 sha256 与 PC 源一致 111/111，MediaStore 已索引 110 张；PC 源 `C:\Users\28988\Desktop\ceshi`） | 已完成：素材入库、可 `--verify` 自校验、`make_sample.py` 记录了三处踩坑 | 原"唯一阻塞项"已解除；**真实素材仍需在 M3 前补一轮**（合成覆盖不到厂商 HEIC/RAW 与真实微信日志边角） |
| **P-2** | Android 侧基线 | ✅ **已解决**：改走**真机**（vivo V2232A / Android 16 / API 36），绕过 AEHD 限制。`scripts/golden/run_android_real.sh` 一条命令出基线，V1/V2/V3 全通过 | 已完成，无需再修 AEHD；模拟器路线保留但非必需 | 真机测出 P0 缺陷（GPS 被系统剥离），见 P-2b |
| **P-2b** | **P0：`ACCESS_MEDIA_LOCATION` 缺失** | ✅ **已修复（2026-11-02）**：manifest 声明 + 运行时一并申请 + 降级提示 + 缓存 GPS 版本位；真机实测 `cache_photos` 有 GPS 0/65 → **59/65**、导出照片 **56/56 与素材逐字节相同**。详见 `docs/P0-GPS-MEDIA-LOCATION-FIX.md` | 已完成，T03 无阻塞；**遗留一件**：负向验证出现"部分剥离"，登记 P1 待复现（基线已于同轮重建） | 若跳过基线重建，T11 的"黄金样本全等"会与错误基线对比 |
| **P-3** | D2 的 Android UI 入口 | `copyPhotos` 同步后安卓无入口，能力不可达 | **先只上移能力（T12），UI 入口不在本计划内**；若要现在加，需破 §2.2（改 UI 交互），我会单独立项并给交互稿 | 影响 T12 验收口径（"不可达 = 黄金样本无差异"） |
| **P-4** | 是否允许 2 处行为变更 | D5（角标判定变严格）、D9（多认 `-` 分隔时间） | **允许**，写入 CHANGELOG「已知行为变更」 | 若要求零变更 → D5 改判 B（保留两份口径），R1 降级但技术债留存 |
| **P-5** | 推送与 CI | 本机 `~/.gitconfig` 代理 `127.0.0.1:7890` 未启动，`git push` 仍不通（`desktop-v2.0.0` 标签也尚未推送） | **每任务本地提交，M2/M4 各推一次**；若需中途靠 CI 验证，请先恢复网络 | 影响 R4/R7 的"自动探测"能否生效 |
| **P-6** | 构建环境 | JDK 17.0.2（`/d/Android/jdk-17.0.2`）、SDK（`/d/Android/sdk`）、Gradle 8.10.2 分发包已缓存（`~/.gradle/wrapper/dists`） | 可离线构建；`gradle` 不在 PATH，一律用两端 `./gradlew` | 换机器需重装环境 |

---

## 10. 提交与发布纪律

**提交粒度**：一个任务一个提交；T11 拆两个（先导出/设置，再分析/调整）。

**message 规范**（沿用现有仓库风格）：

```
refactor(shared): 上移 EXIF 时间/哈希/扩展名，消灭 D9/D10 双份实现
refactor(android): ViewModel 委派 shared 用例，1165→248 行（保留导出闸门/取消语义）
refactor(desktop): core 收窄为适配器+组装根，删除 PhotoMgmCoreImpl
chore(golden): 建立两端黄金样本基线（脚本+快照）
docs(audit): 更新 REPO-LAYOUT 风险矩阵，新增 R6~R8
```

**每个提交的 body 必须写**：`affects: android, desktop | desktop only` + `verify: L1 ✓ L2 ✓ L3 ✓`。

**标签**：M2 → `shared-v0.1`；M4 → `shared-v1.0`；不覆盖已有 `desktop-v2.0.0`。

**回退策略**：任何一步验证失败 → `git revert <commit>`（不做 `reset --hard`）。T04 若角标变化面过大 → 回退并把 D5 改判，不阻塞后续任务。

---

## 11. 进度与下一步

| 任务 | 状态 |
| --- | --- |
| **T01 黄金样本基线** | ✅ **已完成**（PC 侧；Android 侧推迟到 M3，见 T01 遗留） |
| **T02 shared 骨架 + 迁移** | ✅ **已完成 → 抵达 M1**（42 文件纯 rename，两端编译/测试/门禁/黄金样本全绿） |
| **P-2b P0 修复（GPS 被系统剥离）** | ✅ **已完成**（真机正向实测通过；文档 `docs/P0-GPS-MEDIA-LOCATION-FIX.md`；安卓基线已同轮重建，见提交 `8b8664b`）。**遗留**：负向"部分剥离"待复现 → 已编号 **P1-3**，处置见 §12.8 R12 |
| **P0-2 修复（连拍 <1s 误删真实照片）** | ✅ **已完成**（2026-11-02，真实素材首次端到端暴露）：`groupDupContent` 改「锚点 + 派生就近附着」；真机导出 **54 → 55 张**、55/55 逐字节一致；两端黄金样本零回归。文档 `docs/DEDUP-ADJACENT-BURST-FIX.md`。**遗留已清**：合成素材的 <1s 连拍用例已补（`d16ec95`，66→69 张），两端基线同轮重建并双跑验证 0 差异 |
| **T03 纯函数上移 A** | 🚧 **进行中**：C1 ✅（`shared/domain` 新增 `ExifTime`/`Sha256` + **19** 条单测，纯新增未切调用）、C2 ✅（PC 两个适配器改调纯函数，桌面基线已升级；实测差异严格等于 §12.4(4) 清单）；C3（安卓删副本 + 安卓侧 V2 双跑复验）待做。执行细则见 **§12.4** |
| T04~T14 | 待开工（T04/T05 排期见 §12.3） |

**M1 交付**：`shared/{algorithm,domain,app}` 三模块就位；两端以 `:shared:*` 挂载；
CI 结构断言扩到"三模块两端都必须挂 + 禁 `android/shared`/`desktop/shared`"；`REPO-LAYOUT.md` 升 v2。

**下一步（T03，纯函数上移 A）**：完整执行细则见 **§12.4**（含"并集语义怎么取""哪 8 张照片必然变化""三个提交怎么拆"）。

> **⚠️ 口径订正（2026-11-02）**：本节初稿写"L3 黄金样本**必须全等**，因为 D9 只是多认一种格式"——**这是错的**。
> 合成素材里那 8 张短横 EXIF 照片，PC 当前**读不出拍摄时间**，全部落在 `99_未分类`；T03 让 PC 认得出之后，
> 它们会回到 `06_`/`12_`/`21_` 三个事件 → **PC 侧黄金样本必然变化**，而且这个变化正是 T03 的验收证据。
> 正确判据是"差异**只允许**落在这 8 张照片与 3 个事件目录上"，不是"零差异"。

M2 之前仍然只做纯函数，不碰编排。

---

## 12. 下一阶段执行计划（制定于 2026-11-02，目标节点 **M2**）

> 覆盖：**T03 → T04 → T05**（抵达 M2），外加 4 件遗留收尾。
> 依据：§11 进度、`docs/ANDROID-REAL-DEVICE-P2-REPORT.md` §6.5 跨端差异表，以及本轮对**现有代码的实测复核**（见 §12.1 的三条口径订正）。

### 12.1 起点：现在到底是什么状态

**提交链**（本地 `feat/desktop-ui`，均未推送）：

| 提交 | 内容 | 状态 |
| --- | --- | --- |
| `d16ec95` | 合成素材补「同地点 3 连拍、间隔 800ms」用例（66→69 张）+ 负向验证 | ✅ 两端基线已同轮重建 |
| （本轮基线提交） | desktop / android ZIP / android 台账三份基线按新素材重建；`snapshot_xlsx.py` 折叠导出时刻、`run_android_real.sh` 补 `--exclude xlsx` | ✅ 双跑 0 差异 |
| `8b8664b` | 按 P0 修复后的产物重建安卓基线（ZIP 字节 + 台账单元格） | ✅ |
| `15e9389` | P0：声明并申请 `ACCESS_MEDIA_LOCATION` | ✅ 真机验证 |
| `59f5944` | 真机 P-2 基线实测 + 测出 P0 缺陷 | ✅ |
| `8261648` / `86a6be7` | 快照归一化修复 / 模拟器方案（真机已替代模拟器） | ✅ |
| `af008ca` | algorithm 迁入 `shared/`（M1） | ✅ |
| `e029f05` | PC 黄金样本基线 | ✅ |

**一句话**：结构已就位（M1）、行为零变化；两端各有一把可复现、且已验证"既不过敏（连跑 0 差异）也不迟钝（篡改必捕获）"的标尺；**已知的唯一跨端行为分叉是 D9（EXIF 短横格式）**；唯一已知的数据正确性缺陷（GPS 被系统剥离）已修复并真机实测。

**开工前复核代码，订正了计划里的三处口径**（不改这三条，T03 会按错前提做）：

| # | 计划原文 | 代码实测 | 影响 |
| --- | --- | --- | --- |
| ① | T03 后黄金样本"**必须全等**" | 素材含 **8 张短横 EXIF**，PC 现在读不出时间 → 全落 `99_未分类`；T03 让 PC 认得出之后必然归位 | 判据改为"差异**只允许**是这 8 张 + 3 个事件"（§12.4） |
| ② | D11"扩展名集合**两处**" | 全仓只有 PC 一处（`PcPhotoSourceProvider.IMAGE_EXTENSIONS`）；安卓走 MediaStore MIME，**没有扩展名集合** | `ImageExtensions` 降为 T03 **可选子项**，不阻塞 |
| ③ | D10"SHA-256 **两份**" | 实际 **4 处** `MessageDigest`：`FileHasher`、`PcFileHasher`、`PcFolderExporter:139`、`PhotoIdAllocator:79` | T03 只收口前 2 处，另 2 处随 T10 一起收（范围纪律） |

**遗留清单**（与 §9 前提表互补，这里只列"还需要动手"的）：

| # | 遗留 | 等级 | 阻塞 T03~T05？ | 处置节点 |
| --- | --- | --- | --- | --- |
| L1 | 未授权时 GPS 剥离呈**部分生效**（实测只有按拍摄时间最早的 9 张丢 GPS）→ 已编号 **P1-3** | 中高 | 否 | **T11 开工前**定性；可放 M2 缓冲期 |
| L2 | 真实素材未补（P-1 遗留）：合成素材覆盖不到厂商 HEIC/RAW、真实微信日志 | 中 | 否 | M3（T09）之前，需你提供 |
| L3 | 4 处 `MessageDigest`（见上表 ③） | 低 | 否 | T03 收 2 处 / T10 收 2 处 |
| L4 | 本机代理未启动，`git push` 不通，CI 无法验证 | 中 | 否 | M2 收口时尝试；失败则 tag 说明里注明"CI 未验证" |
| L5 | 设备遗留 `/sdcard/Pictures/pmgolden`（**73 文件 / 1.1MB**，含 4 个噪声文件）与 `pmgolden-real`（111 文件 / 115MB 真实素材） | 低 | 否 | 随时可清；真实素材**留着**，M3 前还要复跑 |

### 12.2 排序原则

1. **先改"会让后来者按错前提干活"的东西**：文档与编号错一次，后面每一轮都要付利息。成本 30 分钟，优先级最高（N-0）。
2. **关键路径优先**：T03→T04→T05 是 M2 的唯一路径；**T04 是全计划唯一改语义的任务**，越早撞上越早暴露。
3. **缺陷与重构不混提**：P1-3 单独做，不塞进 T03——它的性质是"平台行为待定性"，不是"代码要改"；混进去会让 T03 的回退粒度失真。
4. **依赖外部的排到关键路径之外**：真实素材、推送网络不占关键路径，只在文档里标欠账。
5. **一个任务一个提交、每个提交单独可 revert**；T03 拆三个（新增 / PC / 安卓）。

### 12.3 任务清单与时间节点

按"每个工作日 4~6 小时有效编码"估算；**D0 = 2026-11-02（一）下午**。

| 编号 | 任务 | 优先级 | 依赖 | 预计 | 计划完成 | 产出 |
| --- | --- | --- | --- | --- | --- | --- |
| **N-0** | 文档订正：P1 编号冲突（`P1-2` 被"GPS 部分剥离"与"嵌图不可比"两处占用）+ 本章 §12 | **P0** | — | 0.5h | **11-02** | 本章 + 报告编号修正 |
| **T03-C1** | `shared/domain` 新增 `ExifTime`/`Sha256` + 测试（**纯新增，不切调用**） | **P0** | N-0 | 0.5d | **11-03** | 2 源码 + 2 测试；两端编译通过 |
| **T03-C2** | PC 侧改委派 + 建"预期差异清单" + 基线升级 | **P0** | C1 | 0.5d | **11-04 上午** | `PcExifReader`/`PcFileHasher` 减重；`desktop-baseline.json` v2 + 旧版存档 |
| **T03-C3** | 安卓侧删 `ExifTimeParser` 副本、`FileHasher` 缩为适配器 + V2 复跑 | **P0** | C1 | 0.5d | **11-04 下午** | 删 1 文件、1 个变 10 行；安卓 V2 0 差异 |
| **V4'** | 跨端差异**首次复测**（T03 后 D-b/D-c/D-d 应收敛） | **P0** | C2+C3 | 0.25d | **11-05 上午** | 报告 §6.5 差异表更新 |
| **T04** | 纯函数上移 B：`MediaSpec` + `DerivedName`（**唯一改语义**） | **P1** | T03 | 1.0d | **11-06** | 2 共享文件；角标变化清单；CHANGELOG 行为变更 |
| **T05-C1** | 上移 `Json`（279 行零改动）+ `Cancellation`（以安卓语义为基准） | **P1** | T04 | 0.5d | **11-09 上午** | 2 共享文件 + 1 测试 |
| **T05-C2** | 上移 `DebouncedTextStore`（只留策略，持久化走端口） | **P1** | T05-C1 | 0.5d | **11-09 下午** | 1 共享文件 + 1 测试；两端薄适配 |
| **M2 收口** | 全量验证 + CHANGELOG + tag `shared-v0.1` | **P1** | T05 | 0.5d | **11-10** | 见 §12.7 检查单 |
| **P1-3** | GPS"部分剥离"复现定性（可滑动） | **P2** | — | 0.5d | **11-11 ~ 11-12 缓冲** | 复现记录 + 去重判据要求 |

**关键节点**：`11-04` 首次出现"两端行为开始一致"的量化证据；`11-06` 唯一改语义点落地；`11-10` 抵达 M2；`11-12` 缓冲截止。

### 12.4 T03 执行细则（明日开工，先看这一节）

#### (1) 它不是"搬代码"，是"把两个不同实现合并成一个更强的实现"

| 维度 | PC `parseExifDateTime` | 安卓 `ExifTimeParser.parse` | 并集取法 | 只取一端的后果 |
| --- | --- | --- | --- | --- |
| 分隔符 | **只认** `yyyy:MM:dd` | 冒号/短横**都认**（按 `s[4]` 判定） | 两者都认（沿用安卓判定） | 只搬 PC → D9 未消，8 张照片仍落未分类 |
| 尾部冗余 | `substring(0,19)` **截断**，容忍毫秒/时区尾巴 | **不截断**，`…08:30:00.123` 直接解析失败 | 取 PC 的截断 | 只搬安卓 → 带毫秒的 EXIF 被判"无时间"（现有缺陷，素材未覆盖所以一直没暴露） |
| 长度守卫 | `length < 19` → null | 无显式守卫（靠异常兜底） | 合并为"trim 后为空 或 <19 → null" | 边界行为两端不一致 |
| 异常 | `runCatching` → null | `runCatching` → null | 一致，保留 | — |

**结论一句**：单一实现要取"**两者能力之并**"，KDoc 里必须写明这两处能力各自来自哪一端，否则下一个人会以为"随便删一条也能过测试"。

#### (2) 文件清单

| 动作 | 文件 | 说明 |
| --- | --- | --- |
| 新增 | `shared/domain/src/main/kotlin/domain/exif/ExifTime.kt` | `object ExifTime { fun parse(text: String, zone: ZoneId = AppZone.ID): Long? }`——**签名与安卓原版一致**，切调用只需改 import |
| 新增 | `shared/domain/src/main/kotlin/domain/hash/Sha256.kt` | `ofBytes(ByteArray)` + `ofStream(InputStream)`（64KB 缓冲沿用安卓；**只收字节，不碰文件**） |
| 新增（可选） | `shared/domain/src/main/kotlin/domain/spec/ImageExtensions.kt` | 仅 PC 使用；有余额再做 |
| 新增 | `shared/domain/src/test/kotlin/domain/ExifTimeTest.kt` | 迁安卓 5 条断言 + 新增 4 类 |
| 新增 | `shared/domain/src/test/kotlin/domain/Sha256Test.kt` | 空文件 / 分块边界 / 与 JDK `MessageDigest` 逐字节一致 |
| 改 | `desktop/core/build.gradle.kts` | 加 `api(project(":shared:domain"))`（现只有 `:shared:algorithm`，不加就 import 不到） |
| 改 | `android/data/build.gradle.kts` | 加 `implementation(project(":shared:domain"))`（同上） |
| 改 | `PcExifReader.kt` | 删 `EXIF_TIME` 与 `parseExifDateTime`；`readTime` 改调 `ExifTime.parse`。**`readAll` 只打开一次文件的逻辑一行不动** |
| 改 | `PcFileHasher.kt` | 委派 `Sha256`（保留它多出的两个重载） |
| 删 | `android/.../ExifTimeParser.kt` | 内容已迁走 |
| 改 | `android/.../FileHasher.kt` | **不删，缩成 10 行适配器**（理由见下） |

> **与计划原文的一处偏离，需要记住**：原文写"删除 Android `FileHasher.kt`"。实测它是"读文件 → 算哈希"，`java.io.File` 的 IO 一旦搬进 `shared/domain`，C2 的边界就变模糊（下一个人会顺手把 `File.exists()` 也搬进去）。所以 **`Sha256` 只收 `ByteArray`/`InputStream`，`FileHasher` 留作平台侧适配器**（`File(src).inputStream().use { Sha256.ofStream(it) }`）。这条偏离不改其他任何结论。

#### (3) 测试：迁 5 条 + 新增 4 类，一条不许少

- **迁移**：`ExifTimeParserTest` 的 5 条（冒号 / 短横 / 跨日 / 非法 / 空白）**断言一行不改**，只改类名与 import → `ExifTimeTest`。
- **新增 4 类**：
  1. `2026:09:03 08:30:00.123`（>19 字符）→ 必须解析成功（PC 独有能力，安卓原来失败）；
  2. `2026:09:03 08:30:00+08:00` → 解析成功，且**按业务时区解释、忽略尾巴里的 `+08:00`**（断言"不是按偏移换算"，否则将来有人以为支持时区）；
  3. `2026:09:03`（<19）→ null；
  4. 混合批次：8 张短横 + 59 张冒号同批解析，全部成功且 epoch 严格递增。
- `ExifMergedReaderTest.baseline2x` 里的 `ExifTimeParser.parse(raw)` 改 `ExifTime.parse(raw)`；**"双构造基线"不许删**——它是"合并读一次省 203ms"那条性能结论的唯一证据。

#### (4) 预期差异清单（T03 **唯一**允许的差异）

> **口径订正（2026-11-02 晚，补完连拍用例后实测重算）**：初稿写"colon 56 / 其余 47 张"是按 66 张素材算的；
> 现在素材 69 张（colon **59** / dash **8** / none 2），且**桌面端黄金样本只拍 xlsx、看不到"照片换了目录"**，
> 所以判据必须拆成"xlsx 差异"与"导出目录差异"两半，各自单独列清楚。

素材实测：`exif=colon` **59** 张、`exif=dash` **8** 张、`exif=none` 2 张。
PC 现状导出 **61** 张 = 事件目录 51 + `99_未分类` 10（8 张 dash + 2 张 none）。

T03 后 PC 首次认出这 8 张的拍摄时间，它们从 `99_未分类` 归位：

| 照片 | PC 现状 | T03 后 |
| --- | --- | --- |
| `IMG_20260814_091000` / `_091030` | `99_未分类` | `06_K519+500…抛洒物`（**新目录**，2 张） |
| `IMG_20260814_105000` / `_105030` / `_105000_original` | `99_未分类` | `12_…隧道渗水`（**新目录**）；`_original` 被三态折叠**不导出** → 2 张 |
| `IMG_20260814_144500` / `_144530` / `_144500_original` | `99_未分类` | `21_检查高边坡K537+100`（**新目录**）；同样只出 2 张 |

**验收四条，同时成立才算通过**：

1. **导出目录**（用 `snapshot_dir.py` 在 T03 前后各拍一次，按条目名比对）：
   `99_未分类` 由 10 → 2；新增 3 个目录（`06_`/`12_`/`21_`）各 2 张；
   **总张数 61 → 59**（差的 2 张就是被三态折叠的 `_original`——未匹配照片不参与去重，所以它们以前才被全量保留）；
   其余 51 张**文件名与 sha256 一条不变**（照片字节没动，只是所在目录变了）。
2. **台账 xlsx**：允许 `media.length` 44→50、`anchors.length` 44→50、`bytes`（总字节）变化，外加 3 行各新增 2 个锚点。
   其余判据按**集合**比而不按索引比（新增嵌图会让后续图片编号整体后移）：
   旧快照 44 张嵌图的 sha256 集合必须原样出现在新快照里；新增的 6 张必须是 400×300。
3. 安卓侧 V2 双跑 **0 差异**——T03 只是换实现，安卓可观测行为必须一点不变。**若出现差异，说明并集实现与原版不等价，回退 C3。**
4. 跨端复测：`D-b 23→26`、`D-c 11→3`、`D-d 44→+6`（具体数待实测，不是承诺值），且**不再存在"仅单端有"的目录**
   （现在单端有的是 `06_`/`12_`/`21_` 只在安卓有，以及 `16_`/`22_` 只在 PC 有？——以实测为准，别照抄）。

> ⚠️ 顺带发现（2026-11-02，登记不改）：样本里 4 张标为"未匹配"的照片（`07:30/12:20/12:47/17:40`）**并没有落在未匹配区**，
> 而是被 `01_`/`17_`/`27_` 三个事件抢走了（PC 实测 `99_未分类` 只剩 8 张 dash + 2 张 none）。
> 这说明"未匹配"这条样本设计**当前不成立**，而 `make_sample.py` 的注释还写着"验证未匹配区"。
> 与本轮改动无关（补连拍前后都一样），但**不要拿它当"未匹配"的回归依据**；定性留给 M2 缓冲期。

#### (4b) C2 实测结果（2026-11-03，四条验收逐项核对）

**导出目录**（按文件名比对 `after-burst2` 与本次导出）：

| 判据 | 预期 | 实测 | 结论 |
| --- | --- | --- | --- |
| `99_未分类` | 10 → 2 | 10 → 2 | ✅ 剩下的正是 2 张无 EXIF 素材 |
| 新增 3 个目录 | `06_`/`12_`/`21_` 各 2 张 | 各 2 张 | ✅ |
| 总张数 | 61 → 59 | 61 → 59 | ✅ |
| 消失的 2 张 | `105000_original` / `144500_original` | 完全一致 | ✅ 被三态折叠合并，不是丢照片 |
| 其余照片 | 文件名 + sha256 一条不变 | 换目录 6 张、字节变化 **0** 张、新增 **0** 张 | ✅ |

**台账 xlsx**（按事件行比对，不按索引）：

| 判据 | 预期 | 实测 | 结论 |
| --- | --- | --- | --- |
| `media.length` / `anchors.length` | 44 → 50 | 44 → 50 | ✅ |
| 新增锚点 | 3 行 × 2 列 | 3 行 × 2 列（K519+500 / K523+400 / K537+100） | ✅ |
| 旧 44 张嵌图 | sha256 集合原样保留 | 共同 22 个事件行 × 2 列共 **44 处逐列全等** | ✅ 比「集合相同」更强 |
| 新增 6 张 | 400×300 | 6 张全为 400×300（全量 50 张尺寸集合 = {(400,300)}） | ✅ |
| 行 / 非空单元格 | 72 / 504 | 72 / 504，单元格文本零变化 | ✅ |
| xlsx 总字节 | 允许变 | 239210 → 270810 | ✅ |

**一处必须记住的坑**：`compare.py` 直接按索引比 `media[i]` 会报 **120 处差异**，看起来像"嵌图内容全变了"，
实际是**新增 6 张嵌图插入序列后，后面所有图片整体重编号**（`image11..image50` 全顺移，`anchors[*].row` 也跟着错位）。
台账类快照必须**按事件行 + 列**比，不能按索引比。判据已落到 `docs/golden/README.md`。

**单测条数订正**：本节 (3) 原写"迁 5 条 + 新增 4 类"，实际落成 ExifTime **11** 条 + Sha256 **8** 条 = **19** 条
（多出的是 64KB 边界、全 0x00–0xFF 单字节覆盖、两种十六进制写法互证、流所有权约定）。

#### (5) 三个提交

```
refactor(shared): 新增 ExifTime/Sha256（并集语义），纯新增不切调用
refactor(desktop): EXIF 时间与哈希改委派 shared；PC 首次识别短横 EXIF（D9）
refactor(android): 删除 ExifTimeParser 副本、FileHasher 缩为适配器，改委派 shared
```

C2 之后必须**同时**更新 `docs/golden/desktop-baseline.json`（`meta.rebuildReason` 写明"T03 让 8 张短横 EXIF 归位"），并把旧版存到 `docs/golden/history/`——**基线换了却不留旧版，等于把"为什么变了"永久丢失**。

### 12.5 T04 执行细则（唯一改语义，留足评审）

1. **先写测试再切调用**：4 类文件名（`_original`、`_compressed`、`_1`、双后缀 `_original_1`）× 现网 PC `contains` 宽松版 vs `DerivedNames` 正则严格版，产出一张**行为对照表**。
2. **量化变化面**：拿黄金样本 66 张跑一遍，统计"角标从有变无"的比例；**>5% 就回退**，把 D5 改判为 B（两端各自保留），不阻塞后续。
3. **常量单点**：`MediaSpec` 只放 400/300/50/4:3/`PT_TO_EMU`；验收用 `grep -rn "400\|300" shared/` 只命中 `MediaSpec`。
4. 差异写进 CHANGELOG「已知行为变更」，并把 `compare.py --explain` 的输出贴进提交 body——**这条是给半年后的人看的**。

### 12.6 T05 执行细则（M2 收口）

1. **`Json.kt` 先 `git mv` 再改 package**，保证 diff 里只有 package 行（279 行手写 JSON 不许"顺手重构"）。
2. **取消语义以安卓为基准**：`runCatchingCancellable` 的"取消不被吞"是踩坑修复，PC 的 `Operation` 跟随；反过来做会让导出中断静默继续。
3. `DebouncedTextStore` 只保留策略（延迟 400ms、合并写、`flush()`），持久化走 `LogTextStorage` 端口——**把 `SharedPreferences`/`ScheduledExecutor` 带进去就破坏 C2**。
4. 必测两条：连续 5 次 `set` 只落盘 1 次、`close` 前 `flush` 不丢最后一次；取消后 `runCatching` 不吞 `CancellationException`。

### 12.7 M2 停止点检查单（10 项，逐条打勾才许进 T06）

1. L1 三门禁全绿**且打印的扫描文件数 > 0**（`shared/` 尚未进门禁扫描范围，本步手工确认一次）。
2. L2 六组测试全绿：algorithm 22 / domain 新增 2 / desktop core 9 / desktop ui 13 / android app 10+ / android data 5。
3. L3：desktop 黄金样本 = 新基线且差异已解释；android V2 双跑 0 差异。
4. 跨端差异表已更新（D-b/D-c/D-d 应消失；D-e/D-g 仍标"不可比"）。
5. 9 个提交各自可单独 `revert`；`git status` 干净。
6. CHANGELOG 两条「已知行为变更」+ `affects:` + `verify:` 齐全。
7. `docs/golden/history/` 存有 T03/T04 之前的基线副本。
8. 本地打 tag `shared-v0.1`（不覆盖 `desktop-v2.0.0`）。
9. 网络若恢复 → push + 看 CI；否则在 tag 说明里写"CI 未验证"。
10. 本文档 §11 与 §12.3 的状态列更新到 M2。

### 12.8 新增风险（R12~R17，接 §8 登记册）

| # | 风险 | 触发任务 | 后果 | 等级 | 探测手段 | 应对 |
| --- | --- | --- | --- | --- | --- | --- |
| R12 | **去重把「场景相同」当成「同一张」** | T11 及任何去重改动 | **已实测发生**：GPS 有效时 `isSame` 只有「时间 + 距离」，同地连拍的两张**不同**照片被并成一组，后者**被永久删除**（真实素材 110 张只导出 54 张）。P1-3 的 GPS 混合态是它的镜像（那一侧表现为漏并） | **已发生（最高）** | 真实素材导出照片数 vs 主图数；`55/55 逐字节相同` | **已修**（锚点模型，见 `docs/DEDUP-ADJACENT-BURST-FIX.md`）：T11 重构**必须保留「锚点之间永不合并」**，不得退回「时间 + GPS 两两判重」；任何去重改动都要跑「真实素材张数 + 逐字节」双校验 |
| R13 | **基线二次失效（假绿）** | T03、T04 | 拿旧基线当"全等"判据，或基线换了没留档 | 中 | `meta.rebuildReason` + 提交内基线 diff | 基线与改动**同提交**更新；旧版存 `history/`；比较命令固定带 `--ignore '^meta'` |
| R14 | **测试迁移净损失** | T03 | 5 条断言被"顺手合并"→ 覆盖下降且无人发现 | 中 | 搬前搬后测试条数对比 | 断言一行不改；新增 4 类同一提交；条数只许多不许少 |
| R15 | **D9 掩盖的其他差异暴露** | T03 | 以为"对齐完就零差异"，结果冒出 D5/排序差异，被误判为回归 | 中 | 跨端 V4 复测 | 预先声明：D5（派生判定）差异在 T04 前仍可能存在，**不算 T03 回归**；只要求"新增差异为零" |
| R16 | **真机不可用**（锁屏 / `uiautomator` 残留） | V4' 复测 | 脚本空转十几分钟 | 中 | 2.5 步预检 | 已沉淀：`/sdcard` 项数探针 + dump 落 `/data/local/tmp` + 必要时重启 |
| R17 | **推送不通 → CI 盲区** | 全程 | 结构断言与门禁只在本地跑，交接时无凭据 | 中 | `git push` 失败 | M2/M4 各推一次；推不了就在 tag 说明里明写 |

### 12.9 需要你拍板 / 提供的三件事

| # | 事项 | 我的建议 | 若不解决的后果 |
| --- | --- | --- | --- |
| 1 | **真实素材**（P-1 遗留） | ✅ **已于 2026-11-02 由你提供并落到真机**（`Desktop\ceshi` → `/sdcard/Pictures/pmgolden-real`，110 张 jpg + 1 个日志，sha256 111/111 一致）。**未含 HEIC/RAW**：该批全是 JPG，厂商 HEIC 分支仍无真实样本 | 只剩 HEIC/RAW 与「真实微信日志边角」两类无真实覆盖，M3 验收时单独说明 |
| 2 | **P1-3 何时做**（GPS 部分剥离复现，0.5d） | 放 `11-11~11-12` 缓冲期，但 **T11 开工前必须有结论** | 去重会在"GPS 可能部分可用"的未知前提下重构，R12 无法量化 |
| 3 | **推送网络**：M2 时能否恢复代理 | 能恢复就把 CI 一起验；不能就只做本地门禁 | CI 的结构断言/门禁长期未验证，等于只有本地一份证据 |

### 12.10 本阶段预期产出汇总

| 类型 | 产出 |
| --- | --- |
| 代码 | `shared/domain` 4~5 个源文件（`ExifTime` / `Sha256` / `ImageExtensions?` / `MediaSpec` / `DerivedName` / `DebouncedTextStore` / `Cancellation` / `Json`）+ 4~5 组测试；desktop 侧 4 个文件减重；android 侧删 1 个、3 个变薄适配器 |
| 标尺 | `desktop-baseline.json` v2（含 `rebuildReason`）+ `history/` 旧版存档；跨端差异表更新到"仅剩不可比项" |
| 文档 | 本文档 §12 + 报告 §6.5 复测 + CHANGELOG 两条「已知行为变更」 |
| 度量 | **跨端差异从 4 项（D-b/D-c/D-d/D-f）降到 0**（嵌图字节与 ZIP 形态除外）；共享率 13.8% → **~22%**（M2 判据） |
