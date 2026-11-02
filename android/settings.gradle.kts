// Photo-Mgm Android 端 —— 工程设置
//
// 仓库布局（2026-11-02 重组，详见 docs/REPO-LAYOUT.md）：
//   algorithm/  共用核心（纯 Kotlin JVM，Android 与 Windows 桌面共用**同一份源码**）
//   android/    本工程：:app（Compose UI）、:data（MediaStore/SAF/ExifInterface）
//   desktop/    Windows 桌面版（独立 Gradle 构建，以 ../algorithm 挂载同一份核心）
//
// ★ :algorithm 不在本目录下，以「外部目录挂载」引入（与 desktop/settings.gradle.kts 同一手法）。
//   复制一份到 android/ 下是最容易犯的错——两份源码必然漂移。挂载后两端永远是同一份实现；
//   代价是改核心会同时影响两端，回归责任见 docs/REPO-LAYOUT.md §4。
//
// 仓库源按环境切换（注：pluginManagement/dependencyResolutionManagement 块内不能引用脚本局部变量，
// 故直接内联判断）：
//   - 本地/国内：走阿里云镜像（默认，国内网络直连原生地址慢/不可达）
//   - CI（GitHub Actions，自动注入 CI=true）：走 google()/mavenCentral()/gradlePluginPortal() 原生源，
//     美国 runner 直连原生源又快又稳（阿里云镜像对海外访问不稳定，曾导致构建失败）
pluginManagement {
    repositories {
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
        mavenLocal()
    }
}

rootProject.name = "PhotoMgmAndroid"
// :algorithm = 核心算法模块（纯 Kotlin JVM，零 Android 依赖，可移植 PC）
// ★ 只挂载、不复制、不改动。产物落点：Android 构建写 algorithm/build；
//   桌面构建经 desktop/build.gradle.kts 的 buildDir 重定向写 desktop/build/external/algorithm，
//   两边不共用产物目录（这也是"互不影响"的一部分）。
include(":algorithm")
project(":algorithm").projectDir = file("../algorithm")
// :data = Android 数据接入层（MediaStore / SAF / ExifInterface；依赖 :algorithm，仅做平台实现）
include(":data")
// :app = Android UI（Compose 三段式；仅经 AlgorithmApi 单向调用核心）
include(":app")
