// :shared:app —— 应用层（用例编排 + 端口定义 + 跨端契约，纯 Kotlin JVM）
//
// 这一层放"把领域规则串成一条流水线"的东西：分析流水线、人工调整、导出计划、设置读写，
// 以及两端都要遵守的契约（门面接口、状态快照、设置模型）与端口接口（平台实现由两端注入）。
//
// 依赖方向：app → domain → algorithm。app 不依赖任何平台 SDK，
// 也不知道"文件系统"或"MediaStore"的存在——它只认端口接口，实现由装配根塞进来。
//
// ★ 契约里不允许出现平台概念：window / Path / Uri / Activity 一旦出现在 app/api 下，
//   就等于让另一端被迫携带永远为 null 的字段（这正是本次重构要修的问题之一）。
plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    api(project(":shared:domain"))
    // ★ T09：门面以 StateFlow 作为「逻辑 → UI」的状态通道，与桌面端既有做法一致
    //   （desktop/core 早已依赖 coroutines 并在 API 上暴露 StateFlow）。
    //   这里必须是 api 而不是 implementation：消费者编译时要能解析 StateFlow 类型，
    //   否则 UI 被迫自己声明一个版本，两处版本一漂移就是 NoSuchMethodError。
    //   ★ 只用 core 的 Flow，不引入协程上下文/调度器——线程模型仍由装配根决定。
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
