// :core —— Windows 桌面版逻辑核心（无 UI）
//
// 依赖策略（与方案 §3.2 一致，本阶段只保留逻辑核心真正需要的）：
//   :shared:algorithm     核心算法（外部目录挂载，零改动）
//   kotlinx-coroutines    仅用 StateFlow 作为「逻辑 → UI」的状态通道（不引入任何 UI 框架）
//   metadata-extractor    读 EXIF（拍摄时间 + GPS）；Apache-2.0，~350KB
//   测试：JUnit5 + kotlin-test + POI（把导出的台账读回来做语义断言）
//
// 明确不引入（本阶段）：Compose / Swing / JavaFX / Coil / AndroidX 任何组件。
plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    api(project(":shared:algorithm"))

    // ★ api 而非 implementation：PhotoMgmCore.state 的类型是 StateFlow，
    //   任何消费者（未来的 UI、:cli）在编译期都要能解析该类型，否则调用方被迫自己声明版本。
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 台账读回断言（与 :algorithm 测试同一手法）：按语义验证单元格文本与嵌入图片字节
    testImplementation("org.apache.poi:poi-ooxml:5.2.5")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    // 测试统一用 JUnit @TempDir 生成临时目录（不依赖系统 tmpdir，避免污染用户目录）
}
