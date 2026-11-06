# Photo-Mgm・高速公路巡查照片分类 App

面向路政巡查场景的照片分类工具：根据巡查日志（含时间、事件、地点），结合照片 GPS 与拍摄时间，自动将照片归类到对应巡查事件，并支持人工调整、台账导出与压缩包交付。

## 模块结构

仓库同时承载两端：**Android App**（`android/`）与 **Windows 桌面版**（`desktop/`），共用仓库根
`shared/` 下的同一份源码（无副本）。**仓库根是唯一的 Gradle 根**，两端模块与共享层模块一起由根
`settings.gradle.kts` 纳入同一个构建（不再是两个独立工程）。

```
Photo-Mgm/
├── settings.gradle.kts   ★ 唯一 Gradle 根：include 全部 8 个项目
├── build.gradle.kts      插件版本（alias 到 gradle/libs.versions.toml）+ testAll 聚合任务
├── gradle.properties     全仓统一 JVM 参数 / AndroidX 开关
├── gradle/
│   ├── libs.versions.toml     ★ 版本单点来源（Gradle Version Catalog）
│   └── wrapper/               wrapper（仅本地开发用，CI 不调用）
├── gradlew / gradlew.bat
├── shared/               ★ 共享层（纯 Kotlin JVM，零平台依赖，仓内唯一物理源码）
│   ├── algorithm/           算法内核：日志解析、GPS 分组、事件分类、三态去重、台账、ZIP 导出
│   ├── domain/              业务规则：视图派生、人工调整、台账投影、EXIF 时间、SHA-256
│   └── app/                 应用层：跨端契约 api/、端口 port/、会话 state/、用例 usecase/、导出 export/
├── android/              ★ Android 模块（:android:data + :android:app）
│   ├── data/                MediaStore / SAF / ExifInterface / 图片编解码 / 设置存储
│   └── app/                 Compose Material3 UI + 编排
├── desktop/              ★ Windows 桌面模块（:desktop:core + :desktop:cli + :desktop:ui）
│   ├── core/                适配器 + 组装根（DesktopCore）
│   ├── cli/                 无头命令行入口
│   └── ui/                  Compose Multiplatform 界面
├── docs/                  两端共享文档（UI 规格、桌面计划、兼容性矩阵、重构执行计划）
├── scripts/               静态门禁（协程取消 / @Ignore 可溯源 / 隐式时区 / 共享层纯度）+ 黄金样本
└── .github/workflows/     ci.yml、build-apk.yml、build-desktop.yml
```

| 项目（Gradle 全名） | 职责                                                     | 依赖                    |
| -------------- | ------------------------------------------------------ | --------------------- |
| `:shared:algorithm` | 算法内核（纯 Kotlin JVM，零平台依赖，被两端以源码挂载）             | 无                     |
| `:shared:domain`   | 业务规则（视图派生 / 人工调整 / 台账投影 / EXIF / SHA-256）        | `:shared:algorithm`    |
| `:shared:app`      | 应用层（跨端契约 / 端口 / 会话 / 用例 / 导出）                      | `:shared:domain`       |
| `:android:data`  | Android 数据接入层（MediaStore / SAF / ExifInterface / 图片编解码） | `:shared:*`            |
| `:android:app`   | Android UI（Compose Material3，经 `app.api` 契约单向调用共享层） | `:shared:*`, `:android:data`   |
| `:desktop:core`  | Windows 适配器 + 组装根（DesktopCore + Pc* 端口实现）           | `:shared:*`            |
| `:desktop:cli`   | 无头命令行入口                                                  | `:shared:*`, `:desktop:core`   |
| `:desktop:ui`    | Compose Multiplatform 界面（含 jpackage 打包）                 | `:shared:*`, `:desktop:core`   |

> **共享层只被挂载、不被复制**：根 `settings.gradle.kts` 用 `include(":shared:xxx")` 纳入，
> 默认 `projectDir` 恰为 `<root>/shared/xxx`，所以物理目录零移动；仓库内不存在第二份实现。
> （旧的两端 settings 里那 6 行 `projectDir = file("../shared/xxx")` 已随双根拓扑一起删除。）

目录边界、构建隔离方式与"共用核心的影响面"见 [docs/REPO-LAYOUT.md](docs/REPO-LAYOUT.md)。

## 核心算法能力（shared/algorithm）



* 日志解析：时间 / 地点（桩号 + 方向）/ 事件类型 / 巡查人员 / 车牌，黄金样本 `收费公路巡查日志`

* GPS 分组：50 米内合并为一组，离群拆簇，跨日按完整时间戳

* 事件分类：按日志时间窗口把照片归入事件（首条日志起点归属 / 后续终点归属），支持人工移动兜底

* 照片三态去重：水印相机一日巡查常产出双份（主图 `IMG_xxx.jpg` + 原图 `IMG_xxx_original.jpg`），手机自带相机单份——支持三选一（默认仅保留带水印）：


  * 仅保留带水印 / 仅保留不带水印 / 两者都保留；单份照片三态一律保留，未匹配照片不受三态影响恒全量

  * 判重（B4 降级）：GPS 有效按 时间≤1s + 距离≤10m；真机 ROM 相册清零 GPS 时降级为 时间≤1s + 任一方含派生后缀 + 文件名最后数字块差≤1000

  * 派生去重（B5 双层判定）：同基名 + SHA-256 内容哈希一致 → 合并复制副本；哈希不同 + `_original` → 保留（水印相机同序号原图，独立照片）；哈希不同 + 其他派生 → 合并；哈希读取失败 → 保守保留

* 台账生成：每事件一行（序号 / 日期 / 结束时间 / 地点 / 日志内容 / 照片 1 / 照片 2）导出 Excel（OOXML 手写，零第三方依赖）


  * 日期列为 Excel 真实日期类型（`yyyy-mm-dd hh:mm` 格式），可直接排序、筛选

  * 结束时间列：跨日事件按实际结束日期展示，单时间点事件留空

  * 照片列显示「文件夹名 / 文件名」，跨文件夹同名照片可精确定位

* 压缩包导出：按「事件类型」或「日期 + 事件类型 + 日志内容」建文件夹，照片 + 台账 Excel 打包 ZIP


  * 文件夹按事件时间升序并加序号前缀（`01_`、`02_` …），未匹配照片统一归入 `99_未分类`

  * 台账照片列前缀与 ZIP 内路径一致，解压后一一对应

## 导出台账可靠性



* 断点续传：重复导出同一批照片且内容未变化时跳过已完成任务，不重复打包

* 失败明细：导出失败时在结果提示中列出失败照片（ZIP 内路径 + 原因），便于定位

* 打开台账：导出完成后可直接一键打开生成的 Excel 台账文件

## 构建

**构建拓扑：仓库根是唯一的 Gradle 根，共 8 个模块**（不再是 android/ 与 desktop/ 两个独立工程）：

| 分组 | 项目 | 物理目录 |
|---|---|---|
| 共享层 | `:shared:algorithm` `:shared:domain` `:shared:app` | `shared/<mod>/` |
| Android | `:android:data` `:android:app` | `android/<mod>/` |
| Windows 桌面 | `:desktop:core` `:desktop:cli` `:desktop:ui` | `desktop/<mod>/` |

物理目录与全部产物路径**零移动**（`android/app/build/...`、`desktop/ui/build/...` 与重构前完全一致）。

### 环境前置（重要）

单根化带来一个**容易被忽略的前置条件变化**，先说清楚再动手：

**① JDK 17** —— 所有 Gradle 任务的前置。8 个模块的 `jvmToolchain(17)` / `jvmTarget 17` 都固定为 17，
低版本 JDK 会在配置阶段直接失败。设 `JAVA_HOME` 指向 JDK 17 即可。

**② Android SDK 只在执行 `:android:*` 任务时需要，不是「所有命令」的前置**

> 本条曾按「单根 → 配置期全量配置 → 必然要 SDK」推断写成「所有命令都需要」，
> **2026-11-06 实测推翻**（本机无 SDK、无 `local.properties`）：
>
> | 命令 | 结果 |
> |---|---|
> | `gradle projects` | `BUILD SUCCESSFUL`，8 个项目全部列出 |
> | `gradle :desktop:core:test` | `BUILD SUCCESSFUL`（11 tasks） |
> | `gradle :shared:algorithm:test :shared:domain:test :shared:app:test` | `BUILD SUCCESSFUL` |
> | `gradle :desktop:ui:test` | `BUILD SUCCESSFUL` |
> | `gradle :android:data:testDebugUnitTest` | `FAILED`：`SDK location not found. Define a valid SDK location with an ANDROID_HOME …` |
>
> 原因：AGP 8.7.3 把 SDK 定位延迟到**任务执行期**，配置期只解析插件、不校验 SDK。
> 所以单根化**没有**把 SDK 变成全仓前置 —— 这是双根→单根唯一一个「比预想更宽松」的变化。

真正需要 SDK 的是：
- 任何 `:android:*` 的 `assemble*` / `test*` / 打包任务（执行期才校验，报错很快，20s 内）；
- `gradle testAll`（它的 8 项 `dependsOn` 含 `:android:data` / `:android:app` 两个测试任务）。

不需要 SDK 的是：`:shared:*` 与 `:desktop:*` 的全部测试与编译、`gradle projects`、
以及 `build-desktop.yml` 里只跑 `:desktop:ui:createDistributable` / `packageMsi` 的出包流程。

配置方式（只在需要跑 Android 任务时任选其一）：
- 环境变量 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 SDK 根目录；
- 或在**仓库根**放 `local.properties`，内容一行 `sdk.dir=D:/Android/sdk`
  （**推荐用正斜杠**；写反斜杠需按 Java properties 规则转义成 `D\\:\\\\Android\\\\sdk`，很容易写错）。
  `local.properties` 已在 `.gitignore:4` 里，不要提交。

**③ CI**：`setup-java` 提供 JDK 17。各 runner 是否预置 Android SDK **未在本机核验**
（`.workbuddy/analysis/05-refactor-execution-verification.md` 的 V-B）；即使不预置，
`build-desktop.yml` 也不受影响——它不执行任何 `:android:*` 任务。

**④ 本地缺 SDK 时不需要开关**：既然只有 `:android:*` 执行期才校验，跑桌面/共享层测试不必绕过；
根 `settings.gradle.kts` 末尾的 `PHOTOMGM_SKIP_ANDROID`（`settings.gradle.kts:85-88`）仍是
**注释草案、未启用**，且启用前必须先把 `build.gradle.kts:31-40` 的 `testAll` 改成条件构造，
否则会引用不存在的项目。**不要**把它当成现成开关。

### 两处 `gradle` 的来源不同，别混用

| 场景 | 用什么 | 为什么 |
|---|---|---|
| **本地开发** | 仓库根的 `./gradlew`（或 `gradlew.bat`） | wrapper 的 `distributionUrl` 指向腾讯云镜像（国内快）；wrapper 文件只服务本地 |
| **CI（GitHub Actions）** | 裸 `gradle`（`gradle/actions/setup-gradle@v6` 装的 8.10.2） | 美国 runner 拉腾讯云分发包容易超时，故 CI **不调用 wrapper**，直接装官方 8.10.2 |

两者版本相同（8.10.2），只是分发包来源不同。CI 的三个 workflow 都不出现 `./gradlew`。

远程仓库按环境自动切换：



* 本地（国内网络）：阿里云镜像，wrapper 发行包走腾讯云镜像，无需额外配置

* CI（GitHub Actions，自动注入 `CI=true`）：`google()` / `mavenCentral()` 原生源，避免海外 runner 访问国内镜像超时

```
# ── 全部命令都在仓库根执行（task 用全限定项目路径）──
cd <仓库根>

# 一次跑完全部 8 个模块的单测（CI 用的就是这一条）
./gradlew testAll
# 等价展开：
#   :shared:algorithm:test :shared:domain:test :shared:app:test
#   :android:data:testDebugUnitTest :android:app:testDebugUnitTest
#   :desktop:core:test :desktop:cli:test :desktop:ui:test
#   ★ :desktop:cli 没有 src/test，它的 test 以 NO-SOURCE 结束（不产出 TEST-*.xml）

# ── Android ──
./gradlew :android:app:assembleDebug     # debug APK（release 已启用 R8 混淆与资源裁剪）
./gradlew :android:app:assembleRelease
./gradlew :android:data:test             # 平台接入层用例
./gradlew :android:app:testDebugUnitTest  # UI/编排用例（Robolectric）

# ── Windows 桌面端 ──
./gradlew :desktop:core:test :desktop:cli:test :desktop:ui:test
./gradlew :desktop:ui:createDistributable  # 便携版 app-image
./gradlew :desktop:ui:packageMsi           # MSI（需 WiX，Windows 上执行）
./gradlew :desktop:cli:run                 # 直接跑 CLI（黄金样本用）
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（release 同目录 `.../release/app-release.apk`）；
桌面端在 `desktop/ui/build/compose/binaries/main/` 下的 `app/PhotoMgm/` 与 `msi/*.msi`。

> `desktop/build/external/` **已不存在**。那是双根时代为了「共享模块产物不被两端互相覆盖」而做的
> `buildDir` 重定向；单根后 8 个模块各写自己的 `build/`，全仓一份产物，这套补丁已整体删除。
> 若你的磁盘上残留该目录，属旧构建遗留，可直接删除。

> 正式签名：release 构建会读取本地 `android/keystore/keystore.properties`（不入库；同时兼容旧的仓库根
> `keystore/keystore.properties`）；缺失时回退 debug 签名，保证任何环境可构建。

## 使用



1. 设置：选择照片源目录（SAF 系统选择器）、输出目录、巡查日期与命名模板

2. 解析：导入巡查日志文本，自动解析事件并分类照片，可在预览页人工调整

3. 导出：一键导出压缩包（照片 + 台账 Excel），完成后可打开台账核对

## 持续集成（GitHub Actions）



* `ci.yml`：静态门禁（协程取消 / `@Ignore` 可溯源 / 隐式时区 / 共享层纯度）+ 单元测试（一条 `gradle testAll` 一次覆盖全部 8 个模块），`push`/`pull_request`/手动均触发

* `build-apk.yml`（Android）：推 `main` 出 APK 并发布预发布版（`auto-*` 标签）；推 `v*` 标签发布正式版

* `build-desktop.yml`（Windows）：仅 `desktop-v*` 标签与手动派发；**只负责出包**（单测已由 `ci.yml` 的 `testAll` 覆盖），标签触发时同时发布 **MSI 安装包 + 便携 ZIP**（runner 自带 WiX 3.14.1）

* 同一分支只保留最新构建，自动取消旧的运行中任务

## 技术栈



* Kotlin 2.0.21・AGP 8.7.3・Gradle 8.10.2（wrapper 走腾讯云镜像）

* Jetpack Compose Material3・Coil・SharedPreferences 持久化

* Android minSdk 26 / targetSdk 34 · Java 17

* 当前版本：Android `v1.0.0`（versionCode 7）· Windows 桌面 `desktop-v1.0.0`