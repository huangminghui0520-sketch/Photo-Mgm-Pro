# 跨端整合方案：共享核心 + 平台适配（CROSSPLATFORM-ARCH）

> 状态：**待审核**。本文件只描述方案，**未改动任何代码**（`git status` 干净，HEAD = `c0eef6b`）。
> 审核通过后再按 §6 的 P0~P4 顺序落地，每阶段独立提交、可单独回滚。
>
> 上游文档：`docs/REPO-LAYOUT.md`（目录重组与共用核心风险矩阵）、`docs/WINDOWS-DESKTOP-PLAN.md`（PC 端技术方案）。

---

## 0. 结论速览

先给三句判断，后面全部是证据。

| 问题 | 结论 |
| --- | --- |
| 现在的共享是否做对了？ | **算法层做对了，但只覆盖 14%**。`algorithm/` 18 文件 2564 行，零平台依赖、零实现依赖、两端挂同一份源码——这部分是教科书级的正确。 |
| 剩下的 86% 呢？ | **平台层已经就位，编排层完全没共享**。PC 的 `PhotoMgmCoreImpl`(783) 与 Android 的 `AppViewModel`(1165) 是同一套流水线的两份实现，重复约 **600~700 行**，且已经出现 **2 处口径分歧**（照片派生判定、有效归属计算）。 |
| 该怎么整合？ | 在现有"纯 Kotlin 共享模块 + 端口注入"的路子上**再补两级**（`domain` / `app`），**不引入 KMP**。理由见 §2.5：当前 Android 端用的是 androidx Compose 而非 Compose Multiplatform，改 KMP 等于换构建体系，收益与风险不成比例。 |

一句话目标：**把"两端行为必须一致的东西"全部搬到 `shared/`，把"只能这么做的东西"钉死在平台层，中间用端口（接口）隔开，并用静态门禁把这条线固化下来。**

---

## 1. 现状测绘（实测，非估算）

### 1.1 规模

| 模块 | 主源码 | 测试 | 性质 |
| --- | --- | --- | --- |
| `algorithm/` | 18 文件 / 2564 行 | 22 文件 / 4255 行 | **已共享** |
| `desktop/core/` | 17 文件 / 2927 行 | 9 文件 / 1148 行 | PC 逻辑核心（api + logic + platform + export + state + infra 六包混在一起） |
| `desktop/ui/` | 38 文件 / 6689 行 | 13 文件 / 1400 行 | PC 界面（Compose Desktop） |
| `desktop/cli/` | 1 文件 / 383 行 | — | PC 无头入口 |
| `android/data/` | 4 文件 / 143 行 | 5 文件 / 385 行 | Android 数据接入 |
| `android/app/` | 20 文件 / 5878 行 | 10 文件 / 1365 行 | Android 界面 + **编排 + 持久化 + 图片处理全在一个模块** |
| 合计 | **98 文件 / 18584 行** | 59 文件 / 8553 行 | 共享率 **13.8%** |

### 1.2 依赖方向（实测 `build.gradle.kts`）

```
desktop/ui ──► desktop/core ──► algorithm ◄── android/app ◄── android/data
     │              │                              │
     └─ Compose     └─ metadata-extractor          └─ androidx(Compose/Exif/MediaStore)
        Desktop        POI(test)                       org.json
```

结论：**依赖方向是干净的**（核心不反向依赖任何端），问题不在"依赖错乱"，而在"共享的边界划得太靠下"——只共享了算法，没共享编排。

### 1.3 已正确共享的部分（不动）

`algorithm/` 全部 18 个文件。实测其 `import` 面只有 `java.time.*`、`java.io.{File,FileOutputStream,...}`、`java.util.zip.*`、`kotlin.math.*`，`build.gradle.kts` 里 **没有任何 implementation 依赖**（只有 3 个 test 依赖）。台账 `XlsxWriter`(575)、`ZipExporter`(372)、`EventClassifyEngine`(245)、`Dedup`(262) 都在这里，且被 22 个纯 JVM 测试覆盖。

**这部分是本次整合的地基，一行不改。**

### 1.4 重复清单（带证据）

| # | 重复内容 | PC 位置 | Android 位置 | 规模 | 是否已分歧 |
| --- | --- | --- | --- | --- | --- |
| D1 | 分析流水线编排（粗筛→派生去重→EXIF→分组→分类） | `PhotoMgmCoreImpl.kt:247-353` | `AppViewModel.kt:506-643` | ~240 行 | 否（步骤一致） |
| D2 | 人工调整后的有效归属 `effectiveMap` | `:442` | `:1143` | 各 ~20 行 | **是**：PC 支持"复制到多事件"(`copyMap`)，Android 没有 |
| D3 | 三态展开 `expandedEventMap` | `:464` | `:684` | 各 ~22 行 | 否（逐行同构） |
| D4 | 有效未匹配 `effectiveUnmatched` | `:479` | `:1159` | 各 ~8 行 | 否 |
| D5 | 内容判重分组 + 缓存 `dupGroups` | `:486` | `:664` | 各 ~15 行 | 否（缓存机制不同：PC 用引用比较，Android 用原子引用） |
| D6 | 台账行投影 `buildLedgerRows`/`buildRows` + `labelOf` | `:495`、`:514` | `:645` | 各 ~25 行 | 否 |
| D7 | 人工移动 / 台账标记 / 重置 | `:355`、`:409`、`:420` | `:748`、`:1129`、`:769` | ~60 行 | **是**：PC 有 `copyPhotos`，Android 无对应功能 |
| D8 | 台账嵌图规格 400×300 / JPEG50 / 4:3 | `PcImageEmbedder.kt` 常量区 | `ImageUtils.kt:20-27` | 2 套常量 | 值一致，**两份定义** |
| D9 | EXIF 时间字符串解析 | `PcExifReader.kt` 的 `parseExifDateTime` | `ExifTimeParser.kt` 全文件 | ~30 行 | **是**：PC 只认 `yyyy:MM:dd`，Android 兼容 `-` 分隔 |
| D10 | SHA-256 文件哈希 | `PcFileHasher.kt` | `FileHasher.kt` | ~25 行 | 否（PC 多两个重载） |
| D11 | 日志文本落盘防抖（400ms） | `LogsTextStore.kt`（ScheduledExecutor） | `LogsTextStore.kt`（协程 delay） | 各 ~75 行 | 否（策略同，机制不同）→ **已消灭**（T05-C2：策略在 `app/infra/DebouncedTextStore`，两端只剩调度适配） |
| D12 | 派生文件后缀判定 `isDerivedName` | `PhotoMgmCoreImpl.kt:605`（`contains` 宽松判定） | 无（Android 不做角标） | ~6 行 | **是**：核心 `Dedup.kt:110 DerivedNames.isDerived` 是正则严格版，PC 这份是"再写一遍的宽松版" |

合计**确定性重复约 600~700 行**，其中 D2/D7/D9/D12 已有可观测的行为分歧。

### 1.5 三个结构性问题

1. **`desktop/core` 一个模块干了五件事**：契约（api）、编排（logic）、平台（platform）、导出（export）、持久化（state）+ 手写 JSON（infra）。它的 `internal` 可见性让 UI 无法越层，这是优点；但它整体只能留在桌面端，导致 Android 无法复用其中真正通用的 60%。
2. **Android 端没有"层"**：`android/app` 20 个文件里，`AppViewModel` 同时是编排器、状态容器、导出器、SAF 适配器、缩略图调度器。1165 行里约 700 行与平台无关，却因为和 `Context` 缠在一起而搬不动。
3. **共享契约里混进了平台概念**：`Settings` 带 `window: WindowState`（窗口几何，Android 无意义）、`ExportRequest` 带 `ResumeVerifyMode`（文件夹断点校验，Android 走 ZIP 状态文件）。这说明"契约"当初是照着 PC 写的，不是照两端写的。

---

## 2. 目标分层与目录

### 2.1 四层职责

| 层 | 职责 | 放哪 | 允许依赖 |
| --- | --- | --- | --- |
| **表现层 UI** | 屏幕、组件、主题、手势、字体、动画 | `desktop/ui`、`android/app/ui` | 只能依赖本端 + `shared/app` 的**契约**（AppState 等），禁止依赖 `shared/domain` |
| **应用层 App** | 用例编排、状态机、进度/取消、端口定义、跨端契约 | `shared/app` | `shared/domain`、`shared/algorithm` |
| **领域层 Domain** | 不依赖任何外部世界的业务规则：视图派生、人工调整、台账投影、命名规则、媒体规格、哈希、EXIF 时间 | `shared/domain` | `shared/algorithm` |
| **算法内核 Algorithm** | 纯计算：解析、分组、分类、去重、台账、OOXML、ZIP | `shared/algorithm` | 无（现状保持） |
| **平台适配层** | 端口实现：目录扫描、MediaStore、EXIF 读取、图片编解码、文件/偏好持久化、系统外壳、断点续传执行 | `desktop/core`、`android/data` | 本端 SDK + 端口定义（`shared/app/port`） |

依赖恒为**单向向下**：`UI → app → domain → algorithm`；平台适配层**只向上实现端口**，被装配根注入，不被上层 import。

### 2.2 目标目录结构

```
Photo-Mgm/
├── shared/                                    ★ 共享层：纯 Kotlin JVM，零平台 SDK
│   ├── algorithm/                             现有 18 文件 2564 行 —— git mv，内容零改动
│   │   ├── build.gradle.kts                   kotlin("jvm") + toolchain 17（不变）
│   │   └── src/main/kotlin/algorithm/…        包名保持 algorithm，避免动 22 个测试
│   ├── domain/                                ★ 新增：业务规则（约 500 行，全部从两端上移）
│   │   └── src/main/kotlin/photomgm/domain/
│   │       ├── view/ViewDerivation.kt         effectiveMap / expandedEventMap / effectiveUnmatched / dupGroups
│   │       ├── adjust/ManualAdjustment.kt     move / copy / mark / reset 的纯状态变换
│   │       ├── ledger/LedgerProjection.kt     LedgerRow → LedgerRowInfo + labelOf
│   │       ├── exif/ExifTime.kt               EXIF 时间解析（合并 D9 两版，取并集）
│   │       ├── hash/Sha256.kt                 SHA-256（合并 D10 两版）
│   │       ├── spec/MediaSpec.kt              400×300 / JPEG50 / 4:3 —— 唯一常量（消灭 D8）
│   │       ├── spec/ImageExtensions.kt        支持的扩展名 / HEIC 判定
│   │       └── naming/DerivedName.kt          派生后缀判定（唯一实现，消灭 D12）
│   └── app/                                   ★ 新增：应用层（约 800 行）
│       └── src/main/kotlin/photomgm/app/
│           ├── api/PhotoMgmCore.kt            门面（现 desktop/core/api/PhotoMgmCore.kt 上移）
│           ├── api/AppState.kt                状态契约（拆出 WindowState 等平台字段）
│           ├── api/Settings.kt                设置契约（同上）
│           ├── api/ExportModels.kt            导出契约（拆出 ResumeVerifyMode）
│           ├── port/PhotoSourceProvider.kt    ← 已在 algorithm，保持原处
│           ├── port/ExifReader.kt             ← 已在 algorithm，保持原处
│           ├── port/ImageEncoder.kt           ★ 新增：ref → 台账用 JPEG 字节
│           ├── port/SettingsStorage.kt        ★ 新增：load/save Settings
│           ├── port/LogTextStorage.kt         ★ 新增：load/save 日志文本
│           ├── port/ShellLauncher.kt          ★ 新增（PC 已有，Android 补）
│           ├── usecase/AnalysisPipeline.kt    流水线（消灭 D1）
│           ├── usecase/AdjustUseCase.kt       人工调整（消灭 D2/D7）
│           ├── usecase/ExportUseCase.kt       导出编排（快照/进度/取消，形态仍由平台执行）
│           ├── usecase/SettingsUseCase.kt     设置读写 + 失效传播
│           ├── state/AppStateStore.kt         StateFlow 快照发布（锁内一次性发布）
│           └── infra/DebouncedTextStore.kt    防抖落盘（消灭 D11）
│           └── infra/Json.kt                  手写 JSON（由 desktop/core/infra 上移）
│           └── infra/Cancellation.kt          取消信号（合并 PC Operation + Android Cancellable）
│
├── desktop/                                   ★ 平台层（Windows）
│   ├── core/                                  → 收窄为「适配器 + 组装根」（2927 → ~1200 行）
│   │   └── src/main/kotlin/com/photomgm/desktop/core/
│   │       ├── DesktopCoreModule.kt           ★ 组装根：把 PC 适配器注入 shared 门面
│   │       ├── platform/  PcPaths / PcPhotoSourceProvider / PcExifReader / PcImageEncoder
│   │       │              PcSettingsStorage / PcLogTextStorage / PcShell / PhotoIdAllocator
│   │       └── export/    PcFolderExporter / ExportManifest / ExportLogWriter（PC 专属形态）
│   ├── ui/                                    6689 行 —— 仅 import 机械改动（§6 P2）
│   ├── cli/                                   383 行 —— 仅 import 机械改动
│   └── packaging/
│
└── android/                                   ★ 平台层（Android）
    ├── data/                                  143 → ~230 行（端口实现变多，视图逻辑变少）
    │   └── MediaStorePhotoSourceProvider / ExifPhotoReader / BitmapImageEncoder
    │       PrefsSettingsStorage / PrefsLogTextStorage / UriShellLauncher
    └── app/                                   5878 → ~4600 行
        ├── AppViewModel.kt                    1165 → ~250：UiState 投影 + SAF + 缩略图 + 委派
        ├── SettingsStore.kt / ThumbnailCache.kt / ui/…   （UI 全部不动）
        └── （删除）PhotoRepository.kt —— 被 shared AnalysisPipeline 取代
```

### 2.3 Gradle 挂载（沿用现有手法，不建第三个根工程）

现有做法已证明可行：**两个独立根工程各自以"外部目录挂载"引入共享模块**，共享源码只有一份，产物目录各自隔离。

```kotlin
// desktop/settings.gradle.kts
include(":shared:algorithm"); project(":shared:algorithm").projectDir = file("../shared/algorithm")
include(":shared:domain");    project(":shared:domain").projectDir    = file("../shared/domain")
include(":shared:app");       project(":shared:app").projectDir       = file("../shared/app")

// android/settings.gradle.kts
include(":shared:algorithm"); project(":shared:algorithm").projectDir = file("../shared/algorithm")
include(":shared:domain");    project(":shared:domain").projectDir    = file("../shared/domain")
include(":shared:app");       project(":shared:app").projectDir       = file("../shared/app")
```

要点：
- 用**嵌套项目名** `:shared:app` 避免与 Android 的 `:app` 撞名（这是必须的，`include(":app")` 会冲突）。
- `desktop/build.gradle.kts` 里的产物重定向要从 `path == ":algorithm"` 改成 `path.startsWith(":shared:")`，把三个共享模块的 `buildDirectory` 都挪进 `desktop/build/external/`，否则两端并发构建会互踩。
- 共享模块 `build.gradle.kts` 一律：`kotlin("jvm")` + `jvmToolchain(17)` + `dependencies { api(project(...)) }`，**禁止** AGP、Compose、androidx。

### 2.4 为什么不引入 KMP

| 方案 | 收益 | 代价 | 结论 |
| --- | --- | --- | --- |
| **A. 纯 Kotlin JVM 共享模块 + 端口注入**（本方案） | 与现状同构，两端零构建改造；端口即接口，可单测 | 需自己保证"共享模块不用平台 API"（靠门禁） | **采用** |
| B. Kotlin Multiplatform + expect/actual | 编译期强制平台隔离；未来可扩 iOS/Web | Android 需换 KMP 插件体系（Android 端是 androidx Compose，不是 CMP），AGP/Kotlin 版本联动，R8/打包链全部重验；PC 端 jpackage 链也需重验 | 否决（收益在未来，代价在当下） |
| C. 抽成独立发布物（Maven 私服） | 版本可独立演进 | 双端日常联调变成"发版—升级"，反馈环变长 | 否决 |

> 备注：若将来要扩 iOS/Web，`shared/` 三层的边界与端口定义**可以原样迁到 KMP**（domain 层本来就是纯 Kotlin），届时只换构建，不重划边界。这是刻意保留的演进路径。

---

## 3. 共享代码的界定标准

判断"这段代码该不该进 `shared/`"，只看下面 5 条准入 + 3 条排除。**可机械校验**（§3.3）。

### 3.1 准入（五条全满足才进共享）

| 编号 | 标准 | 判定方法 |
| --- | --- | --- |
| **C1** | **纯逻辑**：相同输入必得相同输出；不读系统时间/时区/环境变量/文件系统/网络 | 通读函数体，看是否出现 `System.*`、`LocalDate.now()`（无参）、`File(`、`env` |
| **C2** | **零平台 SDK**：只用 `kotlin.*`、`java.time`、`java.io`、`java.util`、`java.security`、`java.util.zip`、`kotlinx.coroutines` | 扫 import（门禁） |
| **C3** | **两端语义必须一致**：不存在"PC 要这样、Android 要那样"的分支 | 若出现 `if (isAndroid)` 或两套默认值 → 出局 |
| **C4** | **单一事实来源**：任何业务常量（尺寸、容差、窗口、时区、后缀）只能在这里定义一次 | 全局 grep 该常量的字面值，>1 处即违规 |
| **C5** | **可独立测试**：能被纯 JVM 单测覆盖，不依赖 Robolectric / AWT / 真机 | 新模块必须有对应测试才准合并 |

### 3.2 排除（命中任一条即留平台层）

| 编号 | 排除项 | 例子 |
| --- | --- | --- |
| **P1** | 依赖平台 SDK | `java.nio.file`（Android 侧路径语义不成立）、`javax.imageio`/`java.awt`（Android 无）、`android.*`、`androidx.*`、`org.json`、Compose |
| **P2** | 涉及权限 / 生命周期 / 窗口 / 线程调度归属 | SAF 授权、`Activity` 结果回调、窗口几何、`Dispatchers.Main` 切换、缩略图 LruCache |
| **P3** | 单端产品形态的"执行" | PC 的文件夹导出 + 断点续传校验 + `export.log`；Android 的 ZIP 写回 SAF；Windows 的 `ShellLauncher` 实现 |

> **P3 的细分很重要**：单端形态的**"计划"要上移，"执行"留平台**。例如"哪个事件进哪个文件夹、重名怎么加 `_2`"是业务规则（已在 `algorithm.ZipExporter.numberedFolderNames`，正确）；"怎么把文件写到磁盘"是执行（留平台）。这条区分是本次判断最容易出错的地方。

### 3.3 机械校验（新增门禁 `scripts/check-shared-purity.py`）

```
对 shared/ 下所有 .kt：
  ① 禁止 import：java.awt / javax.imageio / javax.swing / java.nio.file
                  android. / androidx. / org.json / androidx.compose
  ② 禁止无参时间：LocalDate.now() / LocalDateTime.now() / Instant.now() / ZoneId.systemDefault()
  ③ 禁止 System.getProperty / System.getenv
  ④ 禁止字面量 400×300 / 50（JPEG 质量）/ 1.0（秒容差）等已收编常量重复出现
对 desktop/ 与 android/ 的 UI 层：
  ⑤ 禁止 import shared/domain 与 shared/algorithm（UI 只许认契约，防止再次绕开门面）
```

第 ⑤ 条是**这次才需要的**：现在 PC 的 `desktop/ui` 有 5 处直接 `import algorithm.*`（`DedupKeepMode` 3 处、`AppZone` 2 处），而 `desktop/ui/build.gradle.kts` 根本没有声明 `:algorithm` 依赖——靠 `core` 的 `api` 传递才编过。这是隐式越层，P2 阶段用 typealias 或把枚举放进 `shared/app/api` 修掉。

### 3.4 边界模糊案例的裁定（先定规矩，避免落地时吵）

| 案例 | 裁定 | 理由 |
| --- | --- | --- |
| `WorkQueue` / `PhotoSearch` / `Calendar` / `Layout`（PC 的 1000 行纯 Kotlin UI 状态机） | **不进共享** | 它们是"怎么显示"，不是"是什么"。按既定设计原则 UI 层各自实现。但**照片先后口径**（时间→文件名）是业务语义，单独抽 `domain.view.PhotoOrder` 供两端引用，防止"PC 按拍摄时间排、Android 按名称排"这种静默漂移。 |
| 缩略图生成 / 缓存 | **不进共享** | P1（ImageIO vs Bitmap）+ P2（LruCache 生命周期） |
| 导出 ZIP vs 文件夹 | **不进共享（执行层）**，计划层已在 `algorithm` | P3 |
| 设置持久化 | **拆两半**：`Settings` 契约 + JSON 编解码进共享；读写介质留平台 | C1 不满足（介质），但"字段名单/默认值"必须唯一 |
| 派生文件后缀判定 | **进共享**（`DerivedName`） | 纯字符串规则，且已分歧（D12） |
| 照片 ID 分配（`PhotoIdAllocator`） | **留 PC** | 它是"没有 MediaStore 时如何造稳定 id"的**平台策略**；Android 用 `_ID`。上移无收益且会诱导误用。 |
| 状态缓存（Android 关掉 App 再开还恢复） | **暂不上移**（列为 D3 决策点） | 这是功能差而非逻辑重复；上移等于给 PC 加功能，需产品确认。 |

---

## 4. 逐文件归属判定

### 4.1 `desktop/core`（17 文件）

| 文件 | 判定 | 去向 | 依据 |
| --- | --- | --- | --- |
| `api/PhotoMgmCore.kt` (193) | 共享 | `shared/app/api` | 纯接口 + 数据类；`ShellLauncher` 保留为端口 |
| `api/AppState.kt` (174) | 共享（**拆分**） | `shared/app/api` + PC 专属 `WindowState` 留 `desktop/core` | `window` 字段是 PC 概念（§1.5-3） |
| `api/ExportModels.kt` (76) | 共享（**拆分**） | 通用部分进 `shared/app/api`；`ResumeVerifyMode` 留 PC | 同上 |
| `logic/PhotoMgmCoreImpl.kt` (783) | **拆三层** | 编排/状态机 → `shared/app`；视图派生/台账投影 → `shared/domain`；执行器/持久化 → 端口 | 本方案核心 |
| `platform/PcPaths.kt` (100) | 平台 | 留 PC | P1（`java.nio.file`、`%APPDATA%`） |
| `platform/PcPhotoSourceProvider.kt` (101) | 平台（**扩展名集合上移**） | 留 PC + `domain/spec/ImageExtensions` | C4：扩展名口径需与 Android 统一 |
| `platform/PcExifReader.kt` (80) | 平台（**时间解析上移**） | 留 PC + `domain/exif/ExifTime` | 消灭 D9 |
| `platform/PcFileHasher.kt` (~66) | **共享**（整份） | `domain/hash/Sha256` | C1/C2：`java.io` + `MessageDigest` 双端可用 |
| `platform/PcImageEmbedder.kt` (124) | 平台（**规格上移**） | 留 PC + `domain/spec/MediaSpec` + 实现 `ImageEncoder` 端口 | P1（`javax.imageio`），消灭 D8 |
| `platform/PcShell.kt` | 平台 | 留 PC | P1（`java.awt.Desktop`） |
| `platform/PhotoIdAllocator.kt` (89) | 平台 | 留 PC | §3.4 裁定 |
| `export/PcFolderExporter.kt` (474) | 平台 | 留 PC | P3（文件夹 + 断点续传校验） |
| `export/ExportManifest.kt` (111) | 平台 | 留 PC | P3 |
| `export/ExportLogWriter.kt` (124) | 平台 | 留 PC | P3 |
| `state/SettingsRepository.kt` (100) | 平台（**JSON 编解码上移**） | 留 PC + `app/infra/Json` | C1 不满足（文件 IO） |
| `state/LogsTextStore.kt` (75) | 平台（**防抖策略上移**） | 留 PC + `app/infra/DebouncedTextStore` | 消灭 D11 |
| `infra/Json.kt` (279) | **共享**（整份） | `app/infra/Json` | C1/C2：纯 Kotlin 文本解析 |

### 4.2 `desktop/ui` + `desktop/cli`（39 文件）

**全部留平台层**。仅 `P2` 阶段改 24 个文件里的 62 行 `import`（`com.photomgm.desktop.core.api.*` → `photomgm.app.api.*`），逻辑零改动。`model/` 下 8 个纯 Kotlin 文件按 §3.4 留平台，其中"照片排序口径"单独抽出。

### 4.3 `android/data`（4 文件）

| 文件 | 判定 | 去向 |
| --- | --- | --- |
| `MediaStorePhotoSourceProvider.kt` (47) | 平台 | 留（实现端口，已有） |
| `ExifPhotoReader.kt` (38) | 平台 | 留（时间解析改为调 `domain/exif/ExifTime`） |
| `ExifTimeParser.kt` (31) | **共享**（整份） | `domain/exif/ExifTime`，消灭 D9 |
| `FileHasher.kt` (27) | **共享**（整份） | `domain/hash/Sha256`，消灭 D10 |

### 4.4 `android/app`（20 文件）

| 文件 | 判定 | 去向 |
| --- | --- | --- |
| `AppViewModel.kt` (1165) | **拆三层** | 编排/状态/取消 → `shared/app`；视图派生 → `shared/domain`；剩 `UiState` 投影 + SAF + 缩略图 → 留 ~250 行 |
| `PhotoRepository.kt` (47) | **删除** | 被 `shared/app/AnalysisPipeline` 取代（内部逻辑同构） |
| `SettingsStore.kt` (353) | 平台（**编解码可选上移**） | 留（`SharedPreferences`），序列化逻辑见 D3 决策点 |
| `ImageUtils.kt` (187) | 平台（**规格上移**） | 实现 `ImageEncoder` 端口；常量改引 `MediaSpec` |
| `LogsTextStore.kt` (89) | **共享**（整份） | `app/infra/DebouncedTextStore`，Android 只提供 `persist` 回调 |
| `Cancellable.kt` (33) | **共享** | `app/infra/Cancellation`，与 PC 的 `Operation` 合并 |
| `ThumbnailCache.kt` (130) | 平台 | 留（P2） |
| `ui/**` (10 文件, ~3800 行) | 平台 | **完全不动**（这是本方案最硬的验收条件） |

---

## 5. 两端职责边界

### 5.1 端口清单

| 端口 | 方法 | PC 实现 | Android 实现 | 状态 |
| --- | --- | --- | --- | --- |
| `PhotoSourceProvider` | `listCandidates(dir): List<CandidatePhoto>` | 文件树遍历（`PcPhotoSourceProvider`） | MediaStore 查询 | 已有 |
| `ExifReader` | `readAll(ref): Pair<Long?, Pair<Double,Double>?>` | metadata-extractor | ExifInterface | 已有 |
| `ImageEncoder` | `encodeForLedger(ref, spec): ByteArray?` | ImageIO（降采样 + 裁剪 + JPEG） | ImageDecoder/Bitmap | **新增** |
| `SettingsStorage` | `load(): Settings` / `save(s)` | `%APPDATA%\…\settings.json` | SharedPreferences | **新增** |
| `LogTextStorage` | `load(): String` / `save(t)` | 文件写入 | prefs 独立文件 | **新增** |
| `ShellLauncher` | `open(ref)` / `reveal(ref)` | `java.awt.Desktop` | FileProvider Intent | PC 已有 |
| `ExportExecutor` | `export(plan, snapshot, onProgress): Result` | 文件夹复制 + 校验 | ZIP + SAF 写回 | **保留在平台**（形态不同） |
| `Clock` / `ZoneId` | 构造参数注入 | `Clock.systemUTC()` + `AppZone.ID` | 同 | 已有 |

共享层**只认这些接口**，永远不 `new` 具体实现；装配根（PC 的 `DesktopCoreModule`、Android 的 `AppViewModel` 构造）负责把实现塞进去。

### 5.2 各端各自负责

| 职责 | PC | Android |
| --- | --- | --- |
| 目录选择 / 权限 | 原生目录选择器（`NativeDialogs`） | SAF `ACTION_OPEN_DOCUMENT_TREE` + 持久化授权 |
| 照片来源 | 文件系统（含符号链接/网络盘跳过规则） | MediaStore + 分区存储兜底 |
| EXIF | metadata-extractor | androidx ExifInterface |
| 图片编码 | ImageIO + `setSourceSubsampling` | ImageDecoder(`ALLOCATOR_SOFTWARE`) / BitmapFactory 回退 |
| 导出形态 | **文件夹** + `export.manifest.json` + `export.log` + 断点续传校验（`ResumeVerifyMode`） | **ZIP** + `stateFile` + SAF 写回 + 短时缓存 xlsx 供打开 |
| 持久化 | JSON 文件（用户可见可备份） | SharedPreferences（含分析结果缓存） |
| 窗口/生命周期 | `WindowGeometry`、`AppShell` | `Activity` 生命周期、`viewModelScope` |
| 缩略图 | Compose + ImageIO | Coil + LruCache |
| 打包 | jpackage（MSI / app-image） | AGP + R8（APK/AAB） |

---

## 6. 迁移与整合步骤

原则：**每一阶段都能独立编译、独立测试、独立回滚**；**先立回归基线，再动代码**；**共享层只搬不重写**（搬运时保留原始注释，包括踩坑记录）。

### P0 · 基线与骨架（0 行为变更）

| 项 | 内容 |
| --- | --- |
| 改动 | ① 建**黄金样本回归基线**：固定 1 份日志 + 50 张照片，跑 PC 与 Android 各导出一次，记录 `ledger.xlsx`、ZIP 内文件清单、文件夹清单的 SHA-256（Android 侧记录 ZIP 内条目清单 + 每个条目的哈希，不比整包哈希——ZIP 时间戳不稳定）。基线落 `scripts/golden/`。<br>② `git mv algorithm shared/algorithm`（内容零改动），改两端 `settings.gradle.kts`、`desktop/build.gradle.kts` 的产物重定向、CI 结构断言、`docs/REPO-LAYOUT.md`。<br>③ 建 `shared/domain`、`shared/app` 空模块（只有 `build.gradle.kts` + 一个 `package-info` 占位），两端挂载。 |
| 验证 | `gradle -p android :shared:algorithm:test` 与 `gradle -p desktop :shared:algorithm:test` 同时绿；`android :app:assembleDebug` 绿；`desktop :ui:compileKotlin` 绿；三条既有门禁脚本绿。 |
| 回滚 | 单提交回滚即可（无内容变化）。 |

### P1 · 纯函数上移（消灭 D8~D12，零行为变更）

| 项 | 内容 |
| --- | --- |
| 上移 | `ExifTime`（并集语义：冒号与短横都认）、`Sha256`、`MediaSpec`(400×300/JPEG50/4:3)、`ImageExtensions`、`DerivedName`(统一到 `algorithm.DerivedNames` 正则版)、`DebouncedTextStore`、`Cancellation`、`Json`。 |
| 改调用 | 两端各 5~7 处改为引用共享实现，删除本地副本（`ExifTimeParser.kt`、`FileHasher.kt`、`PcFileHasher.kt`、Android `LogsTextStore.kt` 等）。 |
| **风险点** | D12 合并会让 PC 的"无水印备份"角标判定从 `contains` 改为正则严格版，个别文件名可能出现角标变化；D9 合并会让 PC 接受 `-` 分隔的 EXIF 时间。两者都是**修正**，但属行为变更，必须在 CHANGELOG 标注并跑黄金样本比对。 |
| 验证 | 三处单测 + 黄金样本比对：`ledger.xlsx` 逐单元格比对 + 嵌入图片字节比对；ZIP 条目清单与条目哈希全等。**任何差异都必须能解释**，否则回退该项。 |

### P2 · 契约迁移 + 编排上移（主体工程）

| 项 | 内容 |
| --- | --- |
| 步骤 | ① `shared/domain`：搬 `effectiveMap`/`expandedEventMap`/`effectiveUnmatched`/`dupGroups`/`LedgerProjection`/`ManualAdjustment`，先用"双跑对比测试"证明与两端现有实现逐字节等价，再切调用。<br>② `shared/app`：`AppStateStore` + `AnalysisPipeline` + `AdjustUseCase` + `SettingsUseCase`；`PhotoMgmCore` 门面迁入。<br>③ PC：`desktop/core` 收窄为适配器 + `DesktopCoreModule`；`desktop/ui`、`cli` 改 62 行 import。<br>④ Android：`AppViewModel` 瘦身为 `UiState` 投影层，保留全部既有公开方法签名（`parseLogs`/`buildLedger`/`movePhotos`/`exportZip`/`markLedgerPhoto`…），内部委派给共享用例。**`ui/` 一行不改**。 |
| 关键纪律 | `AppViewModel` 里的**踩坑修复必须原样搬走**，不能"重写得更漂亮"：导出重入 `exportBusy.compareAndSet`、`runCatchingCancellable`（取消信号不被吞）、`dupGroups` 的一次性原子引用发布、缓存时区版本位。这四条都是"改回去就会静默丢数据"的类型。 |
| 验证 | ① 两端 UI 手工冒烟（分析 / 移动 / 三态切换 / 导出）；② Android 侧 10 个既有测试（含 `ExportGateTest`、`SetLogsTextWiringTest`、`ThumbnailCacheWiringTest`）保持绿——它们验证的是**接线**，接线变了必须跟着改测试但不能删断言；③ 黄金样本再比一次。 |
| 回滚 | 分两个提交（domain 一个、app 一个），可分别回退。 |

### P3 · 可选增强（需产品决策，见 §9）

- D2：`copyPhotos`（复制到多事件）是否同步给 Android —— 合并后会天然获得。
- D3：Android 的分析结果缓存是否上移，使 PC 也能"重开即恢复上次分析"。
- 导出计划统一：让 `PcFolderExporter` 与 `ZipExporter` 共用一个 `ExportPlan`，消除"计划规则两处实现"的隐患。

### P4 · 收尾

- 新增门禁 `check-shared-purity.py` 并入 CI `static-guards`；把 `shared/` 加进 `check-timezone.py`、`check-cancellation.py` 的扫描路径（否则新模块是门禁盲区）。
- 更新 `docs/REPO-LAYOUT.md` §4 的风险矩阵（新增 R6 结构漂移、R7 共享层平台 API 越界、R8 双端回归成本）。
- CHANGELOG 标注 "affects: android, desktop"。

---

## 7. 潜在风险

| # | 风险 | 触发条件 | 后果 | 等级 | 缓解 |
| --- | --- | --- | --- | --- | --- |
| R1 | **"抽取即改语义"** | 合并 D9/D12 时取了一端的实现 | 台账/角标/时间解析结果变化，用户不可见但产物不同 | **高** | 黄金样本逐字节比对；差异必须书面解释；P1 单独提交便于定位 |
| R2 | **Android ViewModel 踩坑修复丢失** | 上移时"顺手重写" | 导出重入、取消信号被吞、缓存陈旧 → 数据损坏或静默丢照片 | **高** | 逐段搬运 + 保留注释；`ExportGateTest` 等 10 个测试作为护栏 |
| R3 | **PC UI 契约破坏** | 包名迁移漏改 | 编译失败（可发现，不危险） | 低 | 一次 `sed` + 全量编译 |
| R4 | **测试资产分家** | Android 的 Robolectric 断言无法迁到纯 JVM | 覆盖面净损失 | 中 | 上移逻辑配"假端口"纯 JVM 测试；Robolectric 只留平台适配器验证 |
| R5 | **双端回归成本上升** | 共享层改动影响两端 | 每次改动要跑两套真实素材 | 中 | 黄金样本 + CI 跑 JVM 侧；Android 侧人工跑一次并留档 |
| R6 | **Gradle 结构漂移** | 有人把 `shared/` 复制进 `android/` 或在仓库根加 `settings.gradle.kts` | 两份实现，必然漂移 | 中 | 扩展现有 CI 结构断言（禁止 `android/shared`、根 `settings.gradle.kts`、强制三模块挂载） |
| R7 | **共享层平台 API 越界** | 有人在 `shared/` 里 `import java.nio.file` 或 `androidx.*` | PC 能编、Android 编不过（好）；或 Android 能编、PC 崩（坏） | 中 | `check-shared-purity.py` 门禁 |
| R8 | **构建产物互踩** | 两端同时构建共享模块 | 缓存/产物损坏，随机失败 | 低 | 沿用现有 `buildDirectory` 重定向，扩展到三个模块 |
| R9 | **minSdk 约束被遗忘** | 共享层用了 API 27+ 的 JDK 能力 | 低版本机运行期崩 | 中 | 共享层只许用 API 26 可用的 JDK 子集；`java.time` 在 minSdk 26 原生可用（若将来降到 24 需开 desugaring） |
| R10 | **发布线耦合** | 共享层改动同时改变两端行为，而只有一端重新发版 | 两端行为不一致却版本号各自演进 | 中 | CHANGELOG 强制标注影响面；发版前对两端各跑一次黄金样本 |
| R11 | **范围蔓延** | 顺手"优化" `desktop/ui` 或 Android 的 `ui/` | UI 回归风险与本次目标无关 | 中 | 明确 scope out（§10）；UI 只许改 import |

---

## 8. 验收标准

**结构验收（自动）**
1. `shared/` 下零平台 API（门禁）；`shared/` 三模块 sole-source（无副本）。
2. 两端 UI 层零 `import shared/domain`、零 `import shared/algorithm`（只认契约）。
3. 业务常量单点：`400`、`300`、`50`（JPEG）、`60`（秒）、`Asia/Shanghai` 在仓库内各只有一处定义。

**行为验收（自动 + 人工）**
4. 黄金样本：PC 端改动前后 `ledger.xlsx` 单元格文本 + 嵌入图片字节 **全等**；Android 端 ZIP 条目清单 + 每条目哈希 **全等**。允许差异必须列在 CHANGELOG 的"已知行为变更"里。
5. 测试：`algorithm` 22 + `shared/domain` 新增 + `shared/app` 新增 + `desktop/core` 9 + `desktop/ui` 13 + `android` 15，全绿。
6. 打包：`gradle -p android :app:assembleRelease` 与 `gradle -p desktop :ui:packageMsi` 均成功，产物可安装运行。

**可维护性验收（人工）**
7. 新人只看 `docs/` 能回答："这段代码该放哪层"——即 §3 的 5+3 条足够判定任意一个文件。
8. `AppViewModel` ≤ 300 行；`PhotoMgmCoreImpl` 不再存在于桌面端（逻辑在 `shared/app`）。

---

## 9. 待你拍板的决策点

| # | 决策 | 选项 | 我的建议 |
| --- | --- | --- | --- |
| **D1** | `algorithm/` 是否迁入 `shared/algorithm/` | A 迁（git mv，内容零改）/ B 留在仓库根 | **A**：让"共享=shared/"成为唯一规则，代价是一次 `git mv` + 改 4 处路径。若你希望零风险，选 B 也能落地（只是 `shared/` 少一块）。 |
| **D2** | `copyPhotos`（一张照片同时属于多个事件）是否同步给 Android | A 同步（合并后天然获得）/ B 保持 PC 独有 | **A**：这是同一套业务语义，两端不一致比"少个功能"更糟；但需你确认是否要为此改 Android UI 入口。 |
| **D3** | Android 的"分析结果缓存"是否上移，让 PC 也能重开即恢复 | A 上移 / B 不上移 | **B（先不动）**：属功能增强，与"整合"目标正交，可后续单独立项。 |
| **D4** | 导出形态是否统一（PC 文件夹 / Android ZIP） | A 不统一 / B 统一 | **A**：形态是产品选择（PC 用户要直接看文件夹），强行统一会牺牲一端体验。 |
| **D5** | 派生后缀判定统一到 `algorithm.DerivedNames`（PC 角标可能微调） | A 统一 / B 各自保留 | **A**：现在两份口径，早晚出事；统一后按黄金样本确认影响面。 |

---

## 10. 本次明确不做（scope out）

- 不改 `desktop/ui` 与 `android/app/ui` 的**任何界面逻辑**（只允许改 import）。
- 不改 `algorithm/` 的**任何实现**（只搬目录）。
- 不引入 Kotlin Multiplatform、不引入 kotlinx.serialization、不换 Compose 版本。
- 不统一导出形态、不改 UI 交互、不动打包链。
- 不做性能优化（除上移后自然减少的重复计算）。

---

## 附录 A · 落地顺序一览

```
P0 基线+骨架   ──►  P1 纯函数上移  ──►  P2 契约+编排上移  ──►  P3 可选增强  ──►  P4 门禁收尾
   1 提交           1~2 提交            2 提交                按需              1 提交
   零行为变更        可解释的行为变更      零行为变更            功能变更          零行为变更
```

每个 P 结束都是一个**可发布状态**：任一时点停下，仓库都能编译、能打包、两端行为可解释。
