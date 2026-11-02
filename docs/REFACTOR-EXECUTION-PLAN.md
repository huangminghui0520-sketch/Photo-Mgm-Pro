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
| 遗留 | **Android 侧基线推迟到 M3**（导出走 SAF，需真机/模拟器，属 P-2）：`snapshot_zip.py` 已就绪，届时补 `docs/golden/android-baseline.json` |
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
| 改动 | 新增 `shared/domain/exif/ExifTime.kt`（**取并集**：`yyyy:MM:dd` 与 `yyyy-MM-dd` 都认）、`hash/Sha256.kt`、`spec/ImageExtensions.kt`；PC 侧 `PcExifReader`/`PcFileHasher`/`PcPhotoSourceProvider` 改为委派；Android 侧删 `ExifTimeParser.kt`/`FileHasher.kt`，`ExifPhotoReader` 改为委派；补 3 组纯 JVM 单测（含"冒号/短横混合""带时区偏移""无 EXIF 返回 null"） |
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
| 改动 | `AppViewModel` 内部方法体改为委派 `AnalysisPipeline`/`AdjustUseCase`/`SettingsUseCase`（**公开方法签名全部保留**：`parseLogs`/`buildLedger`/`movePhotos`/`exportZip`/`markLedgerPhoto`/…）；删除 `PhotoRepository.kt`；`LogsTextStore`/`Cancellable` 改调共享实现；**逐段搬运 4 个踩坑修复**：导出重入 `exportBusy.compareAndSet`、`runCatchingCancellable`（取消不被吞）、`dupGroups` 原子引用一次性发布、缓存时区版本位 |
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
| **P-1** | **回归素材** | ✅ 已落地（合成 66 张 + 416 行日志，见 `scripts/golden/`） | 已完成：素材入库、可 `--verify` 自校验、`make_sample.py` 记录了三处踩坑 | 原"唯一阻塞项"已解除；**真实素材仍需在 M3 前补一轮**（合成覆盖不到厂商 HEIC/RAW 与真实微信日志边角） |
| **P-2** | 打包复验方式 | 本机可跑 PC 打包；APK 需安卓设备或模拟器 | 每个里程碑只跑 L4 编译/打包，**不装真机**；装真机留到 M4、M5 各一次 | 影响 L5 手工清单的执行时机 |
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
| T02~T14 | 待开工 |

**T01 实际交付**：`scripts/golden/`（5 个文件）+ `docs/golden/desktop-baseline.json` + `docs/golden/README.md`；
验证结论：双跑 0 差异、反向实验准确报 4 处、最终脚本重跑与基线全等、三道既有门禁全绿。

**下一步（T02，预计一个工作段）**：

1. `git mv algorithm shared/algorithm`（内容零改动），两端 `settings.gradle.kts` 改挂载，`buildDir` 重定向扩到 `:shared:*`；
2. 新建 `shared/domain`、`shared/app` 空模块并两端挂载；
3. CI 结构断言 + 3 个 gate 脚本路径同步（**与 T02 同提交**，否则门禁会静默变成"扫 0 文件也通过"）；
4. 全量验证：L1 门禁 + L2 59 个测试 + `:app:assembleDebug` + `:ui:compileKotlin` + L3 黄金样本全等；
5. **M1 停下来给你看**：目录树 + `git log --stat -1`（证明 algorithm 只是 rename）+ 测试报告。

M1 之前**不碰任何业务逻辑**。
