// :shared:domain —— 业务规则层（纯 Kotlin JVM，零平台 SDK）
//
// 这一层放"不依赖任何外部世界的业务规则"：视图派生、人工调整、台账投影、
// EXIF 时间解析、SHA-256、媒体规格、扩展名、派生文件命名。
//
// 准入（五条全满足才进来，见 docs/CROSSPLATFORM-ARCH.md §3.1）：
//   C1 纯逻辑：相同输入必得相同输出，不读系统时间/时区/环境变量/文件系统
//   C2 零平台 SDK：只用 kotlin.* / java.time / java.io / java.util / java.security / java.util.zip / kotlinx.coroutines
//   C3 两端语义一致：不存在"PC 这样、安卓那样"的分支
//   C4 单一事实来源：业务常量（尺寸/质量/容差/后缀）只在这里定义一次
//   C5 可独立测试：能被纯 JVM 单测覆盖，不依赖 Robolectric / AWT / 真机
//
// 排除（命中任一条就留在平台层）：依赖平台 SDK / 权限与生命周期 / 单端产品形态的"执行"。
// ★ 最容易判错的是最后一条：形态的"计划"要上移，"执行"留平台。
//   例如"哪个事件进哪个文件夹、重名怎么加 _2"是业务规则（已在 algorithm.ZipExporter），
//   "怎么把文件写到磁盘"是执行（留 PcFolderExporter / Android SAF）。
//
// 依赖方向恒为单向：domain → algorithm，domain 不得反向依赖 app，更不得依赖任何平台模块。
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
