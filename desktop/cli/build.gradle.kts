// :cli —— 无头命令行入口（★ 不是 UI，是"没图形界面也能真实跑完一遍"的验证与批处理入口）
//
// 存在理由：
//   1. UI 延后开发，但逻辑必须先能被人真实使用与验收 —— 否则只能靠单测"猜"它能不能跑；
//   2. 它是「核心门面 PhotoMgmCore」的第一个消费者，能反向验证接口是否够用（UI 接不进来时先在这里暴露）；
//   3. 未来要接 Compose Desktop 时，本模块可以原样保留作为批处理/排障工具，不冲突。
//
// 依赖方向严格单向：cli → core → platform → :algorithm，cli 不反向被引。
plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":algorithm"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.photomgm.desktop.cli.MainKt")
    // Windows 控制台默认 GBK：不显式指定会让中文输出与重定向文件乱码。
    // JDK 17 的 System.out 由 sun.stdout.encoding 决定（file.encoding 单独不够）。
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    )
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
