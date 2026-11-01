// Photo-Mgm Windows 桌面版（逻辑核心）—— 工程设置
// ★ 本阶段只产出「无 UI 的逻辑核心」：core 模块零 Compose / 零 Swing / 零 AWT 窗口依赖，
//   以便后续任意 UI（Compose Desktop / JavaFX / 甚至命令行）直接接入。
//
// 复用策略：把原工程的 :algorithm 以「外部目录挂载」方式引入（不复制、不移动、不改动），
//   单一事实来源 → 桌面端与 Android 端行为不会漂移。
//
// 仓库源：本机实测 mavenCentral 直连可用（2s），阿里云镜像在本网络不可达（超时），
//   Gradle 分发走腾讯云镜像（与根工程 wrapper 一致）。CI 环境如需切换另加判断。
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "PhotoMgmDesktop"

// :core = 纯逻辑核心（UI 无关）
include(":core")

// :cli = 无头命令行入口（逻辑核心的第一个消费者；UI 延后开发期间的验收与批处理通道）
include(":cli")

// :algorithm = 直接挂载原工程算法模块（不改动其任何文件）
include(":algorithm")
project(":algorithm").projectDir = file("../algorithm")
