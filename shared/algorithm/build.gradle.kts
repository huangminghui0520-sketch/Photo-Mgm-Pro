// :shared:algorithm 核心算法模块 —— 纯 Kotlin JVM（零 Android 依赖），可移植 PC / 飞牛OS
plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // ★ 2026-10-31：台账用 Apache POI **读回**校验，取代"断言 XML 字符串片段"的旧方式。
    //   价值：按语义断言（单元格文本、行高磅值、嵌入图片的实际字节），直接对应
    //   "Excel 能不能打开 / 照片对不对"这个真实目标；字符串匹配只能证明写进了什么。
    //   只用 poi-ooxml：其默认依赖 poi-ooxml-lite，不会拉进 15MB 的 ooxml-schemas 全量。
    testImplementation("org.apache.poi:poi-ooxml:5.2.5")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// 日志解析诊断入口：gradle :shared:algorithm:runLogParser
// 验证自己的日志：--args="--file=D:/path/log.txt"
tasks.register<JavaExec>("runLogParser") {
    group = "verification"
    description = "运行日志解析诊断，输出解析报告（默认黄金样本，--file= 可指定日志文件）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("algorithm.LogParserReport")
}
