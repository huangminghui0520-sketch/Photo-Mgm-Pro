// 根构建：只声明插件版本（apply false），子模块 plugins{} 继续**不带版本**
// （现状即如此，8 个模块脚本零改动）。版本单点来源为 gradle/libs.versions.toml。
//
// ★ 这里出现的 6 个 plugin id 必须覆盖原先由两端根脚本提供的版本声明集合
//   （kotlin.jvm / kotlin.android / kotlin.plugin.compose / com.android.application /
//   com.android.library / org.jetbrains.compose）。两端旧根脚本已删除 —— 详见 settings.gradle.kts
//   里「关于容器项目」那段：容器项目目录下残留带 version 的根脚本会在配置阶段冲突。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// ★ P0 根治：显式枚举全部 8 个模块的单测 task，一次调用覆盖，杜绝「接线遗漏」。
//   用显式清单（而不是 withType<Test>{}）有两个理由：
//     ① 让 CI 日志产生可枚举的 8 条 `> Task :…:test*` 行，人能直接数；
//     ② 与 ci.yml 的 test-reports path 一一对齐（方案 G1）。
// ★ :android:data 的 task 名仍需在 CI 首次运行确认（`gradle :android:data:tasks --all`，
//   AGP 的 library 模块通常就是 testDebugUnitTest）；若不符，只改这一行。
// ★ 注意 :desktop:cli 没有 src/test，其 test task 会以 NO-SOURCE 结束 —— 它仍算一条 Task 行，
//   但**不能**当作"跑了测试"的证据；判据必须看 8 份 TEST-*.xml 的 tests>0（R-07）。
// ★ R-09：L3（PHOTOMGM_SKIP_ANDROID）不在本次范围。下面 dependsOn 是硬编码 8 项，
//   启用 L3 前**必须**先把本段改成条件构造（按实际 include 结果动态拼 dependsOn），
//   否则会引用不存在的项目、在配置阶段直接报错。
tasks.register("testAll") {
    group = "verification"
    description = "一次覆盖 shared×3 + android×2 + desktop×3 全部单测"
    dependsOn(
        ":shared:algorithm:test",
        ":shared:domain:test",
        ":shared:app:test",
        ":android:data:testDebugUnitTest",
        ":android:app:testDebugUnitTest",
        ":desktop:core:test",
        ":desktop:cli:test",
        ":desktop:ui:test",
    )
}
