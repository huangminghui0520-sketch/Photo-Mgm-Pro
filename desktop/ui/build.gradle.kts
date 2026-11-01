// :ui —— Windows 桌面界面（Compose Multiplatform）
//
// 分层约定（U0 起生效，后续所有 UI 代码必须遵守）：
//   :ui  →  :core/api  （只认门面 / AppState 快照 / ExportModels 三份契约）
//   :ui  ✗→  :core/logic、:core/platform、:algorithm   （禁止越层直接调用）
//
// 注意：这条**目前是约定，不是编译期强制**。Gradle 的 implementation(project(":core"))
// 会把 core 主源码里所有 public 类型暴露给 :ui，所以编译能过不代表没越层。
// 真正的强制手段是后续把 logic/platform 的类改为 internal（对模块外不可见），
// 但那属于修改 core 的可见性，按「api 只增不改」的规则单独立项，不在 UI 阶段顺手做。
//
// 线程约定：Swift/Compose 组合运行在 AWT EDT；核心的 analyze/export 是阻塞任务，
//   必须经 Dispatchers.Default 调度后再把结果推回 StateFlow，ui 侧只 collect。
plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(project(":core"))

    // 当前操作系统对应的 Compose Desktop 运行时（Windows 上会拉 skiko-awt-runtime-windows-x64）
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    // 尺寸/布局用例全是纯函数，不碰 AWT 窗口，可在无头环境跑
    systemProperty("java.awt.headless", "true")
}

compose.desktop {
    application {
        mainClass = "com.photomgm.desktop.ui.MainKt"

        // 中文日志/诊断输出必须是 UTF-8：JDK 17 在中文 Windows 上把 System.out 绑到
        // 控制台代码页（GBK），一旦输出被重定向到文件或管道，中文全部变乱码，
        // "grep 日志做验收"就失效了。三个属性缺一不可（CLI 那边踩过同一个坑）。
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
        )

        nativeDistributions {
            // 目标格式：MSI 需 WiX 3.x；app-image 无需任何外部工具（M7-tech 用它验证）
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
            )
            packageName = "PhotoMgm"
            packageVersion = "2.0.0"
            description = "高速公路巡查照片分类工具（Windows 桌面版）"
            vendor = "Photo-Mgm"
        }
    }
}
