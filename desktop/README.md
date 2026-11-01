# Photo-Mgm Windows 桌面版

> 对现有 Android 版 Photo-Mgm v1.3.2 的二次开发，输出符合 Windows 系统标准的桌面软件。
> 原工程 `algorithm/ data/ app/` 三模块**零改动**（`git diff --stat 6c29bbd -- algorithm data app` 为空可验证）。

当前进度：**逻辑核心已完成（M1–M3 + M5 主体）→ UI 已起步（U0 通过）→ M7 打包技术链路已验证**。

| 模块 | 职责 | 状态 |
|---|---|---|
| `:core` | 逻辑核心，零 UI 依赖（无 Compose / Swing / AWT 窗口） | ✅ 2807 行主源码 + 1101 行测试 |
| `:cli` | 无头命令行入口，也是打包验证的 main class | ✅ 362 行 |
| `:ui` | Compose Multiplatform 桌面界面 | 🚧 U0 空窗口冒烟通过 |
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

## 二、界面层现状（U0：版本矩阵与渲染能力验证）

U0 只验证「能不能在这台机器上起窗、渲染中文、正常退出」，**不是产品界面**。三栏工作区在 U2–U4 实现。

### 2.1 已钉死的结论

| 项 | 结论 | 依据 |
|---|---|---|
| 版本矩阵 | **Kotlin 2.0.21 + Compose Multiplatform 1.7.3 直接可用**，无需升级 Kotlin | `:ui:compileKotlin` 通过 |
| 仓库源 | mavenCentral 可用；**必须加 `google()`** | `ui-desktop` 传递依赖 `androidx.lifecycle:*:2.8.5`，该构件只在 Google Maven |
| — | `google()` 指向 `https://dl.google.com/dl/android/maven2/`（200/0.4s）；`maven.google.com` 本网络超时 | curl 实测 |
| 中文字体 | 正常，无需额外字体配置 | 冒烟窗口渲染「巡查日志 · 桩号 K138+700 · 台账照片 · 99_未分类」 |
| DPI | 本机 **1920×1080 @ scale=1.25**；Compose 按 dp 自动换算 | 冒烟日志 |
| 渲染后端 | 本机 **D3D12 设备创建失败 → Skiko 回退其他 API**，功能正常但非 GPU 加速路径 | `[SKIKO] warn: Fallback to next API` |

**关于 D3D12 回退**：本机主显示适配器是 `OrayIddDriver`（向日葵虚拟显示器），D3D12 设备创建失败属预期。
远程桌面、虚拟机、老旧显卡环境都会遇到。这不是阻塞项，但**必须写进兼容性验收**：
回退后 UI 须仍可用、网格滚动不能卡死，且应能自诊断（日志或状态栏显示当前后端），否则用户报障时无从判断。

### 2.2 冒烟怎么跑

```bash
./gradlew :ui:run                    # 开窗，手动关闭
./gradlew :ui:run --args="--smoke"   # 渲染 1.5s 后自动退出，供脚本断言
```

期望输出：`[SMOKE] headless=false` → `[SMOKE] composed` → `[SMOKE] alive after 1.5s` → `[SMOKE] exited cleanly`，退出码 0。

### 2.3 仍属于「延后开发」的 UI 部分

- 三栏布局（源目录 / 照片网格 / 事件卡 + 未匹配池）、缩略图渲染、大图预览
- 拖拽导入、右键菜单、Ctrl/Shift 多选、快捷键、Esc 关闭
- 原生文件/文件夹选择对话框、窗口位置记忆的实际读写
- 进度条 / 提示条 / 错误弹窗的视觉呈现（核心只给进度与文案数据）
- 安装包（MSI）、开始菜单与卸载项、代码签名

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

---

## 四、构建与测试

前置：JDK 17（本机在 `C:\Users\28988\.workbuddy\jdks\jdk-17.0.2`，未写入系统 `JAVA_HOME`）。

```bash
cd desktop
export JAVA_HOME="/c/Users/28988/.workbuddy/jdks/jdk-17.0.2"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew test              # 全部测试（原算法 316 + 桌面核心 43 = 359 全绿）
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

| 序 | 任务 | 关键验收 |
|---|---|---|
| U1 | `:ui` 骨架：窗口/主题/状态订阅/三栏布局骨架 | 能显示 AppState 真实数据 |
| U1.3 | `PcThumbnailProvider`（core 纯新增，不改既有签名） | 请求可取消、缓存有上限 |
| M6a-2 | 1000 张缩略图压测（只测量） | RSS 峰值 / 单张解码耗时 / 命中率 |
| U2 | 设置 / 源目录 / 日志导入 | 轻量人工验证操作手感 |
| U3 | 照片网格 / 事件卡 / 未匹配池 | **强制人工走查**（含台账照片单选/多选） |
| U4 | 导出进度 / 结果 / 失败明细 | 取消与续传在 UI 上语义正确 |
| M6b | 高 DPI / 窗口记忆 / 滚动 / 渲染回退 | 三档 DPI + 软件渲染不卡死 |
| M7-prod | 换 main class、图标、MSI | 安装→启动→导入→导出→卸载冒烟 |
| M8 | 文档与交付包 | 含校验值 |
