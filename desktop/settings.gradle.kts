// Photo-Mgm Windows 桌面版（逻辑核心）—— 工程设置
// ★ 本阶段只产出「无 UI 的逻辑核心」：core 模块零 Compose / 零 Swing / 零 AWT 窗口依赖，
//   以便后续任意 UI（Compose Desktop / JavaFX / 甚至命令行）直接接入。
//
// 复用策略：把原工程的 :algorithm 以「外部目录挂载」方式引入（不复制、不移动、不改动），
//   单一事实来源 → 桌面端与 Android 端行为不会漂移。
//
// 仓库源（U0 实测结论）：
//   1) mavenCentral 直连可用（~2s）—— Kotlin / Compose Multiplatform / metadata-extractor 都在这里
//   2) 阿里云镜像在本网络不可达（超时），不要配
//   3) ★ google() 必须保留：org.jetbrains.compose.ui:ui-desktop 会传递依赖
//      org.jetbrains.androidx.lifecycle → androidx.lifecycle:*:2.8.5，
//      而 androidx 构件**只发布在 Google Maven**，mavenCentral 上查不到。
//      缺了它 :ui 会在解析阶段直接失败（不是编译失败，是“Could not find androidx.lifecycle”）。
//   4) google() 指向 https://dl.google.com/dl/android/maven2/（实测 200/0.4s）；
//      注意不是 maven.google.com —— 那个域名在本网络 DNS/连接超时。
//   Gradle 分发走腾讯云镜像（与根工程 wrapper 一致）。
pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        mavenLocal()
    }
}

rootProject.name = "PhotoMgmDesktop"

// :core = 纯逻辑核心（UI 无关）
include(":core")

// :cli = 无头命令行入口（逻辑核心的第一个消费者；也是 M7-tech 打包验证的 main class）
include(":cli")

// :ui = Compose Multiplatform 桌面界面（只依赖 :core 的 api 包，禁止越层）
include(":ui")

// :shared:algorithm / :shared:domain / :shared:app = 跨端共享层（纯 Kotlin JVM，零平台 SDK）
// ★ 以「外部目录挂载」引入（不复制、不移动其内部文件），单一事实来源 → 桌面端与 Android 端行为不会漂移。
// ★ 必须用嵌套项目名 :shared:algorithm：平铺成 :algorithm 也能编过，
//   但会丢掉"共享代码只在 shared/ 下"这条结构约束，门禁也就无从断言。
include(":shared:algorithm")
project(":shared:algorithm").projectDir = file("../shared/algorithm")

include(":shared:domain")
project(":shared:domain").projectDir = file("../shared/domain")

include(":shared:app")
project(":shared:app").projectDir = file("../shared/app")
