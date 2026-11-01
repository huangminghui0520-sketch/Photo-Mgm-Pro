// 根构建脚本：统一插件版本（子模块各自 plugins { kotlin("jvm") } 不再写版本）。
// Kotlin 版本与 Android 端保持一致（2.0.21），保证 :algorithm 两侧编译行为一致。
plugins {
    kotlin("jvm") version "2.0.21" apply false
}

// ── 把外部挂载模块的构建产物收回到本工程内 ──────────────────────────────────
// :algorithm 的 projectDir 指向 ../algorithm（原 Android 工程）。如果不改 buildDir，
// Gradle 会把产物写进原工程的 algorithm/build —— 与 Android 构建共享同一目录，
// 两个构建的 Kotlin 版本/插件/缓存会互相踩，且会在原仓库里留下新目录。
// 这里只改"产物放哪"，不改原工程的任何源码与构建脚本。
subprojects {
    if (path == ":algorithm") {
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("external/algorithm"))
    }
}
