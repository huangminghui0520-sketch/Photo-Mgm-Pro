// Photo-Mgm 根工程：仅做插件版本统一声明，不含任何实际构建逻辑
plugins {
    // Kotlin 版本全局统一；Kotlin 插件本体从阿里云 gradle-plugin 镜像解析
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    // Compose Compiler（Kotlin 2.0 起随 Kotlin 版本走）
    kotlin("plugin.compose") version "2.0.21" apply false
    // AGP：从阿里云 google 镜像解析（国内网络禁止原生 google()）
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
}
