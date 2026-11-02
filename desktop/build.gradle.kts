// 根构建脚本：统一插件版本（子模块各自 plugins { ... } 不再写版本）。
// Kotlin 版本与 Android 端保持一致（2.0.21），保证 :algorithm 两侧编译行为一致。
//
// ★ 版本矩阵（U0 已实测，见 desktop/README.md「UI 章节」）：
//   Kotlin 2.0.21 + Compose Multiplatform 1.7.3 —— 官方兼容组合，无需升级 Kotlin。
//   若将来必须升级 Kotlin：只改本文件这两行即可，:algorithm 以源码挂载、随本工程编译器编译，
//   不受原 Android 工程 2.0.21 的约束。
plugins {
    kotlin("jvm") version "2.0.21" apply false
    // Compose 编译器自 Kotlin 2.0 起随 Kotlin 发行（版本必须与 kotlin("jvm") 完全一致）
    kotlin("plugin.compose") version "2.0.21" apply false
    // Compose Multiplatform 的运行库与桌面打包支持
    id("org.jetbrains.compose") version "1.7.3" apply false
}

// ── 把外部挂载模块的构建产物收回到本工程内 ──────────────────────────────────
// :shared:* 的 projectDir 指向 ../shared/*。如果不改 buildDir，
// Gradle 会把产物写进原工程的 shared/*/build —— 与 Android 构建共享同一目录，
// 两个构建的 Kotlin 版本/插件/缓存会互相踩，且会在 shared/ 里留下新目录。
// 这里只改"产物放哪"，不改共享层的任何源码与构建脚本。
subprojects {
    if (path.startsWith(":shared:")) {
        // 用冒号换成短横，落 desktop/build/external/shared-algorithm 这类目录，
        // 避免嵌套路径把 external/ 越铺越深
        val flat = path.removePrefix(":").replace(':', '-')
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("external/$flat"))
    }
}
