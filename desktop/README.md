# Photo-Mgm Windows 桌面版

> 对现有 Android 版 Photo-Mgm v1.3.2 的二次开发，输出符合 Windows 系统标准的桌面软件。
> 原工程 `algorithm/ data/ app/` 三模块**零改动**（`git diff --stat 6c29bbd -- algorithm data app` 为空可验证）。

当前进度：**逻辑核心已完成（M1–M3 + M5 主体）→ UI 已定稿为单页结构（U0 通过、U3 = 事件卡片 + 照片弹窗，渲染真实 AppState）→ M7 打包技术链路已验证**。

| 模块 | 职责 | 状态 |
|---|---|---|
| `:core` | 逻辑核心，零 UI 依赖（无 Compose / Swing / AWT 窗口） | ✅ 2807 行主源码 + 1101 行测试 |
| `:cli` | 无头命令行入口，也是打包验证的 main class | ✅ 362 行 |
| `:ui` | Compose Multiplatform 桌面界面 | 🚧 U0 + U3（窗口自适应 / 设计令牌 / 状态归约 / 单页 + 覆盖层） |
| `:algorithm` | 原工程算法核心，外部目录挂载 | ✅ 零改动复用 |

---

## 一、已实现的功能范围（逻辑层）

| # | 功能 | 状态 | 验证方式 |
|---|---|---|---|
| 1 | 巡查日志解析（时间 / 桩号+方向 / 事件类型 / 巡查人员 / 车牌） | ✅ 复用核心 | `CorePipelineTest.端到端分析得到事件与分类`、CLI 实跑 |
| 2 | 日志文件读取（UTF-8 / GBK 自动识别） | ✅ 实现 | `PhotoMgmCore.readLogFile`，CLI `--log` |
| 3 | 照片源目录扫描（多目录并集、递归、扩展名白名单） | ✅ 实现 | `PcPhotoSourceProviderTest` 4 用例 |
| 4 | 文件名日期粗筛（闭区间，只看文件名） | ✅ 复用核心 | `CorePipelineTest` |
| 5 | 派生去重（水印相机主图 / `_original` 原图，内容哈希双层判定） | ✅ 复用核心 | `CorePipelineTest.端到端分析` |
| 6 | EXIF 精读（拍摄时间 + GPS，含损坏文件降级） | ✅ 实现 | `PcExifReaderTest` 6 用例 |
| 7 | GPS 分组 + 事件分类（含拆簇、时间重叠、无时间事件） | ✅ 复用核心 | `CorePipelineTest` + CLI 实跑 |
| 8 | 三态去重（仅带水印 / 仅不带水印 / 两者都保留） | ✅ 实现 | `CorePipelineTest.三态去重只改输出层且同步生效` |
| 9 | 人工干预（移动照片、移入未匹配池、设置台账照片、恢复最初分类） | ✅ 实现 | `CorePipelineTest.人工移动与恢复最初分类` |
| 10 | 台账生成（手写 OOXML，每事件一行，内嵌 4:3 照片） | ✅ 复用核心 | `PcFolderExporterTest`（POI 读回断言） |
| 11 | 导出到**文件夹**（`月日_时分秒/`（如 `1101_203200/`）+ 事件子文件夹 + `99_未分类/`） | ✅ 实现 | `PcFolderExporterTest` 11 用例 |
| 12 | 导出进度、可取消、失败明细 | ✅ 实现 | `PcFolderExporterTest.取消导出后写入不完整清单且不命中续传` |
| 13 | 断点续传（`export-manifest.json` 逐项校验） | ✅ 实现 | `PcFolderExporterTest` 续传 3 用例 + CLI 连跑两次 |
| 14 | `export.log`（JSON Lines，机器可读 + 人可读） | ✅ 实现 | `PcFolderExporterTest.export_log 是合法 JSONL` |
| 15 | 设置持久化（`%APPDATA%\PhotoMgm\settings.json`） | ✅ 实现 | `CorePipelineTest.设置持久化后新实例可恢复` |
| 16 | 日志文本草稿持久化（防崩溃丢输入） | ✅ 实现 | `LogsTextStore` |
| 17 | 长路径（>260 字符）前置拦截并给出可操作建议 | ✅ 实现 | `PcFolderExporterTest.超长目标路径在导出前被明确阻断` |
| 18 | 无头命令行入口（批处理 / 排障 / 验收） | ✅ 实现（额外交付） | `:desktop:cli:run`，见第五节 |

---

## 二、界面层现状（U0 通过 · U3 单页结构）

### 2.1 U0 已钉死的结论（版本矩阵与渲染能力）

| 项 | 结论 | 依据 |
|---|---|---|
| 版本矩阵 | **Kotlin 2.0.21 + Compose Multiplatform 1.7.3 直接可用**，无需升级 Kotlin | `:ui:compileKotlin` 通过 |
| 仓库源 | mavenCentral 可用；**必须加 `google()`** | `ui-desktop` 传递依赖 `androidx.lifecycle:*:2.8.5`，该构件只在 Google Maven |
| — | `google()` 指向 `https://dl.google.com/dl/android/maven2/`（200/0.4s）；`maven.google.com` 本网络超时 | curl 实测 |
| 中文字体 | 正常，无需额外字体配置 | 冒烟窗口渲染「巡查日志 · 桩号 K138+700 · 台账照片 · 99_未分类」 |
| DPI | 开发机 **1920×1080 @ scale=1.25**（工作区 1536×816 dp）；Compose 按 dp 自动换算 | 冒烟日志 |
| 渲染后端 | 本机 **D3D12 设备创建失败 → Skiko 回退其他 API**，功能正常但非 GPU 加速路径 | `[SKIKO] warn: Fallback to next API` |

**关于 D3D12 回退**：本机主显示适配器是 `OrayIddDriver`（向日葵虚拟显示器），D3D12 设备创建失败属预期。
远程桌面、虚拟机、老旧显卡环境都会遇到。这不是阻塞项，但**必须写进兼容性验收**：
回退后 UI 须仍可用、网格滚动不能卡死，且应能自诊断（日志或状态栏显示当前后端），否则用户报障时无从判断。

### 2.2 窗口尺寸策略

内业机主力是 **1366×768 笔记本 @125% 缩放**，换算后工作区只有 **1093×574 dp**。
原先「最小高度 640」比这台机器的可用高度还高 66 dp，窗口下边框会被屏幕裁掉 —— 所以尺寸策略整体重做。

| 项 | 值 | 适配依据 |
|---|---|---|
| 默认（期望）尺寸 | **1440×900** | 1920×1080@100% 工作区 1920×1040，留出桌面感；不占满屏 |
| 最小尺寸 | **900×540** | 1093×574 下留 34 dp 余量；900 宽是事件列折叠后仍能放 4 列照片的下限 |
| 启动策略 | 按主屏工作区 **clamp**，不写死 | 一台机器一份配置都不用维护 |

**首次启动**（`settings.window == null`）：

```
初始宽 = min(1440, round(工作区宽 × 0.9)) 再夹进 [900, 工作区宽]
初始高 = min( 900, round(工作区高 × 0.9)) 再夹进 [540, 工作区高]
```

> ⚠️ 这里有个必须注意的坑：1366@125% 下 `574 × 0.9 = 517 < 540`，上下界交叉。
> 直接写 `coerceIn(540, 517)` 会抛 `IllegalArgumentException: Cannot coerce value to an empty range`
> —— **崩在启动阶段**，而不是"开出个 517 高的窗口"。所以下限要先跟工作区取 `min` 再夹取。

各档实算（工作区已扣任务栏；任务栏高度实测 **40~48 dp 不等**，所以必须读工作区而不是减常数）：

| 物理分辨率 | 缩放 | 工作区 dp | 实际开出 | 内容宽 | 照片格 |
| --- | --- | --- | --- | --- | --- |
| 1366×768 | 100% | 1366×728 | 1229×655 | 1215 | 88 |
| **1366×768** | **125%** | **1093×574** | **984×540** | **970** | **80** |
| 1920×1080 | 100% | 1920×1040 | 1440×900 | 1426 | 96 |
| 1920×1080 | 125% | 1536×816 | 1382×734 | 1368 | 96 |
| 1920×1080 | 150% | 1280×680 | 1152×612 | 1138 | 88 |
| 3840×2160 | 200% | 1920×1040 | 1440×900 | 1426 | 96 |

> 单页结构下宽度只有两个用途：卡片里的照片格多大、一屏放得下几张卡。没有"栏"可折叠，
> 所以 900 dp 也能用 —— 这是删掉步骤栏与并排布局换来的一笔实际收益。

**二次启动**（M6b）：恢复 `settings.json` 里的窗口几何，只做两件事 ——
低于最小值抬到最小值、超出工作区居中（显示器拔掉/改分辨率后不会留下一个看不见的窗口）。
注意存的是 **dp 而非物理像素**：用户调的是"多大一块地方"，换显示器不漂。
`--smoke` 与 `--size` 不写记忆，免得把用户设置覆盖成测试值。

### 2.3 界面结构：一层页面 + 四层覆盖

v2.0 的"四阶段切换"也已删除。它把 **数据阶段**（输入/复核/导出/结果）当成了 **人的工作单元**：
输入只在开工时做一次却占一个页面，导出后想回列表补照片还得换页，结果页只看四行字却占整屏。
完整对照见 `docs/WINDOWS-DESKTOP-UI-SPEC.md` §0。

现在只有一个页面：

| 区域 | 回答的问题 |
|---|---|
| 顶栏 | 这是什么工具？能不能导出？设置在哪？ |
| 日志条 | 左：**巡查日期**（日历 + 前一天/今天/后一天，两种形态都常驻）；右：未解析＝72 dp 粘贴区 +「开始分析」，**解析后自动收成一行摘要**（事件 N · 照片 M · 未匹配 K） |
| 结果条 | 上次导出成功了吗（仅导出后出现，可收起） |
| 工具条 | 看哪一批事件（全部/待确认/无照片/无时间/未匹配 + 搜索 + 去重口径） |
| 事件卡片 | 一屏 3~5 张，一张卡 = 一个事件：标题行（序号 26 / 时间 96 / 标题 / 标签 / 张数 56）+ 照片行 |
| 状态栏 | 数据口径 + 快捷键表（§6.2） |

覆盖层（同一时刻最多一个）：**设置 / 某事件的照片 / 分配（移动或复制）到某事件 / 重新分析确认**。
除设置外，所有动作都在页面上；照片操作在弹窗里，因为"哪个事件"本身就是那块上下文。

**快捷键集中在 `AppShell` 的 `onPreviewKeyEvent`**：`F5` 分析、`Ctrl+O` 导入日志、`Ctrl+E` 导出、
`Ctrl+,` 设置、`Esc` 关覆盖层/清勾选、`Shift`+点缩略图连选。键盘只是加速——每个键都有点得到的等价入口
（分析在日志条旁、导入在输入框右上、导出/设置在顶栏、关闭在弹窗右上角 ✕），所以按键被输入框吞掉也不会走不下去。
**缩略图没有 `Ctrl`+点加选**：加选只有右上角红勾一个入口（v3.7），点图本体只做单选/连选，见 spec §6.1。

**只有日历不吃键盘**（用户明确要求）：日期字段与日期格子都走 `pmPress`（`pointerInput`）而不是 `clickable`，
没有 focus 节点，所以 `Tab` 进不来、`Enter`/`Space` 选不了日期、方向键也不在网格里移动。选日期只有一条路：
用鼠标点格子。理由见 spec §6.2 —— 网格导航要养"光标在哪格、跨月怎么翻、回车空格语义"，而日期是一次性选择。

**卡片有照片 = 红框**：`component/CardEdge.kt` 是描边语义的唯一出处；无照片的卡片回落为中性描边。
红框的意思是「这里已经有东西了，可以动手」，所以同一个卡片里不再用红字表示「无照片」。

**照片弹窗的四种操作**：大图下方一行文件名 + 「打开原文件」；缩略条可**搜索**（文件名 / 时刻 / 「无时间」）、
可**多选**（右上角红勾逐张加选/减选，或 `Shift`+点图连选一段）；右侧除台账两格外，还有**巡查日志内容**原文区
（判断"这条记录配这些照片对不对"的依据）。底部三个动作 ——「移动到…」「复制到…」「移出到未匹配」。
**复制**是 core 新增能力（`copyPhotos`）：同一张照片常同时是两个事件的证据，移动会毁掉一条证据链；
复制后侧栏出现「本次归属（N 处）」，可以「移除本份」。
弹窗的信息区被压到最薄（头部 38 / 侧栏 300 / 缩略条 72~92 / 操作条 42），省下的高度全给大图：
900×540 下大图高 300（原 ~229），1366×740 下 517（原 ~380）。

**在主页面上就能改归属**：鼠标停在某个照片格上，格子里浮出「移动」「复制」两个按钮（未匹配池只给「移动」），
点了进同一个目标选择层（可搜索、列表已排除它自己所在的事件）。两条路径共享一套校验与反馈：
- 动手之前先问能不能做（`model/Feedback.rejectReason`）：目标已全部持有 → "不重复添加"；选中的有无归属照片 → "不能复制，先移到事件里"；导出还没结束 → "结束后才能调整"（core 这时会静默拒绝，界面必须先说）。
- 动手之后按 core **回来的张数**说话：`movePhotos` / `copyPhotos` 现在都返回真正生效的张数，界面据此
  三选一 —— 成功（绿，带目标名）/ 部分没生效（写明"另 N 张该事件已有"）/ 没生效（黄，写原因）。
  成功后清掉 core 那条简短 message，避免同一件事在屏幕上说两遍。

**为什么是卡片而不是表格**：照片是"一组、数量可变（0~5）"的字段，表格行高会被撑成锯齿；而复核的动作是
"整体看这个事件对不对"，不是跨行比字段。所以卡片**内部按表格排**——序号/时间/张数三列固定宽（26/96/56）、
头部行高 30、照片条与序号列同一左边缘；高度随内容变化（无照片时占位行 40 dp），不强行等高。

**结构仍然是从 core 反推的**：`EventSnapshot.photoIds` 是事件字段 → 事件和它的照片必须是同一张卡片；
core 没有 `EXPORTED` 状态 → 结果条靠 `state.lastExport.exportDir` 的边沿检测出现；
`analyze()` 是原子流水线 → 页面上只有一个「开始分析」。

> 弹窗是**窗口内覆盖层**（`dialog/SheetHost.kt`），不是独立 OS 窗口。
> 后者在 125% 缩放下会被系统按另一套 DPI 处理：实测 830×455 dp 被渲染成约 440×455，标题被裁、按钮溢出。

### 2.4 断点（`model/Layout.kt` 唯一实现）

| 内容宽 | 照片格 | 一行最多 | 说明 |
|---|---|---|---|
| ≥ 1300 | 96 dp | 10 格 | 超出用「+N」，**绝不横向滚动** |
| ≥ 1080 | 88 dp | 10 格 | |
| ≥ 880 | 80 dp | 9~10 格 | 内业机最小窗（900 - 14 = 886）走这一档 |
| < 880 | 72 dp | 8 格 | |

卡片栅格常量：`CARD_INDEX_W=26 / CARD_TIME_W=96 / CARD_COUNT_W=56 / CARD_HEADER_H=30 / EMPTY_STRIP_H=40`，
`cardHeight(tile)` 与 `emptyCardHeight()` 一起盯住"列宽不变、高度随内容"（见 `model/Layout.kt`）。

两条硬约束（已写成单测）：

1. **卡片内照片条永不溢出卡片宽度** —— 7 dp 步长扫 640~3840 全部成立。
2. **弹窗永远落在窗口内** —— 宽 `(窗口宽-56).coerceIn(640,1040)`、高 `(窗口高-48).coerceIn(420,720)`，
   900×540 的窗口得到 830×455（大图 530 + 侧栏 300 + 缩略条 72 + 操作条 42 → 大图高 300）。

### 2.5 冒烟与走查怎么跑

```bash
./gradlew :desktop:ui:run                                                    # 正常窗口
./gradlew :desktop:ui:run --args="--smoke"                                   # 渲染后自动退出
./gradlew :desktop:ui:run --args="--demo --smoke --size 900x540 --shot l.png"          # 事件列表（最小窗）
./gradlew :desktop:ui:run --args="--demo --smoke --sheet photos --shot p.png"           # 照片层
./gradlew :desktop:ui:run --args="--demo --smoke --sheet settings --shot s.png"         # 设置层（已无日期，日期在主页）
./gradlew :desktop:ui:run --args="--demo --smoke --sheet copy --shot c.png"             # 复制到某事件（对比 --sheet move）
./gradlew :desktop:ui:run --args="--sheet log"                                          # 强制展开日志条
```

| 参数 | 作用 |
|---|---|
| `--smoke` | 渲染后自动退出（脚本断言用） |
| `--demo` | 造 12 张**带真实 EXIF（时间 + GPS）**的 JPEG + 一段真实格式巡查日志，走完整 `analyze()`；**数据目录独立**（默认 `%APPDATA%\PhotoMgm-demo`），不污染用户设置 |
| `--size 900x540` | 强制初始尺寸，跳过 clamp（复现小窗用） |
| `--sheet log\|photos\|settings\|move\|copy` | 直接打开某个覆盖层（截图走查用，不改业务逻辑；`move`/`copy` 是同一个分配层的两种模式） |
| `--shot out.png` | 把窗口截成图片，**按 `window.insets` 裁到客户区**（否则会带上 DWM 隐形边框外其他程序的像素） |
| `--log-file out.log` | 日志 tee 成 UTF-8 文件（中文 Windows 控制台是 GBK，重定向必乱码） |
| `--data-dir <dir>` | 覆盖数据目录（演示/测试用） |

`--demo` 的关键在于**数据是真的**：手写小端 TIFF/APP1 段生成 EXIF，然后跑真的 `analyze()`，
所以界面渲染的是真 `AppState`（8 个事件 / 12 张照片 / 2 张未匹配），不是假数据。
累计 20 处缺陷（弹窗被系统缩放、卡片"无照片"重复、单照片事件误标"照2"、照片格过大、
顶栏按钮挤成一排、设置内容被裁、空状态文案与日志框重复、日志框过高、弹窗标题被截断、
设置层"导出内容"落在折叠线以下、滚动区没有任何可见提示、日期写成"周周日"、
缩略图角标把时刻顶偏、无照片占位条撑到 80 dp、日期按钮箭头开合反向、
分配弹窗底边越过窗口下沿、勾选框在浅底上看不见、日期行多余说明、工具条与列表同底色相接、
空状态通栏文字拉到 1400 dp）全部是看真实截图发现的。
其中两处不是审美问题：Compose 的滚动默认零提示，小窗口下勾选框看不见就等于关不掉台账；
分配弹窗"比可用高度再高 40"会让最后一行被切成半行且抓不到滚动条。

每次渲染打一行可 diff 的布局日志：

```
render w=886 h=503 tile=80 maxTiles=9 sheet=none busy=-
state status=READY events=8 photos=12 unmatched=2 ledger=8 parsed=8 filter=ALL q=''
```

证据图（`ui/build/shots/`）：`list-900x540.png`、`list-1366x740.png`、`photos-900x540.png`、
`settings-900x540.png`、`move-900x540.png`、`empty-900x540.png` 及同名 `.log`。

### 2.6 仍属于「延后开发」的 UI 部分

- 缩略图渲染（U1.3 `PcThumbnailProvider`）、大图预览 —— 当前是稳定占位块（按 `id.hashCode()` 取色，滚动不闪）
- 拖拽移动照片 —— 当前用「选中 → 点事件 → 移到」两段式；拖放在跨区域 + 需滚动时命中率低
- 撤销栈 —— core 没有 undo，人工调整只能「全部重置」；与其做半可信的 Ctrl+Z，不如把重置写明白
- 深色模式、安装包（MSI）、开始菜单与卸载项、代码签名
- 窗口位置记忆的**写回**已验证可用，但跨 DPI 显示器的往返一致性留到 M6b 实机确认


---

## 三、分层与依赖方向（界面与逻辑分离的落点）

```
:ui + :cli  （平级消费者）
  │  只允许依赖 api 包：PhotoMgmCore / AppState / ExportModels
  ▼
api/        门面 + 状态快照 + 监听器 + 可注入扩展点      ← UI 与逻辑的唯一契约（421 行）
  ▼
logic/      流水线编排（分析 / 人工调整 / 导出编排）
  ▼
platform/   Pc* 平台实现：目录扫描 / EXIF / 哈希 / 图片编码 / 打开文件
  ▼
:algorithm  原工程算法核心（外部目录挂载，零改动）
```

依赖是**单向**的：`:algorithm` 不知道桌面端存在；`:core` 不依赖任何 UI 框架。
核心中不出现 `JFrame`、不弹对话框、不读键盘鼠标。

> ✅ 分层已是**编译期强制**：core 的 `logic/`、`platform/`、`state/`、`infra/`、`export/` 全部标记 `internal`，
> 对 `:ui`、`:cli` 等外部模块不可见 —— 试图 import `PcPaths` / `PhotoMgmCoreImpl` / `PcFolderExporter` 会直接编译失败。
> core 自己的测试是 friend module，仍可见 internal 成员，不受影响。

### UI 接入只需要三步

```kotlin
val core = PhotoMgmCoreFactory.create(CoreConfig())   // 1) 建核心（执行器/时钟/外壳皆可注入）
core.state.collect { state -> render(state) }         // 2) 订阅（state 是不可变全量快照）
val op = core.analyze(listener)                       // 3) 动作（长任务返回可取消句柄）
op.cancel()
```

UI 需要的数据全在 `AppState` 里：`events` / `photos` / `eventPhotoIds` / `unmatchedPhotoIds` /
`ledger` / `progress` / `exportProgress` / `message` / `error`。核心只给**数据与中文文案**，
不决定颜色字号排版；时间统一给 epoch 毫秒，由 UI 按 `AppZone.ID` 自行格式化。

### UI 侧"口径只能有一份"的落点

`ui/model/` 下全部是**无 Compose 依赖的纯函数**，Composable 里不许写业务算术。
理由很具体：v1.0 里"待确认"在工具条、状态栏、事件行各算一遍，三个数字一度不一致 ——
口径重复实现，最终一定出现"界面说 7 个事件，台账 8 行"。

| 文件 | 唯一负责 | 单测 |
|---|---|---|
| `model/UiState.kt` | `UiState` / `Sheet` / `reduce`：CoreStatus → 界面（含"分析完成自动收日志框""导出完成出结果条"的边沿检测、失效实体清理） | `UiStateTest` 18 例 |
| `model/WorkQueue.kt` | 审核语义：`needsReview` / `issueLabels` / `stats` / `filter` / `exportBlockers` | `WorkQueueTest` 9 例 |
| `model/LedgerJoin.kt` | 日志序 → 台账序的配对（两者行序不同）；单照片事件的槽位标注 | 随 `WorkQueueTest` |
| `model/Layout.kt` | 照片格、一行格数、弹窗尺寸 | `LayoutTest` 11 例 |
| `app/WindowGeometry.kt` | 尺寸策略与 clamp | `WindowGeometryTest` 10 例 |

---

## 四、构建与测试

前置：**JDK 17**（本机在 `D:/Android/jdk-17.0.2`，`C:\Users\28988\.workbuddy\jdks\jdk-17.0.2` 是同一份副本；两者都未写入系统 `JAVA_HOME`，需显式设置）。
Android SDK **只有执行 `:android:*` 任务时才需要**，桌面测试不需要。

> ✅ **桌面模块不需要 Android SDK**（2026-11-06 实测，当次未设 `ANDROID_HOME`、无 `local.properties`，
> 即 Gradle 定位不到 SDK；SDK 实体本机其实在 `D:/Android/sdk`，措辞已从「本机无 SDK」更正）：
> `gradle projects` / `gradle :desktop:core:test` / `:desktop:ui:test` / `:shared:*:test`
> 全部 `BUILD SUCCESSFUL`（669 用例 0 失败）；只有 `gradle :android:data:testDebugUnitTest`
> 报 `SDK location not found`。原因：AGP 8.7.3 把 SDK 定位推迟到**任务执行期**，配置期不校验。
> 注意 `gradle testAll` **包含**两个 `:android:*` 测试任务，所以它仍然要 SDK。
> 详见根 `README.md` 的「环境前置（重要）」节。

> ★ **单根多模块（M3 起）**：`desktop/` 不再是独立 Gradle 工程，本目录下的 `settings.gradle.kts`、
> `build.gradle.kts`、`gradle.properties`、`gradlew`、`gradlew.bat`、`gradle/wrapper/` **已全部删除**。
> 现在所有命令都在**仓库根**执行，桌面端项目名是 `:desktop:core` / `:desktop:cli` / `:desktop:ui`。
> 产物路径未变（`desktop/ui/build/compose/binaries/...`、`desktop/cli/build/install/cli/`）。
> 本地用 `./gradlew`（腾讯云分发包），CI 用 `gradle`（setup-gradle 8.10.2，不用 wrapper）。

```bash
cd <仓库根>
export JAVA_HOME="/c/Users/28988/.workbuddy/jdks/jdk-17.0.2"
export PATH="$JAVA_HOME/bin:$PATH"
# Android SDK 仅在需要跑 :android:* 任务时配置，桌面/共享层测试不需要

./gradlew :desktop:core:test :desktop:ui:test :shared:algorithm:test   # 不需要 SDK
./gradlew testAll           # 全部 8 个模块的测试（含 2 个 :android:* 任务 → 需要 SDK）
./gradlew :desktop:ui:run --args="--smoke"   # UI 冒烟
./gradlew :desktop:cli:installDist  # 可分发命令行到 desktop/cli/build/install/cli/
```

---

## 五、命令行用法（无 UI 也能真实跑完一遍）

```bash
./gradlew :desktop:cli:run --args="\
  --source D:/巡查照片 --date 2026-08-14 \
  --log D:/巡查日志.txt --out D:/导出 --template 0"
```

| 参数 | 说明 |
|---|---|
| `--source, -s` | 照片源目录，可重复传多个 |
| `--date, -d` | 巡查日期 `yyyy-MM-dd`（默认今天，按北京时间） |
| `--log, -l` | 巡查日志文件（UTF-8 / GBK 自动识别） |
| `--log-text` | 直接传日志文本（与 `--log` 二选一） |
| `--out, -o` | 导出父目录 |
| `--template, -t` | 命名模板 `0`=事件类型，`1`=序号+巡查日志内容（默认 1） |
| `--dedup` | `keep-watermarked`（默认）/ `keep-plain` / `keep-both` |
| `--no-ledger` | 不生成台账，只复制照片 |
| `--data-dir` | 数据目录（默认 `%APPDATA%\PhotoMgm`） |
| `--dry-run` | 只分析不导出 |

退出码：`0` 成功、`1` 业务失败（导出不完整）、`2` 参数或环境错误。
在 `cmd.exe` 默认代码页下若中文乱码，先 `chcp 65001`（或改用 Windows Terminal）。

---

## 六、打包（M7-tech 已通过）

```bash
export JAVA_HOME="/c/Users/28988/.workbuddy/jdks/jdk-17.0.2"
./packaging/m7-tech.sh
# 可选：带上真实样例，直接验证打包产物能跑完整业务
SMOKE_ARGS="--source D:/照片 --date 2026-08-14 --log D:/日志.txt -o D:/导出" ./packaging/m7-tech.sh
```

**为什么 main class 用 `:cli` 而不是 UI**：`jpackage` 支持 `--win-console`，控制台应用同样能出 app-image。
用它先把「模块裁剪后运行时报错、中文/长路径、干净机缺依赖」三个高风险点验掉；
M7-prod 只需换 `--main-class` / `--main-jar`，不用重新踩坑。

### 实测数据（2026-11-01，JDK 17.0.2）

| 项 | 值 |
|---|---|
| jdeps 模块集 | `java.base,java.desktop,java.instrument,jdk.unsupported` |
| app-image 总计 | **66 MB** |
| └ 裁剪运行时 | **61 MB** |
| 完整 JDK 17 | 296 MB（节省 235 MB） |
| 脱离 `JAVA_HOME` 运行 | ✅ `env -u JAVA_HOME` 下中文输出、分类、导出、自检全部正常，退出码 0 |

**踩坑记录**：`jdeps` 若把 `lib/*.jar` 全部作为位置参数传入，会为每个 jar 打一行
`Warning: split package` 到 **stdout**，与模块列表混在一起 → `--add-modules` 拿到脏值 →
jlink 报 `java.base not found`。只传主 jar 即可。另：Windows 中文环境下 jdeps 用 GBK 输出，
脚本里必须 `-J-Duser.language=en` 强制英文，否则判断会乱码。

---

## 七、导出产物结构

```
<out>/1101_122223/
├── 01_护栏损坏/            # 事件子文件夹（前缀是事件序号，保证排序稳定）
│   ├── IMG_20260814_101530.jpg
│   └── ...
├── 99_未分类/              # 未匹配照片
├── ledger.xlsx            # 台账：序号/时间/结束时间/地点/日志内容/照片1/照片2（内嵌 4:3 照片 3.81×2.86cm；数据行行高 81pt=2.86cm，F/G 列宽 108pt=3.81cm，与照片严丝合缝；照片下方无字）
├── export.log             # JSON Lines：export_start / copy / skip / failed / rename / export_end
└── export-manifest.json   # 续传清单：相对路径 + 大小 + SHA-256 + 修改时间
```

**计数口径**：`totalTargets / copied / skipped / failed` 只统计**照片**，台账是否生成看 `ledgerFile`。

**续传语义**：同一输出父目录下若已存在「同批照片 + 同台账参数 + 全部产物完好」的导出目录，则整体跳过并复用，
不新建目录；任一产物被删/改动都会重新复制。manifest 随产物走（放在导出根内），换输出目录不会误跳过。

---

## 八、已知限制

1. **HEIC 不解码**：v1 不做原生解码，EXIF 读不到时降级用文件名日期，并在分析摘要里给出计数提示，不静默丢照片。
2. **缩略图未实现**：计划在 U1.3 落地 `PcThumbnailProvider`（异步 + 可取消 + LRU + 内存上限注入），
   接口给 UI 的是**受控加载语义**（句柄），不是裸 `BufferedImage`——1000 张网格持有 1000 个图必然 OOM。
3. **渲染后端回落**：本机 D3D12 不可用，Skiko 回退。需在兼容性阶段补「回退场景仍可用 + 可自诊断」验收。
4. **`.gitignore` 由本目录自带**（`desktop/.gitignore`），未改动根 `.gitignore`，因为根文件同属原工程文件。
5. **首次构建曾把产物写进原工程** `algorithm/build`：当时在 `desktop/build.gradle.kts` 里把 `:algorithm` 的
   buildDir 重定向到 `desktop/build/external/algorithm` 来规避。
   **M3 单根化后这套重定向已整体删除**（`desktop/build.gradle.kts` 与 `desktop/build/external/` 都不再存在），
   `:shared:*` 只有一个 `build/` 目录。若磁盘上仍残留 `desktop/build/external/`，属旧构建遗留，可直接删除。

---

## 九、下一步

| 序 | 任务 | 关键验收 | 状态 |
|---|---|---|---|
| U1 | `:ui` 骨架：窗口 / 主题 / 状态归约 / 布局纯函数 | 能显示 AppState 真实数据 | ✅ 已完成（v1/v2 结构已废弃） |
| U2 | 输入：日期 / 源目录 / 日志导入 / 单按钮分析 | 轻量人工验证操作手感 | ✅ 已完成（日志条 + 设置层） |
| U3 | 复核：事件卡片列表 / 照片弹窗 / 未匹配池 | **强制人工走查**（含台账照片单选/多选） | ✅ 已完成，待人工走查 |
| U4 | 导出与结果：进度 / 结果条 / 失败明细 | 取消与续传在 UI 上语义正确 | ✅ 已完成（顶栏导出 + 结果条） |
| U1.3 | `PcThumbnailProvider`（core 纯新增，不改既有签名） | 请求可取消、缓存有上限 | 待做（接口签名先过审） |
| M6a-2 | 1000 张缩略图压测（只测量） | RSS 峰值 / 单张解码耗时 / 命中率 | 待做 |
| M6b | 高 DPI / 窗口记忆 / 滚动 / 渲染回退 | 三档 DPI + 软件渲染不卡死 | 部分（读写通路已通） |
| M7-prod | 换 main class、图标、MSI | 安装→启动→导入→导出→卸载冒烟 | 待做 |
| M8 | 文档与交付包 | 含校验值 | 待做 |

> 完整 UI 设计规范（页面清单 / 组件库 / 交互 / 响应式 / 交付形式）见 **`docs/WINDOWS-DESKTOP-UI-SPEC.md`**。
