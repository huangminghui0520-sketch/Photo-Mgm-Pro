# Photo-Mgm Windows 桌面版 · 逻辑核心（第一里程碑）

> 本目录是对现有 Android 版 Photo-Mgm v1.3.2 的二次开发。**本阶段只交付「无 UI 的逻辑核心」**，
> 图形界面按计划延后单独开发。原工程 `algorithm/ data/ app/` 三模块**零改动**（`git diff` 可验证）。

---

## 一、已实现的功能范围

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
| 18 | 无头命令行入口（批处理 / 排障 / 验收） | ✅ 实现（额外交付） | `:cli:run`，见第四节 |

### 明确「延后开发」的 UI 部分

以下内容**本期不做**，逻辑层已为其预留接口，接界面时**不需要改核心**：

- 窗口、标题栏、图标、最小/默认尺寸、高 DPI 缩放
- 三栏布局（源目录树 / 照片网格 / 事件卡 + 未匹配池）、照片缩略图渲染与大图预览
- 拖拽导入（文件 / 文件夹）、右键菜单、Ctrl/Shift 多选、快捷键、Esc 关闭
- 文件/文件夹选择对话框（Windows 原生），窗口位置记忆的**读写调用**（核心只负责存取，不做窗口操作）
- 进度条 / 提示条 / 错误弹窗等视觉呈现（核心只提供进度与文案数据）
- 安装包（MSI）、开始菜单与卸载项、代码签名（属 M7 打包阶段）

---

## 二、分层与依赖方向（界面与逻辑分离的落点）

```
UI（延后开发，任何框架）
  │   只允许依赖 api 包：PhotoMgmCore / AppState / ExportModels
  ▼
api/        门面 + 状态快照 + 监听器 + 可注入扩展点      ← UI 与逻辑的唯一契约
  ▼
logic/      流水线编排（分析 / 人工调整 / 导出编排）
  ▼
platform/   Pc* 平台实现：目录扫描 / EXIF / 哈希 / 图片编码 / 打开文件
  ▼
:algorithm  原工程算法核心（外部目录挂载，零改动）
```

依赖是**单向**的：`,algorithm` 不知道桌面端存在；`core` 不依赖任何 UI 框架（无 Compose / Swing / AWT 窗口）；
`:cli` 与未来的 UI 是**平级消费者**。核心中不出现 `JFrame`、不弹对话框、不读键盘鼠标。

### UI 接入只需要三步

```kotlin
// 1) 建核心（可注入执行器 / 时钟 / 外壳，测试与生产用同一套接口）
val core = PhotoMgmCoreFactory.create(CoreConfig())

// 2) 订阅状态（Compose: collectAsState；Swing/JavaFX: addListener）
core.state.collect { state -> render(state) }   // state 是不可变全量快照，渲染整屏即可

// 3) 调动作（长任务返回可取消句柄）
val op = core.analyze(listener)                  // 或 core.export(request, listener)
op.cancel()                                      // 用户点"取消"
```

UI 需要的数据全在 `AppState` 里：`events` / `photos` / `eventPhotoIds` / `unmatchedPhotoIds` /
`ledger` / `progress` / `exportProgress` / `message` / `error`。核心只给**数据与中文文案**，
不决定颜色字号排版；时间统一给 epoch 毫秒，由 UI 按 `AppZone.ID` 自行格式化。

---

## 三、构建与测试

前置：JDK 17（本机已装于 `C:\Users\28988\.workbuddy\jdks\jdk-17.0.2`，未写入系统 `JAVA_HOME`）。

```bash
# 在 desktop/ 目录下
export JAVA_HOME="/c/Users/28988/.workbuddy/jdks/jdk-17.0.2"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew test            # 全部测试（原算法 316 + 桌面核心 43）
./gradlew :core:test      # 只跑桌面核心
./gradlew :cli:installDist  # 产出可分发命令行到 cli/build/install/cli/
```

`./gradlew test` 期望结果：**359 个用例全绿，0 失败**。

---

## 四、命令行用法（无 UI 也能真实跑完一遍）

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

退出码：`0` 成功、`1` 业务失败（导出不完整）、`2` 参数或环境错误 —— 可直接用于脚本/CI 断言。
在 `cmd.exe` 默认代码页下直接运行若出现中文乱码，先执行 `chcp 65001`（或改用 Windows Terminal）。

---

## 五、导出产物结构

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
（混算会导致 `success > total`、`skipped` 变负数 —— 已在实现与单测中钉死。）

**续传语义**：同一输出父目录下若已存在"同批照片 + 同台账参数 + 全部产物完好"的导出目录，则整体跳过并复用，
不新建目录；任一产物被删/改动都会重新复制。manifest 随产物走（放在导出根内），换输出目录不会误跳过。

---

## 六、已知限制（本阶段）

1. **HEIC 不解码**：v1 不做原生解码，EXIF 读不到时降级用文件名日期，且在分析摘要里给出计数提示，不静默丢照片。
2. **缩略图未实现**：属 UI 层职责（核心只给 `sourceRef`），接入 UI 时用 `ImageIO` 抽样解码。
3. **`.gitignore` 由本目录自带**（`desktop/.gitignore`），未改动根 `.gitignore`：只补 `*.msi`/`*.exe`/打包输出，
   因为根文件同属原工程文件，"不动原工程"优先。
4. **首次构建曾把产物写进原工程** `algorithm/build`：已在 `desktop/build.gradle.kts` 里把 `:algorithm` 的
   buildDir 重定向到 `desktop/build/external/algorithm`，之后不会再污染原仓库。若想彻底清掉那次残留，
   在原工程执行 `./gradlew clean` 即可（`algorithm/build` 已被 `.gitignore` 忽略，不影响 git）。

---

## 七、下一步（按开发计划）

- **M4 UI**（延后单独开发）：按第四节的三步接入，先做"单页冒烟"再攻照片网格与事件卡。
- **M6 兼容与性能**：500~1000 张真实照片压测、网络盘/OneDrive、高 DPI 三档。
- **M7 打包**：`jlink` 裁剪运行时 + `jpackage` 出 MSI，`WiX` 缺失时降级 app-image + Inno Setup。
