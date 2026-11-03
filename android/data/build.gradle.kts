// :data Android 数据接入层（§4.4）
// 职责：MediaStore 查候选 + 目录白名单 + EXIF 精读 + 内存缓存；仅做平台实现，算法逻辑全在 :shared:algorithm
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.photomgm.data"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":shared:algorithm"))
    // T03：EXIF 时间解析与 SHA-256 收敛到 :shared:domain（纯 Kotlin，与桌面端同一份实现）
    implementation(project(":shared:domain"))
    // EXIF 精读（§4.3）：androidx ExifInterface（国内镜像 maven.aliyun.com/repository/google）
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // T06：UriShellLauncher 直接用 FileProvider（在 androidx.core 里）。
    //   显式声明而不是吃上游 :app 的传递依赖——本模块自己 import 了这个 API，
    //   就不该假设"恰好有人带我"。版本与 :app 侧一致，避免合成时出现两份 core。
    implementation("androidx.core:core-ktx:1.12.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.platform:junit-platform-launcher:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")  // JUnit4 + Robolectric
    testImplementation("junit:junit:4.13.2")  // JUnit4 API（供 Robolectric 测试类编译）
    // Robolectric：JVM 上运行真实 androidx ExifInterface，验证测试照片 GPS/时间 EXIF 可读性
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
