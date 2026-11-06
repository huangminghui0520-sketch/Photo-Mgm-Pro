// Photo-Mgm 单根多模块构建（8 个项目）。详见 docs/REPO-LAYOUT.md。
//
// ★ 为什么在仓库根：`:shared:*` 的默认 projectDir 恰好是 <root>/shared/<mod>，
//   `:android:app` / `:desktop:ui` 也恰好是 <root>/android/app / <root>/desktop/ui，
//   于是 8 个模块的物理目录、全部产物路径、3 个 golden 基线都零移动（方案 §2.3 收益 2）。
//   旧的两端 settings 里那 6 行 `projectDir = file("../shared/xxx")` 因此**不再需要**。
//
// 仓库源策略：沿用原 android/settings.gradle.kts（该文件已在 M3 删除）的既有约束 ——
//   pluginManagement / dependencyResolutionManagement 块内**不能引用脚本局部变量**，
//   故一律内联 System.getenv() 判断，不使用 val / providers.gradleProperty。
//   · CI（GitHub Actions 自动注入 CI=true）或 PHOTOMGM_NATIVE_REPOS=true → 原生源
//     （美国 runner 直连原生源又快又稳；阿里云镜像对海外访问不稳定，曾导致构建失败）
//   · 本地/国内 → 阿里云三镜像 + 原生源兜底
pluginManagement {
    repositories {
        if (System.getenv("CI") == "true" || System.getenv("PHOTOMGM_NATIVE_REPOS") == "true") {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            // 兜底：Compose Multiplatform 传递依赖的 androidx.lifecycle:* 只发布在 Google Maven，
            // 镜像缺件时解析阶段会直接失败（"Could not find androidx.lifecycle"），故保留原生源。
            google()
            mavenCentral()
            gradlePluginPortal()
        }
        mavenLocal()
    }
}

dependencyResolutionManagement {
    // 8 个模块脚本里一处 repositories{} 都没有（M0 实测 5 行 repositories 全部在 settings），
    // 单根后本文件是唯一来源，故可安全统一为 FAIL_ON_PROJECT_REPOS：
    // 谁在模块里私加仓库源，会在配置阶段直接失败，而不是悄悄改变解析结果。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == "true" || System.getenv("PHOTOMGM_NATIVE_REPOS") == "true") {
            google()
            mavenCentral()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            google()
            mavenCentral()
        }
        mavenLocal()
    }
}

rootProject.name = "PhotoMgm"

// ── 共享层：3 个项目 ─────────────────────────────────────────────────────
// 默认 projectDir 恰为 <root>/shared/<mod>，不需要 projectDir = file("../shared/<mod>")。
// 源码物理位置不变、全仓唯一一份（ci.yml 的结构约束仍断言 android/algorithm、
// android/shared、desktop/shared 都不存在）。
include(":shared:algorithm")
include(":shared:domain")
include(":shared:app")

// ── Android 端：2 个项目 ─────────────────────────────────────────────────
// projectDir 显式写出来（默认值本就一致），这样 CI 可 grep 断言层级没被改平。
include(":android:data")
include(":android:app")
project(":android:data").projectDir = file("android/data")
project(":android:app").projectDir = file("android/app")

// ── 桌面端：3 个项目 ─────────────────────────────────────────────────────
include(":desktop:core")
include(":desktop:cli")
include(":desktop:ui")
project(":desktop:core").projectDir = file("desktop/core")
project(":desktop:cli").projectDir = file("desktop/cli")
project(":desktop:ui").projectDir = file("desktop/ui")

// ★ 关于容器项目（重要，别删错东西）：`:android` / `:desktop` 是 Gradle 依据
//   嵌套 include 语句自动创建的容器项目，其 projectDir 默认就是 <root>/android、
//   <root>/desktop。若那两个目录下还留着旧的根 build.gradle.kts（带 version 的 plugins 声明），
//   Gradle 会把它当容器项目脚本执行，与根 build.gradle.kts 的 alias(...) apply false 冲突
//   （同一 plugin id 既在 classpath 又被带字面 version 请求）。所以两端旧的
//   settings.gradle.kts / build.gradle.kts 共 4 个文件在本次切换中**一并删除**，不留到 M4。
//
// ── 可选加固（默认关闭，CI 永不设置）────────────────────────────────────
// 无 Android SDK 的纯 JVM 开发者可跳过 Android 项目（方案 §3.2 L3）。启用前必须先把根
// build.gradle.kts 的 testAll dependsOn 改成条件构造，否则会引用不存在的项目而报错。
// if (System.getenv("PHOTOMGM_SKIP_ANDROID") == "true") { … }
