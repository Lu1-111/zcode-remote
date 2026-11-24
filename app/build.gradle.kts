import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// release 签名：密钥在 keystore/，口令在 keystore.properties（两者都不入库），
// 都由 scripts/make-keystore.sh 生成。这里只读，缺了就不配签名配置，
// AGP 会打出 app-release-unsigned.apk，scripts/build.sh 发现后直接报错。
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.layout.projectDirectory.file("keystore.properties")
if (keystorePropertiesFile.asFile.isFile) {
    keystorePropertiesFile.asFile.inputStream().use { keystoreProperties.load(it) }
}
val releaseStoreFile = keystoreProperties.getProperty("storeFile")
val releaseSigningReady: Boolean = releaseStoreFile != null &&
    listOf("storePassword", "keyAlias", "keyPassword").all { !keystoreProperties.getProperty(it).isNullOrEmpty() } &&
    rootProject.file(releaseStoreFile).isFile

android {
    namespace = "com.lululu.zcoderemote"

    // Compose 1.13.x 按 SDK 37.1 编译，AGP 9 要求使用方不低于它。
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lululu.zcoderemote"
        minSdk = 26
        targetSdk = 37
        // M4：versionCode 加一（M3 发的包是 2、versionName 0.2.0，docs/ARCHITECTURE.md 第 5 节）。
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 不混淆、不收缩：出问题时堆栈可读，JS 注入与反射相关的类也不会被误删。
            isMinifyEnabled = false
            isDebuggable = false
            // 混淆关着时这一行不起作用；将来打开混淆时，规则写在 app/proguard-rules.pro。
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 只用到 BuildConfig.DEBUG：内核靠它在 debug 包里打开 WebView 调试，release 包里那段代码编译时就去掉了。
        buildConfig = true
    }

    lint {
        // 有 error 就让 lint 任务失败；warning 不拦，但要看报告。
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // M3：电脑列表的存储（App 私有存储，不参与备份与迁移，见清单里的规则）。
    implementation(libs.androidx.datastore.preferences)
    // M3：compose 里的 LocalLifecycleOwner 从 compose.ui 挪到了这个库（androidx.lifecycle.compose）。
    implementation(libs.androidx.lifecycle.runtime.compose)
    // M3：App 内相机扫码。只把扫码视图内嵌进 Compose 界面，不用它自带的 CaptureActivity，
    // 全应用仍然只有一个 Activity。它的清单会并入 android.permission.CAMERA（白名单内）。
    implementation(libs.zxing.android.embedded)
    // M4：保存副本。addDocumentStartJavaScript + addWebMessageListener 都是 WebViewCompat 的
    // 按来源限制注入，不用 addJavascriptInterface（方案第 16 条、docs/ARCHITECTURE.md 6.2）。
    implementation(libs.androidx.webkit)
    // 只进 debug 包（Compose 预览用）。它带进来的导出 Activity 在 src/debug/AndroidManifest.xml 里去掉了。
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
