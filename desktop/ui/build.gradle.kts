// :ui —— Windows 桌面界面（Compose Multiplatform）
//
// 分层约定（U0 起生效，后续所有 UI 代码必须遵守）：
//   :ui  →  :core/api  （只认门面 / AppState 快照 / ExportModels 三份契约）
//   :ui  ✗→  :core/logic、:core/platform、:algorithm   （禁止越层直接调用）
//
// ★ 这条从「约定」升级成了「编译期强制」：core 的 logic / platform / state / infra / export
//   包下所有类型已标记 internal，对 :ui、:cli 等外部模块不可见。所以「编译能过」现在就是
//   「没越层」的证明 —— 试图 import PcPaths / PhotoMgmCoreImpl / PcFolderExporter 会直接编译失败。
//   （core 自己的测试是 friend module，仍可见 internal 成员，不受影响。）
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
    implementation(project(":desktop:core"))

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
        // 另加 -Xmx2g：打包产物的运行时默认堆上限（M7 打包要求）。
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
            "-Xmx2g",
        )

        nativeDistributions {
            // 目标格式：MSI 需 WiX 3.x；app-image 无需任何外部工具（M7-tech 用它验证）
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
            )
            packageName = "PhotoMgm"
            packageVersion = "1.0.2"
            // ★ 注意：description 必须是纯 ASCII（英文），不能用中文。
            //   jpackage 17.0.2 在生成 MSI 时把 description 写入 WiX .wxs 文件，
            //   含非 ASCII 字符会触发已知 bug（compose-jb#2021），报 "Input length = 1"。
            description = "Highway patrol photo classification tool (Windows desktop)"
            vendor = "Photo-Mgm"

            windows {
                // M7 打包要求：应用图标（品牌 .ico）。packaging 在 desktop 根目录，ui 模块在其下一层。
                iconFile.set(project.file("../packaging/assets/photo_mgm.ico"))
                // 桌面快捷方式 + 开始菜单项
                shortcut = true
                menu = true
                menuGroup = "Photo-Mgm"
                // per-user 安装（无需管理员权限，装到 %LOCALAPPDATA%）
                perUserInstall = true
                // 固定升级 UUID（后续发新版时 MSI 可原地升级）
                upgradeUuid = "f6a1b2c3-9d4e-4f5a-8b7c-1e2d3f4a5b6c"
            }
        }
    }
}
