// 根构建脚本：只声明插件版本，不应用
//
// 版本说明：
// - AGP / Kotlin / KSP 的版本号请在 Android Studio 里用 "AGP Upgrade Assistant" 校准一次，
//   或直接改 gradle/libs.versions.toml。这组数字是 2026-11 的合理值，但你的 IDE 可能已有更新版本。
// - compileSdk 必须是 36（Android 16），AppFunctions 是 Android 16+ 的平台能力。

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}
