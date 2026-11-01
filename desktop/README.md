# Photo-Mgm Windows 桌面版

> 对现有 Android 版 Photo-Mgm v1.3.2 的二次开发，输出符合 Windows 系统标准的桌面软件。
> 原工程 `algorithm/ data/ app/` 三模块**零改动**（`git diff --stat 6c29bbd -- algorithm data app` 为空可验证）。

当前进度：**逻辑核心已完成（M1–M3 + M5 主体）→ UI 已按运行逻辑重做（U0 通过、U2 = 四阶段 + 审核主轴，可显示真实 AppState）→ M7 打包技术链路已验证**。

| 模块 | 职责 | 状态 |
|---|---|---|
| `:core` | 逻辑核心，零 UI 依赖（无 Compose / Swing / AWT 窗口） | ✅ 2807 行主源码 + 1101 行测试 |
| `:cli` | 无头命令行入口，也是打包验证的 main class | ✅ 362 行 |
| `:ui` | Compose Multiplatform 桌面界面 | 🚧 U0 + U2（窗口自适应 / 设计令牌 / 状态归约 / 四阶段 / 审核主轴） |
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
| 11 | 导出到**文件夹**（`PhotoMgm_export_yyyyMMdd_HHmmss/` + 事件子文件夹 + `99_未分类/`） | ✅ 实现 | `PcFolderExporterTest` 11 用例 |
| 12 | 导出进度、可取消、失败明细 | ✅ 实现 | `PcFolderExporterTest.取消导出后写入不完整清单且不命中续传` |
| 13 | 断点续传（`export-manifest.json` 逐项校验） | ✅ 实现 | `PcFolderExporterTest` 续传 3 用例 + CLI 连跑两次 |
| 14 | `export.log`（JSON Lines，机器可读 + 人可读） | ✅ 实现 | `PcFolderExporterTest.export_log 是合法 JSONL` |
| 15 | 设置持久化（`%APPDATA%\PhotoMgm\settings.json`） | ✅ 实现 | `CorePipelineTest.设置持久化后新实例可恢复` |
| 16 | 日志文本草稿持久化（防崩溃丢输入） | ✅ 实现 | `LogsTextStore` |
| 17 | 长路径（>260 字符）前置拦截并给出可操作建议 | ✅ 实现 | `PcFolderExporterTest.超长目标路径在导出前被明确阻断` |
| 18 | 无头命令行入口（批处理 / 排障 / 验收） | ✅ 实现（额外交付） | `:cli:run`，见第五节 |

---

## 二、界面层现状（U0 通过 · U2 按运行逻辑重做）

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

| 物理分辨率 | 缩放 | 工作区 dp | 实际开出 | 内容宽 | 事件列 | 照片网格 / 列数 | 检查器 |
|---|---|---|---|---|---|---|---|
| 1366×768 | 100% | 1366×728 | 1229×655 | 1215 | 260 | 889 / 5 | 抽屉 |
| **1366×768** | **125%** | **1093×574** | **984×540** | **970** | **240** | **664 / 3** | **抽屉** |
| 1920×1080 | 100% | 1920×1040 | 1440×900 | 1426 | 280 | 759 / 4 | 常驻 |
| 1920×1080 | 125% | 1536×816 | 1382×734 | 1368 | 260 | 1042 / 5 | 抽屉 |
| 1920×1080 | 150% | 1280×680 | 1152×612 | 1138 | 260 | 812 / 4 | 抽屉 |
| 3840×2160 | 200% | 1920×1040 | 1440×900 | 1426 | 280 | 759 / 4 | 常驻 |

> 1920@125% 的列数（5）比 1920@100%（4）还多，是因为 1368 dp 刚好差 12 dp 没到常驻检查器的 1380 线，
> 少了一栏 320 宽反而给网格腾出 283 dp。这条线是有意选的：用一列照片换「看详情不用点」，复核阶段点击次数才是真实成本。

**二次启动**（M6b）：恢复 `settings.json` 里的窗口几何，只做两件事 ——
低于最小值抬到最小值、超出工作区居中（显示器拔掉/改分辨率后不会留下一个看不见的窗口）。
注意存的是 **dp 而非物理像素**：用户调的是"多大一块地方"，换显示器不漂。
`--smoke` 与 `--size` 不写记忆，免得把用户设置覆盖成测试值。

### 2.3 界面结构：4 个阶段，不是 1 个工作区

v1.0 的三栏骨架（左输入 / 中事件 / 右照片）已整体删除。原因是结构和 core 的语义对不上：
输入项只在第一步用一次却长期占 30% 宽；照片被摆成事件的平行邻居，而 `photoIds` 本来就是事件的字段；
`analyze()` 是一个原子流水线却在界面上拆成两个按钮。完整对照见 `docs/WINDOWS-DESKTOP-UI-SPEC.md` §0。

现在的结构：

| Stage | 回答的问题 | 区域 |
|---|---|---|
| `INPUT` | 处理哪一天、哪些目录、哪份日志 | 素材面板 + 日志面板 + 分析底栏（单按钮） |
| `REVIEW` | 台账对不对、漏了哪些照片 | 事件列 + 照片网格 + 检查器（主轴，占 80% 时间） |
| `EXPORT` | 写出什么、写到哪、怎么校验 | 选项面板 + 台账预览表 + 导出底栏 |
| `RESULT` | 成功了吗、产物在哪 | 结论条 + 统计格 + 产物清单 + 失败明细 |
| 覆盖层 | 在干什么、能取消吗 | `BusyOverlay`（460 dp，按分析/导出给不同取消语义） |

左侧 **64 dp** 竖排步骤条常驻（窄窗 56 dp），显示 3 步 + 当前位置 + 待确认徽标。
`RESULT` 不是第 4 步，它是导出后的结果态，挂在 EXPORT 下。

**这个结构是从 core 反推的，不是画出来的**：`CoreStatus` 的 6 个值里 `ANALYZING`/`EXPORTING` 天然是独立阶段，
`READY` 对应 REVIEW；core 没有 `EXPORTED`，所以结果页靠 `state.lastExport.exportDir` 的边沿检测切入。

### 2.4 REVIEW 断点（`model/Layout.kt` 唯一实现）

| 内容宽 | 事件列 | 检查器 | 网格 | 列数 |
|---|---|---|---|---|
| ≥ 1380 | 280 内联 | 320 内联 | 其余 | 4~7 |
| 1100 ~ 1379 | 260 内联 | 抽屉 | 其余 | 4~5 |
| 900 ~ 1099 | 240 内联 | 抽屉 | 其余 | 3~4 |
| < 900 | 0 折叠 | 抽屉 | 其余 | 2~4 |

两条硬约束（由 900 dp 反推，已写成单测）：

1. **事件列折叠必须留替代入口** —— 照片区顶部的 `EventPicker`（`3/8 切换事件 · 4 待确认`）。
   折叠不能等于删掉一个动作，这是 v1.0 的教训。
2. **绝不出现横向滚动条** —— 网格宽不够就减列：`((gridW + 12) / (168 + 12)).coerceIn(1, 7)`，单块最小 168 dp。

宽度守恒有单测：`rail + 分隔线 + list + grid + inspector == 内容宽`，7 dp 步长扫 640~3840 全部成立。

### 2.5 冒烟与走查怎么跑

```bash
./gradlew :ui:run                                                    # 正常窗口
./gradlew :ui:run --args="--smoke"                                   # 渲染后自动退出
./gradlew :ui:run --args="--demo --shot r.png --log-file r.log"      # 造真实素材，截 REVIEW 页
./gradlew :ui:run --args="--demo --size 900x540 --stage review"      # 复现内业机最小窗
```

| 参数 | 作用 |
|---|---|
| `--smoke` | 渲染后自动退出（脚本断言用） |
| `--demo` | 造 12 张**带真实 EXIF（时间 + GPS）**的 JPEG + 一段真实格式巡查日志，走完整 `analyze()`；**数据目录独立**（`%APPDATA%\PhotoMgm-demo`），不污染用户设置 |
| `--size 900x540` | 强制初始尺寸，跳过 clamp（复现小窗用） |
| `--stage input\|review\|export\|result` | 直接落某阶段（截图走查用，不改业务逻辑） |
| `--shot out.png` | 把窗口截成图片，**按 `window.insets` 裁到客户区**（否则会带上 DWM 隐形边框外其他程序的像素） |
| `--log-file out.log` | 日志 tee 成 UTF-8 文件（中文 Windows 控制台是 GBK，重定向必乱码） |

`--demo` 的关键在于**数据是真的**：手写小端 TIFF/APP1 段生成 EXIF，然后跑真的 `analyze()`，
所以界面渲染的是真 `AppState`（8 个事件 / 12 张照片 / 2 张未匹配），不是假数据。
本版 6 处缺陷（列溢出、路径截断、日期行裁切、表格挤压、状态栏出血、折叠后无事件切换入口）
全部是看这些真实截图发现的。

每次渲染会打一行可 diff 的布局日志，例如：

```
render w=970 stage=REVIEW rail=64 list=240 grid=664 cols=3 inspector=drawer
state status=READY events=8 photos=12 unmatched=2 ledger=8 parsed=8 selected=Event(id=3)
```

证据图（`ui/build/shots/`）：`review-984x540.png`、`review-900x540.png`、`input-984x540.png`、`export-984x540.png` 及同名 `.log`。

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

> ⚠️ 分层目前是**约定**，不是编译期强制：`implementation(project(":core"))` 会暴露 core 的全部 public 类型。
> 真正强制需把 `logic/`、`platform/` 改为 `internal` —— 那属于改 core 可见性，按「api 只增不改」单独立项。

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
| `model/UiState.kt` | `reduce(ui, state)`：CoreStatus → 界面阶段（含"分析完成""导出完成"的边沿检测） | `StageReducerTest` 12 例 |
| `model/WorkQueue.kt` | 审核语义：`needsReview` / `issueLabels` / `stats` / `filter` / `exportBlockers` | `WorkQueueTest` 9 例 |
| `model/LedgerJoin.kt` | 日志序 → 台账序的配对（两者行序不同） | 随 `StageReducerTest` |
| `model/Layout.kt` | 所有宽度、列数、断点 | `LayoutTest` 9 例 |
| `app/WindowGeometry.kt` | 尺寸策略与 clamp | `WindowGeometryTest` 10 例 |

---

## 四、构建与测试

前置：JDK 17（本机在 `C:\Users\28988\.workbuddy\jdks\jdk-17.0.2`，未写入系统 `JAVA_HOME`）。

```bash
cd desktop
export JAVA_HOME="/c/Users/28988/.workbuddy/jdks/jdk-17.0.2"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew test              # 全部测试（原算法 316 + 桌面核心 43 + 界面 40 = 399 全绿）
./gradlew :ui:run --args="--smoke"   # UI 冒烟
./gradlew :cli:installDist  # 可分发命令行到 cli/build/install/cli/
```

---

## 五、命令行用法（无 UI 也能真实跑完一遍）

```bash
./gradlew :cli:run --args="\
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
| `--template, -t` | 命名模板 `0`=事件类型，`1`=月日+事件类型+日志内容（默认 1） |
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
<out>/PhotoMgm_export_20261101_122223/
├── 01_护栏损坏/            # 事件子文件夹（前缀是事件序号，保证排序稳定）
│   ├── IMG_20260814_101530.jpg
│   └── ...
├── 99_未分类/              # 未匹配照片
├── ledger.xlsx            # 台账：序号/日期/结束时间/地点/日志内容/照片1/照片2（内嵌 4:3 照片）
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
5. **首次构建曾把产物写进原工程** `algorithm/build`：已在 `desktop/build.gradle.kts` 里把 `:algorithm` 的
   buildDir 重定向到 `desktop/build/external/algorithm`。想清掉那次残留，在原工程执行 `./gradlew clean` 即可。

---

## 九、下一步

| 序 | 任务 | 关键验收 | 状态 |
|---|---|---|---|
| U1 | `:ui` 骨架：窗口 / 主题 / 状态归约 / 布局纯函数 | 能显示 AppState 真实数据 | ✅ 已完成（v1.0 三栏，已废弃） |
| U2 | 输入页：日期 / 源目录 / 日志导入 / 单按钮分析 | 轻量人工验证操作手感 | ✅ 已完成（PG-1） |
| U3 | 审核页：事件列 / 照片网格 / 检查器 / 未匹配池 | **强制人工走查**（含台账照片单选/多选） | ✅ 已完成（PG-2），待人工走查 |
| U4 | 导出页 + 结果页：进度 / 结果 / 失败明细 | 取消与续传在 UI 上语义正确 | ✅ 已完成（PG-3/PG-4） |
| U1.3 | `PcThumbnailProvider`（core 纯新增，不改既有签名） | 请求可取消、缓存有上限 | 待做（接口签名先过审） |
| M6a-2 | 1000 张缩略图压测（只测量） | RSS 峰值 / 单张解码耗时 / 命中率 | 待做 |
| M6b | 高 DPI / 窗口记忆 / 滚动 / 渲染回退 | 三档 DPI + 软件渲染不卡死 | 部分（读写通路已通） |
| M7-prod | 换 main class、图标、MSI | 安装→启动→导入→导出→卸载冒烟 | 待做 |
| M8 | 文档与交付包 | 含校验值 | 待做 |

> 完整 UI 设计规范（页面清单 / 组件库 / 交互 / 响应式 / 交付形式）见 **`docs/WINDOWS-DESKTOP-UI-SPEC.md`**。
