// 顶层构建文件。AGP 9.x 内置 Kotlin：不要 apply org.jetbrains.kotlin.android。
//
// 要用比 AGP 自带（2.2.10）更新的 Kotlin，就把 KGP 放进 buildscript classpath
// （AGP 9.0 发行说明 "Upgrade to a higher KGP version"）。版本号在下面这一行和
// gradle/libs.versions.toml 的 kotlin 两处，升级时一起改。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
