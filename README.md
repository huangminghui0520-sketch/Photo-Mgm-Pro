# Photo-Mgm・高速公路巡查照片分类 App

面向路政巡查场景的照片分类工具：根据巡查日志（含时间、事件、地点），结合照片 GPS 与拍摄时间，自动将照片归类到对应巡查事件，并支持人工调整、台账导出与压缩包交付。

## 模块结构

仓库同时承载两端：**Android App**（`android/`）与 **Windows 桌面版**（`desktop/`），共用根目录的
`:algorithm` 核心（同一份源码，无副本）。两端各自是独立 Gradle 工程，互不引用、互不影响。

| 模块 / 目录        | 职责                                                     | 依赖                    |
| -------------- | ------------------------------------------------------ | --------------------- |
| `algorithm/`   | 核心算法（纯 Kotlin JVM，零 Android 依赖，被两端以源码挂载）              | 无                     |
| `android/`     | Android 工程（独立构建：`:data` + `:app`）                       | `:algorithm`          |
| `android/:data` | Android 数据接入层（MediaStore / SAF / ExifInterface）        | `:algorithm`          |
| `android/:app`  | Android UI（Compose Material3，仅经 `AlgorithmApi` 单向调用核心） | `:algorithm`, `:data` |
| `desktop/`     | Windows 桌面版（独立构建：`:core` + `:cli` + `:ui`，含 jpackage 打包） | `:algorithm`（`../algorithm` 挂载） |

目录边界、构建隔离方式与"共用核心的影响面"见 [docs/REPO-LAYOUT.md](docs/REPO-LAYOUT.md)。

## 核心算法能力（:algorithm）



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

远程仓库按环境自动切换：



* 本地（国内网络）：阿里云镜像，wrapper 发行包走腾讯云镜像，无需额外配置

* CI（GitHub Actions，自动注入 `CI=true`）：`google()` / `mavenCentral()` 原生源，避免海外 runner 访问国内镜像超时



```
# ── Android 端（仓库根不再有 settings.gradle，命令一律进 android/ 执行）──
cd android
./gradlew :algorithm:test          # 核心算法用例
./gradlew :data:test               # 平台接入层用例
./gradlew :app:testDebugUnitTest   # UI/编排用例（Robolectric）
./gradlew :app:assembleDebug       # debug APK（release 已启用 R8 混淆与资源裁剪）
./gradlew :app:assembleRelease

# ── Windows 桌面端（独立工程，互不干扰）──
cd desktop
./gradlew :core:test :cli:test :ui:test
./gradlew :ui:createDistributable  # 便携版 app-image
./gradlew :ui:packageMsi           # MSI（需 WiX，Windows 上执行）
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（release 同目录 `.../release/app-release.apk`）；
桌面端在 `desktop/ui/build/compose/binaries/main/` 下的 `app/PhotoMgm/` 与 `msi/*.msi`。

> 正式签名：release 构建会读取本地 `android/keystore/keystore.properties`（不入库；同时兼容旧的仓库根
> `keystore/keystore.properties`）；缺失时回退 debug 签名，保证任何环境可构建。

## 使用



1. 设置：选择照片源目录（SAF 系统选择器）、输出目录、巡查日期与命名模板

2. 解析：导入巡查日志文本，自动解析事件并分类照片，可在预览页人工调整

3. 导出：一键导出压缩包（照片 + 台账 Excel），完成后可打开台账核对

## 持续集成（GitHub Actions）



* `ci.yml`：静态门禁（协程取消 / `@Ignore` 可溯源 / 隐式时区）+ 单元测试，`push`/`pull_request`/手动均触发

* `build-apk.yml`（Android）：推 `main` 出 APK 并发布预发布版（`auto-*` 标签）；推 `v*` 标签发布正式版

* `build-desktop.yml`（Windows）：仅 `desktop-v*` 标签与手动派发出便携包（MSI 因依赖 WiX，仍在打包机产出）

* 同一分支只保留最新构建，自动取消旧的运行中任务

## 技术栈



* Kotlin 2.0.21・AGP 8.7.3・Gradle 8.10.2（wrapper 走腾讯云镜像）

* Jetpack Compose Material3・Coil・SharedPreferences 持久化

* Android minSdk 26 / targetSdk 34 · Java 17

* 当前版本：1.3.2（versionCode 6）