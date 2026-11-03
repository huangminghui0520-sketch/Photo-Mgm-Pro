# 仓库布局与两端边界（REPO-LAYOUT）

> 版本：**v2** · 2026-11-02 · 对应提交「refactor(shared): algorithm 迁入 shared/，三模块骨架就位」
>
> 一句话：**一个仓库、两端产品、一份共享层**。`android/` 与 `desktop/` 各自是完整独立的 Gradle
> 工程（各自的 settings / wrapper / 依赖图），唯一共享的是仓库根 `shared/` 下的**同一份源码**。
>
> **v1 → v2 的差异（跨端重构 T02）**：v1 把共用核心放在仓库根的 `algorithm/`；v2 迁入
> `shared/algorithm/`，并新增 `shared/domain`、`shared/app` 两个空模块作为后续上移的落点。
> 理由不再是"迁就 PC 端不动"，而是**让"共享 = shared/"成为唯一规则**——平铺的 `algorithm/`
> 无法表达"这是共享层"，门禁也就无从断言。

---

## 1. 整体目录结构

```
Photo-Mgm/
├── shared/                        ★ 共享层（纯 Kotlin JVM，零 Android / 零桌面依赖）
│   ├── algorithm/                   算法内核：21 文件 / 2666 行（日志解析、GPS 分组、事件分类、
│   │    │                            三态去重、台账 LedgerEngine、XlsxWriter、ZipExporter、AlgorithmApi）
│   │    ├── build.gradle.kts             kotlin("jvm") + toolchain 17（零 implementation 依赖）
│   │    └── src/test/kotlin/algorithm/   23 文件（含 POI 读回、真实素材端到端）
│   ├── domain/                      ★ 业务规则：6 文件 / 683 行（T03 起填充，T10 补 SHA-256 增量入口）
│   │    │                            view/ 视图派生 + 照片顺序、adjust/ 人工调整、
│   │    │                            ledger/ 台账投影、exif/ 时间、hash/ SHA-256
│   │    ├── build.gradle.kts             EXIF 时间 / SHA-256 / 媒体规格 / 派生命名
│   │    └── src/test/kotlin/domain/      6 文件 / 906 行（T10 后 71 条用例）
│   └── app/                         ★ 应用层：25 文件 / 2389 行（T09）
│       │                            api/ 跨端契约（门面/状态快照/导出计划/设置）、
│       │                            port/ 7 个端口、state/ 会话与快照发布、
│       │                            usecase/ 分析·调整·设置·导出、export/ 导出计划、
│       │                            infra/ JSON / 取消 / 防抖 / 文本解码
│       ├── build.gradle.kts             api(domain) + coroutines-core（StateFlow），零平台 SDK
│       └── src/test/kotlin/app/         11 文件 / 1698 行 / 91 条用例（全假端口，纯 JVM）
│
├── android/                       ★ Android 端（独立 Gradle 工程，rootProject = android/）
│   ├── settings.gradle.kts          挂载 ../shared/{algorithm,domain,app}（不复制）
│   ├── build.gradle.kts             插件版本统一声明（Kotlin 2.0.21 / AGP 8.7.3）
│   ├── gradle.properties            android.useAndroidX 等
│   ├── gradlew / gradlew.bat / gradle/wrapper/   安卓端专用 wrapper（腾讯云分发）
│   ├── app/                         :app —— Compose Material3 UI + 编排（19 文件 / 5761 行 + 11 测试文件）
│   ├── data/                        :data —— MediaStore / SAF / ExifInterface / 图片编解码 / 设置存储 / 壳层（6 文件 / 637 行）
│   └── keystore/                    ★ 正式签名（.gitignore 忽略，CI 运行时生成）
│
├── desktop/                       ★ Windows 桌面端（独立 Gradle 工程，rootProject = desktop/）
│   ├── settings.gradle.kts          :core / :cli / :ui + 挂载 ../shared/{algorithm,domain,app}
│   ├── build.gradle.kts             插件版本 + 把 :shared:* 产物重定向到 desktop/build/external/shared-*
│   ├── gradle.properties / gradlew / gradle/wrapper/    桌面端专用 wrapper
│   ├── core/                        ★ 适配器 + 组装根（19 文件 / 1814 行，T10 收窄）
│   │    │                             DesktopCore（装配）+ WindowState/DesktopCoreConfig、
│   │    │                             adapter/ 7 个 Pc* 端口实现、platform/ 目录·EXIF·哈希·嵌图·id、
│   │    │                             export/ 日志·清单；logic/PcViewDerivation 留到 T12 删
│   ├── cli/                         无头命令行入口（1 文件 / 386 行）
│   ├── ui/                          Compose Multiplatform 界面（38 文件 / 6694 行 + 13 测试文件）
│   ├── packaging/                   jpackage / MSI / 便携版打包脚本与 dist 产物（不入库）
│   └── scripts/                     安装冒烟、导出核对等 PowerShell 脚本
│
├── docs/                         两端共享文档（UI 规格、桌面计划、兼容性矩阵…）
├── scripts/                      Android 侧静态门禁（协程取消 / @Ignore 可溯源 / 隐式时区）
├── .github/workflows/            ci.yml（两端门禁+安卓单测）、build-apk.yml（Android 出包）、
│                                 build-desktop.yml（Windows 便携包）
├── README.md / CHANGELOG.md / LICENSE
└── (无 settings.gradle.kts)       ★ 仓库根不再是 Gradle 工程，构建必须进 android/ 或 desktop/
```

**边界规则**

| 规则 | 说明 |
| --- | --- |
| `shared/` 只被挂载，不被复制 | 两端 `settings.gradle.kts` 用 `projectDir = file("../shared/<模块>")` 引入；仓库内**不存在**第二份实现，也不许出现 `android/shared`、`desktop/shared` |
| 两端互不 include | `android` 不知道 `desktop` 存在，反之亦然；没有 `includeBuild`、没有跨端 project 依赖 |
| 平台实现留在各自一侧 | 图片编解码、文件扫描、UI 全在各端；`shared/` 只接受 `ByteArray` / 时间戳等中立类型，禁止 `java.nio.file` / `androidx.*` |
| 构建产物目录隔离 | Android → `shared/*/build`；桌面 → `desktop/build/external/shared-*`（PC 侧 buildDir 重定向），互不覆盖 |

---

## 2. 关键调整点

### 2.1 为什么共享层放在 `shared/`，而不是 `android/` 或 `desktop/` 下

两条历史约束叠加，得出现在这个位置：

1. **不能放进任一端目录**。放进 `android/`，桌面端要写 `../android/algorithm`——等于让 PC 依赖安卓端
   的目录结构；反过来同理。共享代码必须与两端**平级**。
2. **不能平铺在仓库根**（v1 的 `algorithm/`）。它虽然能编过，但"哪些代码是共享的"只存在于人的记忆里：
   目录名 `algorithm/` 不表达层级，门禁也无法用"`shared/` 下不许出现平台 API"这类规则去断言。

所以现在用嵌套项目名 `:shared:algorithm`，两端各写三行：

```kotlin
include(":shared:algorithm")
project(":shared:algorithm").projectDir = file("../shared/algorithm")
```

> 为什么必须是 `:shared:algorithm` 而不是 `:algorithm`：Android 已经有 `:app`，将来若要再挂
> `:shared:app` 就会与它撞名。嵌套项目名一次性把这个坑避开，代价只是依赖写成 `project(":shared:algorithm")`。

### 2.2 `android/` 成为完整独立工程

随 `app/`、`data/` 一起迁入 `android/` 的还有：`settings.gradle.kts`、`build.gradle.kts`、
`gradle.properties`、`gradlew`、`gradlew.bat`、`gradle/wrapper/`。迁完后 `android/` 可以单独
`cd android && ./gradlew :app:assembleRelease`，与 `desktop/` 完全同构。

`android/settings.gradle.kts` 里的挂载段（T02 后为三个共享模块）：

```kotlin
include(":shared:algorithm"); project(":shared:algorithm").projectDir = file("../shared/algorithm")
include(":shared:domain");    project(":shared:domain").projectDir    = file("../shared/domain")
include(":shared:app");       project(":shared:app").projectDir       = file("../shared/app")
```

> 注意：`rootProject` 也随之从"仓库根"变成 `android/`，凡是 `rootProject.file(...)` 的地方都受影响
> （签名配置是唯一一处，见 2.3）。

### 2.3 签名目录改为 `android/keystore/`，并保留旧位置兼容

`app/build.gradle.kts` 里 `rootProject.file("keystore/keystore.properties")` 现在解析到
`android/keystore/`。为不让已有开发机/打包机搬文件，改成**两处探测**：

```kotlin
val keystorePropFile = listOf(
    rootProject.file("keystore/keystore.properties"),          // 新位置 android/keystore/
    rootProject.file("../keystore/keystore.properties"),       // 旧位置 仓库根 keystore/
).firstOrNull { it.exists() }

fun resolveSigningFile(path: String) =
    rootProject.file(path).takeIf { it.exists() } ?: rootProject.file("../$path")
```

两处都没有 → 静默回退 debug 签名（原有行为，保证任何环境可构建）。

### 2.4 门禁脚本的默认扫描根跟着改

`scripts/check-cancellation.py`、`check-ignore-policy.py`、`check-timezone.py` 的默认根由
`app / algorithm / data` 改为 `android/app / shared / android/data`（T02 起 `shared` 覆盖三个共享模块；
`check-timezone.py` 写的是显式路径 `shared/algorithm|domain|app`）。脚本本身仍按
"脚本所在位置"解析仓库根（`REPO_ROOT = scripts/..`），不依赖调用方 cwd —— 这条性质在搬迁后依然成立，
所以只在 CI 传参处与默认值处改路径，判据与样例一律未动。

### 2.5 CI 从"根目录 gradle"改为"`-p android`"

仓库根不再有 `settings.gradle.kts`，在根跑 `gradle` 会直接报 `Directory does not contain a Gradle build`。
两处 workflow 因此改为显式指定工程目录：

```bash
gradle -p android :shared:algorithm:test :app:testDebugUnitTest   # ci.yml
gradle -p android :app:assembleRelease                      # build-apk.yml
```

`:shared:algorithm` 仍能在同一次调用里跑，因为它是 android 工程的挂载子项目。

### 2.6 CI 新增"结构约束"断言

`ci.yml` 的 `static-guards` 里加了一步 bash 断言，把"共享层只有一份、两端都要挂"变成机械可验的规则：

```bash
for d in android/algorithm android/shared desktop/shared; do [ ! -e "$d" ]; done
[ ! -e settings.gradle.kts ]            # 仓库根不许再有 settings（否则"根目录 gradle"又会看起来能跑）
for mod in algorithm domain app; do     # 三个共享模块必须两端都挂，缺一边就会悄悄用旧副本
  grep -q 'projectDir = file("../shared/'$mod'")' android/settings.gradle.kts
  grep -q 'projectDir = file("../shared/'$mod'")' desktop/settings.gradle.kts
done
```

这几类错误都是"几秒钟能查、靠人 review 必漏"的，所以放在门禁而不是文档里。

### 2.7 新增桌面端打包流水线

`build-desktop.yml`：`windows-latest` + `desktop/./gradlew`，只在 `desktop-v*` 标签与手动派发时跑，
产出 `PhotoMgm-<ver>-portable.zip`（app-image 压缩）。**刻意不出 MSI** —— `jpackage --type msi`
依赖 WiX，GitHub 的 Windows 镜像不保证常驻，而 MSI 是主交付物，不能容忍 CI 上时好时坏；
MSI 继续在打包机上产出。

### 2.8 明确"未改动"清单

- `desktop/` 全部文件（源码、构建脚本、打包脚本、文档）：**零改动**；
- `shared/algorithm/`：**内容零改动**（仅目录从 `algorithm/` 迁入 `shared/`，git 识别为 42 个 rename）；
- Android 业务代码（`app/src`、`data/src`）：**零改动**，只动了构建脚本里签名路径的解析；
- 唯一"真改代码"的是 `app/build.gradle.kts` 的签名片段与三个门禁脚本的默认路径。

---

## 3. 打包与上传：配置项 + 验证步骤

### 3.1 需要配置的东西

| 项 | 位置 | 说明 |
| --- | --- | --- |
| JDK 17 | 本机 `JAVA_HOME` / CI `setup-java` | 两端都用 17；本机路径 `/d/Android/jdk-17.0.2` |
| Android SDK | `ANDROID_HOME` 或 `android/local.properties` 的 `sdk.dir` | 搬迁后 `local.properties` 需落在 `android/`；本机走 `ANDROID_HOME` |
| 正式签名（可选） | `android/keystore/keystore.properties`（旧位置仓库根 `keystore/` 仍识别） | 字段 `storeFile/storePassword/keyAlias/keyPassword`；缺省回退 debug 签名 |
| CI Secrets（可选） | `KEYSTORE_BASE64`、`KEYSTORE_STORE_PASSWORD`、`KEYSTORE_KEY_ALIAS`、`KEYSTORE_KEY_PASSWORD` | 由 `build-apk.yml` 解码到 `android/keystore/` |
| 桌面版本号 | `desktop/ui/build.gradle.kts` 的 `packageVersion` + `build-desktop.yml` 的 `DESKTOP_VERSION` | 两处需同改 |
| 标签规范 | Android `v*`（如 `v1.3.3`）；桌面 `desktop-v*`（如 `desktop-v2.0.0`） | 两条独立版本线，前缀不同才不会互相触发发布 |

### 3.2 本地验证步骤（按顺序，任一步红即停）

```bash
# 0) 静态门禁（三个脚本都先自测判据，再实扫）
python3 scripts/check-cancellation.py --selftest && python3 scripts/check-cancellation.py
python3 scripts/check-ignore-policy.py --selftest && python3 scripts/check-ignore-policy.py
python3 scripts/check-timezone.py --selftest && python3 scripts/check-timezone.py

# 1) Android 端：单测 + debug 包
cd android
./gradlew :shared:algorithm:test :data:test :app:testDebugUnitTest
./gradlew :app:assembleDebug          # → android/app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # → android/app/build/outputs/apk/release/app-release.apk

# 2) 桌面端：单测 + 便携包（PC 侧代码本次一行未改，此步用于证明"没被搬坏"）
cd ../desktop
./gradlew :core:test :cli:test :ui:test
./gradlew :ui:createDistributable     # → desktop/ui/build/compose/binaries/main/app/PhotoMgm/

# 3) 桌面 MSI（仅 Windows 打包机，需 WiX）
./gradlew :ui:packageMsi              # → desktop/ui/build/compose/binaries/main/msi/PhotoMgm-2.0.0.msi
```

**本次实测结果（2026-11-02）**

| 检查 | 结果 |
| --- | --- |
| `./gradlew projects`（android/） | `:algorithm` `:app` `:data` 三个子项目，挂载成功 |
| `:shared:algorithm:test :data:test :app:testDebugUnitTest :app:assembleDebug` | BUILD SUCCESSFUL |
| `desktop :core:test :cli:test :ui:test` | BUILD SUCCESSFUL（PC 侧未受影响） |
| 三个门禁脚本 | 自测通过；实扫 79 / 37 / 42 个文件，0 问题 |
| 三个 workflow YAML | `yaml.safe_load` 解析通过，jobs/triggers 符合预期 |

### 3.3 上传（推送）步骤

```bash
# 提交
git add -A && git commit -m "refactor(repo): Android 端收进 android/，与 desktop/ 形成两层独立工程"

# 推分支
git push origin <branch>

# 打标签（两条独立线，按需其一）
git tag -a v1.3.3 -m "Android 1.3.3" && git push origin v1.3.3
git tag -a desktop-v2.0.0 -m "Windows 桌面版 2.0.0" && git push origin desktop-v2.0.0
```

CI 上的判定标准：

- `ci.yml` 绿 = 结构约束（§2.6）+ 三个门禁 + Android 单测通过（`-p android` 生效）；
- `build-apk.yml` 绿 = 出 `Photo-Mgm-release-apk` artifact（`v*` 标签则发 Release）；
- `build-desktop.yml` 绿（仅 `desktop-v*` / 手动）= 出 `PhotoMgm-desktop-portable` artifact。

> 本机当前网络代理（`127.0.0.1:7890`）未启动，`git push` 会报
> `Failed to connect to github.com:443 over proxy`；开启代理后重推即可，提交与标签已在本地就绪。

---

## 4. 共用核心：差异与相互影响分析

### 4.1 审计范围与方法

| 检查项 | 方法 | 结论 |
| --- | --- | --- |
| 是否存在两份实现 | 在 `shared/algorithm/src`、`desktop/core/src` 里按类名交叉检索 `XlsxWriter / LedgerEngine / LogParser / EventClassifyEngine / Dedup` | 全部只命中 `shared/algorithm/` 一处；`desktop/core` 无副本 |
| 依赖方向 | `desktop/core/build.gradle.kts`、`android/app/build.gradle.kts` 的 `project(...)` 声明 | 单向：`app → algorithm`、`core → algorithm`；核心不反向依赖任何端 |
| 平台 API 面 | 扫 `algorithm/src/main` 全部 `java.* / javax.*` import | 仅 `java.io`、`java.time`、`java.util.zip`；**无** `java.awt / javax.imageio / javax.swing`（测试里的 `java.nio.file` 不进产物） |
| 运行期依赖 | `algorithm/build.gradle.kts` 的 `dependencies` | 仅 `kotlin("test")`、JUnit、POI 三个 **test** 依赖，无任何 `implementation` → 不会把版本冲突带进两端 |
| 编译参数 | 两端根构建脚本 | Kotlin 均为 2.0.21；`jvmTarget` 均为 17；核心 toolchain 固定 17 |
| 产物目录 | `desktop/build.gradle.kts` 的 `subprojects { if (path == ":algorithm") ... }` | 桌面构建把核心产物重定向到 `desktop/build/external/algorithm`，与 `algorithm/build` 不冲突 |

### 4.2 差异结论：源码零差异，差异全在"编译环境"与"喂给核心的输入"

两端不存在"同一逻辑两套代码"的漂移风险（这是共用挂载换来的），但仍有 **4 类客观差异**：

| 维度 | Android 端 | Windows 桌面端 | 影响 |
| --- | --- | --- | --- |
| 编译器/工具链 | Kotlin 2.0.21 + AGP 8.7.3 + D8/R8，jvmTarget 17 | Kotlin 2.0.21 + jpackage，jvmTarget 17 | 语义一致；字节码经 D8 处理后与 JVM 直跑有别，属正常 |
| 核心产物目录 | `algorithm/build` | `desktop/build/external/algorithm` | 互不覆盖，可安全交替构建 |
| 嵌入台账的图片 | `data/image/BitmapImageEncoder`：4:3 中心裁剪 → 400×300 → JPEG50 | `PcImageEmbedder`：4:3 中心裁剪 → 400×300 → JPEG50 | 规格已收敛到 `shared/algorithm/MediaSpec` 单点定义（T04）；安卓实现 T06 已搬进 `:data` |
| 时区口径 | 显式 `AppZone.ID`（Asia/Shanghai） | 相同 `AppZone.ID`；导出目录命名另用 UTC `Clock` | 台账时间一致；仅目录名的时间戳语义不同 |

### 4.3 相互影响的风险矩阵

| 编号 | 风险 | 现状证据 | 等级 | 建议 |
| --- | --- | --- | --- | --- |
| R1 | **一改双端**：核心改动会同时改变 Android 与 PC 的行为，而只有一端做了回归 | 已实际发生：为 PC 台账加的 `hh:mm`、行高 81pt、`sheetViews`、照片格无字，同样改变了 Android 导出的 `ledger.xlsx`，当轮 App 未重打包验证 | 中 | 改 `algorithm` 的提交须在两端各跑一次真实素材导出；CHANGELOG 标注"影响两端" |
| R2 | **图源契约靠约定**：`XlsxWriter` 以 `oneCellAnchor` 固定 3.81×2.8575cm（4:3），而 400×300 曾是两个模块各写一遍的常量；任一端改比例（如 16:9），台账里照片会被**拉伸且无告警** | `ImageUtils.TARGET_WIDTH=400` 与 `PcImageEmbedder.DEFAULT_WIDTH=400` 各自独立 | 中 → **已消灭**（T04 收敛到 `MediaSpec`，两端只剩引用；T06 删掉安卓侧的三个转发别名） | 核心侧对"宽高比偏离 4:3"记录告警或以实际比例算 ext；至少在一端加断言 |
| R3 | **平台 API 越界**：核心若引入 `java.awt` / `javax.imageio`，Android 侧编译能过、运行期崩 | 当前 0 处（已扫描） | 低（但无门禁） | 加一条静态门禁（类似现有三个脚本），把"核心只许用 java.io/time/util.zip"固化成规则 |
| R4 | **版本线混淆**：Android 走 `v*`、桌面走 `desktop-v*`，若哪天给桌面打 `v2.0.0`，会误触发 APK 正式发布 | `build-apk.yml` 的 `tags: ['v*']` | 低 | 已用 `desktop-v` 前缀规避；标签规范写入本文 §3.1 |
| R5 | **构建竞争**：两端同时构建会共用 Gradle 用户缓存 | `org.gradle.caching=true` 两端皆开 | 低 | 缓存本身有锁，可并发；只需避免同时写同一个产物目录（已隔离） |

### 4.4 结论

1. **不存在代码级分歧**：核心只有一份，Android 与 PC 的台账、分类、去重、日志解析行为在源码层面必然一致；
   本次重组没有改变这一点（`algorithm/` 一个字节未动）。
2. **风险不是"漂移"，而是"共变"**：改核心等于同时发布两端行为。R1 已经真实发生一次，属于流程问题而非代码问题，
   靠"改核心必跑两端"约定 + CHANGELOG 标注来控制。
3. **真正需要机械化拦截的是 R2 / R3**：一个是跨模块的隐式尺寸契约，一个是平台 API 越界，两者都是
   "编译期不报错、运行期才难看"的类型，建议后续各补一条门禁（本文件已列出判据，未在本次实施，避免动核心）。
4. **隔离度评估**：`android/` 与 `desktop/` 之间没有任何直接依赖、没有共享构建脚本、产物目录分离 ——
   "互不影响"在**工程层面成立**；唯一的耦合点是刻意保留的 `algorithm/`，属设计选择而非事故。
