plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yanhuozhang.offline"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yanhuozhang.offline"
        minSdk = 31
        targetSdk = 36
        versionCode = 25
        versionName = "2.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 仅自用在 arm64 真机上。ML Kit OCR 的原生库每个 ABI 各带一份，只保留 arm64-v8a
        // 可省下约 28MB；代价是 32 位手机和不带 ARM 转译的 x86 模拟器无法安装。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        val storeFilePath = providers.environmentVariable("YANHUO_STORE_FILE").orNull
        val storePasswordValue = providers.environmentVariable("YANHUO_STORE_PASSWORD").orNull
        val keyAliasValue = providers.environmentVariable("YANHUO_KEY_ALIAS").orNull
        val keyPasswordValue = providers.environmentVariable("YANHUO_KEY_PASSWORD").orNull
        if (storeFilePath != null && storePasswordValue != null && keyAliasValue != null && keyPasswordValue != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 1.1.0 会把 androidx.fragment 拉回 1.2.5。那个版本的 FragmentActivity
    // 校验 requestCode 必须落在低 16 位，而 ActivityResultRegistry 生成的 code 恒 >= 65536，
    // 于是 MainActivity(FragmentActivity) 下所有 launcher.launch() 都会抛
    // IllegalArgumentException: Can only use lower 16 bits for requestCode，
    // 权限申请和备份的文件选择器全部不可用。这里显式提到现代版本。
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.room:room-ktx:2.7.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // 微信支付成功页截图后的商家名 OCR。必须用 bundled 版本：模型随 APK 打包，
    // 不依赖 Google Play 服务，国产无 GMS 的设备也能用。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    ksp("androidx.room:room-compiler:2.7.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.room:room-testing:2.7.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    val testUserHome = rootProject.layout.projectDirectory.dir(".test-user-home").asFile
    val configuredTestTemp = providers.environmentVariable("YANHUO_TEST_TMP").orNull
    val testTemp = configuredTestTemp?.let { path -> file(path) } ?: testUserHome.resolve("tmp")
    doFirst {
        testUserHome.mkdirs()
        testTemp.mkdirs()
    }
    systemProperty("user.home", testUserHome.absolutePath)
    systemProperty("java.io.tmpdir", testTemp.absolutePath)
}
