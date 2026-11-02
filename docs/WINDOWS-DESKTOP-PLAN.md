# Photo-Mgm Windows 桌面版 · 二次开发技术方案（v1.1）

> 版本：v1.1　日期：2026-10-31　基线：Android 版 `Photo-Mgm` v1.3.2，HEAD `6c29bbd`（位于工作分支 `workbuddy/main-dd328458`）
> 性质：**待执行确认方案**。本文批准前不创建 `desktop/`、不改动 `algorithm/ data/ app/` 任何一行代码。

## 修订记录

| 版本 | 变更 | 依据 |
|---|---|---|
| v1.0 | 首稿（导出为 ZIP 包） | 初版方案 |
| **v1.1** | ① 导出产物由 ZIP 改为**文件夹输出**，新增《导出器语义契约》(§4.5)；② 补 `Photo.id` 哈希碰撞防御 ( §4.6)；③ R8 补「图片字节差异 → 64MB 降级点双端不同」；④ 关闭 G1、修正 G3 判据、重排 M0 与 v1.1 的先后顺序；⑤ 补 `.gitignore` 增项；⑥ 明确导出根命名与字符集；⑦ 记录 N1/N2 结论与执行分支策略 | 方案审阅意见（P0×3 / P1×7 / P2×4 全采纳）+ 报告补充 4 点 |

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 可行性 | **高**。`:algorithm` 经逐文件核实为纯 Kotlin JVM、零 Android 依赖，可整块编译进桌面端 |
| 新增工作量 | 集中在平台适配层（目录扫描 / EXIF / 缩略图与 JPEG 编码）+ **新建 `PcFolderExporter`** + 桌面 UI 层 |
| 技术栈 | Kotlin + Compose Multiplatform Desktop + metadata-extractor + ImageIO；测试用 POI 读回 xlsx |
| 导出产物 | **文件夹**（不是 ZIP）：`月日_时分秒/`（如 `1101_203200/`），根放 `ledger.xlsx` + `export.log` + `export-manifest.json`，事件子文件夹 + `99_未分类/` |
| 打包 | jlink 裁剪运行时 + jpackage 出 MSI（主产物）；便携 ZIP 为**安装包**形态（非导出产物），WiX 不可用时降级 app-image + Inno Setup |
| 独立文件夹 | `desktop/`，与现有模块平级 |
| 最大前置阻塞 | 本机无 JDK（PATH 无 java、无 JAVA_HOME、`~/.gradle/jdks` 为空）；`winget` 可用、`choco` 不可用 |
| 最大技术风险 | UI 迁移量（约 5000 行 Compose UI）；去 ZIP 后的导出语义返工风险（已用 §4.5 契约封口）；双端台账一致性（算法 + 图片编码双重来源） |

---

## 1. 现状盘点与依赖评估

### 1.1 源码构成

| 模块 | 规模 | 内容 |
|---|---|---|
| `:algorithm` | 17 个主文件 ~2500 行 / 24 个测试文件，**290 用例** | 日志解析、GPS 分组、事件分类、台账、XLSX、ZIP、去重、时区 |
| `:data` | 4 个文件 ~140 行 | Android 平台适配（ExifInterface / MediaStore / SHA-256） |
| `:app` | ~5000 行 | Compose Material3 UI、ViewModel、SharedPreferences、Coil、Android Bitmap |

> 测试总量口径：**algorithm 290 + app/data 141 = 431**（`431 passed / 0 failed / 6 skipped` 来源为 CI，**本机无 JDK，未本地复跑**）。

### 1.2 逐文件迁移判定

**A. 直接复用（零改动）**：`algorithm` 全部 17 个主文件（import 仅 `java.time / java.io / java.util.zip / kotlin.math`）、`Models.kt`、`XlsxWriter.kt`、`ZipExporter.kt`（仅复用其**命名函数**，见 §4.5）、`AppZone.kt`、`ImageCrop.kt`（几何部分）。

**B. 桌面端实现平台接口（主战场）**

| 接口（定义在 `algorithm`） | Android 实现 | 桌面端实现 |
|---|---|---|
| `PhotoSourceProvider.listCandidates` | `MediaStorePhotoSourceProvider` | `PcPhotoSourceProvider`：多目录递归扫描、拖拽目录、网络盘/只读盘 |
| `ExifReader.readAll` | `ExifPhotoReader`（androidx） | `PcExifReader`：metadata-extractor 读 `DateTimeOriginal` + GPS（含南北纬/东西经换算） |
| 文件哈希 `(String)->String?` | `FileHasher`（纯 MessageDigest） | `PcFileHasher`（同实现，桌面端单独一份，不改原文件） |

**C. 需桌面端重写**：缩略图（Coil → ImageIO 抽样解码 + LRU 缓存）、台账嵌入图（Android Bitmap → BufferedImage/Graphics2D/ImageIO）、配置持久化（SharedPreferences → JSON）、目录选择（SAF → AWT/JNA 对话框）、**导出器（ZipExporter → PcFolderExporter）**。

**D. 既有缺陷与桌面端绕行（不改原代码）**

1. **路径分隔符**：`PhotoIngest.dedupDerived` 分组键用 `sourceRef.substringBeforeLast('/')`，Windows 反斜杠路径下每个文件键唯一 → 派生去重失效。→ 桌面端构造 `sourceRef` 时统一规范化正斜杠，全链路同一口径，配套单测。
2. **`Photo.id` 语义**：`dedupById` 只对 `id > 0` 去重（`id<=0` 全保留）。→ 见 §4.6（哈希 id + 碰撞防御）。
3. **时区**：`parseLog / classify / buildLedger` 必须显式传 `AppZone.ID`。
4. **台账图片来源**：`XlsxWriter` 依赖外部 `imageLoader` 注入图片字节，桌面端必须自建链路。
5. **`UNMATCHED_FOLDER` 不可见**：`ZipExporter` 的 `99_未分类` 定义在 `private companion object`（`ZipExporter.kt:358`），桌面端无法引用 → 只能硬编码常量，并以「台账/目录一致性单测」兜底（见 §4.5）。

---

## 2. 功能范围

### 2.1 P0（14 项）

| # | 功能 | 验收标准 |
|---|---|---|
| 1 | 源目录管理 | 原生文件夹对话框多选 + 拖拽添加；列表可增删；重启恢复 |
| 2 | 输出目录 | 未设置时导出被阻断并提示 |
| 3 | 巡查日期 | 日历选择，默认当天；变更触发粗筛+精读 |
| 4 | 命名模板 | 0=事件类型 / 1=日期+事件类型+日志内容；非法字符替换 `_` |
| 5 | 日志导入解析 | 粘贴 / 拖入 `.txt`；显示未识别行数；结果与 Android 端同日志完全一致 |
| 6 | EXIF 精读（JPEG） | 时间 + GPS；损坏/无 EXIF/无 GPS 三类降级不崩，按既有兜底规则 |
| 7 | GPS 分组 + 事件分类 | 同批真实数据结果与 Android 端一致 |
| 8 | 预览调整 | 三栏；多选（Ctrl/Shift）；批量移动/移除；台账槽位标记；未匹配池；搜索；双击大图 |
| 9 | 三态去重 | 三选一；切换后台账行同步重算，不被晚到协程覆盖 |
| 10 | **导出文件夹 + 台账** | 产物结构见 §4.5-①；`ledger.xlsx` 由 POI 读回断言；照片列前缀 = 实际文件夹名（逐行比对） |
| 11 | 进度与取消 | 百分比 + 当前步骤；可取消；取消后立即释放资源 |
| 12 | **续传 + 失败明细** | manifest 判据未变才跳过；失败列相对路径 + 原因；**跳过数 = 实际未复制数**（单测） |
| 13 | 打开台账 | 导出完成后默认程序打开 xlsx |
| 14 | 导出日志 | `export.log` 按 §4.5-④ 规格生成，可被程序解析 |

### 2.2 P1

多显示器/高 DPI 优化、窗口位置记忆、应用内操作日志、未匹配池批量处理、导出前台账预览、拖拽自动识别巡查日期、HEIC 降级提示细化。

### 2.3 P2

命令行批量模式、自动更新、文件关联 `.pmg-log`。

### 2.4 明确不做

云端同步、多用户账号、照片 OCR、照片编辑、iOS/Linux 版、修改源文件；v1 不做命令行批量、不采购代码签名、HEIC 不原生解码。

### 2.5 度量基线

| 指标 | 目标 |
|---|---|
| 300 张端到端（不含人工调整） | ≤ 120s |
| 导出成功率（1000 张量级） | ≥ 99.5% |
| 台账与导出目录一致性 | 100%（前缀逐行比对） |
| 双端台账差异 | 0 行（同批样例） |
| 安装成功率（Win10/11 x64 干净环境） | 100% |

---

## 3. 技术选型与依赖

### 3.1 UI 框架对比

| 维度 | **Compose Multiplatform Desktop**（推荐） | JavaFX 21 | Swing + FlatLaf | Tauri / Electron |
|---|---|---|---|---|
| 复用 Kotlin 资产 | ★★★★★ 同语系，状态层可搬 | ★★★☆ UI 需全写 | ★★☆ 需全写 | ★算法核心是 Kotlin JVM，需再包一层 |
| UI 迁移成本 | 包名替换 + 少量 API 差异，估 60~70% 可复用 | 重写 | 重写 | 重写 |
| Windows 观感 | 中～良（自绘 + AWT 对话框） | 良 | 优 | 优（WebView） |
| 体积 | ~95MB | ~90MB | ~65MB | 最小，但语言栈割裂 |

**推荐 Compose Multiplatform Desktop**；最大风险是版本矩阵与包前缀改造（见 R1/R2）。

### 3.2 依赖清单

| 类别 | 选型 | 许可 | 备注 |
|---|---|---|---|
| UI | `org.jetbrains.compose.*` + `compose.desktop.application` | Apache-2.0 | 与 Kotlin 版本按官方矩阵绑定 |
| EXIF | metadata-extractor 2.19.x | Apache-2.0 | ~350KB；Commons Imaging 仍为 alpha，不用于生产 |
| 图片 | JDK `ImageIO` + `ImageReadParam` 抽样解码 | JDK | 不引 imgscalr（久未维护） |
| HEIC | v1 不支持（§4.4） | — | v1.1 再评估 |
| 测试 | JUnit5 + kotlin-test + POI 5.2.5（读回 xlsx） | Apache-2.0 | 沿用 algorithm 现有做法 |
| 构建 | Gradle 8.10.2（腾讯云镜像）+ 阿里云依赖镜像（CI 走原生源） | — | 与现有工程双轨策略一致 |

### 3.3 算法复用方式

**方案 A（已定）**：桌面端 `settings.gradle.kts` 中 `include(":algorithm") { projectDir = file("../algorithm") }`。
不移动、不复制、不改源码；保留单一事实来源；通过「桌面构建内复用 algorithm 的 290 用例」把版本差异关进笼子。
（否决：复制副本→代码漂移；mavenLocal→多一步且易忘更新；submodule→单机体验差。）

---

## 4. 关键技术方案

### 4.1 时区与确定性

全链路显式传 `AppZone.ID`（Asia/Shanghai）：`parseLog / classify / buildLedger`、导出根时间戳、`export.log` 的 `timestamp`。

### 4.2 大图与内存

- 缩略图：`ImageReader` + `setSourceSubsampling`，禁止整图解码（手机照 4000×3000 常见）。
- 台账嵌入图：压到 **400×300 / JPEG50 / 4:3 居中裁剪**（沿用 Android 规格）。
- 照片复制：32KB 缓冲流式复制，**不整体载入**（去 ZIP 后依然是流式文件复制）。
- JVM：`-Xmx2g` 写入打包 cfg。

### 4.3 Windows 数据存放

| 数据 | 位置 |
|---|---|
| 设置 / 最近源目录 | `%APPDATA%\PhotoMgm\settings.json` |
| 缩略图缓存 | `%LOCALAPPDATA%\PhotoMgm\cache\` |
| 应用运行日志 | `%LOCALAPPDATA%\PhotoMgm\logs\photomgm-yyyy-MM-dd.log` |
| 默认导出目录 | `%USERPROFILE%\Documents\照片分类导出\` |

### 4.4 HEIC 降级

扫描时识别 `.heic/.heif` → 显式提示「N 张 HEIC 未读时间/GPS」；该类照片以**文件名日期 + 修改时间**参与粗筛（`FileNameDateFilter` 已支持）；无 GPS 则进未匹配池由人工移动。**不静默丢照片。**

### 4.5 ★ 导出器语义契约（v1.1 新增，M5 实现与验收的唯一依据）

**① 产物结构（导出根 = 用户输出目录下的新建目录）**

```
<输出目录>/MMdd_HHmmss/                        ← 时间取 AppZone.ID；不含冒号，如 1101_203200/
├── ledger.xlsx                                 ← 台账（复用 AlgorithmApi.exportLedgerXlsx）
├── export.log                                  ← JSON Lines，规格见 ④
├── export-manifest.json                        ← 续传判据，见 ②
├── 01_<事件文件夹名>/                           ← 编号规则复用 ZipExporter.numberedFolderNames
├── 02_<事件文件夹名>/
├── ...
└── 99_未分类/                                   ← 未匹配照片
```

- 文件夹命名**必须**调用 `ZipExporter(zone).numberedFolderNames(events, namingTemplate)`（`ZipExporter.kt:189`，public）。理由：`LedgerEngine`（`LedgerEngine.kt:33/53`）正是用同一函数生成台账「照片列」的 `文件夹名/` 前缀，**只有这样台账前缀与实物文件夹才不会漂移**。
- 未匹配目录常量：核心 `UNMATCHED_FOLDER` 为 `private`（`ZipExporter.kt:358`），桌面端硬编码；**一致性靠 M5 的「台账前缀逐行比对」单测兜底**，并在代码注释标注「核心改动需同步」。
- 若某事件无照片，不创建其文件夹（与 Android 现有行为一致）。

**② 同一照片被多个事件引用 → 按文件夹各复制一份**

与现有 ZIP 行为一致（ZIP 以 `zip 内路径` 为键，同一源文件在不同文件夹下各存一份）。**禁止**改成"全导出只存一份"，否则台账两行指向同一路径而实物仅一份。M5 单测覆盖：一张照片挂两个事件 → 两个文件夹各 1 个文件。

**③ 重名处理：唯一性范围 = 同一文件夹内**

- 重名判定只看「同一个事件文件夹内部」，不同文件夹下同名互不影响。
- 命名规则沿用 `ZipExporter.kt:80-87`：有扩展名 → `IMG_001_2.jpg`；无扩展名 → `IMG_001_2`（**绝不拼出 `IMG_001_2.` 这种尾随点**，Windows 会报「文件名无效」）。
- 每次改名记 `export.log` 一条 `event=rename`。

**④ `export.log` 规格：JSON Lines**

- 编码 **UTF-8 无 BOM**；每行一个 JSON 对象；**每次导出覆盖写**（不追加历史）。
- 字段：`timestamp`（ISO-8601 +08:00）、`level`（info/warn/error）、`event`（`export_start` / `copy` / `skip` / `rename` / `failed` / `export_end`）、`relativePath`（相对导出根，反斜杠统一为正斜杠）、`reason`（失败或跳过原因，限长 120 字）、`bytes`、`durationMs`、`summary{total,success,skipped,failed}`（仅 `export_end` 行）。
- 目的：机器可读（双端比对、支持排障）、人可读（记事本直接看）。

**⑤ 断点续传：以 manifest 为准，不靠"目录里有没有文件"猜**

- `export-manifest.json` **放在导出根内**（与 `ledger.xlsx` 同级），记录：`exportRoot`、`namingTemplate`、`photoSetHash`、`entries[{relativePath, size, sha256, exportedAt}]`。
- **跳过条件（全部满足才跳过）**：manifest 存在 且 `namingTemplate` 与 `photoSetHash` 未变 且 每个 entry 的文件存在且 `size`+`sha256` 一致。
- 这样设计的好处：manifest 随产物走，**换输出目录自然不会误跳过**（Android 1.3.2 踩过的坑：状态文件与输出位置解耦 → 零产出却提示"跳过已完成"）。
- 性能兜底：若照片数 > 500 且用户选择"快速校验"，退化为「同名 + 同大小 + mtime 三者相等」判据，但**必须在 `export.log` 记录每条跳过明细**，且跳过汇总必须等于实际未复制数。
- **M5 硬验收**：单测断言「`skipped` 计数 == 实际未复制文件数」；反向用例「删除产物后重跑必须重新复制，不得只报跳过」。

**⑥ 长路径硬约束**

- 导出根深度 ≤ 2 层（输出目录/导出根/事件文件夹）；
- 事件文件夹名沿用核心 `sanitize` + `truncate(50)`；
- 目标绝对路径 > 240 字符时，**导出前**给出明确错误（指出超限的那条路径），禁止静默截断或静默跳过。

### 4.6 ★ `Photo.id` 与哈希碰撞防御（v1.1 新增）

- `id = SHA-256(规范化后的 sourceRef)` 取前 8 字节、清零符号位（保证 > 0），**结果为 0 时置 1**。
- 会话内维护 `HashMap<Long, String>`（id → sourceRef）做唯一性守卫：**若新 id 已存在且 sourceRef 不同 → 线性探测 `id+1`，直至空闲，并记 warn 日志**（`%LOCALAPPDATA%\PhotoMgm\logs`）。
- 为什么必须做：`dedupById` 只对 `id > 0` 去重，**id 碰撞会静默丢照片**（无异常、无提示）。
- M2 单测两条（缺一不可）：
  1. 1 万条真实路径（含中文/空格/长路径）无碰撞；
  2. **人为构造碰撞**（注入固定哈希函数）→ 探测生效、两照片都被保留、日志有 warn。
- 必须先规范化再哈希，否则同一文件在 `D:\photos\a.jpg` 与 `D:/photos/a.jpg` 两种写法下会得到两个 id。

---

## 5. 目录结构

```
desktop/
├── settings.gradle.kts            # include(":app") + include(":algorithm"){ projectDir=file("../algorithm") }
├── build.gradle.kts               # 镜像策略 + Java toolchain 17
├── gradle.properties              # Kotlin/Compose 版本、UTF-8、中文字体
├── gradle/wrapper/                # gradle-8.10.2（腾讯云镜像）
│
├── app/
│   ├── build.gradle.kts           # org.jetbrains.compose + compose.desktop.application
│   └── src/main/kotlin/com/photomgm/desktop/
│       ├── Main.kt                # 入口（无控制台黑窗）
│       ├── App.kt                 # 根 Composable
│       ├── platform/
│       │   ├── PcPhotoSourceProvider.kt
│       │   ├── PcExifReader.kt
│       │   ├── PcFileHasher.kt
│       │   ├── PcThumbnailer.kt
│       │   ├── PcImageEmbedder.kt      # 400×300 / JPEG50 / 4:3
│       │   ├── PcFolderExporter.kt     # ★ 实现 §4.5 契约
│       │   ├── PcExportManifest.kt     # ★ manifest 读写与比对
│       │   ├── PcExportLog.kt          # ★ JSON Lines 写日志
│       │   └── PcPaths.kt              # AppData 路径 + sourceRef 规范化
│       ├── state/
│       │   ├── DesktopViewModel.kt
│       │   ├── SettingsRepository.kt
│       │   ├── LogsTextStore.kt
│       │   └── ExportOrchestrator.kt
│       ├── ui/
│       │   ├── MainWindow.kt  SourcePanel.kt  LogPanel.kt  PreviewPane.kt
│       │   ├── EventCard.kt   PhotoGrid.kt    PhotoManageDialog.kt  ImageViewer.kt
│       │   ├── MenuBar.kt     StatusBar.kt    ProgressOverlay.kt    ErrorDialog.kt
│       │   └── theme/Theme.kt Strings.kt
│       └── util/ Logger.kt  Desktop.kt  FileDialogs.kt  DragAndDrop.kt
│
├── packaging/
│   ├── icons/app.ico
│   ├── jlink/jlink-args.txt        # java.base,java.desktop,java.logging,jdk.unsupported
│   ├── installer/Build-Installer.ps1
│   ├── installer/Setup.iss         # Inno Setup 降级
│   └── tools/Check-Prereqs.ps1     # 检测 JDK(jlink/jpackage)、WiX、Inno
│
├── scripts/ run-dev.bat  test-all.bat  smoke-test.ps1
└── README.md
```

> `algorithm/ data/ app/` 保持原样。

---

## 6. 打包与安装

### 6.1 产物（版本号按 §9 首发 2.0.0）

| 产物 | 定位 |
|---|---|
| `PhotoMgm-2.0.0.msi` | **主产物**：写入「程序和功能」卸载项、开始菜单、桌面快捷方式 |
| `PhotoMgm-2.0.0.exe` | 双击安装（同需 WiX，M7 实测确认） |
| `PhotoMgm-2.0.0-portable.zip` | **安装包形态**（非导出产物）：免安装绿色版，用于 U 盘 / 无管理员权限机器 |

> 便携包**不具备**开始菜单与卸载项，不符合「Windows 标准软件」完整口径；标准交付以 MSI 为准，便携包为补充（决策 N1）。

### 6.2 链路

```
gradlew :app:build
  → jlink --add-modules java.base,java.desktop,java.logging,jdk.unsupported --strip-debug --compress=zip-6
  → jpackage --type msi --runtime-image <image> --icon app.ico
       --win-menu --win-menu-group "PhotoMgm" --win-shortcut --win-dir-chooser
       --java-options "-Dfile.encoding=UTF-8 -Duser.language=zh -Xmx2g"
```

- **必须捆绑运行时**（用户不会预装 JRE）；预估安装包 90~100MB。
- **WiX**：MSI/EXE 通常需 WiX 3.11+（可能需 .NET 3.5）。M7 先跑 `Check-Prereqs.ps1` 实测；不可用时降级 `app-image` + Inno Setup 6。
- 每用户安装 `--win-per-user-install`（内网无管理员权限常见）。
- 代码签名：v1 不采购，SmartScreen 提示按「更多信息 → 仍要运行」指引（R7）。

---

## 7. 里程碑

### M0 · 前置核实与环境

| # | 项 | 状态 / 判据 |
|---|---|---|
| **G1** | `algorithm/build.gradle.kts` 插件类型 | ✅ **已通过**：`plugins { kotlin("jvm") }`（`algorithm/build.gradle.kts:2-4`），非 `com.android.library`，无需再等 |
| **G2** | Kotlin ↔ Compose Multiplatform 版本矩阵 | ⏳ 待联网复核官方矩阵（本次会话外网不可达）。已知 1.6.x 对应 Kotlin 2.0.x、1.7.x 需 2.0.20+，属中等把握，**必须在 M1 冻结版本前确认** |
| **G3** | JDK 17 安装 | 判据：`java -version` 为 17.x、`JAVA_HOME` 已设、**且 `jlink --version`、`jpackage --version` 均可用**（防装成 JRE 一路通到 M7 才炸）。安装路径：`winget`（本机可用）优先，`choco` 不可用，失败转官网 MSI |

> 本机现状：PATH 无 java、无 JAVA_HOME、`~/.gradle/jdks` 为空、Gradle 发行包未缓存。
> 顺序纪律：**N1/N2 已确认 → v1.1 合入 → M0 核实 → 建 `desktop/`**。

### M1 · 骨架与算法接入

- 建 `desktop/` 与 `settings/build` 脚本（含 `../algorithm`）
- `gradlew :app:run` 出中文标题空窗口；`gradlew -v` 校验 Gradle 版本
- **回归门禁**：`gradlew test` 含 algorithm 290 用例全绿

### M2 · 平台适配层

- `PcPhotoSourceProvider / PcExifReader / PcFileHasher / PcPaths`
- `sourceRef` 正斜杠规范化（§1.2-D1）
- **哈希 id + 碰撞探测**（§4.6），两条单测
- 验收：≥100 张真实照片，时间/GPS 与 Android 端逐张一致；反斜杠去重单测锁死

### M3 · 状态层

- `DesktopViewModel`（移植 AppViewModel）、`SettingsRepository`、`ExportOrchestrator`
- 保留「分类完成即生成台账行」「三态切换同步重算不晚到覆盖」「取消后释放」

### M4 · UI 三栏

最小窗口 1280×800；拖拽、Ctrl/Shift 多选、右键、双击大图、Esc；文案对齐 `docs/USAGE.md`；100%/125%/150% DPI 三档实测。

### M5 · 导出链路（契约落地）

- `PcFolderExporter` + `PcExportManifest` + `PcExportLog`
- **验收（逐条对应 §4.5）**：
  1. 产物结构与 ① 一致，去 ZIP；
  2. 台账照片列前缀 == 实际文件夹名（**逐行比对**）；
  3. 一照多事件 → 各文件夹各一份；
  4. 重名 `_2`，无扩展名不产生尾随点；
  5. `export.log` 可被解析为 JSONL，字段齐全；
  6. `skipped` 计数 == 实际未复制数；删除产物后重跑必须重新复制；
  7. 路径 > 240 字符明确报错；
  8. POI 读回 xlsx：真实日期类型、结束时间列、嵌入图为真实 JPEG 字节；
  9. **双端台账逐行比对**（同批样例，Android 导出 vs 桌面导出），差异 0 行。

### M6 · 兼容性与性能

Win10 1809+/Win11/x64；中文/空格/长路径、OneDrive 重定向、只读盘、网络盘、输出目录被占用、磁盘写满、中途取消；500~1000 张性能与内存达标。

### M7 · 打包

jlink → jpackage MSI → `smoke-test.ps1`（安装→开始菜单启动→导入→导出→校验→卸载→残留检查）；输出便携 ZIP。

### M8 · 文档与交付

`desktop/README.md`、`desktop/docs/USAGE-Windows.md`（**新建，不改 `docs/USAGE.md`**）；发布归档与校验值。若要向根 `CHANGELOG.md` 追加段落，先征询。

---

## 8. 风险与兼容性

### 8.1 风险登记册

| # | 风险 | 等级 | 应对 |
|---|---|---|---|
| R1 | Compose 包前缀切换引发大面积 import 改造 | 高 | M1 先做单页迁移冒烟，最后攻 PreviewScreen |
| R2 | Kotlin 与 Compose Multiplatform 矩阵冲突 | 中 | 固定官方矩阵；algorithm 290 用例双向回归（G2 未关闭） |
| R3 | HEIC 无法解码 | 中 | §4.4 显式降级，不静默丢 |
| R4 | 事件文件夹中文名在资源管理器/OneDrive 的兼容性 | 低 | 核心已 `sanitize` 非法字符 + `truncate(50)`；M6 实测 |
| R5 | 路径 > 260 字符 | **高概率** | §4.5-⑥：导出前 > 240 字符即明确报错，禁止静默截断 |
| R6 | 大图批量解码 OOM | 中 | 抽样解码 + `-Xmx2g` + 分片 |
| R7 | SmartScreen 拦截 | 中 | v1 不买签名，给指引 |
| R8 | **双端行为漂移（双重来源）** | 中 | ①算法：单一事实来源 + 同批样例双端比对；②**图片编码字节差异**：`XlsxWriter.MAX_IMAGE_BYTES = 64MB`（`XlsxWriter.kt:116`）到顶后不再嵌图并追加 `IMAGE_SKIPPED_NOTE`，桌面 ImageIO 压出的 JPEG 与 Android Bitmap 字节数不同 → **降级触发点不同 → 台账"有没有图"不一致**。对策：压缩参数对齐 400×300/JPEG50，M5 加「双端台账逐行比对」 |
| R9 | 依赖下载受限 | 低 | 首次联网缓存 `~/.gradle`；必要时导离线包 |
| R10 | WiX / .NET 装不上 | 低 | 降级 app-image + Inno Setup |
| R11 | 高 DPI 发虚/错位 | 低 | M4 末三档实测 |
| R12 | 迁移基线漂移（Android 侧继续迭代） | 低 | 冻结 1.3.2 基线，增量单独排期 |
| R13 | 未匹配目录常量硬编码与核心漂移 | 低 | M5 一致性单测（台账前缀 vs 实际目录）+ 代码注释标注 |

### 8.2 兼容性矩阵

| 项 | 范围 |
|---|---|
| 系统 | Windows 10 1809+ / 11（x64）；v1 不支持 ARM64 |
| JRE | 不需要（安装包自带运行时） |
| Excel | WPS / Office 2016+ / LibreOffice 7+ |
| 照片格式 | JPEG（主场景）；PNG 可读；HEIC 降级 |
| 文件系统 | NTFS / exFAT；不建议 FAT32 |
| 路径 | 支持中文/空格；建议总长 ≤ 200 字符 |

### 8.3 Android 端遗留项（**不在本方案范围**，单列以免误背）

| 编号 | 现状（已核实存在） | 说明 |
|---|---|---|
| W5 | `ThumbnailCache.TILE_DP = 120`（`ThumbnailCache.kt:38`）单值喂所有界面 | Android UI 问题，桌面端整体重写该层，不受影响 |
| W2 | `XlsxWriter.MAX_IMAGE_BYTES = 64MB` 先攒后写 | 对 Android 内存有压力；**桌面端 `-Xmx2g` 下非瓶颈**，但必须保留同一预算以维持双端一致（见 R8） |
| W3 | `LogsTextStore.DEFAULT_DEBOUNCE_MS = 400L`（`LogsTextStore.kt:43`），强杀可能丢最后输入 | Android 侧风险；桌面端改为「失焦/关闭即刷盘」规避 |

---

## 9. 已拍板决策

| # | 决策 | 结论 |
|---|---|---|
| D1 | 独立目录 | `desktop/` |
| D2 | 算法复用 | `include(":algorithm"){ projectDir = file("../algorithm") }`，不复制不移动 |
| D3 | UI 框架 | Compose Multiplatform Desktop |
| D4 | 桌面首发版本 | 2.0.0（与 Android 1.3.2 独立版本线） |
| D5 | **导出产物** | **不生成 ZIP（指导出产物；安装包便携 ZIP 另议，见 N1）**：输出根 / `MMdd_HHmmss`（`AppZone.ID`，不含冒号，如 `1101_203200`）/ 事件子文件夹 + `99_未分类`；根放 `ledger.xlsx` + `export.log` + `export-manifest.json`；重名 `_2/_3` 记 `export.log` |
| D6 | 分类维度 | 按事件分文件夹，未匹配入独立目录 |
| D7 | 代码签名 | v1 不采购 |
| D8 | 命令行批量 | v1 不做 |
| D9 | HEIC | v1 不原生解码，显式降级 |
| D10 | JDK 17 | **已授权安装**：`winget` 优先（可用），失败转官网 MSI；判据含 `jlink`/`jpackage` |
| D11 | 算法插件核实 | **已完成**：`kotlin("jvm")` |

### N1 / N2 结论

- **N1 便携 ZIP：保留**（`PhotoMgm-2.0.0-portable.zip`），定位为安装包补充形态；`*.zip` 已在 `.gitignore` 全局忽略，不会误提交。
- **N2 `Photo.id`：哈希**，加两条硬约束——先规范化 `sourceRef` 再哈希；会话内唯一 + 碰撞线性探测 + 两条单测（§4.6）。

### 待你一句话确认（本稿按默认值走）

| # | 待确认 | 本稿默认 | 差异影响 |
|---|---|---|---|
| Q1 | 未匹配照片目录名：报告 #5 写 `未匹配/`，本稿写 `99_未分类/` | `99_未分类`（与 Android 一致、`99_` 保证排序在后） | 改 1 个常量 + 1 处文案；若你选 `未匹配/`，需与平板/手机端产物命名分叉 |

---

## 10. 执行顺序与分支策略

1. **v1.1 合入**：从 `workbuddy/main-dd328458`（HEAD `6c29bbd`）切 `docs/desktop-plan-v1.1`，**只改 `docs/WINDOWS-DESKTOP-PLAN.md`**，提 PR 到 `main`，只触发 static-guards；合入后再开 M0 分支。**不直接改 `main`**（分支保护会拦，严格模式要求 rebase）。
2. **M0**：G3 装 JDK（`winget`）→ 验 `java/jlink/jpackage`；G2 联网复核版本矩阵（G1 已通过）。
3. **M1 起**逐里程碑推进，每个里程碑交付「产出清单 + 验证命令 + 失败证明」，不一口气推到底。
4. 全程 `algorithm/ data/ app/` 零改动。

### 附：`.gitignore` 增项（v1.1 一并提交）

```
*.msi
*.exe
desktop/packaging/output/
```

> 现有 `.gitignore` 已忽略 `*.zip`、`*.xlsx`、`build/`、`.gradle/`；`*.py` 有 `!scripts/*.py` 例外。`.kotlin/` 目录在本工作区**不存在**，若后续出现再补忽略项（不占待办名额）。

---

## 附录 A · 未经本地复核的数据（引用时须标注）

| 数据 | 状态 |
|---|---|
| 全量测试 `431 passed / 0 failed / 6 skipped` | 来源 CI；**本机无 JDK 未复跑** |
| 分支保护 `strict=true`、`enforce_admins=true`、required checks | **未复核**（本次会话 GitHub 连接器不可用）；需在联网环境确认 |
| "A→E 五项任务关闭" | 未复核（依赖 issue 跟踪状态） |
| Kotlin ↔ Compose Multiplatform 官方矩阵 | **未能在线核实**（外网不可达），M1 前必须确认 |
