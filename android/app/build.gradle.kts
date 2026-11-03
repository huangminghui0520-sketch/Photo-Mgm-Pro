// :app —— Photo-Mgm Android UI（Compose Material3 三段式）
// 约束：UI 仅经 AlgorithmApi 单向调用核心（§2.4/§8）；设置/解析结果/标记自动保存（§8.4）
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

// 读取本地签名配置（keystore/keystore.properties，不入库；缺失时 release 回退 debug 签名，保证任何环境可构建）
// ★ 2026-11-02 目录重组：rootProject 由仓库根变为 android/，签名默认放 android/keystore/；
//   为兼容重组前沿用仓库根 keystore/ 的开发机，两处都探测（android/keystore 优先），
//   storeFile 同理按"先 android/ 再仓库根"解析。两处都没有 → 静默回退 debug 签名，不阻断构建。
import java.util.Properties
val keystorePropFile = listOf(
    rootProject.file("keystore/keystore.properties"),
    rootProject.file("../keystore/keystore.properties"),
).firstOrNull { it.exists() }
val keystoreProps = Properties().apply {
    if (keystorePropFile != null) keystorePropFile.inputStream().use { load(it) }
}
fun resolveSigningFile(path: String) =
    rootProject.file(path).takeIf { it.exists() } ?: rootProject.file("../$path")

android {
    namespace = "com.photomgm.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.photomgm.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.3.2"
        // ★ 2026-10-07 真机 instrumentation：验证 AppTopBar 按钮在真机 Compose 层的可点击性
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (!keystoreProps.isEmpty()) {
            create("release") {
                storeFile = resolveSigningFile(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // ★ 2026-10-01 渲染验证：让 Robolectric 合并依赖（ui-test-manifest）的 manifest，启动 ComponentActivity
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(project(":shared:algorithm"))
    // T05-C1：`runCatchingCancellable` 上移到 :shared:app（取消信号两端同一类型）。
    implementation(project(":shared:app"))
    implementation(project(":data"))

    // ★ 2026-10-01 升级 BOM 2024.02 → 2024.04（material3 1.1.2 → 1.2.0）：
    //   仅为取得 rememberDatePickerState 的 selectableDates 参数（放开巡查日期可选范围，修复「9-27 不可选」）
    val composeBom = platform("androidx.compose:compose-bom:2024.04.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    // 扩展图标集（Description/PhotoLibrary/Upload/CalendarMonth/FolderZip/ReceiptLong 等），版本由 BOM 托管
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // 缩略图（§8.2 预览网格）
    implementation("io.coil-kt:coil-compose:2.5.0")
    // SAF 目录访问（DocumentFile）
    implementation("androidx.documentfile:documentfile:1.0.1")
    

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.platform:junit-platform-launcher:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    // ★ E(#6)：runTest / StandardTestDispatcher / Dispatchers.setMain ——
    //   让导出门禁的时序语义（取消后释放）用虚拟时间断言，不再靠 await 轮询真实墙钟。
    //   版本与解析出的 kotlinx-coroutines-core 对齐（1.7.3），避免混版。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Robolectric：JVM 上验证 AppViewModel 编排（parseLogs 重新粗筛→精读→自动分类）
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    // ★ 2026-10-01 渲染验证：Robolectric(NATIVE) + Compose ui-test 截图验证 DatePicker 日历列完整性
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // ui-test-manifest 必须为 debugImplementation：其 ComponentActivity 声明需进 debug 变体 manifest，
    // Robolectric 读取 debug 合并 manifest 才能启动组件（testImplementation 的库 manifest 不会合并进 app manifest）
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // ★ 2026-10-07 真机 instrumentation（connectedDebugAndroidTest）
    // compose-bom 需显式注入 androidTest 配置（BOM 约束不跨配置传递）
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
