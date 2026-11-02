# PC 端代码审核 + 安卓端重构规划（AUDIT & ANDROID-REFACTOR）

> 状态：**待审核**。本文件仅为分析与规划，**未改动任何代码**（`git status` 仅有 `docs/` 下两份新增文档，HEAD 仍为 `c0eef6b`）。
> 姊妹文档：`docs/CROSSPLATFORM-ARCH.md`（共享层怎么切、怎么迁移、风险矩阵）。本文回答两个它没展开的问题：
> **① PC 端这套代码本身质量如何、哪些做法值得当标准、哪些是坑；② 以它为基准，安卓端应该长成什么样、每个文件对应到哪。**

---

## 0. 结论速览

### 0.1 八维评分（0–5，证据见 §2/§4）

| 维度 | PC 端 | 安卓端 | 说明 |
| --- | --- | --- | --- |
| 分层与职责单一 | **4** | **2** | PC 有明确五层且用 `internal` 编译期封住；安卓无层，1165 行 ViewModel 一肩挑 |
| 模块边界（编译期强制） | **4** | **2** | PC 三模块 + `internal`；安卓两模块但 `:app` 内部无序 |
| 依赖方向 | 3 | 3 | 两端都单向，但两端都有"UI 越层"（PC 5 处、安卓更重） |
| 契约设计 | **4** | **2** | PC 是接口 + 不可变快照；安卓 UI 直接吃具体 `AppViewModel` |
| 状态管理 | 3 | 3 | PC 全量快照（简单但粗）；安卓 `UiState` 扁平但混了缓存与导出态 |
| 并发模型 | 3 | **4** | PC 用 `Executor`+`ScheduledExecutor`；安卓用协程（更现代，但与共享层不一致） |
| 错误处理 | **2** | 3 | PC `runCatching{}.getOrNull()` 泛滥，失败原因全丢 |
| 可测试性 | **4** | 3 | PC 9 个纯 JVM 用例可注入端口；安卓 10 个 Robolectric 用例依赖真实 ViewModel |
| **合计** | **27/40（68%）** | **22/40（55%）** | — |

### 0.2 三句话

1. **PC 端的架构方向是对的**：端口注入、门面隔离、不可变快照、`internal` 编译期封层 + 独立 `cli` 消费者——这四条是本次重构可以照抄的模板，安卓端恰恰全缺。
2. **PC 端的问题集中在"一个模块装太多"和"契约照着自己写"**：`desktop/core` 六包混居、`PhotoMgmCoreImpl` 783 行七职责、`Settings` 带窗口几何——这三条直接导致安卓端没法复用。
3. **安卓端的重构不必发明新架构，只需把 PC 已验证的四条搬过去**，同时修掉 PC 的两个坑（契约泄漏平台概念、UI 越层）。**重构后的安卓端会比现在的 PC 端更干净**——因为它没有历史包袱。

---

## 1. 审核基准（行业通行规范，四条）

不是"我觉得"，先立标尺。下面四条是跨平台客户端工程的主流共识，本文所有判定都落到这四条上：

| 编号 | 规范 | 可观测判据 |
| --- | --- | --- |
| **S1** | **分层 + 依赖单向**（Clean / Hexagonal） | 依赖图无环；表现层不 import 领域实现；平台能力以接口形式被注入，不被主动 new |
| **S2** | **业务规则与平台能力分离** | 同一规则不因平台不同而重写；平台差异只出现在"实现类"里，不出现在"判断分支"里 |
| **S3** | **模块边界由构建系统强制**（Gradle 多模块） | 越层会**编译失败**，而不是靠 code review 拦；模块各有独立测试入口 |
| **S4** | **表现层只消费不可变快照 + 单向数据流** | UI 不调用业务函数、不持有可变领域对象；状态变更只有一个入口 |

---

## 2. PC 端结构审核（逐项对照 S1–S4）

### 2.1 分层达成度：**做对了，且用了正确的手段**

`desktop/settings.gradle.kts` 划出 `:core` / `:ui` / `:cli` 三模块，`desktop/core` 内部再分六包：

```
api      → 门面 + 状态快照 + 导出契约（对外唯一可见面）
logic    → PhotoMgmCoreImpl（流水线编排）
platform → Pc* 六个适配器（目录/EXIF/哈希/图片/外壳/id 分配）
export   → PcFolderExporter + 清单 + 日志（PC 专属导出形态）
state    → 设置与日志文本持久化
infra    → 手写 JSON
```

**关键优点（值得照抄）**：`logic/platform/state/infra` 里所有类型都标了 `internal`，只有 `api` 包对外可见。于是"UI 有没有越层"从"靠自觉"变成"编译能不能过"——`desktop/ui/build.gradle.kts` 里那句注释写得很准：*"编译能过 现在就是 没越层 的证明"*。这正好满足 **S3**，是本仓库最有价值的一个设计决策。

**缺陷**：六个包在**同一个 Gradle 模块**里。`api` 与 `logic` 同生共死 → 契约无法被另一端独立引用（这正是安卓端今天只能抄代码、不能引依赖的根因）。

### 2.2 依赖方向：单向，但有一条隐式越层

```
ui ──► core ──► algorithm
cli ─► core, algorithm
```

方向没错，但有两处与"声明"不符：

| 位置 | 事实 | 后果 |
| --- | --- | --- |
| `desktop/ui/build.gradle.kts` | **没有** `implementation(project(":algorithm"))` | UI 里却有 5 处 `import algorithm.*`（`Actions.kt:14`、`Main.kt:32`、`WorkspacePage.kt:43`、`format/Format.kt:8`、`dev/DemoFixture.kt:9`），靠 `core` 的 `api(project(":algorithm"))` 传递才编过 |
| `cli` | 同时依赖 `:core` 与 `:algorithm` | 门面的"第二消费者"价值被削弱：它绕过门面直接用核心类型，接口设计缺陷不会在这里暴露 |

→ **建议**：把 `DedupKeepMode` / `AppZone` 这类**业务枚举**从 `algorithm` 包在 `shared/app/api` 里再导出一层（typealias 或直接迁包），UI 只 import 契约包；`cli` 去掉 `:algorithm` 直接依赖。

### 2.3 契约设计：接口隔离做得好，但契约被平台污染

做得好的：`PhotoMgmCore` 是纯接口，`ShellLauncher` 被单独抽出并带 `NOOP` 实现（注释里写清了"无头环境/测试会炸"的动机）；`AppState` 是 20 字段不可变快照，`Progress.fraction`、`canExport` 这类派生值放在快照上而不是让 UI 算——这正是 **S4**。

被污染的（**本次审核最该修的三处**）：

| 契约 | 混入的平台概念 | 为什么是问题 |
| --- | --- | --- |
| `Settings.window: WindowState?` | 窗口几何（x/y/w/h） | 安卓端无窗口概念，却被迫在契约里带一个永远 null 的字段 |
| `ExportRequest.verifyMode: ResumeVerifyMode` | 文件夹断点续传校验策略 | 安卓走 ZIP + stateFile，这套枚举对它无意义 |
| `ExportResult.ledgerFile: Path` | `java.nio.file.Path` | 安卓端没有 Path；端口类型不该出现在跨端契约里 |

→ 契约应当**只描述"用户可感知的业务状态"**，平台差异放端口。落地方式见 `CROSSPLATFORM-ARCH.md` §4.1 的拆分列。

### 2.4 状态与并发：能用，但是两套模型

PC：`StateFlow<AppState>` + 自建单线程 `Executor` + `ScheduledExecutorService`（日志防抖）+ `synchronized(lock)` 保护 `parsed/classifyResult/photos`。
安卓：`MutableStateFlow<UiState>` + `viewModelScope` + `Dispatchers.IO/Default` + `AtomicBoolean` 导出闸门。

**同一份编排逻辑，两种取消语义、两种调度原语。** 这直接违反 **S2**：差异不该出现在"业务规则的表达方式"上，只该出现在"实现"里。→ 共享层统一为协程 + 注入 `CoroutineDispatcher`（两端都有 coroutines，代价为零）。

### 2.5 错误处理：**PC 端最弱的一环（2/5）**

`runCatching { … }.getOrNull()` 在 `PcImageEmbedder.embed`、`PcExifReader.readAll`、`PcFileHasher.sha256`、`PcFolderExporter.entryIntact` 等处反复出现。后果不是崩溃，而是**静默降级**：台账缺一张照片时，用户看到的是"这一格只有文件名"，日志里没有任何一句说明是文件不存在、解码失败、还是 JRE 无 JPEG 编码器。

对比安卓：至少有 `android.util.Log.w("PM_Image", ...)` 带原因。**安卓在这一项上比 PC 好**，重构时应把"失败原因随结果一起返回"（`Result<T>` 或带 `warnings` 的返回值）作为共享层的统一约定，而不是各写各的。

### 2.6 可测试性：PC 端强，靠的是端口

`CoreConfig` 允许注入 `executor`（同步执行器做确定性断言）、`exifReader`、`sourceProvider`、`hasher`、`clock`、`shell` —— 所以 9 个用例能在无显示器、无真实相册的环境跑完流水线。这是 **S1** 的直接红利。

但 `CoreConfig` 本身是"穷人版 DI"：7 个可空参数，每加一个端口加一个字段，且 `dataDir = null` 时会去读 `%APPDATA%`（测试若忘了传就偷偷摸真实文件系统）。→ 建议改为"端口组必填 + `CoreConfig.forProduction()` 工厂"。

### 2.7 可移植性：PC 有 40% 代码搬不动，但**搬不动是设计问题不是能力问题**

`desktop/core` 2927 行里，与平台强绑定的约 1180 行（`Pc*` 570 + `export` 709，含 `PcFolderExporter` 的文件夹语义），剩下约 1750 行是纯逻辑——**却因为和 `internal` 绑在同一个模块里，一行也借不出去**。这是本次整合要解决的核心矛盾。

---

## 3. PC 端质量问题清单

| # | 问题 | 证据 | 严重度 | 影响 | 修法 |
| --- | --- | --- | --- | --- | --- |
| Q1 | **单一类七职责** | `PhotoMgmCoreImpl.kt` 783 行：门面实现 / 流水线编排 / 视图派生 / 人工调整 / 导出快照 / 执行器生命周期 / 持久化协同 | 中 | 无法单测编排本身；安卓无法复用其中 ~60% | 拆三层（见 CROSSPLATFORM §4.1） |
| Q2 | **契约与实现同模块** | `api` 与 `logic/platform` 同属 `:core` | 中 | 契约无法被另一端引用 | 拆 `shared/app` 模块 |
| Q3 | **契约泄漏平台概念** | `Settings.window`、`ExportRequest.verifyMode`、`ExportResult.ledgerFile: Path` | 中 | 安卓被迫携带无意义字段 | 按 §2.3 拆分 |
| Q4 | **UI 隐式越层** | `desktop/ui` 5 处 `import algorithm.*`，但 `ui/build.gradle.kts` 未声明该依赖 | 中 | 一旦 core 改 `implementation` 即静默编译失败；"UI 只认契约"的声明失效 | 契约包再导出 + 显式声明依赖 |
| Q5 | **两套序列化** | PC `Json.kt` 279 行手写解析 + `SettingsRepository` 手工映射；安卓 `SettingsStore` 用 `org.json` 又写 160 行编解码 | 低 | 同一模型两套编解码，字段改名必漏一处 | 编解码上移 `shared/app/infra` |
| Q6 | **错误原因被吞** | `runCatching{}.getOrNull()` ≥ 8 处 | 中 | 台账缺图无法排障 | 统一改为带原因的返回值 |
| Q7 | **穷人版 DI** | `CoreConfig` 7 个可空参数，默认值里藏生产实现 | 低 | 测试易误触真实文件系统 | 端口组必填 + 生产工厂 |
| Q8 | **并发模型与安卓不一致** | `Executor` + `ScheduledExecutor` vs `viewModelScope` + 协程 | 低 | 取消语义两套，共享后难统一 | 共享层统一协程 + 注入调度器 |
| Q9 | **全量快照** | `AppState` 20 字段每次全量 `copy` | 低（已取舍） | Compose 下整树重组；当前规模可接受 | 记录为已知取舍，不修 |
| Q10 | **无版本迁移** | `settings.json` 无 `version` 字段；安卓已有 `markCacheZone()` 版本位 | 低 | 字段改名后用户设置静默丢失 | settings 加 `version` + 迁移函数 |
| Q11 | **包名带平台名** | `com.photomgm.desktop.core.*` 用于可复用概念 | 信息 | 迁移须改 62 行 import | 迁移时一次 `sed` |
| Q12 | **`cli` 是资产但被削弱** | `cli` 直接依赖 `:algorithm`，绕过门面 | 信息 | 门面可用性不再被反向验证 | 去掉直接依赖 |

> **正面结论**：Q1–Q12 没有一条是"方向错了"。PC 端的问题是**颗粒度**与**契约纯度**，不是架构选型。这意味着安卓端可以放心照抄它的骨架。

---

## 4. 安卓端现状对比（同四个维度）

### 4.1 塌陷点（与 PC 逐条对照）

| # | 安卓现状 | PC 对照 | 违反 |
| --- | --- | --- | --- |
| **A1** | UI 依赖**具体类**：`fun AppNav(vm: AppViewModel)`、`UnifiedScreen(vm: AppViewModel)`、`LogSection(vm:)`、`SettingsDialog(vm:)` | UI 依赖**接口** `core: PhotoMgmCore`（`Main.kt:195,274`） | S4 |
| **A2** | UI **调用领域函数**：`vm.effectiveUnmatched`（3 处）、`vm.previewEventMap`（2 处） | UI 只读 `AppState.unmatchedPhotoIds` / `eventPhotoIds` 快照，不计算 | S1 / S4 |
| **A3** | UI 直接 import 领域模型：`algorithm.model.LogEvent`、`algorithm.model.Photo`（`PreviewScreen.kt`、`EventViewer.kt`） | PC 的 `PhotoInfo`/`EventSnapshot` 是投影后的契约类型 | S1 |
| **A4** | `:app` 20 文件 5878 行**无任何编译期边界**；`data` 之外全在一层 | `internal` 封住五个包 | S3 |
| **A5** | `AppViewModel` 1165 行 = 编排 + 视图派生 + 导出 + SAF + 缩略图调度 + 缓存 | `PhotoMgmCoreImpl` 783 行已经够肥，安卓更肥 | S1 |
| **A6** | 重复 PC 已有逻辑约 600~700 行（`CROSSPLATFORM-ARCH` §1.4 的 D1–D12） | —— | S2 |
| **A7** | 目录组织与 PC 不对齐：PC 有 `component/page/dialog/theme/model` 六类；安卓 `ui/` 平铺 10 个文件，`PreviewScreen.kt` 1209 行含 16 个 Composable | —— | 可维护性 |

### 4.2 安卓端**比 PC 好**的地方（重构时不要丢掉）

1. **协程 + 结构化并发**：`viewModelScope`、`runCatchingCancellable`（取消信号不被吞）、`exportBusy.compareAndSet` 原子闸门——这三条比 PC 的 `Executor` + `AtomicBoolean` 更严谨，应作为共享层的并发基线。
2. **落盘防抖与存储隔离**：`LogsTextStore` + 独立 prefs 文件，注释里记录了"每敲一个字符重写 MB 级 XML"的原始故障。搬进共享层后 PC 也受益。
3. **踩坑注释密度**：`AppViewModel` 里对"缓存引用原子发布""时区版本位""三态折叠计数"的记录，是资产不是噪音，迁移时必须原样保留。

### 4.3 规模对比

| | PC | Android |
| --- | --- | --- |
| 逻辑核心 | 2927 行 / 17 文件（3 模块） | 5878 行 / 20 文件（1 个 UI 模块全包） |
| 最大单文件 | `PhotoMgmCoreImpl` 783 | `PreviewScreen` 1209 |
| 最大逻辑类 | 783 | `AppViewModel` 1165 |
| UI 测试 | 13 个纯 JVM（无 AWT 窗口） | 10 个 Robolectric（依赖真实 ViewModel） |

---

## 5. 安卓端重构目标

### 5.1 各层职责边界（对齐 PC，逐条可判定）

| 层 | 安卓模块 | 只允许做 | 明令禁止 |
| --- | --- | --- | --- |
| 表现层 | `:ui` | Compose 屏幕、组件、主题、手势、布局状态、把快照渲染成界面 | 调用业务函数；import `:data`；import `shared/domain`；持有 `Context` 做业务 |
| 应用层 | `:app`（含 ViewModel） | 组装端口、实现 `PhotoActions`、把 `AppState` 投影成 `PhotoUiState`、SAF/权限/生命周期 | 写业务规则；直接算台账/归属/去重 |
| 适配层 | `:data` | 实现 6 个端口（MediaStore / EXIF / Bitmap / Prefs / Uri Shell / 缩略图源） | 做业务判断；被 `:ui` 依赖 |
| 共享层 | `shared/*` | 算法、领域规则、用例编排、契约 | 任何平台 SDK（门禁拦） |

### 5.2 目标目录结构

```
android/
├── data/                                    :data —— 适配层（对齐 PC desktop/core/platform）
│   └── src/main/java/com/photomgm/data/
│       ├── source/MediaStorePhotoSourceProvider.kt      （已有，不动）
│       ├── exif/ExifPhotoReader.kt                      （已有；时间解析改调共享 ExifTime）
│       ├── image/BitmapImageEncoder.kt                  ★ 由 app/ImageUtils 的 process 部分迁来
│       ├── storage/PrefsSettingsStorage.kt              ★ 由 app/SettingsStore 的介质部分拆出
│       ├── storage/PrefsLogTextStorage.kt               ★ 新增（薄，仅 load/save）
│       ├── shell/UriShellLauncher.kt                    ★ 新增（FileProvider Intent）
│       └── hash/                                        （删除 → 共享 Sha256）
│
├── ui/                                      :ui —— 表现层（对齐 PC desktop/ui 的六类划分）
│   └── src/main/java/com/photomgm/ui/
│       ├── PhotoUiState.kt                              ★ 表现层快照（UI 只认它）
│       ├── PhotoActions.kt                              ★ 表现层动作契约（接口，由 :app 实现）
│       ├── theme/{Theme.kt, Tokens.kt, Type.kt}         ★ 由 theme/Theme.kt 181 行拆三类（对齐 PC theme/）
│       ├── component/                                   ★ 由 PreviewScreen/UnifiedScreen 拆出
│       │   ├── AppTopBar.kt  BottomActionBar.kt
│       │   ├── EventCard.kt  PhotoGrid.kt  PhotoTile.kt  Thumb.kt
│       │   └── DateField.kt  Notice.kt  BusyOverlay.kt  Scroll.kt
│       ├── dialog/                                      ★ 由三处内联 Dialog 拆出
│       │   ├── SettingsDialog.kt  MoveTargetDialog.kt
│       │   └── MarkLedgerDialog.kt  EventPhotosSheet.kt  UnmatchedSheet.kt
│       ├── page/
│       │   ├── UnifiedScreen.kt（现 391 行，保留骨架 + 导航）
│       │   ├── LogSection.kt  LogInputSection.kt
│       │   └── PreviewScreen.kt（1209 → 目标 ~250，只留装配）
│       └── model/{PhotoOrder.kt, PhotoSearch.kt, CalendarCells.kt}   ★ 纯 Kotlin UI 口径（对齐 PC model/）
│
└── app/                                     :app —— 组装根（对齐 PC DesktopCoreModule + Main）
    └── src/main/java/com/photomgm/app/
        ├── MainActivity.kt  PhotoMgmApp.kt
        ├── AppViewModel.kt                              （1165 → 目标 ≤300）
        ├── di/AndroidCoreModule.kt                      ★ 构造 shared 门面 + 注入 6 个端口
        ├── ThumbnailCache.kt                             （留在本层，UI 缩略图源）
        └── （删除）PhotoRepository.kt                     ← 被 shared AnalysisPipeline 取代
```

### 5.3 模块依赖（编译期强制，对齐 PC 的 `internal` 手段）

```
:app ──► :ui            （装配 + 实现 PhotoActions）
  │        └──► :shared:app       只 api 契约：PhotoUiState / PhotoInfo / EventSnapshot / DedupKeepMode
  ├──► :data ──► :shared:app（端口定义）+ :shared:domain
  └──► :shared:app（门面 PhotoMgmCore）+ :shared:domain

:ui  ✗→ :data        ✗→ :shared:domain      ✗→ :shared:algorithm
:data ✗→ :ui
```

三条硬规则（用 Gradle 依赖 + 门禁双向保证）：
1. `:ui` 的 `build.gradle.kts` **不声明** `:data`、`shared/domain`、`shared/algorithm` —— 想 import 也编不过。
2. `:ui` 暴露的 `PhotoUiState` 里**不含** `ParsedLog` / `ClassifyResult` / `Photo` 等核心类型，只含投影后的 `PhotoInfo` / `EventSnapshot` / 字符串文案。
3. 任何"计算"（排序、筛选、归属、台账）都在 `:app` 或共享层完成，`PhotoUiState` 只带结果。

### 5.4 数据流（单向，对齐 PC 的 StateFlow 快照）

```
用户手势 ──► PhotoActions.xxx()            （:ui 只调接口，不知道实现）
                  │
                  ▼
         AppViewModel（实现 PhotoActions）── 委派 ──► shared 用例（Analysis/Adjust/Export）
                  │                                        │
                  │◄──────── StateFlow<AppState> ──────────┘
                  ▼
         map(AppState) → PhotoUiState          （:app 内唯一投影点）
                  │
                  ▼
         :ui  collectAsState() ──► 重组        （UI 不做任何计算）
```

**这一步是安卓重构的核心收益**：A1/A2/A3 三个塌陷点一次消掉——UI 从此只看得见 `PhotoUiState` 与 `PhotoActions`，编译期不可能再调到 `effectiveUnmatched`。

### 5.5 与共享层的关系

共享层怎么切（`shared/algorithm` + `shared/domain` + `shared/app`）、共享界定标准（C1–C5 / P1–P3）、迁移步骤与风险矩阵，**见 `docs/CROSSPLATFORM-ARCH.md` §2–§7**，本文不重复。本文只补充安卓侧的消费方式：

| 共享能力 | 安卓侧怎么用 |
| --- | --- |
| `AnalysisPipeline` | `AppViewModel.analyze()` 直接调用，替代 `PhotoRepository` + `parseLogs` 里的编排 |
| `ViewDerivation` | 不再由 UI 调用；`AppState` 里已含 `eventPhotoIds` / `unmatchedPhotoIds` |
| `ManualAdjustment` | `movePhotos` / `markLedgerPhoto` / `resetOverrides` 委派，Android 天然获得 `copyPhotos`（决策点 D2） |
| `MediaSpec` | `BitmapImageEncoder` 只读常量，不再自带 400/300/50 |
| `Sha256` | `data/hash` 目录删除，改调共享实现 |
| `ExifTime` | `ExifPhotoReader` 内部改调；`ExifTimeParser.kt` 删除 |
| `DebouncedTextStore` | `AppViewModel` 只提供 `persist = { prefs.logsText = it }` |
| `Json` | 若采纳决策点 D3（缓存上移），`SettingsStore` 的 160 行手写编解码整体删除 |

---

## 6. 迁移对应关系（核心交付物）

### 6.1 逻辑类映射：PC 现行 → 共享目标 → 安卓现状 → 安卓目标

| PC 现行文件 | 职责 | 共享目标 | 安卓现状 | 安卓目标 | 动作 |
| --- | --- | --- | --- | --- | --- |
| `api/PhotoMgmCore.kt` (193) | 门面接口 + `CoreConfig` + `ShellLauncher` | `shared/app/api` | 无（UI 直接用 `AppViewModel`） | `:ui/PhotoActions` 实现它、`:app` 调用它 | 迁移 + 安卓实现 |
| `api/AppState.kt` (174) | 20 字段快照 | `shared/app/api`（拆出 `WindowState`） | `UiState` 39 字段（混了 `busy`/`exportOk`/`coarseCount`） | `PhotoUiState`（投影产物） | 重构为投影 |
| `api/ExportModels.kt` (76) | 导出契约 | `shared/app/api`（拆出 `ResumeVerifyMode`） | `exportProgress`/`exportMessage`/`exportOk`/`lastExport` 散在 `UiState` | 同 PC 的 `ExportProgress`/`ExportResult` | 对齐 |
| `logic/PhotoMgmCoreImpl.kt` (783) | 编排 | 拆 `shared/app`（用例+状态机）+ `shared/domain`（派生） | `AppViewModel` 1165 行 | ≤300 行（投影 + SAF + 缩略图） | 双向收敛到共享 |
| `platform/PcPhotoSourceProvider.kt` (101) | 目录扫描 | 平台专属 | `MediaStorePhotoSourceProvider` (47) | `data/source/`（不动逻辑） | 仅换目录 |
| `platform/PcExifReader.kt` (80) | EXIF | 平台专属 + 时间解析上移 | `ExifPhotoReader` (38) + `ExifTimeParser` (31) | `data/exif/`，删 `ExifTimeParser` | 合并 |
| `platform/PcFileHasher.kt` (~66) | SHA-256 | **`shared/domain/hash`** | `FileHasher` (27) | 删除 | 上移 |
| `platform/PcImageEmbedder.kt` (124) | 台账嵌图 | 平台专属 + `MediaSpec` 上移 | `ImageUtils` (187) | `data/image/BitmapImageEncoder.kt` | 上移规格 + 换目录 |
| `platform/PcShell.kt` | 打开/定位 | 平台专属 | 无（散落在 `openExportedFile`） | `data/shell/UriShellLauncher.kt` | 新增端口实现 |
| `platform/PcPaths.kt` (100) | 路径口径 | 平台专属（不上移） | 无（用 `Uri`/`File` 混用） | 不上移；`data` 内部自管 | 不迁 |
| `platform/PhotoIdAllocator.kt` (89) | 稳定 id | 平台专属（iOS 用 MediaStore `_ID`） | 无（用 `_ID`） | 不需要 | 不迁 |
| `export/PcFolderExporter.kt` (474) | 文件夹导出 | 平台专属（计划层已在 `algorithm`） | `AlgorithmApi.exportZip` + SAF | 保持 ZIP 形态 | 不迁 |
| `export/ExportManifest.kt` / `ExportLogWriter.kt` | 清单/日志 | 平台专属 | 无（ZIP 有 stateFile） | 不需要 | 不迁 |
| `state/SettingsRepository.kt` (100) | 设置持久化 | JSON 编解码上移 | `SettingsStore` (353) | `data/storage/PrefsSettingsStorage.kt` | 拆介质/编码 |
| `state/LogsTextStore.kt` (75) | 防抖落盘 | **`shared/app/infra`** | `LogsTextStore` (89) | 删除（改调共享） | 上移 |
| `infra/Json.kt` (279) | JSON | **`shared/app/infra`** | `org.json` 手写 | 删（若采纳 D3） | 上移 |

### 6.2 UI 目录映射：PC 的组织方式 → 安卓目标

| PC `desktop/ui` | 行数 | 安卓现状 | 安卓目标 |
| --- | --- | --- | --- |
| `theme/Theme.kt` + `theme/Tokens.kt`（色彩/间距/字号分离） | 181 + 135 | `theme/Theme.kt` 181 行（24 个 Light + 24 个 Dark 硬编码色，无 Tokens） | `ui/theme/{Theme, Tokens, Type}` 三文件 |
| `component/`（8 个：BusyOverlay/Controls/DateField/EventCard/Notice/Press/Scroll/TextField） | 1715 | 散在 `ui/` 顶层（AppTopBar/BottomActionBar/AdaptiveDatePicker）+ `PreviewScreen` 内联（ThumbCell/Thumb/PhotoTile） | `ui/component/` 10 个文件 |
| `dialog/`（6 个：AssignSheet/ConfirmSheet/DatePickerLayer/PhotosSheet/SettingsSheet/SheetHost） | 1635 | 内联在 `PreviewScreen`（MoveTargetDialog 109 / MarkLedgerDialog 87 / EventPhotosSheet 191 / MoveTargetDialogWithSearch 140）、`SettingsDialog` 379 | `ui/dialog/` 5 个文件 |
| `page/`（3 个：LogBar/ResultBanner/WorkspacePage） | 729 | `UnifiedScreen` 391 + `LogSection` 297 + `PreviewScreen` 装配段 | `ui/page/` 4 个文件 |
| `model/`（8 个纯 Kotlin：WorkQueue/PhotoSearch/Calendar/CardEdge/Layout/Feedback/LedgerJoin/UiState） | 1224 | **无对应层**（排序/筛选写在 `PreviewScreen` 内） | `ui/model/` 3 个（PhotoOrder/PhotoSearch/CalendarCells） |
| `format/Format.kt` | ~60 | 无（`PreviewScreen` 内联 `DateTimeFormatter`） | `ui/format/Format.kt` |
| `platform/{NativeDialogs, ShellActions}` | ~150 | 无（散在 `AppViewModel.pickSourceDir`） | `:app` 内（SAF 属应用层职责，不上 UI） |
| `image/Thumbnails.kt` | 150 | `ThumbnailCache` 130（在 `:app`） | 留 `:app` |

**最大的一处落差**：`PreviewScreen.kt` 1209 行 / 16 个 Composable，把"对话框 + 卡片 + 网格 + 缩略图 + 装配"全塞在一起。按 PC 的粒度拆开后，最大文件约 250 行。这也是 A7 的具体落点。

### 6.3 新增 / 删除 / 移动清单（安卓侧）

**新增（8 个文件）**
| 文件 | 职责 |
| --- | --- |
| `ui/PhotoUiState.kt` | 表现层快照（40 行） |
| `ui/PhotoActions.kt` | 动作契约接口（30 行） |
| `ui/theme/Tokens.kt`、`ui/theme/Type.kt` | 从 `Theme.kt` 抽常量与排版 |
| `ui/model/PhotoOrder.kt`、`PhotoSearch.kt`、`CalendarCells.kt` | 排序/搜索/日历格口径（纯 Kotlin，可单测） |
| `app/di/AndroidCoreModule.kt` | 组装根：构造 `shared` 门面 + 注入 6 端口 |
| `data/shell/UriShellLauncher.kt` | `ShellLauncher` 的 Uri/FileProvider 实现 |

**删除（5 个文件）**
| 文件 | 原因 |
| --- | --- |
| `app/PhotoRepository.kt` | 被 `shared/app/AnalysisPipeline` 取代 |
| `app/LogsTextStore.kt` | 上移 `shared/app/infra` |
| `app/Cancellable.kt` | 上移 `shared/app/infra` |
| `data/ExifTimeParser.kt` | 上移 `shared/domain/exif` |
| `data/FileHasher.kt` | 上移 `shared/domain/hash` |

**移动（4 个文件，逻辑不变）**
| 从 | 到 |
| --- | --- |
| `app/ImageUtils.kt` 的 `process`/`processBitmap` 段 | `data/image/BitmapImageEncoder.kt` |
| `app/SettingsStore.kt` 的介质读写段 | `data/storage/PrefsSettingsStorage.kt` |
| `app/ui/*`（10 个文件） | `ui/{component,dialog,page}/`（按 §6.2 拆） |
| `app/theme/Theme.kt` | `ui/theme/Theme.kt` |

### 6.4 目录迁移的"同名对齐"原则

重构后两端目录**逐层同构**，这样任何一处改动都能在另一端找到对称位置：

```
desktop/core/platform   ←→  android/data           （端口实现）
desktop/core/export     ←→  （无对应，平台形态不同）
desktop/core/（组装根）   ←→  android/app/di        （组装根）
desktop/ui/component    ←→  android/ui/component
desktop/ui/dialog       ←→  android/ui/dialog
desktop/ui/page         ←→  android/ui/page
desktop/ui/model        ←→  android/ui/model
desktop/ui/theme        ←→  android/ui/theme
desktop/cli             ←→  （建议补一个 headless androidTest 入口，可选）
```

---

## 7. 分阶段迁移计划（安卓侧重构）

前置：`CROSSPLATFORM-ARCH.md` 的 **P0/P1** 必须先完成（共享模块骨架 + 纯函数上移）。下面是安卓侧的动作序列。

| 阶段 | 内容 | 改动面 | 验证 | 回滚 |
| --- | --- | --- | --- | --- |
| **A0** 基线 | 录安卓侧黄金样本：一段日志 + 50 张照片，记录 ZIP 条目清单 + 每条目哈希 + 台账 xlsx 单元格文本；另录 UI 手工清单（12 项交互） | 0 代码 | 样本可复现 | — |
| **A1** 适配层归位 | `ImageUtils`→`data/image`、`SettingsStore` 介质段→`data/storage`、新增 `UriShellLauncher`；`FileHasher`/`ExifTimeParser` 删除改调共享 | ~6 文件，逻辑不变 | 单测 + 黄金样本全等 | 单提交可回退 |
| **A2** 契约化（**最关键**） | 新增 `PhotoUiState` + `PhotoActions`；`AppViewModel` 输出 `StateFlow<PhotoUiState>`；UI 去掉 `vm.effectiveUnmatched`/`vm.previewEventMap`/`algorithm.model.*` 的调用，改读快照；`PreviewScreen` 等 4 个文件的 `vm:` 参数换成 `actions:` | 10 个 UI 文件（签名 + 取数），~150 行改动 | 编译通过即证明 A1/A2/A3 已消；12 项手工清单逐条过 | 独立提交 |
| **A3** ViewModel 瘦身 | 委派给 `shared` 用例；删除 `PhotoRepository`；`LogsTextStore`/`Cancellable` 上移。**逐段搬运，保留全部踩坑注释** | 1 个主文件 + 3 个删除 | 10 个既有测试全绿（`ExportGateTest` 等）；黄金样本全等 | 分两提交 |
| **A4** UI 目录重排 | 按 §6.2 拆 `PreviewScreen`（1209→~250）、`Theme` 拆三类、新建 `model/` 三个纯函数 | 10 → 22 文件 | 手工 12 项 + 现有 4 个 UI 测试 | 单独提交，可只回退本步 |
| **A5** 模块拆分（可选） | 把 `ui/` 提成 `:ui` 模块，`app/` 只留装配；加 Gradle 依赖规则 + 门禁 | 1 个新模块 | `:ui` 编译期无法 import `:data` | 最后做，可长期不做 |

**顺序不可颠倒的理由**：A2 必须在 A3 之前——先把 UI 与 ViewModel 解耦，才能在 A3 大胆改 ViewModel 内部；反过来做，A3 的每次改动都会同时打破 UI 编译。

---

## 8. 风险与对策（安卓重构特有）

| # | 风险 | 触发 | 后果 | 等级 | 对策 |
| --- | --- | --- | --- | --- | --- |
| AR1 | **Robolectric 测试大面积红** | A3 改 `AppViewModel` 构造签名 | 10 个测试依赖真实 `ViewModel` + `Application` | **高** | 保留测试专用工厂（`AppViewModel.forTest(ports)`）；断言不改只改装配；必要时先把断言迁到 `shared` 的纯 JVM 测试 |
| AR2 | **Compose 状态提升出错** | A2/A4 拆 `PreviewScreen` | 选中态/滚动位置/弹窗开关丢失（用户可感知） | **高** | 用 `rememberSaveable` 的现有 Saver 逐段搬；12 项手工清单逐条验；每步只拆一个 Composable |
| AR3 | **SAF 授权泄漏 Activity** | A1 抽 `UriShellLauncher` | 内存泄漏 / 后台崩溃 | 中 | 端口只接 `ApplicationContext`；`Uri` 持久化权限在 `:app` 侧申请 |
| AR4 | **踩坑修复丢失** | A3 "顺手重写" ViewModel | 导出重入、取消被吞、缓存陈旧 → 静默丢数据 | **高** | 逐段搬运 + 保留注释；`ExportGateTest`/`SetLogsTextWiringTest`/`ThumbnailCacheWiringTest` 作为护栏 |
| AR5 | **投影层成为新上帝类** | `PhotoUiState` 字段照搬 `UiState` 39 个 | 换个地方继续胖 | 中 | 目标是"只含 UI 需要的"，`busy`/`coarseCount` 等能由 `status` 派生的不单列 |
| AR6 | **拆模块后构建变慢** | A5 | 每次构建多一个 AGP 模块 | 低 | A5 可长期不做；先靠门禁保证规则 |
| AR7 | **两端行为再次漂移** | 共享层改了只测一端 | 台账/ZIP 不一致 | 中 | 沿用 `CROSSPLATFORM-ARCH` R10：CHANGELOG 标 `affects:`，双端跑黄金样本 |

---

## 9. 验收标准

**结构（自动）**
1. `:ui` 的 Gradle 依赖里没有 `:data` / `shared/domain` / `shared/algorithm`（门禁断言 `build.gradle.kts`）。
2. `ui/` 下零 `import android.content.Context`、零 `import algorithm.`、零 `import com.photomgm.data.`。
3. `AppViewModel.kt` ≤ 300 行；`PreviewScreen.kt` ≤ 300 行；最大 Composable 文件 ≤ 350 行。
4. 两端目录同构度：`component/dialog/page/model/theme` 五类在两端都存在且语义对应（§6.4）。

**行为（自动 + 人工）**
5. 黄金样本：ZIP 条目清单 + 每条目 SHA-256 **全等**；台账 xlsx 单元格文本 + 嵌入图片字节 **全等**。
6. 测试：安卓 15 个（app 10 + data 5）全绿；新增 `ui/model/` 纯函数测试 ≥ 15 条。
7. `:app:assembleRelease` 成功，真机安装后完成 12 项手工清单。

**质量（人工复核）**
8. `PhotoUiState` 内不含任何 `algorithm.*` 类型。
9. `AppViewModel` 内出现的每一个 `//` 长注释，都能在 A3 之前的版本里找到出处（证明搬运而非重写）。

---

## 10. 待拍板决策点

与 `CROSSPLATFORM-ARCH.md` §9 的 D1–D5 合并看，安卓侧额外需要你定三条：

| # | 决策 | 选项 | 建议 |
| --- | --- | --- | --- |
| **A1** | UI 与 ViewModel 之间是否引入 `PhotoActions` 接口 | A 引入（A2 阶段）/ B 继续直接传 `AppViewModel` | **A**：这是消掉"UI 调领域函数"的唯一干净办法；代价是 4 个文件改签名 |
| **A2** | `PreviewScreen` 是否拆成 5 个文件 | A 拆（对齐 PC 的 component/dialog 粒度）/ B 保留 1209 行 | **A**：拆分本身是纯机械动作，且有 12 项手工清单兜底 |
| **A3** | 是否新建 `:ui` 模块（A5） | A 做 / B 暂不做，先靠门禁 | **B（先不做）**：等 `PhotoUiState` 稳定一轮再拆，避免契约变动时反复改模块边界 |

---

## 11. 本次不做（scope out）

- 不改任何 Compose 视觉与交互（拆文件不等于改设计）。
- 不换 Compose BOM、不升 AGP/Kotlin、不引入 Hilt/Koin（`AndroidCoreModule` 手写装配即可，7 个端口不值得上框架）。
- 不动 `MainActivity` / 权限声明 / Manifest。
- 不做性能优化（除拆分后自然减少的重组范围）。
- 不合并两端的 UI 代码（设计原则明确：UI 各自实现）。

---

## 附录 B · 一句话记住这次重构

> **把 PC 端已经验证过的四条（接口门面、端口注入、不可变快照、编译期封层）复制到安卓端，同时修掉 PC 端的两处不纯（契约带平台概念、UI 越层）。**
> 结果不是"安卓变成 PC 的样子"，而是"两端共享同一套规则，各自保留自己的实现"——这才是跨平台工程的标准形态。
