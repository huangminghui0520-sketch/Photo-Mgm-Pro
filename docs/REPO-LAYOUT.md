# 仓库布局与两端边界（REPO-LAYOUT）

> 版本：**v3** · 单根多模块构建重构（M0~M5）落地后
>
> 一句话：**一个仓库、一个 Gradle 根、两端产品、一份共享层**。仓库根是唯一的 Gradle 根，
> `:shared:*` / `:android:*` / `:desktop:*` 共 8 个项目由根 `settings.gradle.kts` 纳入同一个构建；
> 唯一共享的是仓库根 `shared/` 下的**同一份源码**。
>
> **v2 → v3 的差异（Gradle 单根化重构）**：v2 是"双根"——`android/` 与 `desktop/` 各自是完整独立的
> Gradle 工程（各自的 settings / build / gradle.properties / wrapper）。双根带来两个结构性代价：
> ① 共享层单测要跑两遍，CI 里 `-p android` 与 `-p desktop` 各一套命令，容易漏挂；
> ② 桌面侧为了不让 `:shared:*` 产物被 Android 侧构建覆盖，要在 `desktop/build.gradle.kts` 里做
> `buildDir` 重定向到 `desktop/build/external/`，属纯补丁。
> v3 把两端收进一个根构建：8 个项目、一份产物、一条 `gradle testAll`，上述两个代价同时消失。
>
> **v1 → v2 的差异（跨端重构 T02，历史记录）**：v1 把共用核心放在仓库根的 `algorithm/`；v2 迁入
> `shared/algorithm/`，并新增 `shared/domain`、`shared/app` 两个空模块作为后续上移的落点。
> 理由不再是"迁就 PC 端不动"，而是**让"共享 = shared/"成为唯一规则**——平铺的 `algorithm/`
> 无法表达"这是共享层"，门禁也就无从断言。
>
> ⚠️ **历史小节里的命令已失效**：§2.2 / §2.5 / §3.2 中标注「历史」的片段记录了双根时代的子根写法
> （`-p <子根>`、先 `cd` 进子目录、`projectDir = file("../shared/...")` 等），**仅供追溯重构动机**，
> 照抄会失败（`android/` 下已无 `settings.gradle.kts` 与 wrapper）。现行命令以 §2.9 与 §3.2 为准。

---

## 1. 整体目录结构

```
Photo-Mgm/
├── settings.gradle.kts            ★ 唯一 Gradle 根：8 条 include + 仓库源策略 + FAIL_ON_PROJECT_REPOS
├── build.gradle.kts               6 条 alias(...) apply false + testAll 聚合任务（硬编码 8 项）
├── gradle.properties              全仓统一 JVM 参数 / AndroidX 开关 / caching
├── gradle/
│   ├── libs.versions.toml         ★ 版本单点来源（Version Catalog，31 versions / 20 libraries / 6 plugins）
│   └── wrapper/                   gradle-wrapper.{jar,properties}（腾讯云分发，**仅本地用**）
├── gradlew / gradlew.bat          ★ 只服务本地开发；CI 三个 workflow 都不用 wrapper
│
├── shared/                        ★ 共享层（纯 Kotlin JVM，零 Android / 零桌面依赖）
│   ├── algorithm/                   算法内核：21 文件 / 2666 行（日志解析、GPS 分组、事件分类、
│   │    │                            三态去重、台账 LedgerEngine、XlsxWriter、ZipExporter、AlgorithmApi）
│   │    ├── build.gradle.kts             kotlin("jvm") + toolchain 17（零 implementation 依赖）
│   │    └── src/test/kotlin/algorithm/   23 文件（含 POI 读回、真实素材端到端）
│   ├── domain/                      ★ 业务规则：6 文件 / 683 行
│   │    │                            view/ 视图派生 + 照片顺序、adjust/ 人工调整、
│   │    │                            ledger/ 台账投影、exif/ 时间、hash/ SHA-256
│   │    ├── build.gradle.kts             EXIF 时间 / SHA-256 / 媒体规格 / 派生命名
│   │    └── src/test/kotlin/domain/      6 文件 / 906 行（71 条用例）
│   └── app/                         ★ 应用层：25 文件 / 2389 行
│       │                            api/ 跨端契约、port/ 7 个端口、state/ 会话与快照发布、
│       │                            usecase/ 分析·调整·设置·导出、export/ 导出计划、
│       │                            infra/ JSON / 取消 / 防抖 / 文本解码
│       ├── build.gradle.kts             api(domain) + coroutines-core（StateFlow），零平台 SDK
│       └── src/test/kotlin/app/         11 文件 / 1698 行 / 91 条用例（全假端口，纯 JVM）
│
├── android/                       ★ Android 模块（:android:data + :android:app）
│   ├── app/                         :android:app —— Compose Material3 UI + 编排（19 文件 / 5761 行 + 11 测试文件）
│   ├── data/                        :android:data —— MediaStore / SAF / ExifInterface / 图片编解码 / 设置存储 / 壳层（6 文件 / 637 行）
│   └── keystore/                    ★ 正式签名（.gitignore 忽略，CI 运行时生成）
│       （★ 本目录下**不再有** settings.gradle.kts / build.gradle.kts / gradle.properties / gradlew / gradle/wrapper/）
│
├── desktop/                       ★ Windows 桌面模块（:desktop:core + :desktop:cli + :desktop:ui）
│   ├── core/                        ★ 适配器 + 组装根（19 文件 / 1814 行）
│   │    │                             DesktopCore（装配）+ WindowState/DesktopCoreConfig、
│   │    │                             adapter/ 7 个 Pc* 端口实现、platform/ 目录·EXIF·哈希·嵌图·id、
│   │    │                             export/ 日志·清单
│   ├── cli/                         无头命令行入口（1 文件 / 386 行，无 src/test）
│   ├── ui/                          Compose Multiplatform 界面（38 文件 / 6694 行 + 13 测试文件）
│   ├── packaging/                   jpackage / MSI / 便携版打包脚本与 dist 产物（不入库）
│   ├── scripts/                     安装冒烟、导出核对等 PowerShell 脚本
│   └── （★ 本目录下**不再有** settings.gradle.kts / build.gradle.kts / gradle.properties / gradlew / gradle/wrapper/）
│
├── docs/                         两端共享文档（UI 规格、桌面计划、兼容性矩阵…）
├── scripts/                      静态门禁 4 个（协程取消 / @Ignore 可溯源 / 隐式时区 / 共享层纯度）+ 黄金样本
├── .github/workflows/            ci.yml（门禁 + `gradle testAll` 覆盖 8 模块）、build-apk.yml（Android 出包）、
│                                 build-desktop.yml（Windows 出包，不含单测）
├── README.md / CHANGELOG.md / LICENSE
└── （无 android/settings.gradle.kts、desktop/settings.gradle.kts）★ 双根已删除，M4 起连两端 wrapper 一起删
```

**边界规则**

| 规则 | 说明 |
| --- | --- |
| `shared/` 只被挂载，不被复制 | 根 `settings.gradle.kts` 用 `include(":shared:<模块>")` 引入，默认 `projectDir` 恰为 `<root>/shared/<模块>`；仓库内**不存在**第二份实现，也不许出现 `android/shared`、`desktop/shared`、`android/algorithm` |
| 两端互不依赖 | `:android:*` 不依赖 `:desktop:*`，反之亦然；没有 `includeBuild`、没有跨端 project 依赖（有依赖就必须经由 `:shared:*`） |
| 平台实现留在各自一侧 | 图片编解码、文件扫描、UI 全在各端；`shared/` 只接受 `ByteArray` / 时间戳等中立类型，禁止 `java.nio.file` / `androidx.*` |
| 产物目录天然隔离 | 8 个项目各写自己的 `<projectDir>/build/`：`shared/*/build`、`android/*/build`、`desktop/*/build`。**不需要**任何 `buildDir` 重定向，`desktop/build/external/` 已不存在 |

---

## 2. 关键调整点

### 2.1 为什么共享层放在 `shared/`，而不是 `android/` 或 `desktop/` 下

两条历史约束叠加，得出现在这个位置：

1. **不能放进任一端目录**。放进 `android/`，桌面端要写 `../android/algorithm`——等于让 PC 依赖安卓端
   的目录结构；反过来同理。共享代码必须与两端**平级**。
2. **不能平铺在仓库根**（v1 的 `algorithm/`）。它虽然能编过，但"哪些代码是共享的"只存在于人的记忆里：
   目录名 `algorithm/` 不表达层级，门禁也无法用"`shared/` 下不许出现平台 API"这类规则去断言。

所以现在用嵌套项目名 `:shared:algorithm`，**根 settings 一行就够**（不需要再写 `projectDir`）：

```kotlin
include(":shared:algorithm")   // 默认 projectDir = <root>/shared/algorithm，恰好正确
```

> 为什么必须是 `:shared:algorithm` 而不是 `:algorithm`：Android 已经有 `:android:app`，而共享层也有
> `:shared:app`；嵌套项目名一次性把撞名坑避开，代价只是依赖写成 `project(":shared:algorithm")`。
>
> 只有 `android/` 与 `desktop/` 下的模块需要显式写 `projectDir`（因为默认会解析成
> `<root>/android/algorithm` 这类不存在的路径）：

```kotlin
project(":android:data").projectDir = file("android/data")
project(":android:app").projectDir  = file("android/app")
project(":desktop:core").projectDir = file("desktop/core")
project(":desktop:cli").projectDir  = file("desktop/cli")
project(":desktop:ui").projectDir   = file("desktop/ui")
```

### 2.2 【历史】`android/` 曾成为完整独立工程（v2，已废弃）

> ⚠️ 本节记录 v2 双根时代的做法，**M3/M4 后已全部回退**。保留只为说明"为什么当初这么做、后来为什么推翻"。

v2 随 `app/`、`data/` 一起把 `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、
`gradlew`、`gradlew.bat`、`gradle/wrapper/` 也搬进了 `android/`，使其可以单独构建；`desktop/` 同构。
代价是**共享层单测要在两套构建里各跑一遍**，而且 `:shared:*` 的产物会被两端各写一次，
必须靠 `desktop/build.gradle.kts` 的 `subprojects { ... buildDirectory.set(...) }` 重定向到
`desktop/build/external/` 才不互相覆盖。

v3 的选择是：**产物冲突用"一个根、一份产物"根治，而不是用 buildDir 重定向压制**。
于是两端各自的 settings / build / gradle.properties / wrapper 共 14 个文件全部删除
（M3 删 4 个根脚本，M4 删 10 个构建文件），仓库根成为唯一入口。

### 2.3 签名路径：单根下 `rootProject` = 仓库根，故改为双探测

单根化后 `rootProject` 从 `android/` **回到仓库根**，`rootProject.file("keystore/keystore.properties")`
会解析到 `<root>/keystore/`，而实际位置是 `<root>/android/keystore/`。为不让已有开发机/打包机搬文件，
`android/app/build.gradle.kts` 改成 `rootProject.layout.projectDirectory` 的**两处探测**：

```kotlin
val keystorePropFile = listOf(
    rootProject.layout.projectDirectory.file("android/keystore/keystore.properties").asFile,  // 新位置（CI 注入点）
    rootProject.layout.projectDirectory.file("keystore/keystore.properties").asFile,           // 旧位置（老开发机）
).firstOrNull { it.exists() }

fun resolveSigningFile(path: String) =
    rootProject.layout.projectDirectory.file("android/$path").asFile.takeIf { it.exists() }
        ?: rootProject.layout.projectDirectory.file(path).asFile
```

两处都没有 → 静默回退 debug 签名（原有行为，保证任何环境可构建）。
⚠️ 正因为是**静默**回退，`BUILD SUCCESSFUL` **不能**证明签名正确 —— 必须用 `apksigner verify` 验（G11）。

### 2.4 门禁脚本的默认扫描根跟着改

`scripts/check-cancellation.py`、`check-ignore-policy.py`、`check-timezone.py` 的默认根由
`app / algorithm / data` 改为 `android/app / shared / android/data`（T02 起 `shared` 覆盖三个共享模块；
`check-timezone.py` 写的是显式路径 `shared/algorithm|domain|app`）。脚本本身仍按
"脚本所在位置"解析仓库根（`REPO_ROOT = scripts/..`），不依赖调用方 cwd —— 这条性质在搬迁后依然成立，
所以只在 CI 传参处与默认值处改路径，判据与样例一律未动。

**I-04：三个门禁的默认根补入 `desktop/`（M1 补做，此前是整段盲区）**

| 脚本 | 补入的根 | 判据范围 |
|---|---|---|
| `check-cancellation.py` | `desktop/{core,cli,ui}/src/main` + `src/test` | 全部 `.kt`（生产 + 测试都判） |
| `check-ignore-policy.py` | `desktop/{core,cli,ui}/src/test` | 只判 `*/src/test/**`（本脚本不判生产代码） |
| `check-timezone.py` | `desktop/{core,cli,ui}/src/main` | 只判 `src/main`（测试里 `TimeZone.setDefault` 合法） |

补扫前这 80 个 `.kt`（其中 23 个测试文件）**完全不在任何行为门禁的扫描面内**；补扫后实测全绿
（`EXIT=0`），属"覆盖缺口"而非"已发生缺陷"。`check-shared-purity.py` 的 `PROD_ROOTS` / `UI_ROOTS`
本来就含 `desktop` 的 `src/main`，不属此缺口 —— 这也是为什么 desktop 侧改为按目录缩范围扫描时
要额外排除 `src/test`（P2-03）。

**同时**：`ci.yml` 的「扫描 Kotlin 源码」step 由传位置参数改为**不传参**（走各脚本的
`DEFAULT_ROOTS`）。理由：位置参数会**覆盖**默认根 —— CI 若继续写死 `android/app shared android/data`，
脚本默认根里新补的 desktop 就永远用不上。

### 2.5 【历史】CI 曾改为 `-p android`（v2，已废弃）

> ⚠️ 双根时代仓库根没有 `settings.gradle.kts`，在根跑 `gradle` 会报
> `Directory does not contain a Gradle build`，于是 workflow 全部改成显式指定子根
> （子根写法 `-p android` / `-p desktop`），三个 workflow 累计出现 5 处。
> **M3 起单根化，这些 `-p` 全部删除**（见 §2.9 的判据命令，实测 0）。
> 现行写法见 §2.9。

### 2.6 结构约束断言（M3/M4 后重写）

`ci.yml` 的 `static-guards` 里有一步 bash 断言，把"共享层只有一份、8 个项目都要挂、不许双根复活"
变成机械可验的规则：

```bash
# ① 共享层不许有第二份物理副本
for d in android/algorithm android/shared desktop/shared; do
  [ ! -e "$d" ] || { echo "✗ 残留 $d"; exit 1; }
done
# ② 根构建文件必须齐（缺一个就跑不起来）
for f in settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat \
         gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.jar \
         gradle/wrapper/gradle-wrapper.properties; do
  [ -e "$f" ] || { echo "✗ 缺 $f"; exit 1; }
done
# ③ M3 删除的 4 个容器项目脚本不许复活
for f in android/settings.gradle.kts android/build.gradle.kts \
         desktop/settings.gradle.kts desktop/build.gradle.kts; do
  [ ! -e "$f" ] || { echo "✗ 残留 $f"; exit 1; }
done
# ④ M4 删除的两端 10 项不许复活（防双根复活）
for f in android/gradle.properties desktop/gradle.properties android/gradlew desktop/gradlew \
         android/gradlew.bat desktop/gradlew.bat android/gradle/wrapper desktop/gradle/wrapper; do
  [ ! -e "$f" ] || { echo "✗ 残留 $f"; exit 1; }
done
# ⑤ 8 个项目必须都被根 settings include
for p in ':shared:algorithm' ':shared:domain' ':shared:app' ':android:data' ':android:app' \
         ':desktop:core' ':desktop:cli' ':desktop:ui'; do
  grep -Fq "include(\"$p\")" settings.gradle.kts || { echo "✗ 未 include $p"; exit 1; }
done
# ⑥ 共享层源码不许被移动/复制
for mod in algorithm domain app; do
  [ -f "shared/$mod/build.gradle.kts" ] || { echo "✗ 缺 shared/$mod"; exit 1; }
done
```

> ⚠️ **判据必须真的能让 CI 红**：上面每一条都带 `|| { echo …; exit 1; }`（P2-05）。
> 早期草稿写成 `for f in …; do [ -e "$f" ]; done` —— `[ -e ]` 的退出码被 `done` 丢弃，
> 整个循环**永远返回 0**，是"看起来有断言、实际上永远绿"的最坏形态。
> 真实实现见 `ci.yml` 的「结构约束（单根 8 项目 + 共享层唯一）」step。

> **R-02 通则**：这 step **只断言本次改动内已删除的文件**，绝不断言"尚未删除"的文件 ——
> 否则第一次 CI 必红。M3 提交时只能断言那 4 个脚本，M4 提交后才能把 10 项补进来，两步不能合并。

这几类错误都是"几秒钟能查、靠人 review 必漏"的，所以放在门禁而不是文档里。

### 2.7 桌面端打包流水线（M5 起只出包，不跑单测）

`build-desktop.yml`：`windows-latest` + `gradle/actions/setup-gradle@v6`（8.10.2），只在 `desktop-v*`
标签与手动派发时跑，产出 MSI + `PhotoMgm-<ver>-portable.zip`。
**M5 删掉了原「Unit tests (desktop core / cli / ui + shared domain / app)」step** ——
单测已由 `ci.yml` 的 `gradle testAll` 在每次 push/PR 覆盖，这里再跑一遍是纯重复且多花 3~5 分钟。
（历史注：v2 文档曾写"刻意不出 MSI"，那是 WiX 不常驻时代的结论；runner 现已自带 WiX 3.14.1，MSI 改为 CI 产出。）

### 2.8 明确"未改动"清单（单根化重构 M0~M5）

- **`.kt` 业务源码：零改动**（`shared/*`、`android/*/src`、`desktop/*/src` 一个字节未动）；
- **所有产物路径：零改动**（`android/app/build/outputs/apk/...`、`desktop/ui/build/compose/binaries/...`）；
- **3 个 golden 基线 JSON：零改动**（`sha256sum docs/golden/*.json` 与 M0 冻结值一致）；
- 真正改动的是：构建脚本（新增根 8 文件 / 删除两端 14 文件 / 3 处 `project()` 改名 / 签名探测）、
  3 个 workflow、3 个黄金脚本、4 个门禁脚本（任务 #1 的 M1 部分）、文档。

### 2.9 现行构建命令（唯一权威写法）

**两处 `gradle` 来源不同，别混用**：

| 场景 | 命令 | 说明 |
|---|---|---|
| 本地开发 | `./gradlew <task>` / `gradlew.bat <task>` | wrapper 的 `distributionUrl` 指向腾讯云镜像；wrapper 只服务本地 |
| CI | `gradle <task>` | `gradle/actions/setup-gradle@v6` 装官方 8.10.2；**不调用 wrapper**（美国 runner 拉腾讯云分发包易超时） |

版本相同（8.10.2），差别只在分发包来源。CI 三个 workflow 均不出现 `./gradlew`
（判据：`grep -rnE '^[[:space:]]*run:.*(gradlew|gradle -p )' .github/workflows/` = 0）。

```bash
# 全部在仓库根执行，task 用全限定项目路径
./gradlew testAll                    # 一次跑完 8 个模块（CI 用的就是这条）

./gradlew :android:app:assembleDebug
./gradlew :android:data:test :android:app:testDebugUnitTest
./gradlew :desktop:ui:createDistributable
./gradlew :desktop:ui:packageMsi
./gradlew :desktop:cli:run
```

---

## 3. 打包与上传：配置项 + 验证步骤

### 3.1 需要配置的东西

| 项 | 位置 | 说明 |
| --- | --- | --- |
| JDK 17 | 本机 `JAVA_HOME` / CI `setup-java` | 两端都用 17；本机路径 `/d/Android/jdk-17.0.2` |
| Android SDK | `ANDROID_HOME` 或 `android/local.properties` 的 `sdk.dir` | 单根后 `local.properties` 仍落在 `android/`（AGP 按模块目录找）；本机走 `ANDROID_HOME` |
| 正式签名（可选） | `android/keystore/keystore.properties`（旧位置仓库根 `keystore/` 仍识别） | 字段 `storeFile/storePassword/keyAlias/keyPassword`；缺省回退 debug 签名 |
| CI Secrets（可选） | `KEYSTORE_BASE64`、`KEYSTORE_STORE_PASSWORD`、`KEYSTORE_KEY_ALIAS`、`KEYSTORE_KEY_PASSWORD` | 由 `build-apk.yml` 解码到 `android/keystore/` |
| 桌面版本号 | `desktop/ui/build.gradle.kts` 的 `packageVersion` + `build-desktop.yml` 的 `DESKTOP_VERSION` | 两处需同改 |
| 标签规范 | Android `v*`（如 `v1.3.3`）；桌面 `desktop-v*`（如 `desktop-v2.0.0`） | 两条独立版本线，前缀不同才不会互相触发发布 |

### 3.2 本地验证步骤（按顺序，任一步红即停）

```bash
# 全部在仓库根执行
# 0) 静态门禁（四个脚本都先自测判据，再实扫）
for s in cancellation ignore-policy timezone shared-purity; do
  python3 scripts/check-$s.py --selftest && python3 scripts/check-$s.py
done

# 1) 单测：一次覆盖 8 个模块
./gradlew testAll

# 2) Android 出包
./gradlew :android:app:assembleDebug      # → android/app/build/outputs/apk/debug/app-debug.apk
./gradlew :android:app:assembleRelease    # → android/app/build/outputs/apk/release/app-release.apk

# 3) 桌面出包（仅 Windows）
./gradlew :desktop:ui:createDistributable # → desktop/ui/build/compose/binaries/main/app/PhotoMgm/
./gradlew :desktop:ui:packageMsi          # → desktop/ui/build/compose/binaries/main/msi/*.msi（需 WiX）
```

**单根化重构（M0~M5）的静态自验结果**

> ⚠️ 下表是**静态检查**结论（文件存在性 / grep 计数 / YAML 解析 / Python 词法门禁）。
> 涉及 gradle 实跑的全部条目一律标 **【需 CI 验证】** —— 本机无 JDK/SDK，不产 `BUILD SUCCESSFUL` 结论。

| 检查 | 结果 |
| --- | --- |
| `settings.gradle.kts` 的 `include("` 条数 | 8（`:shared:{algorithm,domain,app}`、`:android:{data,app}`、`:desktop:{core,cli,ui}`） |
| 平铺项目名残留（`project(":core")` 等） | 0 |
| 双根构建文件残留（两端 settings/build/gradle.properties/wrapper） | 14 个全删（M3 删 4 + M4 删 10） |
| `testAll` 的 `dependsOn` 项数 | 8（硬编码） |
| 4 个门禁脚本自测 / 实扫 | 17 / 21 / 13 / 18 组自测通过；实扫 84 / 54 / 231 / 147 文件，0 问题 |
| 三个 workflow YAML | `yaml.safe_load` 解析通过；`run: gradle ` 计数 = 4，子根写法（`-p`）= 0 |
| `gradle testAll` 实跑 | **已本地全量跑通（2026-11-06 18:16）**：带 `ANDROID_HOME=D:/Android/sdk` + `--rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL in 5m 58s, EXIT=0`；8 个测试任务中 7 个真实执行、`:desktop:cli` NO-SOURCE；**80 份 XML / 765 用例 / 4 skipped / 0 failures / 0 errors / 0 份 tests=0**。CI 首跑仍需复核 runner 环境（V-B），但「本机不可判定」已解除 |
| `:android:data` 的 test task 名是否为 `testDebugUnitTest` | **已本地确认**：`gradle :android:data:tasks --all` 输出含 `testDebugUnitTest`（`build.gradle.kts:35` 无需改动） |
| SDK 前置的真实边界 | **已本地实测**：Gradle **定位不到** SDK 时（SDK 实体在 `D:/Android/sdk`，只是未设 `ANDROID_HOME`、仓库无 `local.properties`）`gradle projects`、`:desktop:*`、`:shared:*` 测试全部成功；只有 `:android:*` 的**执行期**报 `SDK location not found`（README「环境前置」已按此改写）。带 SDK 时 `testAll --dry-run` 8 个测试任务全在图中 |
| `scripts/check-ci-gradle-source.py` | G10 **完备**判据（仅标准库；15 组 selftest + 3 类真实变异）；**尚未挂进 `static-guards`**，待首跑绿后单独提交挂入 |

#### 判据通则（写门禁 / 验收命令前先读这条）

**判据一律只看「调用行 / 代码行」，不把注释与"文件存在性清单"当命中。**

本项目已经被这条坑过两次，两次都是"判据 pattern 与断言文本重叠"，**不是**真实违规：

| 踩坑 | 现象 | 正确判据 |
|---|---|---|
| G10（M3/M4） | `grep -rn "gradle -p \|gradlew" .github/workflows/` 实测 **3 行**，看着像违规；实际全部命中结构约束 step 里的**存在性/残留清单**（`ci.yml:80` 根构建文件清单、`:93-94` M4 防双根复活清单），没有一处是调用 | `grep -rnE '^[[:space:]]*run:.*(gradlew|gradle -p )' .github/workflows/` → **0** |
| `gradle .*test` 计数 | 裸 `grep "gradle .*test" .github/workflows/*.yml` 实测 **4 行**，但真正的 `run:` 调用只有 **1 行**（`ci.yml:168`）—— 另外 3 行全是注释里的命令示例（`ci.yml:7,161`、`build-desktop.yml:55`） | 只看 `run:` 行：`grep -nE '^[[:space:]]*run:.*gradle ' .github/workflows/*.yml` → 4 条（本例即 `run: gradle ` 计数 = 4） |

**具体三条纪律：**
1. **判据锚定行首结构**：要判"调用"就锚 `^[[:space:]]*run:`；要判"代码"就锚 import / 声明语法，
   不要用会同时匹配注释与文档正文的裸关键词。
2. **断言文本可以留，判据要收窄**：为了让裸 grep 归零而删掉真实断言（比如"根 8 个构建文件必须存在"）
   是拿拦截能力换指标好看 —— 不许这么做；要么收窄 pattern，要么显式记录"该处是断言不是调用"并说明。
3. **门禁里 `[ … ]` 的退出码必须真的传出去**：`for f in …; do [ -e "$f" ]; done` 会丢弃退出码、
   整个循环恒返回 0（永远绿）。必须写成 `do [ -e "$f" ] || { echo "✗ 缺 $f"; exit 1; }; done`，
   见 §2.6 的样板。

### 3.3 上传（推送）步骤

```bash
# 提交（分两次，M3 的原子切换与 M4 的删除残留不建议合并，便于二分定位）
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat
git commit -m "build: 引入单根多模块构建（版本目录 + 根 wrapper + testAll）"

git add -A
git commit -m "refactor(build): 删除双根残留，workflow/脚本/文档同步单根化"

# 推分支
git push origin <branch>

# 打标签（两条独立线，按需其一）
git tag -a v1.3.3 -m "Android 1.3.3" && git push origin v1.3.3
git tag -a desktop-v2.0.0 -m "Windows 桌面版 2.0.0" && git push origin desktop-v2.0.0
```

CI 上的判定标准：

- `ci.yml` 绿 = 结构约束（§2.6，含"无双根残留"）+ 四个门禁 + `gradle testAll` 覆盖全部 8 个模块；
- `build-apk.yml` 绿 = 出 `Photo-Mgm-release-apk` artifact（`v*` 标签则发 Release）；
- `build-desktop.yml` 绿（仅 `desktop-v*` / 手动）= 出 `PhotoMgm-desktop-portable` artifact（**不含单测**）。

> **`testAll` 绿的真正判据**：不是"8 条 Task 行都出现"，而是 8 份 `build/test-results/**/TEST-*.xml`
> 里 `tests>0`。`:desktop:cli` 没有 `src/test`，它的 `test` 必然 `NO-SOURCE`（不产 XML），
> 属预期而非失败。这条口径由 `scripts/golden/` 之外的 CI 注释与 `build.gradle.kts` 共同固定。

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
| 编译参数 | 根 `build.gradle.kts` 的 `alias(...)` + `gradle/libs.versions.toml` | Kotlin 均为 2.0.21；`jvmTarget` 均为 17；核心 toolchain 固定 17（单点来源：版本目录） |
| 产物目录 | 8 个项目各写自己的 `<projectDir>/build/` | `shared/algorithm/build`、`android/*/build`、`desktop/*/build` 互不覆盖；**无需任何 buildDir 重定向**，`desktop/build/external/` 已随双根一起删除 |

### 4.2 差异结论：源码零差异，差异全在"编译环境"与"喂给核心的输入"

两端不存在"同一逻辑两套代码"的漂移风险（这是共用挂载换来的），但仍有 **4 类客观差异**：

| 维度 | Android 端 | Windows 桌面端 | 影响 |
| --- | --- | --- | --- |
| 编译器/工具链 | Kotlin 2.0.21 + AGP 8.7.3 + D8/R8，jvmTarget 17 | Kotlin 2.0.21 + jpackage，jvmTarget 17 | 语义一致；字节码经 D8 处理后与 JVM 直跑有别，属正常 |
| 核心产物目录 | `shared/algorithm/build` | `shared/algorithm/build`（**同一个**） | 单根后只有一份产物，不存在"两端各写一遍"的覆盖问题 |
| 嵌入台账的图片 | `data/image/BitmapImageEncoder`：4:3 中心裁剪 → 400×300 → JPEG50 | `PcImageEmbedder`：4:3 中心裁剪 → 400×300 → JPEG50 | 规格已收敛到 `shared/algorithm/MediaSpec` 单点定义（T04）；安卓实现 T06 已搬进 `:data` |
| 时区口径 | 显式 `AppZone.ID`（Asia/Shanghai） | 相同 `AppZone.ID`；导出目录命名另用 UTC `Clock` | 台账时间一致；仅目录名的时间戳语义不同 |

### 4.3 相互影响的风险矩阵

| 编号 | 风险 | 现状证据 | 等级 | 建议 |
| --- | --- | --- | --- | --- |
| R1 | **一改双端**：核心改动会同时改变 Android 与 PC 的行为，而只有一端做了回归 | 已实际发生：为 PC 台账加的 `hh:mm`、行高 81pt、`sheetViews`、照片格无字，同样改变了 Android 导出的 `ledger.xlsx`，当轮 App 未重打包验证 | 中 | 改 `algorithm` 的提交须在两端各跑一次真实素材导出；CHANGELOG 标注"影响两端" |
| R2 | **图源契约靠约定**：`XlsxWriter` 以 `oneCellAnchor` 固定 3.81×2.8575cm（4:3），而 400×300 曾是两个模块各写一遍的常量；任一端改比例（如 16:9），台账里照片会被**拉伸且无告警** | `ImageUtils.TARGET_WIDTH=400` 与 `PcImageEmbedder.DEFAULT_WIDTH=400` 各自独立 | 中 → **已消灭**（T04 收敛到 `MediaSpec`，两端只剩引用；T06 删掉安卓侧的三个转发别名） | 核心侧对"宽高比偏离 4:3"记录告警或以实际比例算 ext；至少在一端加断言 |
| R3 | **平台 API 越界**：核心若引入 `java.awt` / `javax.imageio`，Android 侧编译能过、运行期崩 | 当前 0 处（已扫描） | 低（但无门禁） | 加一条静态门禁（类似现有三个脚本），把"核心只许用 java.io/time/util.zip"固化成规则 |
| R4 | **版本线混淆**：Android 走 `v*`、桌面走 `desktop-v*`，若哪天给桌面打 `v2.0.0`，会误触发 APK 正式发布 | `build-apk.yml` 的 `tags: ['v*']` | 低 | 已用 `desktop-v` 前缀规避；标签规范写入本文 §3.1 |
| R5 | **构建竞争**：过去两端各自构建会共用 Gradle 用户缓存，且 `:shared:*` 产物可能被对方覆盖 | 单根后 `:shared:*` 只有一个 `build/` 目录、一个 Gradle 用户缓存；`org.gradle.caching=true` 全仓一处配置 | 低 → **已消灭**（M3 单根化 + M4 删两端 wrapper，物理上不存在"两套构建"） | 同一时刻仍在两个终端跑同一份构建时，Gradle 自身有锁；`--no-daemon` 的 CI 之间互不干扰 |
| R6 | **共享层越界引入平台 API**：`shared/*` 一旦 import `android.*`/`java.awt`/`javax.*` 或直接构造 `java.io.File`，一端能编、另一端运行期才崩 | T14 前靠人肉 grep，无机械拦截 | 中 → **已消灭**（T14 起 `check-shared-purity.py` 的 P1/P1b 拦截，进 CI） | 共享层只许用 JVM 通用 API（`java.io`/`java.nio.file.Path`/`java.time`/`java.util`）；路径与文档树经端口传入 |
| R7 | **契约常量重复**：产物契约字符串（`99_未分类`、`export.log` 等）写两遍，改一处忘另一处 → "ZIP 里叫 A、台账里叫 B" | T14 前 `99_未分类` 在 `ZipExporter` 与 `ExportModels` 各写一份、`export.log` 在两端执行器各写一份 | 中 → **已消灭**（T14 收敛为各一处定义，P4 门禁保证只减不增） | 新契约常量只能有一处 `= "..."` 定义，其余全引用 |
| R8 | **UI 层越层 import**：UI 直接 import `algorithm.model.*` / `app.usecase.*`，绕过契约层，改契约时 UI 静默漂移 | T14 实测 9 处 `import algorithm.*`（历史遗留） | 低 → **棘轮锁定**（P5 门禁：`app.usecase/state/port/export/infra` 与 `domain.*` 一律禁止；`algorithm.*` 上限锁 9，只减不增） | 跨端契约只在 `app.api`；UI 要数据经平台层投影，不直接摸算法层 |

### 4.4 结论

1. **不存在代码级分歧**：核心只有一份，Android 与 PC 的台账、分类、去重、日志解析行为在源码层面必然一致；
   本次重组没有改变这一点（`algorithm/` 一个字节未动）。
2. **风险不是"漂移"，而是"共变"**：改核心等于同时发布两端行为。R1 已经真实发生一次，属于流程问题而非代码问题，
   靠"改核心必跑两端"约定 + CHANGELOG 标注来控制。
3. **真正需要机械化拦截的是 R2 / R3 / R6 / R7**：跨模块的隐式尺寸契约、平台 API 越界、契约常量重复，
   都是"编译期不报错、运行期才难看"的类型。R2 已由 T04 的 `MediaSpec` 收敛、R3/R6/R7 已由 T14 的
   `check-shared-purity.py` 机械拦截（R3 对应 P1/P1b，R6 与 R3 同门禁，R7 对应 P4），R8 由 P5 棘轮锁定。
4. **隔离度评估**：`:android:*` 与 `:desktop:*` 之间没有任何直接依赖、没有各自的构建脚本、产物目录天然分离 ——
   "互不影响"在**工程层面成立**；唯一的耦合点是刻意保留的 `:shared:*`，属设计选择而非事故。
   单根化进一步把"两套构建"这个最后的物理不确定性也消掉了：8 个项目、一个根、一份产物。
