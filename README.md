# Photo-Mgm · 高速公路巡查照片分类工具

面向路政巡查场景的照片分类工具：根据巡查日志（含时间、事件、地点），结合照片 GPS 与拍摄时间，自动将照片归类到对应巡查事件，支持人工调整、台账（Excel）导出与压缩包/文件夹交付。

**双端共用同一份 Kotlin 核心**：Android App（Jetpack Compose）与 Windows 桌面版（Compose Multiplatform），算法与业务规则只在 `shared/` 维护一份。

---

## 功能特性

### 核心能力

- **日志解析**：自动识别巡查日志中的时间、地点（桩号 + 方向）、事件类型、巡查人员与车牌
- **GPS 分组**：50 米内合并为一组，离群自动拆簇，跨日按完整时间戳
- **事件分类**：按日志时间窗口把照片归入事件，支持人工移动兜底
- **照片三态去重**：水印相机一日巡查常产出双份（主图 + 原图），支持「仅保留带水印 / 仅保留不带水印 / 两者都保留」三选一；单份照片一律保留，未匹配照片不受影响

### 台账与交付

- **台账生成**：每事件一行（序号 / 日期 / 结束时间 / 地点 / 日志内容 / 照片 1 / 照片 2），导出 Excel（OOXML 手写，零第三方依赖）
  - 日期列为 Excel 真实日期类型，可直接排序、筛选
  - 结束时间列：跨日事件按实际结束日期展示，单时间点事件留空
  - 照片列显示「文件夹名 / 文件名」，跨文件夹同名照片可精确定位
- **压缩包导出**：按「事件类型」或「日期 + 事件类型 + 日志内容」建文件夹，照片 + 台账 Excel 打包 ZIP
  - 文件夹按事件时间升序并加序号前缀（`01_`、`02_` …），未匹配照片统一归入 `99_未分类`
  - 台账照片列前缀与 ZIP 内路径一致，解压后一一对应
- **断点续传**：重复导出同一批照片且内容未变化时跳过已完成任务，不重复打包
- **失败明细**：导出失败时在结果提示中列出失败照片（ZIP 内路径 + 原因）

### 手机导入照片（Windows 桌面版）

- 从手机相册按**时间区间**导入照片，自动在电脑源目录下按区间建子文件夹（如 `04_2300__05_2359`）
- **时间区间可自由指定，支持跨自然日**：例如 11 月 5 日凌晨班次，可把起点设为 11 月 4 日 23:00
- 支持 **ADB** 与 **USB 盘符** 两种连接方式，自动探测、可手动切换
- **断点续传**：中断后重进可继续，已完成的照片跳过、半截文件重写、冲突不覆盖
- 未设置源目录或未选择手机目录时给出明确提示，不静默失败

---

## 下载与使用

### Windows 桌面版

从 [Releases](https://github.com/huangminghui0520-sketch/Photo-Mgm-Pro/releases) 下载：

| 产物 | 说明 |
|---|---|
| `PhotoMgm-1.0.1-portable.zip` | 免安装便携版，解压即用 |
| `PhotoMgm-1.0.1.msi` | 安装包，支持自动升级 |

便携版内含裁剪后的 JRE，**无需单独安装 Java**。

### Android 版

从 [Releases](https://github.com/huangminghui0520-sketch/Photo-Mgm-Pro/releases) 下载 APK。

> **签名说明**：当前发布的 APK 为 debug 签名，功能完整可安装，但不是正式签名版。正式签名版需配置 `KEYSTORE_BASE64` 后由 `v*` 标签触发构建。

### 使用流程

1. **设置**：选择照片源目录、输出目录、巡查日期与命名模板
2. **解析**：导入巡查日志文本，自动解析事件并分类照片，可在预览页人工调整
3. **导出**：一键导出压缩包（照片 + 台账 Excel），完成后可打开台账核对

---

## 环境前置

### 必需

- **JDK 17**：所有 Gradle 任务的前置。8 个模块的 `jvmToolchain(17)` / `jvmTarget 17` 均固定为 17，低版本会在配置阶段直接失败。设置 `JAVA_HOME` 指向 JDK 17 即可。

### 仅在需要时

- **Android SDK**：只在执行 `:android:*` 任务时需要，**不是所有命令的前置**。

  | 命令 | 是否需要 SDK |
  |---|---|
  | `./gradlew projects` | 不需要 |
  | `./gradlew :desktop:core:test` | 不需要 |
  | `./gradlew :shared:algorithm:test` | 不需要 |
  | `./gradlew :android:app:assembleDebug` | **需要** |
  | `./gradlew :android:data:testDebugUnitTest` | **需要** |
  | `./gradlew testAll` | **需要**（含 2 个 Android 测试任务） |

  配置方式（任选其一）：
  - 环境变量 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 SDK 根目录；
  - 或在仓库根放 `local.properties`，内容一行 `sdk.dir=D:/Android/sdk`（**推荐用正斜杠**）。`local.properties` 已在 `.gitignore` 中，不要提交。

### 两处 `gradle` 的来源不同

| 场景 | 用什么 | 说明 |
|---|---|---|
| **本地开发** | 仓库根的 `./gradlew`（或 `gradlew.bat`） | wrapper 的 `distributionUrl` 指向腾讯云镜像（国内快） |
| **CI（GitHub Actions）** | 裸 `gradle`（`setup-gradle@v6` 装的 8.10.2） | 海外 runner 拉腾讯云分发包容易超时，CI 不调用 wrapper |

两者版本相同（8.10.2），只是分发包来源不同。

### 远程仓库自动切换

- **本地（国内网络）**：阿里云镜像，wrapper 发行包走腾讯云镜像，无需额外配置
- **CI（GitHub Actions，自动注入 `CI=true`）**：`google()` / `mavenCentral()` 原生源，避免海外 runner 访问国内镜像超时

---

## 构建

> 全部命令都在**仓库根**执行，task 用全限定项目路径。

### 单测

```bash
# 一次跑完全部 8 个模块的单测（CI 用的就是这一条）
./gradlew testAll

# 等价展开：
#   :shared:algorithm:test :shared:domain:test :shared:app:test
#   :android:data:testDebugUnitTest :android:app:testDebugUnitTest
#   :desktop:core:test :desktop:cli:test :desktop:ui:test
#   :desktop:cli 没有 src/test，它的 test 以 NO-SOURCE 结束
```

### Android

```bash
./gradlew :android:app:assembleDebug       # debug APK
./gradlew :android:app:assembleRelease     # release APK（已启用 R8 混淆与资源裁剪）
./gradlew :android:data:testDebugUnitTest  # 平台接入层用例
./gradlew :android:app:testDebugUnitTest   # UI/编排用例（Robolectric）
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（release 同目录 `.../release/app-release.apk`）。

### Windows 桌面端

```bash
./gradlew :desktop:core:test :desktop:cli:test :desktop:ui:test
./gradlew :desktop:ui:run                    # 开发态直接运行界面
./gradlew :desktop:ui:createDistributable    # 便携版 app-image（含裁剪 JRE）
./gradlew :desktop:ui:packageMsi             # MSI 安装包（需 WiX，Windows 上执行）
./gradlew :desktop:cli:run                   # 直接跑 CLI
```

产物：

- 便携版：`desktop/ui/build/compose/binaries/main/app/PhotoMgm/`
- MSI：`desktop/ui/build/compose/binaries/main/msi/*.msi`

MSI 需要 WiX 3.x。本机未安装会直接失败；CI 的 `windows-latest` 自带 WiX 3.14.1，所以线上一直能出。

### 正式签名

release 构建会读取本地 `android/keystore/keystore.properties`（不入库）；缺失时回退 debug 签名，保证任何环境可构建。

## 项目结构

仓库根是唯一的 Gradle 根，8 个项目由根 `settings.gradle.kts` 纳入同一个构建。

```text
Photo-Mgm/
├── settings.gradle.kts          ★ 唯一 Gradle 根：include 全部 8 个项目
├── build.gradle.kts             插件版本（alias 到 libs.versions.toml）+ testAll 聚合任务
├── gradle.properties            全仓统一 JVM 参数 / AndroidX 开关
├── gradle/
│   ├── libs.versions.toml       ★ 版本单点来源（Gradle Version Catalog）
│   └── wrapper/                 wrapper（仅本地开发用，CI 不调用）
├── gradlew / gradlew.bat
├── shared/                      ★ 共享层（纯 Kotlin JVM，零平台依赖，仓内唯一物理源码）
│   ├── algorithm/               算法内核：日志解析、GPS 分组、事件分类、三态去重、台账、ZIP 导出
│   ├── domain/                  业务规则：视图派生、人工调整、台账投影、EXIF 时间、SHA-256
│   └── app/                     应用层：跨端契约 api/、端口 port/、会话 state/、用例 usecase/、导出 export/
├── android/                     ★ Android 模块
│   ├── data/                    MediaStore / SAF / ExifInterface / 图片编解码 / 设置存储
│   └── app/                     Compose Material3 UI + 编排
├── desktop/                     ★ Windows 桌面模块
│   ├── core/                    适配器 + 组装根（DesktopCore）
│   ├── cli/                     无头命令行入口
│   └── ui/                      Compose Multiplatform 界面
├── docs/                        两端共享文档
├── scripts/                     静态门禁 + 黄金样本
└── .github/workflows/           ci.yml、build-apk.yml、build-desktop.yml
```

### 模块职责与依赖

| 项目 | 职责 | 依赖 |
|---|---|---|
| `:shared:algorithm` | 算法内核（纯 Kotlin JVM，零平台依赖） | 无 |
| `:shared:domain` | 业务规则（视图派生 / 人工调整 / 台账投影 / EXIF / SHA-256） | `:shared:algorithm` |
| `:shared:app` | 应用层（跨端契约 / 端口 / 会话 / 用例 / 导出） | `:shared:domain` |
| `:android:data` | Android 数据接入层 | `:shared:*` |
| `:android:app` | Android UI（Compose Material3） | `:shared:*`, `:android:data` |
| `:desktop:core` | Windows 适配器 + 组装根 | `:shared:*` |
| `:desktop:cli` | 无头命令行入口 | `:shared:*`, `:desktop:core` |
| `:desktop:ui` | Compose Multiplatform 界面（含 jpackage 打包） | `:shared:*`, `:desktop:core` |

共享层只被挂载、不被复制：根 `settings.gradle.kts` 用 `include(":shared:xxx")` 纳入，默认 `projectDir` 恰为 `<root>/shared/xxx`，物理目录零移动；仓库内不存在第二份实现。

## 技术栈

| 项 | 版本 |
|---|---|
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.10.2（wrapper 走腾讯云镜像） |
| Compose Multiplatform | 1.7.3（桌面） |
| Jetpack Compose | Material3 / BOM 2024.04.00（Android） |
| JDK | 17 |
| Android | minSdk 26 / targetSdk 34 |
| 桌面打包 | jpackage（MSI + app-image） |

### 共享层依赖口径

- 零平台依赖（不 import `android.*` / `androidx.*` / `java.awt.*` 等）
- 有 1 个第三方运行时依赖：`kotlinx-coroutines-core:1.9.0`（跨平台库，用于 StateFlow 状态通道）
- Excel / ZIP / JSON 均为手写实现，未引入第三方库

## 持续集成（GitHub Actions）

| workflow | 触发 | 内容 |
|---|---|---|
| `ci.yml` | push main / pull_request / 手动 | 静态门禁（协程取消 / `@Ignore` 可溯源 / 隐式时区 / 共享层纯度）+ 单元测试（一条 `gradle testAll` 覆盖全部 8 个模块） |
| `build-apk.yml` | 仅 `v*` 标签 + 手动 | Android APK 构建与发布 |
| `build-desktop.yml` | push main / `desktop-v*` 标签 / 手动 | Windows 打包（MSI + 便携 ZIP）；标签触发时发布 Release |

- 同一分支只保留最新构建，自动取消旧的运行中任务
- push main 时 `build-desktop.yml` 只出 Artifact，不发 Release；打 `desktop-v*` 标签才发 Release

### 静态门禁

仓库自带 4 个零依赖的静态检查脚本，均在 `scripts/` 下，各带 `--selftest`：

| 脚本 | 检查内容 |
|---|---|
| `check-cancellation.py` | 协程取消信号是否被吞 |
| `check-ignore-policy.py` | 被跳过的测试是否可溯源 |
| `check-timezone.py` | 是否依赖隐式默认时区 |
| `check-shared-purity.py` | 共享层是否引入平台依赖、UI 是否越层 |

## 版本

| 平台 | 当前版本 |
|---|---|
| Android | `v1.0.1`（versionCode 8） |
| Windows 桌面 | `desktop-v1.0.1` |

版本历史见 [CHANGELOG.md](CHANGELOG.md) 与 [Releases](https://github.com/huangminghui0520-sketch/Photo-Mgm-Pro/releases)。

## 已知限制

- **Android release 签名**：未配置 `KEYSTORE_BASE64` 时回退 debug 签名。正式发布需在仓库 Secrets 中配置签名凭据。
- **手机导入照片（ADB 方式）**：Windows 上自动扫描 `C:`–`Z:` 的 `\Android\sdk\platform-tools\adb.exe`；macOS / Linux 的自动扫描暂未实现，需手动确保 `adb` 在 PATH 中或设置 `ANDROID_HOME`。
- **原生 MTP 设备**：Windows 上原生 MTP 通常不分配盘符，USB 盘符方式仅覆盖 UMS 模式或手动挂载的目录；推荐使用 ADB 方式。

## 许可证

见 [LICENSE](LICENSE)。
